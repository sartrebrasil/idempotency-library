package com.example.idempotency.spring.store.jdbc;

import com.example.idempotency.model.IdempotencyKeyId;
import com.example.idempotency.model.IdempotencyRecord;
import com.example.idempotency.model.IdempotencyStatus;
import com.example.idempotency.spi.IdempotencyStore;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Relational store. Atomicity comes from the primary key
 * {@code (idempotency_key, scope)}: a concurrent insert fails with
 * {@link DuplicateKeyException} and that loser becomes the caller who replays
 * or waits.
 *
 * <p>Plain JDBC rather than a JPA entity on purpose: an entity shipped inside a
 * library is not picked up by the application's entity scan, and the SQL here
 * is portable. The table comes from the Flyway migration shipped in
 * {@code idempotency-core} ({@code classpath:db/idempotency/postgresql}) —
 * the library never issues DDL.
 *
 * <p>{@link TransactionMode#REQUIRES_NEW} (default): writes run in their own
 * transaction, so a reservation (and its completion) survives independently of
 * the business transaction. {@link TransactionMode#JOIN}: writes join the
 * caller's transaction, so the reservation and the completion commit or roll
 * back with the business work (the transactional inbox of messaging-inbox,
 * ADR-0004). The reserve insert then runs in a savepoint ({@code NESTED}), so a
 * lost race never poisons the caller's transaction; that needs a transaction
 * manager with savepoints, such as {@code DataSourceTransactionManager}.
 *
 * <p>{@code complete} and {@code release} match the owner token in the
 * {@code WHERE} clause, so a caller whose reservation expired cannot touch the
 * row of the one that took the key over.
 */
public class JdbcIdempotencyStore implements IdempotencyStore {

    /** How the store's writes relate to the caller's transaction. */
    public enum TransactionMode { REQUIRES_NEW, JOIN }

    private static final String COLUMNS =
            "idempotency_key, scope, status, fingerprint, result_type, result_payload, created_at, expires_at, owner";

    private static final String SELECT = "SELECT " + COLUMNS
            + " FROM idempotency_record WHERE idempotency_key = ? AND scope = ?";
    private static final String INSERT = "INSERT INTO idempotency_record (" + COLUMNS
            + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String DELETE_EXPIRED =
            "DELETE FROM idempotency_record WHERE idempotency_key = ? AND scope = ? AND expires_at <= ?";
    private static final String UPDATE_COMPLETE = "UPDATE idempotency_record"
            + " SET status = ?, result_type = ?, result_payload = ?"
            + " WHERE idempotency_key = ? AND scope = ? AND owner = ? AND expires_at > ?";
    private static final String UPDATE_COMPLETE_WITH_EXPIRY = "UPDATE idempotency_record"
            + " SET status = ?, result_type = ?, result_payload = ?, expires_at = ?"
            + " WHERE idempotency_key = ? AND scope = ? AND owner = ? AND expires_at > ?";
    private static final String DELETE = "DELETE FROM idempotency_record"
            + " WHERE idempotency_key = ? AND scope = ? AND owner = ?";
    private static final String PURGE = "DELETE FROM idempotency_record WHERE expires_at <= ?";

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate insertTx;
    private final TransactionTemplate writeTx;
    private final Clock clock;

    public JdbcIdempotencyStore(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager,
            Clock clock) {
        this(jdbcTemplate, transactionManager, clock, TransactionMode.REQUIRES_NEW);
    }

    public JdbcIdempotencyStore(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager,
            Clock clock, TransactionMode mode) {
        this.jdbcTemplate = jdbcTemplate;
        this.insertTx = new TransactionTemplate(transactionManager);
        this.writeTx = new TransactionTemplate(transactionManager);
        if (mode == TransactionMode.JOIN) {
            // REQUIRED joins the caller's transaction, or starts one when there is none
            this.insertTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
            this.writeTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        } else {
            this.insertTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            this.writeTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        }
        this.clock = clock;
    }

    @Override
    public Optional<IdempotencyRecord> reserve(IdempotencyRecord candidate) {
        Optional<IdempotencyRecord> live = find(candidate.id());
        if (live.isPresent()) {
            return live;
        }

        boolean inserted = Boolean.TRUE.equals(insertTx.execute(status -> {
            jdbcTemplate.update(DELETE_EXPIRED, candidate.id().key(), candidate.id().scope(), now());
            try {
                jdbcTemplate.update(INSERT,
                        candidate.id().key(),
                        candidate.id().scope(),
                        candidate.status().name(),
                        candidate.fingerprint(),
                        candidate.resultType(),
                        candidate.resultPayload(),
                        Timestamp.from(candidate.createdAt()),
                        Timestamp.from(candidate.expiresAt()),
                        candidate.owner());
                return true;
            } catch (DuplicateKeyException e) {
                // Another caller inserted first. The transaction (or the
                // savepoint, in JOIN mode) is unusable from here on, so the
                // holder is read back outside of it.
                status.setRollbackOnly();
                return false;
            }
        }));
        if (inserted) {
            return Optional.empty();
        }

        // Lost the race. If the winner already vanished, report the candidate as
        // held: the caller retries instead of risking a second execution.
        return Optional.of(find(candidate.id()).orElse(candidate));
    }

    @Override
    public boolean complete(IdempotencyKeyId id, String owner, String resultType, byte[] resultPayload,
            Instant expiresAt) {
        Integer updated = writeTx.execute(status -> expiresAt == null
                ? jdbcTemplate.update(UPDATE_COMPLETE, IdempotencyStatus.COMPLETED.name(), resultType, resultPayload,
                        id.key(), id.scope(), owner, now())
                : jdbcTemplate.update(UPDATE_COMPLETE_WITH_EXPIRY, IdempotencyStatus.COMPLETED.name(), resultType,
                        resultPayload, Timestamp.from(expiresAt), id.key(), id.scope(), owner, now()));
        return updated != null && updated > 0;
    }

    @Override
    public boolean release(IdempotencyKeyId id, String owner) {
        Integer deleted = writeTx.execute(status -> jdbcTemplate.update(DELETE, id.key(), id.scope(), owner));
        return deleted != null && deleted > 0;
    }

    @Override
    public Optional<IdempotencyRecord> find(IdempotencyKeyId id) {
        List<IdempotencyRecord> found = jdbcTemplate.query(SELECT, ROW_MAPPER, id.key(), id.scope());
        return found.stream().findFirst().filter(record -> !record.isExpired(clock.instant()));
    }

    /** Deletes expired rows; the relational backend has no native TTL. */
    public int purgeExpired() {
        Integer deleted = writeTx.execute(status -> jdbcTemplate.update(PURGE, now()));
        return deleted == null ? 0 : deleted;
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant());
    }

    private static final RowMapper<IdempotencyRecord> ROW_MAPPER = (ResultSet rs, int rowNum) -> mapRow(rs);

    private static IdempotencyRecord mapRow(ResultSet rs) throws SQLException {
        return new IdempotencyRecord(
                new IdempotencyKeyId(rs.getString("idempotency_key"), rs.getString("scope")),
                IdempotencyStatus.valueOf(rs.getString("status")),
                rs.getString("fingerprint"),
                rs.getString("result_type"),
                rs.getBytes("result_payload"),
                instant(rs, "created_at"),
                instant(rs, "expires_at"),
                rs.getString("owner"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}

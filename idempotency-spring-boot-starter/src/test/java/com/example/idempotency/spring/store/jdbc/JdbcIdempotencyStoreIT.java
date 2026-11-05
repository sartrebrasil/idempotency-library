package com.example.idempotency.spring.store.jdbc;

import java.time.Clock;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import com.example.idempotency.model.IdempotencyStatus;
import com.example.idempotency.model.IdempotencyKeyId;
import com.example.idempotency.model.IdempotencyRecord;
import com.example.idempotency.spi.AbstractIdempotencyStoreContractTest;
import com.example.idempotency.spi.IdempotencyStore;
import com.example.idempotency.spring.testapp.TestApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Relational store against a real PostgreSQL, including the shipped DDL — the
 * script is what applications are told to run, so it is what the tests use.
 */
@SpringBootTest(classes = TestApplication.class, properties = {
        "idempotency.store=jdbc",
        "idempotency.jdbc.cleanup-enabled=false",
        "spring.main.web-application-type=none",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema/idempotency-record-postgres.sql",
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration,"
                + "org.springframework.boot.mongodb.autoconfigure.MongoAutoConfiguration,"
                + "org.springframework.boot.data.mongodb.autoconfigure.DataMongoAutoConfiguration"
})
@Testcontainers
class JdbcIdempotencyStoreIT extends AbstractIdempotencyStoreContractTest {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private JdbcIdempotencyStore store;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Override
    protected IdempotencyStore store() {
        return store;
    }

    @Test
    void expiredRowIsIgnoredAndOverwritten() {
        IdempotencyKeyId id = newKeyId();
        Instant now = Instant.now();
        store.reserve(IdempotencyRecord.inProgress(id, "fp", now.minusSeconds(600), now.minusSeconds(300)));

        assertTrue(store.find(id).isEmpty(), "an expired row must read as absent");
        assertTrue(store.reserve(candidate(id)).isEmpty(), "an expired row must be replaceable");
    }

    @Test
    void purgeDeletesOnlyExpiredRows() {
        IdempotencyKeyId expired = newKeyId();
        IdempotencyKeyId live = newKeyId();
        Instant now = Instant.now();
        store.reserve(IdempotencyRecord.inProgress(expired, null, now.minusSeconds(600), now.minusSeconds(300)));
        store.reserve(candidate(live));

        store.purgeExpired();

        assertEquals(0, countRows(expired));
        assertEquals(1, countRows(live));
    }

    @Test
    void completedResultSurvivesAReadBack() {
        IdempotencyKeyId id = newKeyId();
        IdempotencyRecord reserved = candidate(id);
        store.reserve(reserved);

        store.complete(id, reserved.owner(), "java.lang.String", new byte[] { 1, 2, 3 }, null);

        Optional<IdempotencyRecord> stored = store.find(id);
        assertTrue(stored.isPresent());
        assertEquals(3, stored.orElseThrow().resultPayload().length);
    }

    @Test
    void joinModeCommitsWithTheCallerAndALostRaceDoesNotPoisonIt() {
        var joined = new JdbcIdempotencyStore(jdbcTemplate, transactionManager, Clock.systemUTC(),
                JdbcIdempotencyStore.TransactionMode.JOIN);
        var tx = new TransactionTemplate(transactionManager);
        IdempotencyKeyId committed = newKeyId();
        IdempotencyKeyId rolledBack = newKeyId();

        tx.executeWithoutResult(status -> {
            IdempotencyRecord mine = candidate(committed);
            assertTrue(joined.reserve(mine).isEmpty());
            assertTrue(joined.reserve(candidate(committed)).isPresent(), "a second reserve loses the race");
            // the savepoint kept the transaction usable after the duplicate key
            assertTrue(joined.complete(committed, mine.owner(), null, null, null));
        });
        tx.executeWithoutResult(status -> {
            assertTrue(joined.reserve(candidate(rolledBack)).isEmpty());
            status.setRollbackOnly();
        });

        assertEquals(IdempotencyStatus.COMPLETED, store.find(committed).orElseThrow().status());
        assertTrue(store.find(rolledBack).isEmpty(), "the reservation rolls back with the caller");
    }

    private int countRows(IdempotencyKeyId id) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM idempotency_record WHERE idempotency_key = ? AND scope = ?",
                Integer.class, id.key(), id.scope());
        return count == null ? 0 : count;
    }
}

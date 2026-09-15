package com.example.idempotency.spring.store.mongo;

import com.example.idempotency.model.IdempotencyKeyId;
import com.example.idempotency.model.IdempotencyRecord;
import com.example.idempotency.model.IdempotencyStatus;
import com.example.idempotency.spi.IdempotencyStore;
import org.bson.Document;
import org.bson.types.Binary;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

/**
 * MongoDB store. Atomicity is the unique index on {@code (key, scope)}: a
 * concurrent insert fails with {@link DuplicateKeyException}. Expiry is a TTL
 * index on {@code expiresAt}.
 *
 * <p>Mongo's TTL monitor runs about once a minute, so an expired document can
 * linger. Reads filter on {@code expiresAt} and {@link #reserve} deletes a
 * stale document before retrying, so behaviour does not depend on the monitor.
 */
public class MongoIdempotencyStore implements IdempotencyStore {

    private static final String KEY = "key";
    private static final String SCOPE = "scope";
    private static final String STATUS = "status";
    private static final String FINGERPRINT = "fingerprint";
    private static final String RESULT_TYPE = "resultType";
    private static final String RESULT_PAYLOAD = "resultPayload";
    private static final String CREATED_AT = "createdAt";
    private static final String EXPIRES_AT = "expiresAt";

    private final MongoTemplate mongo;
    private final String collection;
    private final Clock clock;

    public MongoIdempotencyStore(MongoTemplate mongo, String collection, Clock clock) {
        this.mongo = mongo;
        this.collection = collection;
        this.clock = clock;
    }

    /** Creates the unique index and the TTL index; both are idempotent. */
    public void ensureIndexes() {
        mongo.indexOps(collection).createIndex(new Index()
                .on(KEY, Sort.Direction.ASC)
                .on(SCOPE, Sort.Direction.ASC)
                .unique()
                .named("idempotency_key_scope_unique"));
        mongo.indexOps(collection).createIndex(new Index()
                .on(EXPIRES_AT, Sort.Direction.ASC)
                .expire(Duration.ZERO)
                .named("idempotency_expires_at_ttl"));
    }

    @Override
    public Optional<IdempotencyRecord> reserve(IdempotencyRecord candidate) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                mongo.getCollection(collection).insertOne(toDocument(candidate));
                return Optional.empty();
            } catch (com.mongodb.MongoWriteException | DuplicateKeyException e) {
                Optional<IdempotencyRecord> live = find(candidate.id());
                if (live.isPresent()) {
                    return live;
                }
                // The blocking document is expired but not yet reaped: drop it
                // and try once more.
                mongo.remove(byId(candidate.id()), collection);
            }
        }
        // Still losing the race: report the key as held so the caller retries.
        return Optional.of(candidate);
    }

    @Override
    public void complete(IdempotencyKeyId id, String resultType, byte[] resultPayload) {
        Update update = new Update()
                .set(STATUS, IdempotencyStatus.COMPLETED.name())
                .set(RESULT_TYPE, resultType)
                .set(RESULT_PAYLOAD, resultPayload);
        mongo.updateFirst(liveQuery(id), update, collection);
    }

    @Override
    public void release(IdempotencyKeyId id) {
        mongo.remove(byId(id), collection);
    }

    @Override
    public Optional<IdempotencyRecord> find(IdempotencyKeyId id) {
        Document document = mongo.findOne(liveQuery(id), Document.class, collection);
        return Optional.ofNullable(document).map(this::toRecord);
    }

    private Query byId(IdempotencyKeyId id) {
        return Query.query(Criteria.where(KEY).is(id.key()).and(SCOPE).is(id.scope()));
    }

    private Query liveQuery(IdempotencyKeyId id) {
        return Query.query(Criteria.where(KEY).is(id.key())
                .and(SCOPE).is(id.scope())
                .and(EXPIRES_AT).gt(Date.from(clock.instant())));
    }

    private Document toDocument(IdempotencyRecord record) {
        return new Document()
                .append(KEY, record.id().key())
                .append(SCOPE, record.id().scope())
                .append(STATUS, record.status().name())
                .append(FINGERPRINT, record.fingerprint())
                .append(RESULT_TYPE, record.resultType())
                .append(RESULT_PAYLOAD, record.resultPayload())
                .append(CREATED_AT, Date.from(record.createdAt()))
                .append(EXPIRES_AT, Date.from(record.expiresAt()));
    }

    private IdempotencyRecord toRecord(Document document) {
        return new IdempotencyRecord(
                new IdempotencyKeyId(document.getString(KEY), document.getString(SCOPE)),
                IdempotencyStatus.valueOf(document.getString(STATUS)),
                document.getString(FINGERPRINT),
                document.getString(RESULT_TYPE),
                payloadOf(document),
                instant(document, CREATED_AT),
                instant(document, EXPIRES_AT));
    }

    private byte[] payloadOf(Document document) {
        Object payload = document.get(RESULT_PAYLOAD);
        if (payload instanceof Binary binary) {
            return binary.getData();
        }
        return (byte[]) payload;
    }

    private Instant instant(Document document, String field) {
        Date date = document.getDate(field);
        return date == null ? null : date.toInstant();
    }
}

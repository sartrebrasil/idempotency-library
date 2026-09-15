package com.example.idempotency.spring.store.mongo;

import com.example.idempotency.model.IdempotencyKeyId;
import com.example.idempotency.model.IdempotencyRecord;
import com.example.idempotency.spi.AbstractIdempotencyStoreContractTest;
import com.example.idempotency.spi.IdempotencyStore;
import com.example.idempotency.spring.testapp.TestApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MongoDB store against a real server. Expiry is asserted through the query
 * filter, not the TTL monitor: the monitor runs about once a minute and no test
 * should wait for it.
 */
@SpringBootTest(classes = TestApplication.class, properties = {
        "idempotency.store=mongo",
        "spring.main.web-application-type=none",
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration"
})
@Testcontainers
class MongoIdempotencyStoreIT extends AbstractIdempotencyStoreContractTest {

    @Container
    @SuppressWarnings("resource")
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    @DynamicPropertySource
    static void mongo(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", MONGO::getReplicaSetUrl);
    }

    @Autowired
    private MongoIdempotencyStore store;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Override
    protected IdempotencyStore store() {
        return store;
    }

    @Test
    void expiredDocumentIsIgnoredAndReplaced() {
        IdempotencyKeyId id = newKeyId();
        Instant now = Instant.now();
        store.reserve(IdempotencyRecord.inProgress(id, "fp", now.minusSeconds(600), now.minusSeconds(300)));

        assertTrue(store.find(id).isEmpty(), "an expired document must read as absent");
        assertTrue(store.reserve(candidate(id)).isEmpty(),
                "a stale document must be dropped instead of blocking the key");
    }

    @Test
    void indexesAreCreatedForUniquenessAndTtl() {
        List<String> indexNames = mongoTemplate.indexOps("idempotency_record").getIndexInfo().stream()
                .map(index -> index.getName())
                .toList();

        assertTrue(indexNames.contains("idempotency_key_scope_unique"), indexNames.toString());
        assertTrue(indexNames.contains("idempotency_expires_at_ttl"), indexNames.toString());
    }

    @Test
    void completedResultSurvivesAReadBack() {
        IdempotencyKeyId id = newKeyId();
        store.reserve(candidate(id));

        store.complete(id, "java.lang.String", new byte[] { 1, 2, 3, 4 });

        assertEquals(4, store.find(id).orElseThrow().resultPayload().length);
    }
}

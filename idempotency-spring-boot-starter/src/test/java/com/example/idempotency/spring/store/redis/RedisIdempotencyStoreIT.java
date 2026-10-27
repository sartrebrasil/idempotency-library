package com.example.idempotency.spring.store.redis;

import com.example.idempotency.model.IdempotencyKeyId;
import com.example.idempotency.model.IdempotencyRecord;
import com.example.idempotency.spi.AbstractIdempotencyStoreContractTest;
import com.example.idempotency.spi.IdempotencyStore;
import com.example.idempotency.spring.testapp.TestApplication;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Redis store against a real server, including its native key expiry. */
@SpringBootTest(classes = TestApplication.class, properties = {
        "idempotency.store=redis",
        "spring.main.web-application-type=none",
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.boot.mongodb.autoconfigure.MongoAutoConfiguration,"
                + "org.springframework.boot.data.mongodb.autoconfigure.DataMongoAutoConfiguration"
})
@Testcontainers
class RedisIdempotencyStoreIT extends AbstractIdempotencyStoreContractTest {

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private RedisIdempotencyStore store;

    @Override
    protected IdempotencyStore store() {
        return store;
    }

    @Test
    void keyExpiresOnItsOwn() {
        IdempotencyKeyId id = newKeyId();
        Instant now = Instant.now();
        store.reserve(IdempotencyRecord.inProgress(id, "fp", now, now.plusSeconds(1)));

        Awaitility.await().atMost(Duration.ofSeconds(5))
                .until(() -> store.find(id).isEmpty());

        assertTrue(store.reserve(candidate(id)).isEmpty(), "an expired key must be reservable again");
    }

    @Test
    void completionKeepsTheRemainingTtl() {
        IdempotencyKeyId id = newKeyId();
        Instant now = Instant.now();
        store.reserve(IdempotencyRecord.inProgress(id, "fp", now, now.plusSeconds(2)));

        store.complete(id, "java.lang.String", new byte[] { 7 });
        assertEquals(1, store.find(id).orElseThrow().resultPayload().length);

        Awaitility.await().atMost(Duration.ofSeconds(6))
                .until(() -> store.find(id).isEmpty());
    }
}

package com.example.idempotency.store;

import com.example.idempotency.model.IdempotencyKeyId;
import com.example.idempotency.model.IdempotencyRecord;
import com.example.idempotency.spi.AbstractIdempotencyStoreContractTest;
import com.example.idempotency.spi.IdempotencyStore;
import com.example.idempotency.support.TestClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryIdempotencyStoreTest extends AbstractIdempotencyStoreContractTest {

    private final InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();

    @Override
    protected IdempotencyStore store() {
        return store;
    }

    @Test
    void expiredRecordIsTreatedAsAbsent() {
        TestClock clock = new TestClock(Instant.parse("2026-01-01T00:00:00Z"));
        InMemoryIdempotencyStore expiring = new InMemoryIdempotencyStore(clock);
        IdempotencyKeyId id = newKeyId();
        expiring.reserve(expiringIn(clock, id, Duration.ofSeconds(30)));

        clock.advance(Duration.ofSeconds(31));

        assertTrue(expiring.find(id).isEmpty(), "expired record must not be found");
        assertTrue(expiring.reserve(expiringIn(clock, id, Duration.ofSeconds(30))).isEmpty(),
                "expired record must be replaceable");
    }

    private IdempotencyRecord expiringIn(TestClock clock, IdempotencyKeyId id, Duration ttl) {
        Instant now = clock.instant();
        return IdempotencyRecord.inProgress(id, null, now, now.plus(ttl));
    }
}

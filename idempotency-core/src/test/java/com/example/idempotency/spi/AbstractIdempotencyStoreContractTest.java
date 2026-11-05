package com.example.idempotency.spi;

import com.example.idempotency.model.IdempotencyKeyId;
import com.example.idempotency.model.IdempotencyRecord;
import com.example.idempotency.model.IdempotencyStatus;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behaviour every {@link IdempotencyStore} must satisfy. Published in this
 * module's test-jar so each backend inherits the assertions instead of
 * restating them.
 *
 * <p>Keys are randomized per test, so the suite is safe against a store shared
 * between test methods (a Testcontainers instance, for example).
 *
 * <p>Expiry is deliberately not covered here: backends expire records by
 * wildly different mechanisms (fake clock, scheduled purge, native TTL with a
 * ~60s monitor in MongoDB), so each implementation tests it on its own terms.
 */
public abstract class AbstractIdempotencyStoreContractTest {

    private static final Duration TTL = Duration.ofMinutes(10);

    /** The store under test. May be shared across test methods. */
    protected abstract IdempotencyStore store();

    @Test
    void reserveGrantsTheKeyWhenAbsent() {
        IdempotencyRecord candidate = candidate(newKeyId());

        assertTrue(store().reserve(candidate).isEmpty(), "first reserve must win");
        assertEquals(IdempotencyStatus.IN_PROGRESS, store().find(candidate.id()).orElseThrow().status());
    }

    @Test
    void reserveReturnsTheExistingRecordWhenHeld() {
        IdempotencyKeyId id = newKeyId();
        store().reserve(candidate(id));

        Optional<IdempotencyRecord> existing = store().reserve(candidate(id));

        assertTrue(existing.isPresent(), "second reserve must report the holder");
        assertEquals(id, existing.orElseThrow().id());
        assertEquals("fp-" + id.key(), existing.orElseThrow().fingerprint());
    }

    @Test
    void completeAttachesTheResultAndKeepsExpiry() {
        IdempotencyKeyId id = newKeyId();
        IdempotencyRecord reserved = candidate(id);
        store().reserve(reserved);

        assertTrue(store().complete(id, reserved.owner(), "java.lang.String", "hello".getBytes(StandardCharsets.UTF_8),
                null));

        IdempotencyRecord stored = store().find(id).orElseThrow();
        assertEquals(IdempotencyStatus.COMPLETED, stored.status());
        assertEquals("java.lang.String", stored.resultType());
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), stored.resultPayload());
        assertEquals(reserved.expiresAt().toEpochMilli(), stored.expiresAt().toEpochMilli(),
                "completion must not extend the deduplication window");
    }

    @Test
    void completeWithoutPayloadKeepsTheKeyReserved() {
        IdempotencyKeyId id = newKeyId();
        IdempotencyRecord reserved = candidate(id);
        store().reserve(reserved);

        store().complete(id, reserved.owner(), null, null, null);

        IdempotencyRecord stored = store().find(id).orElseThrow();
        assertEquals(IdempotencyStatus.COMPLETED, stored.status());
        assertTrue(store().reserve(candidate(id)).isPresent(), "a completed key must stay taken");
    }

    @Test
    void releaseFreesTheKeyForRetry() {
        IdempotencyKeyId id = newKeyId();
        IdempotencyRecord reserved = candidate(id);
        store().reserve(reserved);

        assertTrue(store().release(id, reserved.owner()));

        assertTrue(store().find(id).isEmpty());
        assertTrue(store().reserve(candidate(id)).isEmpty(), "released key must be reservable again");
    }

    @Test
    void completeAndReleaseOnUnknownKeyAreNoOps() {
        IdempotencyKeyId id = newKeyId();

        assertFalse(store().complete(id, "someone", "java.lang.String", new byte[] { 1 }, null));
        assertFalse(store().release(id, "someone"));

        assertTrue(store().find(id).isEmpty());
    }

    @Test
    void completeCanMoveTheExpiryToTheCompletedWindow() {
        IdempotencyKeyId id = newKeyId();
        IdempotencyRecord reserved = candidate(id);
        store().reserve(reserved);
        Instant later = reserved.expiresAt().plus(Duration.ofDays(7));

        assertTrue(store().complete(id, reserved.owner(), null, null, later));

        assertEquals(later.toEpochMilli(), store().find(id).orElseThrow().expiresAt().toEpochMilli());
    }

    @Test
    void anotherOwnerCannotCompleteOrRelease() {
        IdempotencyKeyId id = newKeyId();
        IdempotencyRecord reserved = candidate(id);
        store().reserve(reserved);

        assertFalse(store().complete(id, "intruder", "java.lang.String", new byte[] { 1 }, null));
        assertFalse(store().release(id, "intruder"));

        IdempotencyRecord stored = store().find(id).orElseThrow();
        assertEquals(IdempotencyStatus.IN_PROGRESS, stored.status());
        assertEquals(reserved.owner(), stored.owner());
    }

    @Test
    void theFormerOwnerCannotTouchTheRecordOfTheOneThatTookTheKeyOver() {
        IdempotencyKeyId id = newKeyId();
        IdempotencyRecord first = candidate(id);
        store().reserve(first);
        store().release(id, first.owner());
        IdempotencyRecord second = candidate(id);
        assertTrue(store().reserve(second).isEmpty());

        assertFalse(store().complete(id, first.owner(), "java.lang.String", new byte[] { 1 }, null));
        assertFalse(store().release(id, first.owner()));

        IdempotencyRecord stored = store().find(id).orElseThrow();
        assertEquals(IdempotencyStatus.IN_PROGRESS, stored.status());
        assertEquals(second.owner(), stored.owner());
        assertTrue(store().complete(id, second.owner(), null, null, null));
    }

    @Test
    void sameKeyInDifferentScopesDoesNotCollide() {
        String key = UUID.randomUUID().toString();

        assertTrue(store().reserve(candidate(new IdempotencyKeyId(key, "scope-a"))).isEmpty());
        assertTrue(store().reserve(candidate(new IdempotencyKeyId(key, "scope-b"))).isEmpty(),
                "uniqueness must span key + scope, not the key alone");
    }

    @Test
    void concurrentReserveGrantsExactlyOneCaller() throws Exception {
        IdempotencyKeyId id = newKeyId();
        int callers = 16;

        try (ExecutorService pool = Executors.newFixedThreadPool(callers)) {
            List<Callable<Boolean>> tasks = IntStream.range(0, callers)
                    .<Callable<Boolean>>mapToObj(i -> () -> store().reserve(candidate(id)).isEmpty())
                    .toList();

            long winners = 0;
            for (Future<Boolean> future : pool.invokeAll(tasks)) {
                if (future.get()) {
                    winners++;
                }
            }
            assertEquals(1, winners, "exactly one caller may win the reservation");
        }
    }

    @Test
    void findReturnsEmptyForUnknownKey() {
        assertFalse(store().find(newKeyId()).isPresent());
    }

    protected IdempotencyKeyId newKeyId() {
        return new IdempotencyKeyId(UUID.randomUUID().toString(), "contract-test");
    }

    protected IdempotencyRecord candidate(IdempotencyKeyId id) {
        Instant now = Instant.now();
        return IdempotencyRecord.inProgress(id, "fp-" + id.key(), now, now.plus(TTL));
    }
}

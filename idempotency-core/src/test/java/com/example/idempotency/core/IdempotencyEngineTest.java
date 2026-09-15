package com.example.idempotency.core;

import com.example.idempotency.exception.IdempotencyInProgressException;
import com.example.idempotency.exception.IdempotencyKeyConflictException;
import com.example.idempotency.model.IdempotencyContext;
import com.example.idempotency.model.IdempotencyKeyId;
import com.example.idempotency.model.IdempotencyRecord;
import com.example.idempotency.store.InMemoryIdempotencyStore;
import com.example.idempotency.store.JdkResultSerializer;
import com.example.idempotency.support.TestClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdempotencyEngineTest {

    private static final Duration TTL = Duration.ofMinutes(5);

    private final TestClock clock = new TestClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final InMemoryIdempotencyStore store = new InMemoryIdempotencyStore(clock);
    private final IdempotencyEngine engine = new IdempotencyEngine(store, new JdkResultSerializer(), clock,
            Duration.ofMillis(10));

    @Test
    void executesOnceAndReplaysTheStoredResult() throws Throwable {
        AtomicInteger runs = new AtomicInteger();
        IdempotencyContext context = context("order-1", "fp-a", null);

        String first = engine.execute(context, () -> "order-" + runs.incrementAndGet());
        String second = engine.execute(context, () -> "order-" + runs.incrementAndGet());

        assertEquals("order-1", first);
        assertEquals("order-1", second);
        assertEquals(1, runs.get(), "business logic must run once");
    }

    @Test
    void replayDisabledDeduplicatesWithoutReturningTheResult() throws Throwable {
        AtomicInteger runs = new AtomicInteger();
        IdempotencyContext context = new IdempotencyContext(keyId("order-2"), null, TTL, false, null);

        assertEquals("first", engine.execute(context, () -> {
            runs.incrementAndGet();
            return "first";
        }));
        assertNull(engine.execute(context, () -> {
            runs.incrementAndGet();
            return "second";
        }));
        assertEquals(1, runs.get());
    }

    @Test
    void nullResultStillMarksTheKeyCompleted() throws Throwable {
        AtomicInteger runs = new AtomicInteger();
        IdempotencyContext context = context("order-void", null, null);

        engine.execute(context, () -> {
            runs.incrementAndGet();
            return null;
        });
        assertNull(engine.execute(context, () -> {
            runs.incrementAndGet();
            return null;
        }));

        assertEquals(1, runs.get(), "a void operation must not run twice");
    }

    @Test
    void sameKeyWithDifferentPayloadIsRejected() throws Throwable {
        engine.execute(context("order-3", "fp-a", null), () -> "ok");

        assertThrows(IdempotencyKeyConflictException.class,
                () -> engine.execute(context("order-3", "fp-b", null), () -> "other"));
    }

    @Test
    void concurrentExecutionFailsFastByDefault() {
        IdempotencyKeyId id = keyId("order-4");
        store.reserve(IdempotencyRecord.inProgress(id, "fp-a", clock.instant(), clock.instant().plus(TTL)));

        assertThrows(IdempotencyInProgressException.class,
                () -> engine.execute(context("order-4", "fp-a", null), () -> "never runs"));
    }

    @Test
    void waitingForCompletionReplaysTheOtherExecutionResult() throws Throwable {
        IdempotencyKeyId id = keyId("order-5");
        store.reserve(IdempotencyRecord.inProgress(id, "fp-a", clock.instant(), clock.instant().plus(TTL)));
        Thread other = new Thread(() -> {
            sleep(50);
            store.complete(id, String.class.getName(), new JdkResultSerializer().serialize("from-other-thread"));
        });
        other.start();

        String result = engine.execute(context("order-5", "fp-a", Duration.ofSeconds(5)), () -> "never runs");

        other.join();
        assertEquals("from-other-thread", result);
    }

    @Test
    void waitingGivesUpWhenTheOtherExecutionDoesNotFinish() {
        IdempotencyKeyId id = keyId("order-6");
        store.reserve(IdempotencyRecord.inProgress(id, "fp-a", clock.instant(), clock.instant().plus(TTL)));

        // Zero wait: the deadline is already reached on the first poll.
        assertThrows(IdempotencyInProgressException.class,
                () -> engine.execute(context("order-6", "fp-a", Duration.ZERO), () -> "never runs"));
    }

    @Test
    void waitingTakesOverWhenTheOtherExecutionReleasesTheKey() throws Throwable {
        IdempotencyKeyId id = keyId("order-7");
        store.reserve(IdempotencyRecord.inProgress(id, "fp-a", clock.instant(), clock.instant().plus(TTL)));
        Thread other = new Thread(() -> {
            sleep(50);
            store.release(id);
        });
        other.start();

        String result = engine.execute(context("order-7", "fp-a", Duration.ofSeconds(5)), () -> "retried here");

        other.join();
        assertEquals("retried here", result);
    }

    @Test
    void failedOperationReleasesTheKey() {
        IdempotencyContext context = context("order-8", "fp-a", null);
        AtomicInteger runs = new AtomicInteger();

        assertThrows(IllegalStateException.class, () -> engine.execute(context, () -> {
            runs.incrementAndGet();
            throw new IllegalStateException("boom");
        }));
        assertTrue(store.find(context.id()).isEmpty(), "a failed operation must not leave the key reserved");

        assertThrows(IllegalStateException.class, () -> engine.execute(context, () -> {
            runs.incrementAndGet();
            throw new IllegalStateException("boom again");
        }));
        assertEquals(2, runs.get(), "retry after failure must re-execute");
    }

    @Test
    void expiredKeyAllowsReexecution() throws Throwable {
        IdempotencyContext context = context("order-9", "fp-a", null);
        AtomicInteger runs = new AtomicInteger();

        engine.execute(context, () -> "run-" + runs.incrementAndGet());
        clock.advance(TTL.plusSeconds(1));
        String afterExpiry = engine.execute(context, () -> "run-" + runs.incrementAndGet());

        assertEquals("run-2", afterExpiry);
    }

    @Test
    void supplyExposesTheSameGuardWithoutCheckedExceptions() {
        AtomicInteger runs = new AtomicInteger();
        IdempotencyContext context = context("order-10", "fp-a", null);

        assertEquals("once", engine.supply(context, () -> {
            runs.incrementAndGet();
            return "once";
        }));
        assertEquals("once", engine.supply(context, () -> {
            runs.incrementAndGet();
            return "twice";
        }));
        assertEquals(1, runs.get());
    }

    private IdempotencyContext context(String key, String fingerprint, Duration waitForCompletion) {
        return new IdempotencyContext(keyId(key), fingerprint, TTL, true, waitForCompletion);
    }

    private IdempotencyKeyId keyId(String key) {
        return new IdempotencyKeyId(key, "engine-test");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}

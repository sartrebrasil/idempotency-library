package com.example.idempotency.core;

import com.example.idempotency.exception.IdempotencyException;
import com.example.idempotency.exception.IdempotencyInProgressException;
import com.example.idempotency.exception.IdempotencyKeyConflictException;
import com.example.idempotency.model.IdempotencyContext;
import com.example.idempotency.model.IdempotencyRecord;
import com.example.idempotency.spi.IdempotencyStore;
import com.example.idempotency.spi.ResultSerializer;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Runs an operation at most once per key, replaying the stored result on
 * repeats. Framework agnostic: it only needs a store, a serializer and a clock.
 *
 * <p>Sequence for one call:
 * <ol>
 *   <li>reserve the key atomically;</li>
 *   <li>reservation won: run the operation, then record the result (or release
 *       the key if it threw, so a retry can run);</li>
 *   <li>reservation lost: replay the stored result, reject a payload conflict,
 *       or report the concurrent execution — optionally after waiting for it.</li>
 * </ol>
 *
 * <p>Known window: the result is recorded <em>after</em> the operation returns,
 * in a separate store call. A crash in between leaves the key reserved until
 * its TTL expires. For operations where that matters, call this engine inside
 * the business transaction with a store that shares it (JPA), instead of
 * relying on the interceptor.
 */
public final class IdempotencyEngine {

    private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(50);

    private final IdempotencyStore store;
    private final ResultSerializer serializer;
    private final Clock clock;
    private final Duration pollInterval;

    public IdempotencyEngine(IdempotencyStore store, ResultSerializer serializer) {
        this(store, serializer, Clock.systemUTC(), DEFAULT_POLL_INTERVAL);
    }

    public IdempotencyEngine(IdempotencyStore store, ResultSerializer serializer, Clock clock,
            Duration pollInterval) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.serializer = Objects.requireNonNull(serializer, "serializer must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval must not be null");
    }

    /**
     * Executes the operation under the guard of {@code context}.
     *
     * @return the operation result, or the replayed result of the first
     *         execution ({@code null} when replay is off or the result was void)
     * @throws IdempotencyKeyConflictException same key, different payload
     * @throws IdempotencyInProgressException  another execution holds the key
     * @throws Throwable                       whatever the operation threw
     */
    public <T> T execute(IdempotencyContext context, IdempotentOperation<T> operation) throws Throwable {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(operation, "operation must not be null");

        Instant deadline = context.waitForCompletion() == null
                ? null
                : clock.instant().plus(context.waitForCompletion());

        while (true) {
            Instant now = clock.instant();
            IdempotencyRecord candidate = IdempotencyRecord.inProgress(context.id(), context.fingerprint(), now,
                    now.plus(context.ttl()));

            Optional<IdempotencyRecord> existing = store.reserve(candidate);
            if (existing.isEmpty()) {
                return runAndRecord(context, operation);
            }

            IdempotencyRecord current = existing.get();
            verifyFingerprint(context, current);
            if (current.isCompleted()) {
                return replay(context, current);
            }

            // Key is held by a running execution.
            if (deadline == null) {
                throw new IdempotencyInProgressException(context.id(), false);
            }
            IdempotencyRecord completed = awaitCompletion(context, deadline);
            if (completed == null) {
                continue; // key was released (the other execution failed): try to take it
            }
            verifyFingerprint(context, completed);
            return replay(context, completed);
        }
    }

    /**
     * Unchecked variant for application code: same behaviour, no
     * {@code Throwable} to declare.
     */
    public <T> T supply(IdempotencyContext context, Supplier<T> operation) {
        try {
            return execute(context, operation::get);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IdempotencyException("Unexpected checked exception from supplier", t);
        }
    }

    private <T> T runAndRecord(IdempotencyContext context, IdempotentOperation<T> operation) throws Throwable {
        T result;
        try {
            result = operation.execute();
        } catch (Throwable failure) {
            releaseQuietly(context, failure);
            throw failure;
        }
        if (context.replay() && result != null) {
            store.complete(context.id(), result.getClass().getName(), serializer.serialize(result));
        } else {
            store.complete(context.id(), null, null);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private <T> T replay(IdempotencyContext context, IdempotencyRecord record) {
        if (!context.replay() || record.resultPayload() == null) {
            return null;
        }
        return (T) serializer.deserialize(record.resultPayload(), record.resultType());
    }

    /**
     * Rejects a key reused with a different payload. Skipped when either side
     * has no fingerprint (fingerprinting off, or a record written while it was).
     */
    private void verifyFingerprint(IdempotencyContext context, IdempotencyRecord record) {
        if (context.fingerprint() == null || record.fingerprint() == null) {
            return;
        }
        if (!context.fingerprint().equals(record.fingerprint())) {
            throw new IdempotencyKeyConflictException(context.id());
        }
    }

    /**
     * Polls until the concurrent execution completes.
     *
     * @return the completed record, or {@code null} when the key vanished
     *         (released by a failed execution, or expired)
     * @throws IdempotencyInProgressException on timeout
     */
    private IdempotencyRecord awaitCompletion(IdempotencyContext context, Instant deadline) {
        while (true) {
            sleep(pollInterval);
            Optional<IdempotencyRecord> found = store.find(context.id());
            if (found.isEmpty()) {
                return null;
            }
            if (found.get().isCompleted()) {
                return found.get();
            }
            if (!clock.instant().isBefore(deadline)) {
                throw new IdempotencyInProgressException(context.id(), true);
            }
        }
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IdempotencyException("Interrupted while waiting for a concurrent execution", e);
        }
    }

    /**
     * Failing to release must not hide the business failure that caused it, so
     * it travels as a suppressed exception.
     */
    private void releaseQuietly(IdempotencyContext context, Throwable failure) {
        try {
            store.release(context.id());
        } catch (RuntimeException releaseFailure) {
            failure.addSuppressed(releaseFailure);
        }
    }
}

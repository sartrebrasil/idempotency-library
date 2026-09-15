package com.example.idempotency.spi;

import com.example.idempotency.model.IdempotencyKeyId;
import com.example.idempotency.model.IdempotencyRecord;

import java.util.Optional;

/**
 * Persistence contract for reservations. Implementations must honour one thing
 * above all: {@link #reserve(IdempotencyRecord)} is atomic. Everything else in
 * the library is built on top of that single guarantee.
 *
 * <p>Implementations are expected to be thread safe and safe across processes
 * (the whole point is deduplicating concurrent callers on different nodes).
 */
public interface IdempotencyStore {

    /**
     * Stores {@code candidate} only if no live record exists for its id, in a
     * single atomic operation (unique constraint, {@code SET NX}, unique index
     * — whatever the backend offers).
     *
     * <p>An expired record must be treated as absent, so that a new candidate
     * takes its place.
     *
     * @return empty when the reservation was taken by this caller, or the
     *         existing record when another caller already holds the key
     */
    Optional<IdempotencyRecord> reserve(IdempotencyRecord candidate);

    /**
     * Marks the reservation completed and attaches the result. Keeps the
     * original expiry — completion must not extend the deduplication window.
     * A no-op when the key is gone (expired or released concurrently).
     */
    void complete(IdempotencyKeyId id, String resultType, byte[] resultPayload);

    /**
     * Drops the reservation so a retry can run. Called when the guarded
     * operation fails. A no-op when the key is already gone.
     */
    void release(IdempotencyKeyId id);

    /**
     * Reads the current record, or empty when absent or expired. Used while
     * waiting for a concurrent execution to finish.
     */
    Optional<IdempotencyRecord> find(IdempotencyKeyId id);
}

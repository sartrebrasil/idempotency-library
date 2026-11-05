package com.example.idempotency.spi;

import com.example.idempotency.model.IdempotencyKeyId;
import com.example.idempotency.model.IdempotencyRecord;

import java.time.Instant;
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
     * Marks the reservation completed and attaches the result, atomically and
     * only while {@code owner} still holds it. A no-op when the key is gone
     * (expired or released) or held by another owner (it expired and was taken
     * over): a slow caller never overwrites the record of the one after it.
     *
     * @param expiresAt new end of the deduplication window, {@code null} to
     *                  keep the reservation's. A short reservation TTL with a
     *                  longer completed TTL keeps a crashed caller from holding
     *                  the key for the whole window
     * @return whether the record was completed
     */
    boolean complete(IdempotencyKeyId id, String owner, String resultType, byte[] resultPayload, Instant expiresAt);

    /**
     * Drops the reservation so a retry can run, only while {@code owner} still
     * holds it. Called when the guarded operation fails. A no-op when the key
     * is gone or held by another owner.
     *
     * @return whether the record was removed
     */
    boolean release(IdempotencyKeyId id, String owner);

    /**
     * Reads the current record, or empty when absent or expired. Used while
     * waiting for a concurrent execution to finish.
     */
    Optional<IdempotencyRecord> find(IdempotencyKeyId id);
}

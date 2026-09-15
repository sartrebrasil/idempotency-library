package com.example.idempotency.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Persisted state of one reservation.
 *
 * <p>{@code resultPayload} is held as raw bytes so the core stays free of any
 * serialization library; the {@code resultType} names the class those bytes
 * decode to. Note that, being an array, it is compared by reference in
 * {@code equals} — records are matched by {@link #id()} in practice.
 *
 * @param id            key plus scope
 * @param status        lifecycle state
 * @param fingerprint   hash of the request payload, or {@code null} when
 *                      fingerprinting is off
 * @param resultType    class name the payload decodes to, {@code null} for
 *                      void or non-replayable operations
 * @param resultPayload serialized result, {@code null} until completion
 * @param createdAt     when the key was reserved
 * @param expiresAt     when the key stops being honoured
 */
public record IdempotencyRecord(
        IdempotencyKeyId id,
        IdempotencyStatus status,
        String fingerprint,
        String resultType,
        byte[] resultPayload,
        Instant createdAt,
        Instant expiresAt) {

    public IdempotencyRecord {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    }

    /** A fresh reservation, before the operation runs. */
    public static IdempotencyRecord inProgress(IdempotencyKeyId id, String fingerprint, Instant createdAt,
            Instant expiresAt) {
        return new IdempotencyRecord(id, IdempotencyStatus.IN_PROGRESS, fingerprint, null, null, createdAt, expiresAt);
    }

    /** Same reservation, now carrying the result of a successful execution. */
    public IdempotencyRecord completedWith(String resultType, byte[] resultPayload) {
        return new IdempotencyRecord(id, IdempotencyStatus.COMPLETED, fingerprint, resultType, resultPayload,
                createdAt, expiresAt);
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public boolean isCompleted() {
        return status == IdempotencyStatus.COMPLETED;
    }
}

package com.example.idempotency.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

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
 * @param owner         token of the execution holding the reservation; only
 *                      that owner may complete or release it, so a slow caller
 *                      whose reservation expired cannot touch the record of the
 *                      caller that took the key over. {@code null} on records
 *                      written before owner tokens existed
 */
public record IdempotencyRecord(
        IdempotencyKeyId id,
        IdempotencyStatus status,
        String fingerprint,
        String resultType,
        byte[] resultPayload,
        Instant createdAt,
        Instant expiresAt,
        String owner) {

    public IdempotencyRecord {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    }

    /** A fresh reservation, before the operation runs, held by {@code owner}. */
    public static IdempotencyRecord inProgress(IdempotencyKeyId id, String owner, String fingerprint,
            Instant createdAt, Instant expiresAt) {
        Objects.requireNonNull(owner, "owner must not be null");
        return new IdempotencyRecord(id, IdempotencyStatus.IN_PROGRESS, fingerprint, null, null, createdAt, expiresAt,
                owner);
    }

    /** A fresh reservation with a new random owner token. */
    public static IdempotencyRecord inProgress(IdempotencyKeyId id, String fingerprint, Instant createdAt,
            Instant expiresAt) {
        return inProgress(id, newOwner(), fingerprint, createdAt, expiresAt);
    }

    public static String newOwner() {
        return UUID.randomUUID().toString();
    }

    /**
     * Same reservation, now carrying the result of a successful execution.
     *
     * @param newExpiresAt new end of the deduplication window, {@code null} to keep the reservation's
     */
    public IdempotencyRecord completedWith(String resultType, byte[] resultPayload, Instant newExpiresAt) {
        return new IdempotencyRecord(id, IdempotencyStatus.COMPLETED, fingerprint, resultType, resultPayload,
                createdAt, newExpiresAt == null ? expiresAt : newExpiresAt, owner);
    }

    public boolean ownedBy(String candidate) {
        return owner != null && owner.equals(candidate);
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public boolean isCompleted() {
        return status == IdempotencyStatus.COMPLETED;
    }
}

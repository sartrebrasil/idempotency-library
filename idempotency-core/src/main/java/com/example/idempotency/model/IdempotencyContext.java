package com.example.idempotency.model;

import java.time.Duration;
import java.util.Objects;

/**
 * Everything the engine needs for one guarded call: what the key is and how to
 * behave when it already exists. Built by the caller (the Spring aspect, or
 * application code using the engine directly).
 *
 * @param id                key plus scope
 * @param fingerprint       hash of the request payload, {@code null} to skip
 *                          the payload conflict check
 * @param ttl               how long the reservation stays valid
 * @param replay            whether to store and replay the result
 * @param waitForCompletion how long to wait for a concurrent execution of the
 *                          same key, {@code null} to fail fast
 */
public record IdempotencyContext(
        IdempotencyKeyId id,
        String fingerprint,
        Duration ttl,
        boolean replay,
        Duration waitForCompletion) {

    public IdempotencyContext {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(ttl, "ttl must not be null");
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl must be positive, got " + ttl);
        }
        if (waitForCompletion != null && waitForCompletion.isNegative()) {
            throw new IllegalArgumentException("waitForCompletion must not be negative, got " + waitForCompletion);
        }
    }

    /** Fail-fast context with result replay enabled and no payload check. */
    public static IdempotencyContext of(IdempotencyKeyId id, Duration ttl) {
        return new IdempotencyContext(id, null, ttl, true, null);
    }
}

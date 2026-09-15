package com.example.idempotency.model;

import java.util.Objects;

/**
 * Identity of a reservation: the caller supplied key plus the scope it belongs
 * to. Stores must enforce uniqueness over the pair, never over the key alone.
 *
 * @param key   caller supplied idempotency key
 * @param scope logical operation the key belongs to
 */
public record IdempotencyKeyId(String key, String scope) {

    public IdempotencyKeyId {
        requireText(key, "key");
        requireText(scope, "scope");
    }

    private static void requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    @Override
    public String toString() {
        return scope + "::" + key;
    }
}

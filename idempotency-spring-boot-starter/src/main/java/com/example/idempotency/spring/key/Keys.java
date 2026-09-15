package com.example.idempotency.spring.key;

import java.util.Optional;

/** Normalizes whatever a key source produced into a usable key. */
final class Keys {

    private Keys() {
    }

    /** @return the trimmed string form of {@code value}, or empty when null or blank */
    static Optional<String> asKey(Object value) {
        if (value == null) {
            return Optional.empty();
        }
        String key = String.valueOf(value).trim();
        return key.isEmpty() ? Optional.empty() : Optional.of(key);
    }
}

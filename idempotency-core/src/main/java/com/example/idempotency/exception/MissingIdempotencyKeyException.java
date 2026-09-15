package com.example.idempotency.exception;

/**
 * No key source produced a value for a method annotated
 * {@code @Idempotent} — no expression, no annotated parameter, no header.
 * Guarding nothing is worse than failing, so the call is rejected (400 over
 * HTTP).
 */
public class MissingIdempotencyKeyException extends IdempotencyException {

    private static final long serialVersionUID = 1L;

    public MissingIdempotencyKeyException(String scope) {
        super("No idempotency key resolved for " + scope);
    }
}

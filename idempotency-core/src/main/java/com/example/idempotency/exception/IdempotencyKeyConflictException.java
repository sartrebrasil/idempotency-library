package com.example.idempotency.exception;

import com.example.idempotency.model.IdempotencyKeyId;

/**
 * The key was already used with a different payload. Replaying the stored
 * result would answer the wrong question, so the call is rejected (422 over
 * HTTP). Almost always a client bug: a key reused across distinct requests.
 */
public class IdempotencyKeyConflictException extends IdempotencyException {

    private static final long serialVersionUID = 1L;

    private final IdempotencyKeyId id;

    public IdempotencyKeyConflictException(IdempotencyKeyId id) {
        super("Idempotency key " + id + " was already used with a different payload");
        this.id = id;
    }

    public IdempotencyKeyId getId() {
        return id;
    }
}

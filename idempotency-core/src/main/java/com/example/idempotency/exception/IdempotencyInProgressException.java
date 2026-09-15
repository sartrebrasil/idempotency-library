package com.example.idempotency.exception;

import com.example.idempotency.model.IdempotencyKeyId;

/**
 * The key is held by an execution that has not finished yet, so there is no
 * result to replay. Retry after a backoff: over HTTP this maps to 409, in a
 * queue listener it lets the message be redelivered.
 */
public class IdempotencyInProgressException extends IdempotencyException {

    private static final long serialVersionUID = 1L;

    private final IdempotencyKeyId id;

    public IdempotencyInProgressException(IdempotencyKeyId id, boolean afterWaiting) {
        super("Operation for idempotency key " + id + " is already in progress"
                + (afterWaiting ? " and did not complete within the configured wait" : ""));
        this.id = id;
    }

    public IdempotencyKeyId getId() {
        return id;
    }
}

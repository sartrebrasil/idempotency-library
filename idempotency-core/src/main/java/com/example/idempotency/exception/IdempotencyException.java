package com.example.idempotency.exception;

/** Base type for every failure raised by the idempotency layer itself. */
public class IdempotencyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public IdempotencyException(String message) {
        super(message);
    }

    public IdempotencyException(String message, Throwable cause) {
        super(message, cause);
    }
}

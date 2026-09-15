package com.example.idempotency.exception;

/** The backing store could not be read or written. */
public class IdempotencyStoreException extends IdempotencyException {

    private static final long serialVersionUID = 1L;

    public IdempotencyStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}

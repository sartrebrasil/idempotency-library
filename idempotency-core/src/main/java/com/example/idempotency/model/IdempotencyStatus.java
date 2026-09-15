package com.example.idempotency.model;

/**
 * Lifecycle of a reservation. There is no {@code FAILED} state on purpose: a
 * failed operation releases its key so a legitimate retry can run.
 */
public enum IdempotencyStatus {

    /** Reserved, operation still running (or the process died mid-flight). */
    IN_PROGRESS,

    /** Operation finished successfully; the stored result can be replayed. */
    COMPLETED
}

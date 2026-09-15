package com.example.idempotency.core;

/**
 * The guarded operation. Declares {@code Throwable} so an AOP interceptor can
 * hand over {@code ProceedingJoinPoint::proceed} as-is; application code that
 * prefers unchecked signatures uses
 * {@link IdempotencyEngine#supply(com.example.idempotency.model.IdempotencyContext, java.util.function.Supplier)}.
 */
@FunctionalInterface
public interface IdempotentOperation<T> {

    T execute() throws Throwable;
}

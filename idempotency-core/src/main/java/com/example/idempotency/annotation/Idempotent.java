package com.example.idempotency.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method whose execution must happen at most once per idempotency key.
 *
 * <p>The key is resolved from the first available source, in this order:
 * the {@link #key()} expression, a parameter annotated with
 * {@link IdempotentKey}, then (in HTTP requests) the {@code Idempotency-Key}
 * header. When no source yields a key the call fails fast.
 *
 * <p>This annotation lives in the core module so that non-Spring runtimes can
 * read it, but interpreting it requires an interceptor — the Spring starter
 * ships one.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {

    /**
     * Expression yielding the key (SpEL in the Spring starter, e.g.
     * {@code "#order.id"}). Empty means: fall back to the other key sources.
     */
    String key() default "";

    /**
     * Logical scope the key belongs to. Empty derives it from the call site:
     * {@code "POST /orders"} for HTTP requests, {@code "com.acme.Listener#consume"}
     * otherwise. Two operations sharing a business identifier need distinct
     * scopes to avoid one replaying the other's result.
     */
    String scope() default "";

    /**
     * How long the key stays reserved, as an ISO-8601 duration
     * ({@code "PT24H"}). Empty uses the configured default.
     */
    String ttl() default "";

    /**
     * Whether the result is stored and returned again on a repeated call.
     * {@code false} only deduplicates: repeated calls return {@code null}
     * without re-executing.
     */
    boolean replay() default true;

    /**
     * How long to wait for a concurrent execution of the same key to finish,
     * as an ISO-8601 duration. Empty (the default) fails fast with
     * {@code IdempotencyInProgressException} instead of blocking the thread.
     */
    String waitForCompletion() default "";

    /**
     * Whether to hash the arguments and reject a repeated key carrying a
     * different payload. Turn off when arguments are not serializable or
     * legitimately vary between retries.
     */
    boolean fingerprintPayload() default true;
}

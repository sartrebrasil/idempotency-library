package com.example.idempotency.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the parameter carrying the idempotency key, for methods that are not
 * served over HTTP (queue listeners, scheduled jobs, plain services).
 *
 * <p>Use a business identifier (order id, transaction id, SQS
 * {@code MessageDeduplicationId}). Do not use a value the producer regenerates
 * per delivery, such as the SQS {@code MessageId}: a duplicate would carry a
 * different value and slip through.
 */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface IdempotentKey {

    /**
     * Optional property path inside the annotated argument, e.g. {@code "id"}
     * or {@code "header.transactionId"}. Empty uses the argument itself.
     */
    String value() default "";
}

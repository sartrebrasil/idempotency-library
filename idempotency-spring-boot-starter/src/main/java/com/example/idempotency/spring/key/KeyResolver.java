package com.example.idempotency.spring.key;

import java.util.Optional;

/**
 * One source of idempotency keys. The aspect asks each resolver in
 * {@code @Order} sequence and takes the first non-empty answer, so adding a
 * source (a message property, a tracing header) means publishing another bean.
 */
public interface KeyResolver {

    /** @return the key, or empty when this source has nothing to say */
    Optional<String> resolve(IdempotentInvocation invocation);
}

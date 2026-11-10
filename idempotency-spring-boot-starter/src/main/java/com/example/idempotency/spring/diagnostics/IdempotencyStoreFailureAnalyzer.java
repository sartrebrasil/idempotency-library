package com.example.idempotency.spring.diagnostics;

import com.example.idempotency.spi.IdempotencyStore;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;

import java.util.Set;

/**
 * Turns "no / several IdempotencyStore beans" into a message that names the missing module or the
 * property to set. Runs ahead of Spring Boot's generic bean analyzers.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
public class IdempotencyStoreFailureAnalyzer extends AbstractFailureAnalyzer<NoSuchBeanDefinitionException> {

    private static final Set<String> MODULE_STORES = Set.of("jdbc", "redis", "mongo");

    private final Environment environment;

    public IdempotencyStoreFailureAnalyzer(Environment environment) {
        this.environment = environment;
    }

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, NoSuchBeanDefinitionException cause) {
        if (cause.getBeanType() == null || !IdempotencyStore.class.isAssignableFrom(cause.getBeanType())) {
            return null;
        }
        if (cause instanceof NoUniqueBeanDefinitionException unique) {
            return new FailureAnalysis(
                    "Several idempotency stores are configured: " + unique.getBeanNamesFound() + ".",
                    "Set idempotency.store to the one to use (memory, jdbc, redis or mongo), "
                            + "or remove the store modules you do not need.",
                    cause);
        }
        String store = environment.getProperty("idempotency.store");
        if (store != null && MODULE_STORES.contains(store.trim().toLowerCase())) {
            return new FailureAnalysis(
                    "idempotency.store is '" + store + "' but no store for it is on the classpath.",
                    "Add the dependency com.example.idempotency:idempotency-store-" + store.trim().toLowerCase() + ".",
                    cause);
        }
        return new FailureAnalysis(
                "No idempotency store is configured (idempotency.store='" + store + "').",
                "Set idempotency.store to memory, jdbc, redis or mongo, and add the matching "
                        + "idempotency-store-* module for jdbc, redis or mongo.",
                cause);
    }
}

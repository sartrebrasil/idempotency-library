package com.example.idempotency.spring.store.jdbc;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Relational backend, prefix {@code idempotency.jdbc}. It has no native expiry, hence the purge job. */
@ConfigurationProperties("idempotency.jdbc")
public class JdbcIdempotencyProperties {

    /** Whether to delete expired rows on a schedule. Turn off to purge by your own means. */
    private boolean cleanupEnabled = true;

    /**
     * Whether to add {@code classpath:db/idempotency/<vendor>} to the application's Flyway locations.
     * Turn off to manage the {@code idempotency_record} table by your own means.
     */
    private boolean flywayEnabled = true;

    /** Delay between purge runs. */
    private Duration cleanupInterval = Duration.ofMinutes(15);

    /**
     * {@code requires-new}: writes in their own transaction. {@code join}: writes join the caller's
     * transaction (reserve in a savepoint), so they commit with the business work.
     */
    private JdbcIdempotencyStore.TransactionMode transactionMode = JdbcIdempotencyStore.TransactionMode.REQUIRES_NEW;

    public JdbcIdempotencyStore.TransactionMode getTransactionMode() {
        return transactionMode;
    }

    public void setTransactionMode(JdbcIdempotencyStore.TransactionMode transactionMode) {
        this.transactionMode = transactionMode;
    }

    public boolean isCleanupEnabled() {
        return cleanupEnabled;
    }

    public void setCleanupEnabled(boolean cleanupEnabled) {
        this.cleanupEnabled = cleanupEnabled;
    }

    public boolean isFlywayEnabled() {
        return flywayEnabled;
    }

    public void setFlywayEnabled(boolean flywayEnabled) {
        this.flywayEnabled = flywayEnabled;
    }

    public Duration getCleanupInterval() {
        return cleanupInterval;
    }

    public void setCleanupInterval(Duration cleanupInterval) {
        this.cleanupInterval = cleanupInterval;
    }
}

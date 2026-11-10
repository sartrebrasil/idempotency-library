package com.example.idempotency.spring.store.jdbc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Deletes expired rows from the relational store on a fixed delay. Redis and
 * MongoDB expire records themselves; SQL does not.
 */
public class IdempotencyCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCleanupScheduler.class);

    private final JdbcIdempotencyStore store;

    public IdempotencyCleanupScheduler(JdbcIdempotencyStore store) {
        this.store = store;
    }

    @Scheduled(fixedDelayString = "${idempotency.jdbc.cleanup-interval:PT15M}",
            initialDelayString = "${idempotency.jdbc.cleanup-interval:PT15M}")
    public void purgeExpired() {
        int deleted = store.purgeExpired();
        if (deleted > 0) {
            log.debug("Purged {} expired idempotency records", deleted);
        }
    }
}

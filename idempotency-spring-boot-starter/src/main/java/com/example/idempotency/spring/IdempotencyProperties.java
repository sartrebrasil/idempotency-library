package com.example.idempotency.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Configuration for the idempotency layer, prefix {@code idempotency}. */
@ConfigurationProperties("idempotency")
public class IdempotencyProperties {

    /**
     * Which backend keeps the reservations. Chosen explicitly on purpose: with
     * several backends on the classpath, guessing is worse than asking.
     */
    public enum StoreType {
        MEMORY, JDBC, REDIS, MONGO
    }

    /**
     * Backend for reservations. {@code MEMORY} does not deduplicate across
     * instances, so production deployments set {@code jdbc}, {@code redis} or
     * {@code mongo}.
     */
    private StoreType store = StoreType.MEMORY;

    /** Deduplication window applied when {@code @Idempotent} does not set its own TTL. */
    private Duration defaultTtl = Duration.ofHours(24);

    /** Header read as the key in HTTP requests. */
    private String headerName = "Idempotency-Key";

    /** How often to re-check the store while waiting for a concurrent execution. */
    private Duration pollInterval = Duration.ofMillis(50);

    /** Whether to map idempotency failures to HTTP status codes (web applications only). */
    private boolean webExceptionHandler = true;

    private final Jdbc jdbc = new Jdbc();

    private final Redis redis = new Redis();

    private final Mongo mongo = new Mongo();

    public StoreType getStore() {
        return store;
    }

    public void setStore(StoreType store) {
        this.store = store;
    }

    public Duration getDefaultTtl() {
        return defaultTtl;
    }

    public void setDefaultTtl(Duration defaultTtl) {
        this.defaultTtl = defaultTtl;
    }

    public String getHeaderName() {
        return headerName;
    }

    public void setHeaderName(String headerName) {
        this.headerName = headerName;
    }

    public Duration getPollInterval() {
        return pollInterval;
    }

    public void setPollInterval(Duration pollInterval) {
        this.pollInterval = pollInterval;
    }

    public boolean isWebExceptionHandler() {
        return webExceptionHandler;
    }

    public void setWebExceptionHandler(boolean webExceptionHandler) {
        this.webExceptionHandler = webExceptionHandler;
    }

    public Jdbc getJdbc() {
        return jdbc;
    }

    public Redis getRedis() {
        return redis;
    }

    public Mongo getMongo() {
        return mongo;
    }

    /** Relational backend. Unlike Redis and MongoDB it has no native expiry, hence the purge job. */
    public static class Jdbc {

        /** Whether to delete expired rows on a schedule. Turn off to purge by your own means. */
        private boolean cleanupEnabled = true;

        /** Delay between purge runs. */
        private Duration cleanupInterval = Duration.ofMinutes(15);

        public boolean isCleanupEnabled() {
            return cleanupEnabled;
        }

        public void setCleanupEnabled(boolean cleanupEnabled) {
            this.cleanupEnabled = cleanupEnabled;
        }

        public Duration getCleanupInterval() {
            return cleanupInterval;
        }

        public void setCleanupInterval(Duration cleanupInterval) {
            this.cleanupInterval = cleanupInterval;
        }
    }

    public static class Redis {

        /** Prefix for every key written by this library. */
        private String keyPrefix = "idempotency:";

        public String getKeyPrefix() {
            return keyPrefix;
        }

        public void setKeyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
        }
    }

    public static class Mongo {

        /** Collection holding the reservations. */
        private String collection = "idempotency_record";

        public String getCollection() {
            return collection;
        }

        public void setCollection(String collection) {
            this.collection = collection;
        }
    }
}

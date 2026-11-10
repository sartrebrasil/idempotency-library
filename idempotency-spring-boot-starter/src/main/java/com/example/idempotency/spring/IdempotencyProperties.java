package com.example.idempotency.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Configuration for the idempotency layer, prefix {@code idempotency}. */
@ConfigurationProperties("idempotency")
public class IdempotencyProperties {

    /**
     * Backend for reservations: {@code memory}, {@code jdbc}, {@code redis} or {@code mongo}. Unset, the
     * store module on the classpath is used, and {@code memory} when there is none. Required only when
     * several store modules are present. {@code memory} does not deduplicate across instances.
     */
    private String store;

    /** Deduplication window applied when {@code @Idempotent} does not set its own TTL. */
    private Duration defaultTtl = Duration.ofHours(24);

    /** Header read as the key in HTTP requests. */
    private String headerName = "Idempotency-Key";

    /** How often to re-check the store while waiting for a concurrent execution. */
    private Duration pollInterval = Duration.ofMillis(50);

    /** Whether to map idempotency failures to HTTP status codes (web applications only). */
    private boolean webExceptionHandler = true;

    public String getStore() {
        return store;
    }

    public void setStore(String store) {
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
}

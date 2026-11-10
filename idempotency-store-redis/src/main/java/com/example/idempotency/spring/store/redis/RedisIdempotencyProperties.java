package com.example.idempotency.spring.store.redis;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Redis backend, prefix {@code idempotency.redis}. */
@ConfigurationProperties("idempotency.redis")
public class RedisIdempotencyProperties {

    /** Prefix for every key written by this library. */
    private String keyPrefix = "idempotency:";

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }
}

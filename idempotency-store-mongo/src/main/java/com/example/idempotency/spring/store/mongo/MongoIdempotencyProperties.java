package com.example.idempotency.spring.store.mongo;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** MongoDB backend, prefix {@code idempotency.mongo}. */
@ConfigurationProperties("idempotency.mongo")
public class MongoIdempotencyProperties {

    /** Collection holding the reservations. */
    private String collection = "idempotency_record";

    public String getCollection() {
        return collection;
    }

    public void setCollection(String collection) {
        this.collection = collection;
    }
}

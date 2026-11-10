package com.example.idempotency.spring.store.mongo;

import com.example.idempotency.spring.IdempotencyAutoConfiguration;
import com.example.idempotency.spi.IdempotencyStore;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Clock;

/** Wires the MongoDB store when this module is on the classpath and {@code idempotency.store}
 * is unset or {@code mongo}. */
// before: the in-memory fallback in IdempotencyAutoConfiguration backs off once this store exists
@AutoConfiguration(before = IdempotencyAutoConfiguration.class)
@ConditionalOnProperty(name = "idempotency.store", havingValue = "mongo", matchIfMissing = true)
@ConditionalOnClass(MongoTemplate.class)
@EnableConfigurationProperties(MongoIdempotencyProperties.class)
public class MongoStoreAutoConfiguration {

    @Bean(initMethod = "ensureIndexes")
    @ConditionalOnMissingBean(IdempotencyStore.class)
    MongoIdempotencyStore mongoIdempotencyStore(MongoTemplate mongoTemplate, MongoIdempotencyProperties properties,
            Clock clock) {
        return new MongoIdempotencyStore(mongoTemplate, properties.getCollection(), clock);
    }
}

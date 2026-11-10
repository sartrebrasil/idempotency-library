package com.example.idempotency.spring.store.redis;

import com.example.idempotency.spring.IdempotencyAutoConfiguration;
import com.example.idempotency.spi.IdempotencyStore;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;

/** Wires the Redis store when this module is on the classpath and {@code idempotency.store}
 * is unset or {@code redis}. */
// before: the in-memory fallback in IdempotencyAutoConfiguration backs off once this store exists
@AutoConfiguration(before = IdempotencyAutoConfiguration.class)
@ConditionalOnProperty(name = "idempotency.store", havingValue = "redis", matchIfMissing = true)
@ConditionalOnClass(StringRedisTemplate.class)
@EnableConfigurationProperties(RedisIdempotencyProperties.class)
public class RedisStoreAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(IdempotencyStore.class)
    RedisIdempotencyStore redisIdempotencyStore(StringRedisTemplate redisTemplate,
            ObjectMapper idempotencyObjectMapper, RedisIdempotencyProperties properties, Clock clock) {
        return new RedisIdempotencyStore(redisTemplate, idempotencyObjectMapper, properties.getKeyPrefix(), clock);
    }
}

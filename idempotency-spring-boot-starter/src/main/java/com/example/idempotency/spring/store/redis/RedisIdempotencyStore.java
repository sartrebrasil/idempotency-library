package com.example.idempotency.spring.store.redis;

import com.example.idempotency.exception.IdempotencyStoreException;
import com.example.idempotency.model.IdempotencyKeyId;
import com.example.idempotency.model.IdempotencyRecord;
import com.example.idempotency.spi.IdempotencyStore;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Redis store. Atomicity is {@code SET key value NX PX ttl} — one round trip,
 * no transaction — and expiry is the key's own TTL, so nothing has to purge.
 *
 * <p>Durability is Redis' durability: with AOF/RDB off, a crash can drop keys
 * before their TTL and let an operation run twice. For payments and the like,
 * prefer the relational store or enable {@code appendfsync everysec} at least.
 */
public class RedisIdempotencyStore implements IdempotencyStore {

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final String keyPrefix;

    public RedisIdempotencyStore(StringRedisTemplate redis, ObjectMapper mapper, String keyPrefix) {
        this.redis = redis;
        this.mapper = mapper;
        this.keyPrefix = keyPrefix;
    }

    @Override
    public Optional<IdempotencyRecord> reserve(IdempotencyRecord candidate) {
        String key = redisKey(candidate.id());
        Duration ttl = Duration.between(candidate.createdAt(), candidate.expiresAt());

        if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, write(candidate), ttl))) {
            return Optional.empty();
        }
        // Lost the race. A key that already expired in between is reported as
        // held anyway: the caller retries rather than risking a second run.
        String existing = redis.opsForValue().get(key);
        return Optional.of(existing == null ? candidate : read(existing));
    }

    @Override
    public void complete(IdempotencyKeyId id, String resultType, byte[] resultPayload) {
        String key = redisKey(id);
        IdempotencyRecord current = find(id).orElse(null);
        if (current == null) {
            return; // expired or released meanwhile
        }
        Long remainingMillis = redis.getExpire(key, TimeUnit.MILLISECONDS);
        if (remainingMillis == null || remainingMillis <= 0) {
            return;
        }
        // SET ... XX keeps completion from resurrecting a key that just expired,
        // and reuses the remaining TTL so the window is not extended.
        redis.opsForValue().setIfPresent(key, write(current.completedWith(resultType, resultPayload)),
                Duration.ofMillis(remainingMillis));
    }

    @Override
    public void release(IdempotencyKeyId id) {
        redis.delete(redisKey(id));
    }

    @Override
    public Optional<IdempotencyRecord> find(IdempotencyKeyId id) {
        String value = redis.opsForValue().get(redisKey(id));
        return Optional.ofNullable(value).map(this::read);
    }

    private String redisKey(IdempotencyKeyId id) {
        return keyPrefix + id.scope() + "::" + id.key();
    }

    private String write(IdempotencyRecord record) {
        try {
            return mapper.writeValueAsString(record);
        } catch (JacksonException e) {
            throw new IdempotencyStoreException("Could not encode record " + record.id(), e);
        }
    }

    private IdempotencyRecord read(String json) {
        try {
            return mapper.readValue(json, IdempotencyRecord.class);
        } catch (JacksonException e) {
            throw new IdempotencyStoreException("Could not decode stored record: " + json, e);
        }
    }
}

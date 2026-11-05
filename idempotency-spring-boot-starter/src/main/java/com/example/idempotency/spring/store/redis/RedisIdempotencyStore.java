package com.example.idempotency.spring.store.redis;

import com.example.idempotency.exception.IdempotencyStoreException;
import com.example.idempotency.model.IdempotencyKeyId;
import com.example.idempotency.model.IdempotencyRecord;
import com.example.idempotency.spi.IdempotencyStore;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Redis store. Atomicity is {@code SET key value NX PX ttl} — one round trip,
 * no transaction — and expiry is the key's own TTL, so nothing has to purge.
 *
 * <p>Durability is Redis' durability: with AOF/RDB off, a crash can drop keys
 * before their TTL and let an operation run twice. For payments and the like,
 * prefer the relational store or enable {@code appendfsync everysec} at least.
 *
 * <p>{@code complete} and {@code release} are Lua scripts that compare the
 * stored owner token and write in the same atomic step.
 */
public class RedisIdempotencyStore implements IdempotencyStore {

    /** ARGV: owner, new record JSON, TTL in ms ({@code -1} keeps the remaining TTL). */
    private static final RedisScript<Long> COMPLETE = new DefaultRedisScript<>("""
            local v = redis.call('GET', KEYS[1])
            if not v or cjson.decode(v).owner ~= ARGV[1] then return 0 end
            local ttl = tonumber(ARGV[3])
            if ttl < 0 then ttl = redis.call('PTTL', KEYS[1]) end
            if ttl <= 0 then return 0 end
            redis.call('SET', KEYS[1], ARGV[2], 'PX', ttl)
            return 1
            """, Long.class);

    /** ARGV: owner. */
    private static final RedisScript<Long> RELEASE = new DefaultRedisScript<>("""
            local v = redis.call('GET', KEYS[1])
            if not v or cjson.decode(v).owner ~= ARGV[1] then return 0 end
            redis.call('DEL', KEYS[1])
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final String keyPrefix;
    private final Clock clock;

    public RedisIdempotencyStore(StringRedisTemplate redis, ObjectMapper mapper, String keyPrefix) {
        this(redis, mapper, keyPrefix, Clock.systemUTC());
    }

    public RedisIdempotencyStore(StringRedisTemplate redis, ObjectMapper mapper, String keyPrefix, Clock clock) {
        this.redis = redis;
        this.mapper = mapper;
        this.keyPrefix = keyPrefix;
        this.clock = clock;
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
    public boolean complete(IdempotencyKeyId id, String owner, String resultType, byte[] resultPayload,
            Instant expiresAt) {
        IdempotencyRecord current = find(id).orElse(null);
        if (current == null || !current.ownedBy(owner)) {
            return false; // expired, released or taken over meanwhile
        }
        long ttl = -1;
        if (expiresAt != null) {
            ttl = Duration.between(clock.instant(), expiresAt).toMillis();
            if (ttl <= 0) {
                return false;
            }
        }
        // The script re-checks the owner, so a takeover between find and here is never overwritten
        Long done = redis.execute(COMPLETE, List.of(redisKey(id)), owner,
                write(current.completedWith(resultType, resultPayload, expiresAt)), Long.toString(ttl));
        return done != null && done == 1L;
    }

    @Override
    public boolean release(IdempotencyKeyId id, String owner) {
        Long done = redis.execute(RELEASE, List.of(redisKey(id)), owner);
        return done != null && done == 1L;
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

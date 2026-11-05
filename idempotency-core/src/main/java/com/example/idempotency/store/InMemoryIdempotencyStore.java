package com.example.idempotency.store;

import com.example.idempotency.model.IdempotencyKeyId;
import com.example.idempotency.model.IdempotencyRecord;
import com.example.idempotency.spi.IdempotencyStore;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Single-JVM store backed by a {@link ConcurrentHashMap}. Deduplicates across
 * threads, not across processes — fine for tests and single-node deployments,
 * useless behind a load balancer.
 *
 * <p>Expired entries are dropped when touched; nothing scans the map in the
 * background.
 */
public final class InMemoryIdempotencyStore implements IdempotencyStore {

    private final Map<IdempotencyKeyId, IdempotencyRecord> records = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryIdempotencyStore() {
        this(Clock.systemUTC());
    }

    public InMemoryIdempotencyStore(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public Optional<IdempotencyRecord> reserve(IdempotencyRecord candidate) {
        AtomicReference<IdempotencyRecord> existing = new AtomicReference<>();
        // compute() holds the bin lock, so check-and-insert is atomic.
        records.compute(candidate.id(), (key, current) -> {
            if (current == null || current.isExpired(clock.instant())) {
                return candidate;
            }
            existing.set(current);
            return current;
        });
        return Optional.ofNullable(existing.get());
    }

    @Override
    public boolean complete(IdempotencyKeyId id, String owner, String resultType, byte[] resultPayload,
            Instant expiresAt) {
        AtomicBoolean done = new AtomicBoolean();
        records.computeIfPresent(id, (key, current) -> {
            if (!current.ownedBy(owner) || current.isExpired(clock.instant())) {
                return current;
            }
            done.set(true);
            return current.completedWith(resultType, resultPayload, expiresAt);
        });
        return done.get();
    }

    @Override
    public boolean release(IdempotencyKeyId id, String owner) {
        AtomicBoolean done = new AtomicBoolean();
        records.computeIfPresent(id, (key, current) -> {
            if (!current.ownedBy(owner)) {
                return current;
            }
            done.set(true);
            return null;
        });
        return done.get();
    }

    @Override
    public Optional<IdempotencyRecord> find(IdempotencyKeyId id) {
        IdempotencyRecord record = records.get(id);
        if (record == null) {
            return Optional.empty();
        }
        if (record.isExpired(clock.instant())) {
            records.remove(id, record);
            return Optional.empty();
        }
        return Optional.of(record);
    }

    /** Visible for tests and diagnostics. */
    public int size() {
        return records.size();
    }
}

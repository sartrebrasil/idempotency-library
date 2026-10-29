package com.example.idempotency.spring.aspect;

import com.example.idempotency.core.Fingerprints;
import com.example.idempotency.exception.IdempotencyException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Method;

/**
 * Hashes the call arguments, so a key reused with a different payload can be
 * rejected instead of replaying the wrong answer.
 *
 * <p>Uses its own mapper with alphabetical property and map-key ordering: two
 * instances must hash the same payload identically, and Jackson's default
 * ordering does not guarantee that across nodes.
 */
public class PayloadFingerprinter {

    private final ObjectMapper mapper;

    public PayloadFingerprinter() {
        this.mapper = JsonMapper.builder()
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
    }

    public String fingerprint(Method method, Object[] args) {
        try {
            return Fingerprints.sha256Hex(mapper.writeValueAsBytes(args));
        } catch (JacksonException e) {
            throw new IdempotencyException("Could not fingerprint the arguments of " + method
                    + ". Make them serializable or set @Idempotent(fingerprintPayload = false).", e);
        }
    }
}

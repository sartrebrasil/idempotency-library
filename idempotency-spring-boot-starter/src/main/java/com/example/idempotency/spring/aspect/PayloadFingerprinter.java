package com.example.idempotency.spring.aspect;

import com.example.idempotency.core.Fingerprints;
import com.example.idempotency.exception.IdempotencyException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

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
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public String fingerprint(Method method, Object[] args) {
        try {
            return Fingerprints.sha256Hex(mapper.writeValueAsBytes(args));
        } catch (JsonProcessingException e) {
            throw new IdempotencyException("Could not fingerprint the arguments of " + method
                    + ". Make them serializable or set @Idempotent(fingerprintPayload = false).", e);
        }
    }
}

package com.example.idempotency.spring.serializer;

import com.example.idempotency.exception.IdempotencyException;
import com.example.idempotency.spi.ResultSerializer;
import org.springframework.util.ClassUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Stores results as JSON, which keeps records readable and survives a redeploy
 * better than Java serialization.
 *
 * <p>A {@code ResponseEntity} gets an envelope of its own when spring-web is on
 * the classpath ({@link ResponseEntityCodec}): Jackson cannot reconstruct one
 * directly, so status and body are stored separately and the entity is rebuilt
 * on replay. Headers are not preserved — a replayed response carries the status
 * and body of the first execution only.
 */
public class JacksonResultSerializer implements ResultSerializer {

    // spring-web is optional: ResponseEntityCodec is only touched when it is present
    private static final boolean WEB = ClassUtils.isPresent("org.springframework.http.ResponseEntity",
            JacksonResultSerializer.class.getClassLoader());

    private final ObjectMapper mapper;

    public JacksonResultSerializer(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public byte[] serialize(Object value) {
        try {
            if (WEB && ResponseEntityCodec.handles(value)) {
                return ResponseEntityCodec.serialize(mapper, value);
            }
            return mapper.writeValueAsBytes(value);
        } catch (JacksonException e) {
            throw new IdempotencyException("Could not serialize result of type " + value.getClass().getName(), e);
        }
    }

    @Override
    public Object deserialize(byte[] payload, String resultType) {
        try {
            Class<?> type = Class.forName(resultType);
            if (WEB && ResponseEntityCodec.handles(type)) {
                return ResponseEntityCodec.deserialize(mapper, payload);
            }
            return mapper.readValue(payload, type);
        } catch (JacksonException | ClassNotFoundException e) {
            throw new IdempotencyException("Could not deserialize stored result of type " + resultType, e);
        }
    }
}

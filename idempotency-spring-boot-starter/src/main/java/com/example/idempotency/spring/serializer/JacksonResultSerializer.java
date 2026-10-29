package com.example.idempotency.spring.serializer;

import com.example.idempotency.exception.IdempotencyException;
import com.example.idempotency.spi.ResultSerializer;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Stores results as JSON, which keeps records readable and survives a redeploy
 * better than Java serialization.
 *
 * <p>{@link ResponseEntity} gets an envelope of its own: Jackson cannot
 * reconstruct one directly, so status and body are stored separately and the
 * entity is rebuilt on replay. Headers are not preserved — a replayed response
 * carries the status and body of the first execution only.
 */
public class JacksonResultSerializer implements ResultSerializer {

    private final ObjectMapper mapper;

    public JacksonResultSerializer(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public byte[] serialize(Object value) {
        try {
            if (value instanceof ResponseEntity<?> response) {
                Object body = response.getBody();
                return mapper.writeValueAsBytes(new ResponseEnvelope(response.getStatusCode().value(),
                        body == null ? null : body.getClass().getName(),
                        body == null ? null : mapper.valueToTree(body)));
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
            if (ResponseEntity.class.isAssignableFrom(type)) {
                return rebuildResponse(mapper.readValue(payload, ResponseEnvelope.class));
            }
            return mapper.readValue(payload, type);
        } catch (JacksonException | ClassNotFoundException e) {
            throw new IdempotencyException("Could not deserialize stored result of type " + resultType, e);
        }
    }

    private ResponseEntity<Object> rebuildResponse(ResponseEnvelope envelope) throws ClassNotFoundException {
        HttpStatus status = HttpStatus.valueOf(envelope.status());
        if (envelope.body() == null) {
            return ResponseEntity.status(status).build();
        }
        Object body = mapper.treeToValue(envelope.body(), Class.forName(envelope.bodyType()));
        return ResponseEntity.status(status).body(body);
    }

    /** Stored shape of a {@link ResponseEntity}. */
    record ResponseEnvelope(int status, String bodyType, JsonNode body) {
    }
}

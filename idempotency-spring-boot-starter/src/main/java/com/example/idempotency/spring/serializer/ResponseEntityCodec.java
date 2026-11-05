package com.example.idempotency.spring.serializer;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** {@link ResponseEntity} support of {@link JacksonResultSerializer}; loaded only when spring-web is present. */
final class ResponseEntityCodec {

    private ResponseEntityCodec() {
    }

    static boolean handles(Object value) {
        return value instanceof ResponseEntity<?>;
    }

    static boolean handles(Class<?> type) {
        return ResponseEntity.class.isAssignableFrom(type);
    }

    static byte[] serialize(ObjectMapper mapper, Object value) {
        ResponseEntity<?> response = (ResponseEntity<?>) value;
        Object body = response.getBody();
        return mapper.writeValueAsBytes(new ResponseEnvelope(response.getStatusCode().value(),
                body == null ? null : body.getClass().getName(),
                body == null ? null : mapper.valueToTree(body)));
    }

    static ResponseEntity<Object> deserialize(ObjectMapper mapper, byte[] payload) throws ClassNotFoundException {
        ResponseEnvelope envelope = mapper.readValue(payload, ResponseEnvelope.class);
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

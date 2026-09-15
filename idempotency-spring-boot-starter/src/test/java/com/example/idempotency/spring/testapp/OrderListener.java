package com.example.idempotency.spring.testapp;

import com.example.idempotency.annotation.Idempotent;
import com.example.idempotency.annotation.IdempotentKey;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Stands in for a queue listener: no HTTP request, the key comes from the
 * message itself. Annotated exactly like a {@code @JmsListener} would be.
 */
@Component
public class OrderListener {

    private final List<String> processed = new CopyOnWriteArrayList<>();

    /** Key from the annotated parameter. */
    @Idempotent(ttl = "PT5M")
    public void consume(@IdempotentKey String orderId, String payload) {
        processed.add(orderId + ":" + payload);
    }

    /** Key from a property of the message, via SpEL. */
    @Idempotent(key = "#message.dedupId", ttl = "PT5M")
    public String consumeWithExpression(Message message) {
        processed.add(message.dedupId());
        return "handled-" + message.dedupId();
    }

    /** Key from a property path inside the argument. */
    @Idempotent(ttl = "PT5M", fingerprintPayload = false)
    public void consumeWithPath(@IdempotentKey("dedupId") Message message) {
        processed.add("path:" + message.dedupId());
    }

    public List<String> processed() {
        return processed;
    }

    public record Message(String dedupId, String body) {
    }
}

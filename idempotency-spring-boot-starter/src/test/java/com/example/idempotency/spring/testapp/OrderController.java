package com.example.idempotency.spring.testapp;

import com.example.idempotency.annotation.Idempotent;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Exercises the HTTP header key source and ResponseEntity replay. */
@RestController
@RequestMapping("/orders")
public class OrderController {

    private final AtomicInteger executions = new AtomicInteger();

    @PostMapping
    @Idempotent(ttl = "PT5M")
    public ResponseEntity<OrderResponse> create(@RequestBody OrderRequest request) {
        executions.incrementAndGet();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new OrderResponse(UUID.randomUUID().toString(), request.quantity()));
    }

    @PostMapping("/fail")
    @Idempotent(ttl = "PT5M")
    public ResponseEntity<OrderResponse> alwaysFails(@RequestBody OrderRequest request) {
        executions.incrementAndGet();
        throw new IllegalStateException("downstream exploded");
    }

    public int executions() {
        return executions.get();
    }

    public void resetExecutions() {
        executions.set(0);
    }

    public record OrderRequest(String product, int quantity) {
    }

    public record OrderResponse(String orderId, int quantity) {
    }
}

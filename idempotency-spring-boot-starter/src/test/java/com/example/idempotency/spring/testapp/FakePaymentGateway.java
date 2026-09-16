package com.example.idempotency.spring.testapp;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/** Implementation without annotations: the contract carries them. */
@Component
public class FakePaymentGateway implements PaymentGateway {

    private final AtomicInteger charges = new AtomicInteger();

    @Override
    public String charge(String transactionId, int amount) {
        return "receipt-" + transactionId + "-" + charges.incrementAndGet();
    }

    public int charges() {
        return charges.get();
    }
}

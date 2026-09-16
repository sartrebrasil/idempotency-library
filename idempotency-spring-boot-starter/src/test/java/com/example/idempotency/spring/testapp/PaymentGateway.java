package com.example.idempotency.spring.testapp;

import com.example.idempotency.annotation.Idempotent;
import com.example.idempotency.annotation.IdempotentKey;

/** Contract that declares the guard, with an implementation that does not repeat it. */
public interface PaymentGateway {

    @Idempotent(ttl = "PT5M")
    String charge(@IdempotentKey String transactionId, int amount);
}

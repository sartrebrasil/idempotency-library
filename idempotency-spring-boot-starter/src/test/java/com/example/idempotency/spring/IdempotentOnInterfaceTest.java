package com.example.idempotency.spring;

import com.example.idempotency.spring.testapp.FakePaymentGateway;
import com.example.idempotency.spring.testapp.PaymentGateway;
import com.example.idempotency.spring.testapp.TestApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Java does not inherit method annotations, so a guard declared only on the
 * interface must still be found through the implementation.
 */
@SpringBootTest(classes = TestApplication.class, properties = {
        "idempotency.store=memory",
        "spring.main.web-application-type=none",
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration"
})
class IdempotentOnInterfaceTest {

    @Autowired
    private PaymentGateway gateway;

    @Autowired
    private FakePaymentGateway implementation;

    @Test
    void annotationDeclaredOnTheInterfaceIsHonoured() {
        int before = implementation.charges();

        String first = gateway.charge("tx-1", 100);
        String second = gateway.charge("tx-1", 100);

        assertEquals(first, second, "the second call must replay the first receipt");
        assertEquals(1, implementation.charges() - before, "business logic must run once");
    }

    @Test
    void keyParameterDeclaredOnTheInterfaceDiscriminatesCalls() {
        gateway.charge("tx-2", 10);
        gateway.charge("tx-2", 10);
        int afterSameKey = implementation.charges();

        gateway.charge("tx-3", 10);

        assertEquals(afterSameKey + 1, implementation.charges(),
                "@IdempotentKey on the interface parameter must select the key");
    }
}

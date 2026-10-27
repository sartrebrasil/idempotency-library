package com.example.idempotency.spring;

import com.example.idempotency.exception.MissingIdempotencyKeyException;
import com.example.idempotency.spring.testapp.OrderListener;
import com.example.idempotency.spring.testapp.TestApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Same aspect, no HTTP: keys come from the annotated parameter or an
 * expression, exactly as a queue listener would declare them.
 */
@SpringBootTest(classes = TestApplication.class, properties = {
        "idempotency.store=memory",
        "spring.main.web-application-type=none",
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration,"
                + "org.springframework.boot.mongodb.autoconfigure.MongoAutoConfiguration,"
                + "org.springframework.boot.data.mongodb.autoconfigure.DataMongoAutoConfiguration"
})
class IdempotencyListenerTest {

    @Autowired
    private OrderListener listener;

    @Test
    void annotatedParameterKeyDeduplicatesRedeliveries() {
        listener.consume("order-100", "payload");
        listener.consume("order-100", "payload");

        assertEquals(1, listener.processed().stream().filter(entry -> entry.startsWith("order-100")).count());
    }

    @Test
    void expressionKeyDeduplicatesAndReplaysTheResult() {
        OrderListener.Message message = new OrderListener.Message("dedup-200", "body");

        assertEquals("handled-dedup-200", listener.consumeWithExpression(message));
        assertEquals("handled-dedup-200", listener.consumeWithExpression(message));

        assertEquals(1, listener.processed().stream().filter("dedup-200"::equals).count());
    }

    @Test
    void propertyPathKeyReadsInsideTheMessage() {
        listener.consumeWithPath(new OrderListener.Message("dedup-300", "body"));
        listener.consumeWithPath(new OrderListener.Message("dedup-300", "other body"));

        assertEquals(1, listener.processed().stream().filter("path:dedup-300"::equals).count(),
                "the key alone decides, the payload is not fingerprinted here");
    }

    @Test
    void nullKeyOutsideHttpIsRejected() {
        MissingIdempotencyKeyException failure = assertThrows(MissingIdempotencyKeyException.class,
                () -> listener.consume(null, "payload"));

        assertTrue(failure.getMessage().contains("OrderListener#consume"));
    }
}

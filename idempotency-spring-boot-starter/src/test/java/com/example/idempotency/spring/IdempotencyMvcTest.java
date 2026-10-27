package com.example.idempotency.spring;

import com.example.idempotency.spring.testapp.OrderController;
import com.example.idempotency.spring.testapp.TestApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** End-to-end behaviour over HTTP with the in-memory store. */
@SpringBootTest(classes = TestApplication.class, properties = {
        "idempotency.store=memory",
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration,"
                + "org.springframework.boot.mongodb.autoconfigure.MongoAutoConfiguration,"
                + "org.springframework.boot.data.mongodb.autoconfigure.DataMongoAutoConfiguration"
})
@AutoConfigureMockMvc
class IdempotencyMvcTest {

    private static final String BODY = "{\"product\":\"book\",\"quantity\":2}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrderController controller;

    @BeforeEach
    void resetCounter() {
        controller.resetExecutions();
    }

    @Test
    void repeatedRequestReplaysTheFirstResponse() throws Exception {
        String key = "key-replay";

        MvcResult first = createOrder(key, BODY).andExpect(status().isCreated()).andReturn();
        MvcResult second = createOrder(key, BODY).andExpect(status().isCreated()).andReturn();

        assertEquals(first.getResponse().getContentAsString(), second.getResponse().getContentAsString(),
                "replay must return the first response verbatim");
        assertEquals(1, controller.executions(), "business logic must run once");
    }

    @Test
    void differentKeysRunIndependently() throws Exception {
        createOrder("key-a", BODY).andExpect(status().isCreated());
        createOrder("key-b", BODY).andExpect(status().isCreated());

        assertEquals(2, controller.executions());
    }

    @Test
    void missingHeaderIsRejected() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
                .andExpect(status().isBadRequest());

        assertEquals(0, controller.executions());
    }

    @Test
    void sameKeyWithDifferentPayloadIsRejected() throws Exception {
        createOrder("key-conflict", BODY).andExpect(status().isCreated());

        createOrder("key-conflict", "{\"product\":\"book\",\"quantity\":99}")
                .andExpect(status().isUnprocessableEntity());

        assertEquals(1, controller.executions());
    }

    @Test
    void failedRequestCanBeRetriedWithTheSameKey() {
        String key = "key-failure";

        // The controller throws an unmapped exception, so MockMvc rethrows it;
        // what matters is that the key did not stay reserved.
        assertThrows(Exception.class, () -> mockMvc.perform(MockMvcRequestBuilders.post("/orders/fail")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY)));
        assertThrows(Exception.class, () -> mockMvc.perform(MockMvcRequestBuilders.post("/orders/fail")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY)));

        assertEquals(2, controller.executions(), "a failed call must not burn the key");
    }

    private ResultActions createOrder(String key, String body) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post("/orders")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }
}

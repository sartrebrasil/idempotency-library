package com.example.idempotency.spring;

import com.example.idempotency.spi.IdempotencyStore;
import com.example.idempotency.spring.diagnostics.IdempotencyStoreFailureAnalyzer;
import com.example.idempotency.spring.testapp.TestApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyStoreFailureAnalyzerTest {

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void startupNamesTheMissingStoreModule(CapturedOutput output) {
        assertThatThrownBy(() -> new SpringApplicationBuilder(TestApplication.class)
                .properties("idempotency.store=redis", "spring.main.web-application-type=none")
                .run().close());

        assertThat(output).contains("Add the dependency com.example.idempotency:idempotency-store-redis");
    }

    @Test
    void severalStoresAskForTheProperty() {
        FailureAnalysis analysis = new IdempotencyStoreFailureAnalyzer(new MockEnvironment())
                .analyze(new NoUniqueBeanDefinitionException(IdempotencyStore.class, "jdbcIdempotencyStore",
                        "redisIdempotencyStore"));

        assertThat(analysis).isNotNull();
        assertThat(analysis.getDescription()).contains("jdbcIdempotencyStore", "redisIdempotencyStore");
        assertThat(analysis.getAction()).contains("idempotency.store");
    }
}

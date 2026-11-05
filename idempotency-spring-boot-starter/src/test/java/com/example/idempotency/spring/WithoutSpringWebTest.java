package com.example.idempotency.spring;

import com.example.idempotency.core.IdempotencyEngine;
import com.example.idempotency.spi.IdempotencyStore;
import com.example.idempotency.spi.ResultSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** The stores and the engine without spring-web, as a messaging consumer uses them. */
class WithoutSpringWebTest {

    @Test
    void contextStartsWithoutSpringWeb() {
        new ApplicationContextRunner()
                .withClassLoader(new FilteredClassLoader("org.springframework.web", "org.springframework.http",
                        "jakarta.servlet"))
                .withConfiguration(AutoConfigurations.of(IdempotencyAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(IdempotencyStore.class);
                    assertThat(context).hasSingleBean(IdempotencyEngine.class);
                    ResultSerializer serializer = context.getBean(ResultSerializer.class);
                    assertThat(serializer.deserialize(serializer.serialize("ok"), String.class.getName())).isEqualTo("ok");
                });
    }
}

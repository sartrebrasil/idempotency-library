package com.example.idempotency.spring.store.jdbc;

import com.example.idempotency.spring.IdempotencyAutoConfiguration;
import com.example.idempotency.spi.IdempotencyStore;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;

/** Wires the JDBC store when this module is on the classpath and {@code idempotency.store}
 * is unset or {@code jdbc}. */
// before: the in-memory fallback in IdempotencyAutoConfiguration backs off once this store exists
@AutoConfiguration(before = IdempotencyAutoConfiguration.class)
@ConditionalOnProperty(name = "idempotency.store", havingValue = "jdbc", matchIfMissing = true)
@ConditionalOnClass(JdbcTemplate.class)
@EnableConfigurationProperties(JdbcIdempotencyProperties.class)
public class JdbcStoreAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(IdempotencyStore.class)
    JdbcIdempotencyStore jdbcIdempotencyStore(JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager, Clock clock, JdbcIdempotencyProperties properties) {
        return new JdbcIdempotencyStore(jdbcTemplate, transactionManager, clock, properties.getTransactionMode());
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer")
    @ConditionalOnProperty(name = "idempotency.jdbc.flyway-enabled", matchIfMissing = true)
    static class FlywayConfiguration {

        @Bean
        @ConditionalOnMissingBean
        IdempotencyFlywayLocationCustomizer idempotencyFlywayLocationCustomizer() {
            return new IdempotencyFlywayLocationCustomizer();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "idempotency.jdbc.cleanup-enabled", matchIfMissing = true)
    @EnableScheduling
    static class CleanupConfiguration {

        @Bean
        @ConditionalOnBean(JdbcIdempotencyStore.class)
        @ConditionalOnMissingBean
        IdempotencyCleanupScheduler idempotencyCleanupScheduler(JdbcIdempotencyStore store) {
            return new IdempotencyCleanupScheduler(store);
        }
    }
}

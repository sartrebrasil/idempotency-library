package com.example.idempotency.spring;

import com.example.idempotency.core.IdempotencyEngine;
import com.example.idempotency.spi.IdempotencyStore;
import com.example.idempotency.spi.ResultSerializer;
import com.example.idempotency.store.InMemoryIdempotencyStore;
import com.example.idempotency.spring.aspect.IdempotencyAdvisor;
import com.example.idempotency.spring.aspect.IdempotencyInterceptor;
import com.example.idempotency.spring.aspect.IdempotentMetadata;
import com.example.idempotency.spring.aspect.PayloadFingerprinter;
import com.example.idempotency.spring.key.AnnotatedParameterKeyResolver;
import com.example.idempotency.spring.key.ExpressionKeyResolver;
import com.example.idempotency.spring.key.HttpHeaderKeyResolver;
import com.example.idempotency.spring.key.KeyResolver;
import com.example.idempotency.spring.serializer.JacksonResultSerializer;
import com.example.idempotency.spring.store.jdbc.IdempotencyCleanupScheduler;
import com.example.idempotency.spring.store.jdbc.JdbcIdempotencyStore;
import com.example.idempotency.spring.store.mongo.MongoIdempotencyStore;
import com.example.idempotency.spring.store.redis.RedisIdempotencyStore;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.util.List;

/**
 * Wires the engine, the key resolvers, the aspect and exactly one store.
 *
 * <p>The store is chosen by {@code idempotency.store} instead of being guessed
 * from the classpath: an application that has both a datasource and Redis
 * should say which one guards its operations.
 */
@AutoConfiguration
@EnableConfigurationProperties(IdempotencyProperties.class)
public class IdempotencyAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public Clock idempotencyClock() {
        return Clock.systemUTC();
    }

    /**
     * Mapper for stored records. Unknown properties are tolerated so records
     * written by an older version of the library still load.
     */
    @Bean
    @ConditionalOnMissingBean(name = "idempotencyObjectMapper")
    public ObjectMapper idempotencyObjectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    @Bean
    @ConditionalOnMissingBean
    public ResultSerializer idempotencyResultSerializer(ObjectMapper idempotencyObjectMapper) {
        return new JacksonResultSerializer(idempotencyObjectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public PayloadFingerprinter payloadFingerprinter() {
        return new PayloadFingerprinter();
    }

    @Bean
    @ConditionalOnMissingBean
    public ExpressionKeyResolver expressionKeyResolver() {
        return new ExpressionKeyResolver();
    }

    @Bean
    @ConditionalOnMissingBean
    public IdempotentMetadata idempotentMetadata() {
        return new IdempotentMetadata();
    }

    @Bean
    @ConditionalOnMissingBean
    public AnnotatedParameterKeyResolver annotatedParameterKeyResolver(IdempotentMetadata metadata) {
        return new AnnotatedParameterKeyResolver(metadata);
    }

    @Bean
    @ConditionalOnWebApplication
    @ConditionalOnMissingBean
    public HttpHeaderKeyResolver httpHeaderKeyResolver(IdempotencyProperties properties) {
        return new HttpHeaderKeyResolver(properties.getHeaderName());
    }

    @Bean
    @ConditionalOnMissingBean
    public IdempotencyEngine idempotencyEngine(IdempotencyStore store, ResultSerializer serializer, Clock clock,
            IdempotencyProperties properties) {
        return new IdempotencyEngine(store, serializer, clock, properties.getPollInterval());
    }

    @Bean
    @ConditionalOnMissingBean
    public IdempotencyInterceptor idempotencyInterceptor(IdempotencyEngine engine, List<KeyResolver> keyResolvers,
            PayloadFingerprinter fingerprinter, IdempotentMetadata metadata, IdempotencyProperties properties) {
        return new IdempotencyInterceptor(engine, keyResolvers, fingerprinter, metadata, properties.getDefaultTtl());
    }

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    @ConditionalOnMissingBean
    public IdempotencyAdvisor idempotencyAdvisor(IdempotentMetadata metadata, IdempotencyInterceptor interceptor) {
        return new IdempotencyAdvisor(metadata, interceptor);
    }

    @Bean
    @ConditionalOnWebApplication
    @ConditionalOnProperty(name = "idempotency.web-exception-handler", matchIfMissing = true)
    @ConditionalOnMissingBean
    public com.example.idempotency.spring.web.IdempotencyExceptionHandler idempotencyExceptionHandler() {
        return new com.example.idempotency.spring.web.IdempotencyExceptionHandler();
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "idempotency.store", havingValue = "memory", matchIfMissing = true)
    static class MemoryStoreConfiguration {

        @Bean
        @ConditionalOnMissingBean(IdempotencyStore.class)
        InMemoryIdempotencyStore inMemoryIdempotencyStore(Clock clock) {
            log.warn("Idempotency is using the in-memory store: keys are not shared between instances. "
                    + "Set idempotency.store to jdbc, redis or mongo for a distributed guarantee.");
            return new InMemoryIdempotencyStore(clock);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "idempotency.store", havingValue = "jdbc")
    @ConditionalOnClass(JdbcTemplate.class)
    static class JdbcStoreConfiguration {

        @Bean
        @ConditionalOnMissingBean(IdempotencyStore.class)
        JdbcIdempotencyStore jdbcIdempotencyStore(JdbcTemplate jdbcTemplate,
                PlatformTransactionManager transactionManager, Clock clock) {
            return new JdbcIdempotencyStore(jdbcTemplate, transactionManager, clock);
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

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "idempotency.store", havingValue = "redis")
    @ConditionalOnClass(StringRedisTemplate.class)
    static class RedisStoreConfiguration {

        @Bean
        @ConditionalOnMissingBean(IdempotencyStore.class)
        RedisIdempotencyStore redisIdempotencyStore(StringRedisTemplate redisTemplate,
                ObjectMapper idempotencyObjectMapper, IdempotencyProperties properties) {
            return new RedisIdempotencyStore(redisTemplate, idempotencyObjectMapper,
                    properties.getRedis().getKeyPrefix());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "idempotency.store", havingValue = "mongo")
    @ConditionalOnClass(MongoTemplate.class)
    static class MongoStoreConfiguration {

        @Bean(initMethod = "ensureIndexes")
        @ConditionalOnMissingBean(IdempotencyStore.class)
        MongoIdempotencyStore mongoIdempotencyStore(MongoTemplate mongoTemplate, IdempotencyProperties properties,
                Clock clock) {
            return new MongoIdempotencyStore(mongoTemplate, properties.getMongo().getCollection(), clock);
        }
    }
}

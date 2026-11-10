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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.List;

/**
 * Wires the engine, the key resolvers, the aspect and the in-memory fallback store.
 *
 * <p>A store module ({@code idempotency-store-*}) on the classpath wins on its own; the
 * in-memory store is only the fallback. With several store modules, {@code idempotency.store}
 * picks one.
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
        return JsonMapper.builder()
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
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

    /** Web-only beans; spring-web is optional, so messaging consumers can use the stores without it. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.web.context.request.RequestContextHolder")
    @ConditionalOnWebApplication
    static class WebConfiguration {

        @Bean
        @ConditionalOnMissingBean
        HttpHeaderKeyResolver httpHeaderKeyResolver(IdempotencyProperties properties) {
            return new HttpHeaderKeyResolver(properties.getHeaderName());
        }

        @Bean
        @ConditionalOnProperty(name = "idempotency.web-exception-handler", matchIfMissing = true)
        @ConditionalOnMissingBean
        com.example.idempotency.spring.web.IdempotencyExceptionHandler idempotencyExceptionHandler() {
            return new com.example.idempotency.spring.web.IdempotencyExceptionHandler();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "idempotency.store", havingValue = "memory", matchIfMissing = true)
    static class MemoryStoreConfiguration {

        @Bean
        @ConditionalOnMissingBean(IdempotencyStore.class)
        InMemoryIdempotencyStore inMemoryIdempotencyStore(Clock clock) {
            log.warn("Idempotency is using the in-memory store: keys are not shared between instances. "
                    + "Add idempotency-store-jdbc, -redis or -mongo for a distributed guarantee.");
            return new InMemoryIdempotencyStore(clock);
        }
    }
}

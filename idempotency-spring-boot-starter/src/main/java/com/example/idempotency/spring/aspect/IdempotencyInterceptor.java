package com.example.idempotency.spring.aspect;

import com.example.idempotency.annotation.Idempotent;
import com.example.idempotency.core.IdempotencyEngine;
import com.example.idempotency.exception.MissingIdempotencyKeyException;
import com.example.idempotency.model.IdempotencyContext;
import com.example.idempotency.model.IdempotencyKeyId;
import com.example.idempotency.spring.key.IdempotentInvocation;
import com.example.idempotency.spring.key.KeyResolver;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Turns {@code @Idempotent} into a guarded call. Works for anything Spring
 * proxies — REST controllers, {@code @JmsListener}/{@code @KafkaListener}
 * methods, plain services — because it only reads the annotation and the
 * arguments, never the transport.
 */
public class IdempotencyInterceptor implements MethodInterceptor {

    private final IdempotencyEngine engine;
    private final List<KeyResolver> keyResolvers;
    private final PayloadFingerprinter fingerprinter;
    private final IdempotentMetadata metadata;
    private final Duration defaultTtl;

    public IdempotencyInterceptor(IdempotencyEngine engine, List<KeyResolver> keyResolvers,
            PayloadFingerprinter fingerprinter, IdempotentMetadata metadata, Duration defaultTtl) {
        this.engine = engine;
        this.keyResolvers = keyResolvers;
        this.fingerprinter = fingerprinter;
        this.metadata = metadata;
        this.defaultTtl = defaultTtl;
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Object target = invocation.getThis();
        Class<?> targetClass = metadata.targetClassOf(target);
        Method method = metadata.specificMethod(invocation.getMethod(), targetClass);

        Optional<Idempotent> found = metadata.findIdempotent(method, targetClass);
        if (found.isEmpty()) {
            return invocation.proceed();
        }
        Idempotent annotation = found.get();
        String scope = scopeOf(annotation, method);
        Object[] args = invocation.getArguments();

        String key = resolveKey(new IdempotentInvocation(method, args, target, annotation, scope))
                .orElseThrow(() -> new MissingIdempotencyKeyException(scope));

        IdempotencyContext context = new IdempotencyContext(
                new IdempotencyKeyId(key, scope),
                annotation.fingerprintPayload() ? fingerprinter.fingerprint(method, args) : null,
                annotation.ttl().isBlank() ? defaultTtl : Duration.parse(annotation.ttl()),
                annotation.replay(),
                annotation.waitForCompletion().isBlank() ? null : Duration.parse(annotation.waitForCompletion()));

        return engine.execute(context, invocation::proceed);
    }

    private Optional<String> resolveKey(IdempotentInvocation invocation) {
        for (KeyResolver resolver : keyResolvers) {
            Optional<String> key = resolver.resolve(invocation);
            if (key.isPresent()) {
                return key;
            }
        }
        return Optional.empty();
    }

    /**
     * Scope defaults to the call site rather than the HTTP path: it is stable
     * across route changes and works identically off HTTP. It names the
     * implementation class, so two implementations of the same annotated
     * interface get separate scopes — set {@code scope} explicitly to share one.
     */
    private String scopeOf(Idempotent annotation, Method method) {
        if (!annotation.scope().isBlank()) {
            return annotation.scope();
        }
        return method.getDeclaringClass().getName() + "#" + method.getName();
    }
}

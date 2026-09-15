package com.example.idempotency.spring.aspect;

import com.example.idempotency.annotation.Idempotent;
import com.example.idempotency.core.IdempotencyEngine;
import com.example.idempotency.exception.MissingIdempotencyKeyException;
import com.example.idempotency.model.IdempotencyContext;
import com.example.idempotency.model.IdempotencyKeyId;
import com.example.idempotency.spring.key.IdempotentInvocation;
import com.example.idempotency.spring.key.KeyResolver;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.support.AopUtils;

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
@Aspect
public class IdempotencyAspect {

    private final IdempotencyEngine engine;
    private final List<KeyResolver> keyResolvers;
    private final PayloadFingerprinter fingerprinter;
    private final Duration defaultTtl;

    public IdempotencyAspect(IdempotencyEngine engine, List<KeyResolver> keyResolvers,
            PayloadFingerprinter fingerprinter, Duration defaultTtl) {
        this.engine = engine;
        this.keyResolvers = keyResolvers;
        this.fingerprinter = fingerprinter;
        this.defaultTtl = defaultTtl;
    }

    @Around("@annotation(com.example.idempotency.annotation.Idempotent)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        Method method = targetMethod(joinPoint);
        Idempotent annotation = method.getAnnotation(Idempotent.class);
        String scope = scopeOf(annotation, method);

        IdempotentInvocation invocation = new IdempotentInvocation(method, joinPoint.getArgs(), joinPoint.getTarget(),
                annotation, scope);
        String key = resolveKey(invocation).orElseThrow(() -> new MissingIdempotencyKeyException(scope));

        IdempotencyContext context = new IdempotencyContext(
                new IdempotencyKeyId(key, scope),
                annotation.fingerprintPayload() ? fingerprinter.fingerprint(method, joinPoint.getArgs()) : null,
                annotation.ttl().isBlank() ? defaultTtl : Duration.parse(annotation.ttl()),
                annotation.replay(),
                annotation.waitForCompletion().isBlank() ? null : Duration.parse(annotation.waitForCompletion()));

        return engine.execute(context, joinPoint::proceed);
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
     * across route changes and works identically off HTTP.
     */
    private String scopeOf(Idempotent annotation, Method method) {
        if (!annotation.scope().isBlank()) {
            return annotation.scope();
        }
        return method.getDeclaringClass().getName() + "#" + method.getName();
    }

    /**
     * The signature seen by the pointcut can be the interface method, which
     * carries no parameter annotations — resolve the implementation method.
     */
    private Method targetMethod(ProceedingJoinPoint joinPoint) {
        Method signatureMethod = ((MethodSignature) joinPoint.getSignature()).getMethod();
        Object target = joinPoint.getTarget();
        if (target == null) {
            return signatureMethod;
        }
        return AopUtils.getMostSpecificMethod(signatureMethod, AopProxyUtils.ultimateTargetClass(target));
    }
}

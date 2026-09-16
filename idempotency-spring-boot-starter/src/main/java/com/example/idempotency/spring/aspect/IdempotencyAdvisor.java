package com.example.idempotency.spring.aspect;

import org.aopalliance.aop.Advice;
import org.springframework.aop.support.StaticMethodMatcherPointcutAdvisor;

import java.io.Serial;
import java.lang.reflect.Method;

/**
 * Selects the methods the interceptor guards.
 *
 * <p>An advisor rather than an AspectJ {@code @annotation} pointcut on purpose:
 * that pointcut only matches annotations physically present on the target
 * method, so {@code @Idempotent} declared on an interface (which Java does not
 * inherit) would be silently ignored. Matching goes through
 * {@link IdempotentMetadata}, which walks the whole method hierarchy.
 */
public class IdempotencyAdvisor extends StaticMethodMatcherPointcutAdvisor {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient IdempotentMetadata metadata;

    public IdempotencyAdvisor(IdempotentMetadata metadata, Advice advice) {
        super(advice);
        this.metadata = metadata;
    }

    @Override
    public boolean matches(Method method, Class<?> targetClass) {
        return metadata.findIdempotent(method, targetClass).isPresent();
    }
}

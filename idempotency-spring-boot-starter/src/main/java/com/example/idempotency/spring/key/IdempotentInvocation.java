package com.example.idempotency.spring.key;

import com.example.idempotency.annotation.Idempotent;

import java.lang.reflect.Method;

/**
 * The intercepted call, handed to every {@link KeyResolver}.
 *
 * @param method     the annotated method
 * @param args       call arguments
 * @param target     the bean the method was called on
 * @param annotation the annotation instance in effect
 * @param scope      resolved scope, useful in error messages
 */
public record IdempotentInvocation(Method method, Object[] args, Object target, Idempotent annotation, String scope) {
}

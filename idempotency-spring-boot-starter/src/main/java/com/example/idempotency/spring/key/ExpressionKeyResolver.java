package com.example.idempotency.spring.key;

import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.annotation.Order;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.context.expression.MethodBasedEvaluationContext;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Highest precedence source: the SpEL expression in
 * {@code @Idempotent(key = "...")}, evaluated against the call arguments
 * (e.g. {@code "#order.id"}, {@code "#tenant + ':' + #order.id"}).
 *
 * <p>Requires parameter names in the bytecode ({@code -parameters}); Spring
 * Boot's compiler settings enable it by default.
 */
@Order(10)
public class ExpressionKeyResolver implements KeyResolver {

    private final SpelExpressionParser parser = new SpelExpressionParser();
    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();
    private final Map<String, Expression> cache = new ConcurrentHashMap<>();

    @Override
    public Optional<String> resolve(IdempotentInvocation invocation) {
        String expression = invocation.annotation().key();
        if (expression.isBlank()) {
            return Optional.empty();
        }
        Object value = cache.computeIfAbsent(expression, parser::parseExpression)
                .getValue(new MethodBasedEvaluationContext(invocation.target(), invocation.method(),
                        invocation.args(), parameterNameDiscoverer));
        return Keys.asKey(value);
    }
}

package com.example.idempotency.spring.key;

import com.example.idempotency.annotation.IdempotentKey;
import org.springframework.core.annotation.Order;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;

import java.lang.reflect.Parameter;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads the key from the parameter annotated {@link IdempotentKey} — the way
 * queue listeners and scheduled jobs declare it. An optional property path
 * digs into the argument ({@code @IdempotentKey("header.transactionId")}).
 */
@Order(20)
public class AnnotatedParameterKeyResolver implements KeyResolver {

    private final SpelExpressionParser parser = new SpelExpressionParser();
    private final Map<String, Expression> cache = new ConcurrentHashMap<>();

    @Override
    public Optional<String> resolve(IdempotentInvocation invocation) {
        Parameter[] parameters = invocation.method().getParameters();
        for (int i = 0; i < parameters.length; i++) {
            IdempotentKey annotation = parameters[i].getAnnotation(IdempotentKey.class);
            if (annotation == null) {
                continue;
            }
            Object argument = invocation.args()[i];
            if (argument == null) {
                return Optional.empty();
            }
            if (annotation.value().isBlank()) {
                return Keys.asKey(argument);
            }
            Expression path = cache.computeIfAbsent(annotation.value(), parser::parseExpression);
            return Keys.asKey(path.getValue(argument));
        }
        return Optional.empty();
    }
}

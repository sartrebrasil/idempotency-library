package com.example.idempotency.spring.key;

import com.example.idempotency.annotation.IdempotentKey;
import com.example.idempotency.spring.aspect.IdempotentMetadata;
import org.springframework.core.annotation.Order;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads the key from the parameter annotated {@link IdempotentKey} — the way
 * queue listeners and scheduled jobs declare it. An optional property path
 * digs into the argument ({@code @IdempotentKey("header.transactionId")}).
 *
 * <p>The annotation is looked up through {@link IdempotentMetadata}, so
 * declaring it on an interface parameter works too.
 */
@Order(20)
public class AnnotatedParameterKeyResolver implements KeyResolver {

    private final SpelExpressionParser parser = new SpelExpressionParser();
    private final Map<String, Expression> cache = new ConcurrentHashMap<>();
    private final IdempotentMetadata metadata;

    public AnnotatedParameterKeyResolver(IdempotentMetadata metadata) {
        this.metadata = metadata;
    }

    @Override
    public Optional<String> resolve(IdempotentInvocation invocation) {
        IdempotentKey[] annotations = metadata.keyParameters(invocation.method(),
                metadata.targetClassOf(invocation.target()));
        for (int i = 0; i < annotations.length; i++) {
            if (annotations[i] == null) {
                continue;
            }
            Object argument = invocation.args()[i];
            if (argument == null) {
                return Optional.empty();
            }
            if (annotations[i].value().isBlank()) {
                return Keys.asKey(argument);
            }
            Expression path = cache.computeIfAbsent(annotations[i].value(), parser::parseExpression);
            return Keys.asKey(path.getValue(argument));
        }
        return Optional.empty();
    }
}

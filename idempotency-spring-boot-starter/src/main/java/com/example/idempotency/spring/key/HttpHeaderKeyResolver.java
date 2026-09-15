package com.example.idempotency.spring.key;

import org.springframework.core.annotation.Order;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;

/**
 * Last resort in HTTP requests: the {@code Idempotency-Key} header (name
 * configurable). Returns empty outside a request, so the same annotated method
 * works when called from a listener or a test.
 */
@Order(30)
public class HttpHeaderKeyResolver implements KeyResolver {

    private final String headerName;

    public HttpHeaderKeyResolver(String headerName) {
        this.headerName = headerName;
    }

    @Override
    public Optional<String> resolve(IdempotentInvocation invocation) {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servletAttributes)) {
            return Optional.empty();
        }
        return Keys.asKey(servletAttributes.getRequest().getHeader(headerName));
    }
}

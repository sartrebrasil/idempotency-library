package com.example.idempotency.spring.web;

import com.example.idempotency.exception.IdempotencyInProgressException;
import com.example.idempotency.exception.IdempotencyKeyConflictException;
import com.example.idempotency.exception.MissingIdempotencyKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps idempotency failures to HTTP semantics. Only active in web
 * applications, and only while {@code idempotency.web-exception-handler} is on
 * — an application with its own advice can take over.
 *
 * <p>Outside MVC (a queue listener, for instance) nothing intercepts these
 * exceptions: they reach the listener, which is what makes a message be
 * redelivered.
 */
@RestControllerAdvice
public class IdempotencyExceptionHandler {

    @ExceptionHandler(MissingIdempotencyKeyException.class)
    public ProblemDetail handleMissingKey(MissingIdempotencyKeyException exception) {
        return problem(HttpStatus.BAD_REQUEST, "Missing idempotency key", exception.getMessage());
    }

    /** 409: the first execution is still running, so there is nothing to replay yet. */
    @ExceptionHandler(IdempotencyInProgressException.class)
    public ProblemDetail handleInProgress(IdempotencyInProgressException exception) {
        return problem(HttpStatus.CONFLICT, "Request already in progress", exception.getMessage());
    }

    /** 422: same key, different payload — replaying would answer the wrong request. */
    @ExceptionHandler(IdempotencyKeyConflictException.class)
    public ProblemDetail handleConflict(IdempotencyKeyConflictException exception) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Idempotency key reused", exception.getMessage());
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}

package com.schwab.urlshortener.exception;

import com.schwab.urlshortener.exception.ShortenerExceptions.InvalidUrlException;
import com.schwab.urlshortener.exception.ShortenerExceptions.RateLimitExceededException;
import com.schwab.urlshortener.exception.ShortenerExceptions.UrlNotFoundException;
import com.schwab.urlshortener.dto.ApiError;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * Centralized error handling so every failure returns a consistent, well-typed JSON body
 * with a correct status code, rather than a stack trace or a generic 500.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Handles unknown/expired codes.
     *
     * @param e the thrown exception
     * @return 404 Not Found with an {@link ApiError} body
     */
    @ExceptionHandler(UrlNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(UrlNotFoundException e) {
        return build(HttpStatus.NOT_FOUND, e.getMessage());
    }

    /**
     * Handles URLs that fail validation/normalization.
     *
     * @param e the thrown exception
     * @return 400 Bad Request with an {@link ApiError} body
     */
    @ExceptionHandler(InvalidUrlException.class)
    public ResponseEntity<ApiError> handleInvalid(InvalidUrlException e) {
        return build(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /**
     * Handles callers that exceed the rate limit.
     *
     * @param e the thrown exception
     * @return 429 Too Many Requests with an {@link ApiError} body
     */
    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ApiError> handleRateLimit(RateLimitExceededException e) {
        return build(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
    }

    /**
     * Handles bean-validation failures on request bodies, flattening field errors into a
     * single readable message.
     *
     * @param e the validation exception raised by Spring
     * @return 400 Bad Request with an {@link ApiError} body listing the field failures
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return build(HttpStatus.BAD_REQUEST, message.isEmpty() ? "validation failed" : message);
    }

    /**
     * Builds a uniform error response.
     *
     * @param status  the HTTP status to return
     * @param message the human-readable error message
     * @return a {@link ResponseEntity} wrapping an {@link ApiError}
     */
    private static ResponseEntity<ApiError> build(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .body(ApiError.of(status.value(), status.getReasonPhrase(), message));
    }
}

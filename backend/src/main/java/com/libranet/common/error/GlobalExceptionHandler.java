package com.libranet.common.error;

import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> api(ApiException ex) {
        return respond(ex.status().value(), ex.code(), ex.getMessage(), ex.details());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException ex) {
        Map<String, Object> fields = new HashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            fields.put(fe.getField(), fe.getDefaultMessage());
        }
        return respond(400, "VALIDATION_FAILED", "Request validation failed", Map.of("fields", fields));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ApiError> missingHeader(MissingRequestHeaderException ex) {
        return respond(400, "MISSING_HEADER", "Required header missing: " + ex.getHeaderName(), Map.of());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException ex) {
        return respond(400, "MALFORMED_BODY", "Request body is missing or malformed", Map.of());
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiError> denied(AccessDeniedException ex) {
        return respond(403, "ACCESS_DENIED", "You do not have access to this resource", Map.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> noResource(NoResourceFoundException ex) {
        return respond(404, "NOT_FOUND", "Resource not found", Map.of());
    }

    /** Gateway-level multipart backstop (the service-level per-file cap is stricter). */
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> tooLarge(org.springframework.web.multipart.MaxUploadSizeExceededException ex) {
        return respond(413, "FILE_TOO_LARGE", "The uploaded file exceeds the maximum allowed size", Map.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> fallback(Exception ex) {
        ApiError body = ApiError.of(500, "INTERNAL", "Unexpected error");
        log.error("Unhandled error [traceId={}]", body.traceId(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    private ResponseEntity<ApiError> respond(int status, String code, String message, Map<String, Object> details) {
        return ResponseEntity.status(status).body(ApiError.of(status, code, message, details));
    }
}

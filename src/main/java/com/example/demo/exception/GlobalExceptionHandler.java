package com.example.demo.exception;

import com.example.demo.client.MlServiceClient;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpStatusCodeException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<Map<String, Object>> handleBadCredentials(BadCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                "error", "Unauthorized",
                "message", "Invalid email or password"
        ));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Map<String, Object>> handleAuthentication(AuthenticationException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                "error", "Unauthorized",
                "message", ex.getMessage() != null ? ex.getMessage() : "Authentication failed"
        ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fields = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(FieldError::getField, FieldError::getDefaultMessage, (a, b) -> a, LinkedHashMap::new));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "Validation failed");
        body.put("fields", fields);
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(UnknownInnException.class)
    public ResponseEntity<Map<String, Object>> handleUnknownInn(UnknownInnException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "Unknown INN");
        body.put("message", ex.getMessage());
        body.put("unknownInns", ex.getUnknownInns());
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(ForbiddenInnException.class)
    public ResponseEntity<Map<String, Object>> handleForbiddenInn(ForbiddenInnException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                "error", "Forbidden",
                "message", ex.getMessage()
        ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "Bad request",
                "message", ex.getMessage()
        ));
    }

    @ExceptionHandler(InvalidDataAccessApiUsageException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidSort(InvalidDataAccessApiUsageException ex) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "Bad request",
                "message", "Invalid sort/page parameters. Use e.g. sort=operationDate,desc"
        ));
    }

    @ExceptionHandler(MlServiceClient.MlServiceException.class)
    public ResponseEntity<Map<String, Object>> handleMlService(MlServiceClient.MlServiceException ex) {
        Throwable cause = ex.getCause();
        if (cause instanceof HttpStatusCodeException httpEx) {
            return ResponseEntity.status(httpEx.getStatusCode()).body(Map.of(
                    "error", "ML service error",
                    "message", httpEx.getResponseBodyAsString()
            ));
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "error", "ML service unavailable",
                "message", ex.getMessage()
        ));
    }
}

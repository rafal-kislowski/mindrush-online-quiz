package pl.mindrush.backend.api;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String NOTIFICATION_STREAM_PATH = "/api/notifications/stream";

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpServletRequest request
    ) {
        List<ApiErrorResponse.ValidationError> validationErrors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(this::toValidationError)
                .toList();

        return response(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "Validation failed",
                request,
                validationErrors
        );
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraintViolation(
            ConstraintViolationException ex,
            HttpServletRequest request
    ) {
        List<ApiErrorResponse.ValidationError> validationErrors = ex.getConstraintViolations()
                .stream()
                .map(v -> {
                    String path = v.getPropertyPath() == null ? "" : v.getPropertyPath().toString();
                    String field = extractFieldName(path);
                    String message = safeMessage(v.getMessage(), "Invalid value");
                    String rejected = safeRejected(v.getInvalidValue());
                    return new ApiErrorResponse.ValidationError(field, message, rejected);
                })
                .toList();

        return response(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "Validation failed",
                request,
                validationErrors
        );
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex,
            HttpServletRequest request
    ) {
        Throwable cause = ex.getCause();
        if (cause instanceof InvalidFormatException ife) {
            String field = ife.getPath().isEmpty()
                    ? ""
                    : ife.getPath().get(ife.getPath().size() - 1).getFieldName();
            ApiErrorResponse.ValidationError validationError = new ApiErrorResponse.ValidationError(
                    field,
                    "Invalid value format",
                    safeRejected(ife.getValue())
            );
            return response(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_REQUEST",
                    "Invalid request payload",
                    request,
                    List.of(validationError)
            );
        }

        return response(
                HttpStatus.BAD_REQUEST,
                "INVALID_REQUEST",
                "Invalid request payload",
                request,
                List.of()
        );
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiErrorResponse> handleResponseStatus(
            ResponseStatusException ex,
            HttpServletRequest request
    ) {
        String message = safeMessage(ex.getReason(), defaultMessageFor(ex.getStatusCode()));
        String code = "HTTP_" + ex.getStatusCode().value();
        return response(ex.getStatusCode(), code, message, request, List.of());
    }

    @ExceptionHandler({ ObjectOptimisticLockingFailureException.class, OptimisticLockException.class })
    public ResponseEntity<ApiErrorResponse> handleOptimisticLock(
            Exception ex,
            HttpServletRequest request
    ) {
        return response(
                HttpStatus.CONFLICT,
                "STALE_STATE",
                "Resource changed by another request. Refresh and try again.",
                request,
                List.of()
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleUnhandled(
            Exception ex,
            HttpServletRequest request
    ) {
        String path = request == null ? "unknown" : request.getRequestURI();
        if (isClientDisconnect(ex)) {
            log.debug("Client disconnected for path {}: {}", path, safeMessage(ex.getMessage(), "I/O closed"));
            return ResponseEntity.noContent().build();
        }
        if (isSseRequest(request)) {
            log.warn("Unhandled SSE exception for path {}: {}", path, safeMessage(ex.getMessage(), "stream failed"));
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        log.error("Unhandled exception for path {}", path, ex);
        return response(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "Unexpected server error",
                request,
                List.of()
        );
    }

    private ResponseEntity<ApiErrorResponse> response(
            HttpStatusCode status,
            String code,
            String message,
            HttpServletRequest request,
            List<ApiErrorResponse.ValidationError> validationErrors
    ) {
        ApiErrorResponse body = ApiErrorResponse.of(
                status,
                code,
                message,
                request == null ? null : request.getRequestURI(),
                validationErrors
        );
        return ResponseEntity.status(status).body(body);
    }

    private ApiErrorResponse.ValidationError toValidationError(FieldError fieldError) {
        String field = fieldError == null ? "" : fieldError.getField();
        String message = safeMessage(fieldError == null ? null : fieldError.getDefaultMessage(), "Invalid value");
        String rejectedValue = fieldError == null ? null : safeRejected(fieldError.getRejectedValue());
        return new ApiErrorResponse.ValidationError(field, message, rejectedValue);
    }

    private static String safeRejected(Object value) {
        if (value == null) return null;
        String s = String.valueOf(value);
        if (s.length() <= 120) return s;
        return s.substring(0, 117) + "...";
    }

    private static String safeMessage(String message, String fallback) {
        if (message == null) return fallback;
        String trimmed = message.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    private static String defaultMessageFor(HttpStatusCode statusCode) {
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        return status == null ? "Request failed" : status.getReasonPhrase();
    }

    private static String extractFieldName(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) return "";
        int dot = rawPath.lastIndexOf('.');
        if (dot < 0 || dot + 1 >= rawPath.length()) return rawPath;
        return rawPath.substring(dot + 1);
    }

    private static boolean isSseRequest(HttpServletRequest request) {
        if (request == null) return false;
        String path = request.getRequestURI();
        if (path != null && path.startsWith(NOTIFICATION_STREAM_PATH)) return true;
        String accept = request.getHeader("Accept");
        return accept != null && accept.contains("text/event-stream");
    }

    private static boolean isClientDisconnect(Throwable ex) {
        Throwable current = ex;
        int depth = 0;
        while (current != null && depth < 10) {
            String className = current.getClass().getName();
            if (className.contains("ClientAbortException")
                    || className.contains("EOFException")
                    || className.contains("EofException")) {
                return true;
            }

            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase();
                if (normalized.contains("broken pipe")
                        || normalized.contains("connection reset")
                        || normalized.contains("forcibly closed")
                        || normalized.contains("przerwane przez oprogramowanie")) {
                    return true;
                }
            }

            current = current.getCause();
            depth++;
        }
        return false;
    }
}

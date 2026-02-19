package pl.mindrush.backend.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

import java.time.Instant;
import java.util.List;

public record ApiErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String code,
        String message,
        String path,
        List<ValidationError> validationErrors
) {
    public static ApiErrorResponse of(
            HttpStatusCode statusCode,
            String code,
            String message,
            String path,
            List<ValidationError> validationErrors
    ) {
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        String reason = status == null ? "HTTP " + statusCode.value() : status.getReasonPhrase();
        List<ValidationError> errors = validationErrors == null ? List.of() : List.copyOf(validationErrors);
        return new ApiErrorResponse(
                Instant.now(),
                statusCode.value(),
                reason,
                code,
                message,
                path,
                errors
        );
    }

    public record ValidationError(
            String field,
            String message,
            String rejectedValue
    ) {
    }
}

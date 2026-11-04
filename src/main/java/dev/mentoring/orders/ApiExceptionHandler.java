package dev.mentoring.orders;

import jakarta.validation.ConstraintViolationException;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

@RestControllerAdvice
public class ApiExceptionHandler {
    public record ErrorBody(String code) {}

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorBody> api(ApiException error) { return response(error.status(), error.code()); }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentNotValidException.class,
            ConstraintViolationException.class, HandlerMethodValidationException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorBody> invalid(Exception error) { return response(400, "INVALID_INPUT"); }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ResponseEntity<ErrorBody> database(Exception error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLTransientConnectionException) return response(503, "DATABASE_UNAVAILABLE");
            if (cause instanceof SQLException sql) {
                var state = sql.getSQLState();
                if (state != null && (state.startsWith("08") || state.startsWith("40") || state.equals("55P03")
                        || state.equals("57014") || state.equals("57P01"))) return response(503, "DATABASE_UNAVAILABLE");
            }
        }
        return unexpected(error);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorBody> unexpected(Exception error) {
        if (error instanceof org.springframework.web.ErrorResponse rejection) {
            return response(rejection.getStatusCode().value(), "REQUEST_REJECTED");
        }
        LoggerFactory.getLogger(getClass()).error("Request failed: reference={}, type={}", UUID.randomUUID(), error.getClass().getSimpleName());
        return response(500, "INTERNAL_ERROR");
    }

    private static ResponseEntity<ErrorBody> response(int status, String code) {
        var builder = ResponseEntity.status(status);
        if (status == 429 || status == 503) builder.header("Retry-After", "1");
        return builder.body(new ErrorBody(code));
    }
}

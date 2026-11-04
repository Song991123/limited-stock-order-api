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
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 입력 오류, 주문 실패와 DB 오류에 대한 응답 처리.
 *
 * <p>SQL, 요청값과 상세 예외 메시지는 응답에 포함하지 않음.
 */
@RestControllerAdvice
public class ApiExceptionHandler {
    /**
     * API 오류 응답 본문.
     * @param code 클라이언트가 오류 종류를 구분할 고정 코드
     */
    public record ErrorBody(String code) {}

    /** 주문 처리 중 발생한 오류를 HTTP 상태와 오류 코드로 반환. */
    @ExceptionHandler(OrderProblem.class)
    public ResponseEntity<ErrorBody> handleOrderProblem(OrderProblem error) {
        int httpStatus = switch (error.reason()) {
            case NOT_FOUND -> 404;
            case INVALID_REQUEST_KEY, UNEXPECTED_BODY -> 400;
            case IDEMPOTENCY_KEY_REUSED -> 409;
            case INVARIANT_VIOLATION -> 500;
        };
        return buildErrorResponse(httpStatus, error.reason().name());
    }

    /** 요청 수가 한도를 넘으면 429 응답. 다시 요청할 때까지 기다릴 초도 함께 반환. */
    @ExceptionHandler(WriteLimitExceeded.class)
    public ResponseEntity<ErrorBody> handleWriteLimit(WriteLimitExceeded error) {
        return ResponseEntity.status(429)
                .header("Retry-After", Integer.toString(error.retryAfterSeconds()))
                .body(new ErrorBody("TOO_MANY_REQUESTS"));
    }

    /** JSON 변환, 본문/경로/쿼리 검증 실패를 입력 오류 400으로 변환. */
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentNotValidException.class,
            ConstraintViolationException.class, HandlerMethodValidationException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorBody> handleInvalidInput(Exception error) {
        return buildErrorResponse(400, "INVALID_INPUT");
    }

    /**
     * DB 오류의 원인을 확인해 응답 처리.
     *
     * <p>연결 끊김과 잠금 시간 초과 같은 일시 오류는 503, 나머지 저장 오류는 500 응답.
     * 연결이 끊기면 커밋 여부를 모를 수 있으므로 같은 구매 키로 주문 결과 확인 필요.
     */
    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ResponseEntity<ErrorBody> handleDatabaseFailure(Exception error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (isRetryableDatabaseFailure(cause)) {
                return buildErrorResponse(503, "DATABASE_UNAVAILABLE");
            }
        }
        return handleUnexpectedFailure(error);
    }

    /**
     * 나머지 오류 처리. Spring이 반환한 404, 405, 415 등의 상태는 그대로 사용.
     *
     * <p>예상하지 못한 오류에는 참조 번호와 예외 종류만 로그로 남기고 500 반환.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorBody> handleUnexpectedFailure(Exception error) {
        if (error instanceof ErrorResponse rejection) {
            return buildErrorResponse(rejection.getStatusCode().value(), "REQUEST_REJECTED");
        }
        LoggerFactory.getLogger(getClass()).error("Request failed: reference={}, type={}",
                UUID.randomUUID(), error.getClass().getSimpleName());
        return buildErrorResponse(500, "INTERNAL_ERROR");
    }

    /** JDBC 예외와 PostgreSQL 오류 코드(SQLSTATE)로 재시도할 수 있는 DB 오류인지 확인. */
    private static boolean isRetryableDatabaseFailure(Throwable cause) {
        if (cause instanceof SQLTransientConnectionException) return true;
        if (!(cause instanceof SQLException sqlException)) return false;
        String sqlState = sqlException.getSQLState();
        return sqlState != null && (sqlState.startsWith("08") || sqlState.startsWith("40")
                || sqlState.equals("55P03") || sqlState.equals("57014") || sqlState.equals("57P01"));
    }

    /** 고정 오류 코드 본문 생성. DB 일시 오류 503에는 1초 재시도 안내 추가. */
    private static ResponseEntity<ErrorBody> buildErrorResponse(int httpStatus, String errorCode) {
        var responseBuilder = ResponseEntity.status(httpStatus);
        if (httpStatus == 503) responseBuilder.header("Retry-After", "1");
        return responseBuilder.body(new ErrorBody(errorCode));
    }
}

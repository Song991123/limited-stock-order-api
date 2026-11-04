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
 * 컨트롤러 처리 오류를 일관된 HTTP 상태와 오류 코드로 변환.
 *
 * <p>클라이언트에 SQL, 원문 입력과 내부 예외 메시지를 노출하지 않음.
 */
@RestControllerAdvice
public class ApiExceptionHandler {
    /**
     * API 오류 응답 본문.
     * @param code 클라이언트가 오류 종류를 구분할 고정 코드
     */
    public record ErrorBody(String code) {}

    /** 업무상 문제를 HTTP 상태로 변환. 서비스의 문제 구분과 전송 규격 분리. */
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

    /** 쓰기 한도 초과를 429로 변환하고 검사기가 계산한 재시도 초를 그대로 전달. */
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
     * DB 연결, 잠금과 트랜잭션 실패를 원인 예외까지 확인해 분류.
     *
     * <p>일시 오류는 503과 재시도 안내, 그 외 저장 오류는 내부 오류로 반환.
     * 커밋 성공 여부를 모르는 연결 오류도 있으므로 새 구매 키를 발급하는 복구는 하지 않음.
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
     * 분류하지 않은 오류 처리. Spring의 경로/메서드/미디어 타입 거절 상태는 유지.
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

    /** JDBC 일시 연결 오류와 PostgreSQL의 연결/트랜잭션/잠금/취소/종료 SQLSTATE 판정. */
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

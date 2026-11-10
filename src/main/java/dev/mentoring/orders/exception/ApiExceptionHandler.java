package dev.mentoring.orders.exception;

import dev.mentoring.orders.dto.response.ErrorResponse;
import dev.mentoring.orders.web.admission.WriteLimitExceeded;
import jakarta.validation.ConstraintViolationException;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 입력 오류, 주문 실패와 DB 오류에 대한 응답 처리.
 *
 * <p>SQL, 요청값과 상세 예외 메시지는 응답에 포함하지 않음.
 * RestControllerAdvice로 공통 오류 응답을 담당하고 ExceptionHandler로 예외 종류별 변환 선택.
 */
@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** 주문 처리 중 발생한 오류를 HTTP 상태와 오류 코드로 반환. */
    @ExceptionHandler(OrderProblem.class)
    public ResponseEntity<ErrorResponse> handleOrderProblem(OrderProblem error) {
        HttpStatus httpStatus = switch (error.reason()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case INVALID_INPUT, INVALID_REQUEST_KEY, UNEXPECTED_BODY -> HttpStatus.BAD_REQUEST;
            case IDEMPOTENCY_KEY_REUSED, OUT_OF_STOCK -> HttpStatus.CONFLICT;
            case INVARIANT_VIOLATION -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        if (httpStatus.is5xxServerError()) return buildServerError(httpStatus, error.reason().name(), error);
        return buildErrorResponse(httpStatus, error.reason().name());
    }

    /** 요청 수가 한도를 넘으면 429 응답. 다시 요청할 때까지 기다릴 초도 함께 반환. */
    @ExceptionHandler(WriteLimitExceeded.class)
    public ResponseEntity<ErrorResponse> handleWriteLimit(WriteLimitExceeded error) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", Integer.toString(error.retryAfterSeconds()))
                .body(new ErrorResponse("TOO_MANY_REQUESTS"));
    }

    /** JSON 변환, 본문/경로/쿼리 검증 실패를 입력 오류 400으로 변환. */
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentNotValidException.class,
            ConstraintViolationException.class, HandlerMethodValidationException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> handleInvalidInput(Exception error) {
        return buildErrorResponse(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
    }

    /**
     * DB 오류의 원인을 확인해 응답 처리.
     *
     * <p>연결 끊김과 잠금 시간 초과 같은 일시 오류는 503, 나머지 저장 오류는 500 응답.
     * 연결이 끊기면 커밋 여부를 모를 수 있으므로 같은 구매 키로 주문 결과 확인 필요.
     */
    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ResponseEntity<ErrorResponse> handleDatabaseFailure(Exception error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (isRetryableDatabaseFailure(cause)) {
                return buildServerError(HttpStatus.SERVICE_UNAVAILABLE, "DATABASE_UNAVAILABLE", error);
            }
        }
        return handleUnexpectedFailure(error);
    }

    /**
     * 나머지 오류 처리. Spring이 반환한 404, 405, 415 등의 상태는 그대로 사용.
     *
     * <p>서버 오류는 응답 헤더와 로그에 같은 오류 번호 기록. 상세 메시지와 SQL 원문은 기록하지 않음.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpectedFailure(Exception error) {
        if (error instanceof org.springframework.web.ErrorResponse rejection) {
            HttpStatusCode status = rejection.getStatusCode();
            return status.is5xxServerError()
                    ? buildServerError(status, "REQUEST_REJECTED", error)
                    : buildErrorResponse(status, "REQUEST_REJECTED");
        }
        return buildServerError(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", error);
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
    private static ResponseEntity<ErrorResponse> buildErrorResponse(HttpStatusCode status, String errorCode) {
        return errorResponseBuilder(status).body(new ErrorResponse(errorCode));
    }

    /** 서버 오류의 로그와 응답을 오류 번호로 연결. 예외 메시지와 요청값은 노출하지 않음. */
    private static ResponseEntity<ErrorResponse> buildServerError(
            HttpStatusCode status, String errorCode, Exception error) {
        String errorId = UUID.randomUUID().toString();
        LOGGER.error("Request failed: reference={}, code={}, type={}, sqlState={}",
                errorId, errorCode, error.getClass().getSimpleName(), safeSqlState(error));
        return errorResponseBuilder(status).header("X-Error-Id", errorId).body(new ErrorResponse(errorCode));
    }

    /** HTTP 상태에 맞는 응답 생성기 준비. 일시적 DB 오류에는 제한 재시도 안내 추가. */
    private static ResponseEntity.BodyBuilder errorResponseBuilder(HttpStatusCode status) {
        var builder = ResponseEntity.status(status);
        if (status.value() == HttpStatus.SERVICE_UNAVAILABLE.value()) builder.header("Retry-After", "1");
        return builder;
    }

    /** DB 오류 코드는 고정 길이 영문/숫자 값만 기록하고 그 외 값은 생략. */
    private static String safeSqlState(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlError) {
                String state = sqlError.getSQLState();
                if (state != null && state.matches("[0-9A-Z]{5}")) return state;
            }
        }
        return "none";
    }
}

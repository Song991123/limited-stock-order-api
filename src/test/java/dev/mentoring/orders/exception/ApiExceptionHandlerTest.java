package dev.mentoring.orders.exception;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.mentoring.orders.dto.response.ErrorResponse;
import java.sql.SQLException;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;

/** 오류 응답과 실제 로그 이벤트를 대조하고 민감한 예외 메시지의 노출 여부 확인. */
class ApiExceptionHandlerTest {
    private final ApiExceptionHandler handler = new ApiExceptionHandler();
    private Logger logger;
    private Level originalLevel;
    private ListAppender<ILoggingEvent> events;

    @BeforeEach
    void captureLogEvents() {
        logger = (Logger) LoggerFactory.getLogger(ApiExceptionHandler.class);
        originalLevel = logger.getLevel();
        logger.setLevel(Level.ERROR);
        events = new ListAppender<>();
        events.start();
        logger.addAppender(events);
    }

    @AfterEach
    void releaseLogEvents() {
        logger.detachAppender(events);
        logger.setLevel(originalLevel);
        events.stop();
    }

    @ParameterizedTest
    @MethodSource("serverFailures")
    void serverFailureConnectsResponseToSafeLog(Exception failure, int status, String code, String sqlState) {
        ResponseEntity<ErrorResponse> response;
        if (failure instanceof OrderProblem problem) response = handler.handleOrderProblem(problem);
        else if (failure instanceof DataAccessException) response = handler.handleDatabaseFailure(failure);
        else response = handler.handleUnexpectedFailure(failure);

        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getBody()).isEqualTo(new ErrorResponse(code));
        String reference = response.getHeaders().getFirst("X-Error-Id");
        assertThat(reference).isNotBlank();
        assertThat(UUID.fromString(reference).toString()).isEqualTo(reference);
        assertThat(events.list).singleElement().satisfies(event -> {
            assertThat(event.getFormattedMessage()).contains(reference, code, "sqlState=" + sqlState)
                    .doesNotContain("private-test-data", "SELECT password");
            assertThat(event.getThrowableProxy()).isNull();
        });
        if (status == 503) assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("1");
    }

    @Test
    void badInputDoesNotCreateAServerErrorLog() {
        var response = handler.handleOrderProblem(new OrderProblem(OrderProblem.Reason.INVALID_REQUEST_KEY));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getHeaders().getFirst("X-Error-Id")).isNull();
        assertThat(events.list).isEmpty();
    }

    @Test
    void shortageUsesTheCommonConflictResponse() {
        var response = handler.handleOrderProblem(new OrderProblem(OrderProblem.Reason.OUT_OF_STOCK));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isEqualTo(new ErrorResponse("OUT_OF_STOCK"));
        assertThat(events.list).isEmpty();
    }

    private static Stream<Arguments> serverFailures() {
        return Stream.of(
                Arguments.of(OrderProblem.invariant(), 500, "INVARIANT_VIOLATION", "none"),
                Arguments.of(new DataAccessResourceFailureException("private-test-data",
                        new SQLException("SELECT password", "08006")), 503, "DATABASE_UNAVAILABLE", "08006"),
                Arguments.of(new DataIntegrityViolationException("private-test-data",
                        new SQLException("SELECT password", "23514")), 500, "INTERNAL_ERROR", "23514"),
                Arguments.of(new DataIntegrityViolationException("private-test-data",
                        new SQLException("SELECT password", "23514\nprivate-test-data")),
                        500, "INTERNAL_ERROR", "none"),
                Arguments.of(new IllegalStateException("private-test-data"), 500, "INTERNAL_ERROR", "none"));
    }
}

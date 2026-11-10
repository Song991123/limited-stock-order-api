package dev.mentoring.orders;

import static dev.mentoring.orders.support.ReviewFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

/** 전용 PostgreSQL만 강제 중단. 서버 JVM과 기존 클라이언트는 유지한 채 재접속 확인. */
@Tag("database-recovery")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DatabaseRecoveryIntegrationTest {
    private static final long PAUSE_KEY = 88112711;
    private Server server;
    private final List<Map<String, Object>> events = new ArrayList<>();

    @BeforeAll void startServer() throws Exception {
        String instance = System.getenv("STOCK_DB_TEST_INSTANCE");
        assertThat(instance).matches("isolated-[a-f0-9]{16}");
        assertThat(System.getenv("STOCK_ALLOW_DB_CRASH")).isEqualTo(instance);
        migrate(); server = new Server("review-recovery");
    }
    @BeforeEach void prepareStock() throws Exception { control("start"); reset(10); }
    @AfterEach void ensureDatabaseRunning() throws Exception { control("start"); sql("DROP FUNCTION IF EXISTS review_pause() CASCADE"); }
    @AfterAll void stopServer() throws Exception {
        try { save("database-recovery.json", events); } finally { if (server != null) server.close(); }
    }

    @RepeatedTest(value = 3, name = "{displayName} [{currentRepetition}/{totalRepetitions}]") void purchaseBeforeCommitRollsBackAfterDatabaseCrash() throws Exception { beforeCommit(false); }
    @RepeatedTest(value = 3, name = "{displayName} [{currentRepetition}/{totalRepetitions}]") void cancellationBeforeCommitRollsBackAfterDatabaseCrash() throws Exception { beforeCommit(true); }
    @RepeatedTest(value = 3, name = "{displayName} [{currentRepetition}/{totalRepetitions}]") void committedPurchaseWithLostResponseSurvivesDatabaseCrash() throws Exception { afterCommit(false); }
    @RepeatedTest(value = 3, name = "{displayName} [{currentRepetition}/{totalRepetitions}]") void committedCancellationWithLostResponseSurvivesDatabaseCrash() throws Exception { afterCommit(true); }

    @RepeatedTest(value = 3, name = "{displayName} [{currentRepetition}/{totalRepetitions}]") void databaseFailureDuringAuthenticationIsNotWrongPassword() throws Exception {
        try (var client = new Client(server.port, "alice")) {
            long pid = server.pid();
            control("crash");
            HttpResponse<String> failed;
            try { failed = client.send("GET", "/products", null, null); }
            finally { control("start"); }
            assertRecovered(client, pid);
            events.add(Map.of("scenario", "authentication-db-down", "http", failed.statusCode(),
                    "body", failed.body(), "database", snapshot(), "sameJvm", server.pid() == pid));
            assertThat(failed.statusCode()).isEqualTo(503);
            assertThat(body(failed).path("code").asText()).isEqualTo("DATABASE_UNAVAILABLE");
            assertThat(failed.headers().firstValue("X-Error-Id")).isPresent();
            assertThat(failed.headers().firstValue("Retry-After")).contains("1");
        }
    }

    /** 마지막 저장을 마친 뒤 지연 트리거에서 멈춰, 미확정 변경이 DB 재시작 후 남는지 검사. */
    private void beforeCommit(boolean cancellation) throws Exception {
        try (var client = new Client(server.port, "alice"); var executor = Executors.newSingleThreadExecutor()) {
            long id = cancellation ? body(client.buy("recover-key")).path("id").asLong() : 0;
            long pid = server.pid();
            Connection blocker = connection();
            try {
                try (var statement = blocker.createStatement()) { statement.execute("SELECT pg_advisory_lock(" + PAUSE_KEY + ")"); }
                sql("CREATE FUNCTION review_pause() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_advisory_xact_lock(" + PAUSE_KEY + "); RETURN NULL; END $$");
                String table = cancellation ? "orders" : "order_requests";
                sql("CREATE CONSTRAINT TRIGGER review_pause_at_commit AFTER UPDATE ON " + table
                        + " DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION review_pause()");
                var response = executor.submit(() -> cancellation ? client.cancel(id) : client.buy("recover-key"));
                await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(20)).until(() ->
                        number("SELECT count(*) FROM pg_stat_activity WHERE application_name='review-recovery' AND wait_event_type='Lock' AND wait_event='advisory'") > 0);
                control("crash");
                HttpResponse<String> failed = response.get(20, TimeUnit.SECONDS);
                control("start");
                sql("DROP FUNCTION review_pause() CASCADE");
                assertRecovered(client, pid);
                var rolledBack = snapshot();
                assertThat(number("SELECT available_quantity FROM inventories WHERE product_id=1")).isEqualTo(cancellation ? 9 : 10);
                var retried = cancellation ? client.cancel(id) : client.buy("recover-key");
                assertThat(retried.statusCode()).isEqualTo(cancellation ? 200 : 201);
                if (cancellation) assertThat(body(retried).path("status").asText()).isEqualTo("CANCELLED");
                events.add(Map.of("scenario", cancellation ? "cancel-before-commit" : "purchase-before-commit",
                        "failureHttp", failed.statusCode(), "retryHttp", retried.statusCode(), "returnedId", body(retried).path("id").asLong(),
                        "rolledBack", rolledBack, "database", snapshot(), "sameJvm", server.pid() == pid));
                assertThat(failed.statusCode()).isEqualTo(503);
            } finally {
                control("start");
                try { blocker.close(); } catch (java.sql.SQLException disconnected) { /* DB 강제 종료로 이미 끊긴 검사 연결. */ }
            }
        }
    }

    private void afterCommit(boolean cancellation) throws Exception {
        try (var client = new Client(server.port, "alice"); var proxy = new LostResponse(server.port)) {
            long id = cancellation ? body(client.buy("durable-key")).path("id").asLong() : 0;
            long pid = server.pid();
            String path = cancellation ? "/orders/" + id + "/cancel" : "/orders";
            assertThatThrownBy(() -> client.sendTo(proxy.port(), "POST", path, cancellation ? null : "durable-key",
                    cancellation ? null : "{\"productId\":1,\"quantity\":1}")).isInstanceOf(java.io.IOException.class);
            assertThat(proxy.upstream).isNotNull();
            assertThat(proxy.upstream.statusCode()).isEqualTo(cancellation ? 200 : 201);
            long committedId = body(proxy.upstream).path("id").asLong();
            control("crash"); control("start"); assertRecovered(client, pid);
            var replayed = cancellation ? client.cancel(id) : client.buy("durable-key");
            assertThat(replayed.statusCode()).isEqualTo(200);
            assertThat(body(replayed).path("id").asLong()).isEqualTo(committedId);
            assertThat(body(replayed).path("status").asText()).isEqualTo(cancellation ? "CANCELLED" : "ACCEPTED");
            events.add(Map.of("scenario", cancellation ? "cancel-after-commit-lost-response" : "purchase-after-commit-lost-response",
                    "upstreamHttp", proxy.upstream.statusCode(), "replayHttp", replayed.statusCode(), "committedId", committedId,
                    "returnedId", body(replayed).path("id").asLong(), "returnedStatus", body(replayed).path("status").asText(),
                    "database", snapshot(), "sameJvm", server.pid() == pid));
        }
    }

    private void assertRecovered(Client client, long pid) {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200)).until(() -> client.refreshCsrf() == 200);
        assertThat(server.alive()).isTrue(); assertThat(server.pid()).isEqualTo(pid);
    }

    private void control(String action) throws Exception {
        var process = new ProcessBuilder("node", Path.of("scripts", "local-db.cjs").toAbsolutePath().toString(), action)
                .redirectErrorStream(true).start();
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        String result = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(process.exitValue()).as("Owned database control: %s", result).isZero();
    }
}

package dev.mentoring.orders;

import static dev.mentoring.orders.support.ReviewFixture.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

/** 두 서버의 메모리를 공유하지 않아도 DB가 중복 차감과 복구를 막는지 확인. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TwoServerIntegrationTest {
    private Server first;
    private Server second;
    private final List<Map<String, Object>> events = new ArrayList<>();

    @BeforeAll void startServers() throws Exception {
        migrate();
        first = new Server("review-first");
        second = new Server("review-second");
    }
    @BeforeEach void prepareStock() throws Exception { reset(20); }
    @AfterAll void stopServers() throws Exception {
        try { save("two-servers.json", events); }
        finally { try { if (second != null) second.close(); } finally { if (first != null) first.close(); } }
    }

    @RepeatedTest(value = 3, name = "{displayName} [{currentRepetition}/{totalRepetitions}]") void samePurchaseAcrossTwoServersCreatesOneOrder() throws Exception {
        try (var left = new Client(first.port, "alice"); var right = new Client(second.port, "alice")) {
            var responses = concurrent(20, index -> (index % 2 == 0 ? left : right).buy("shared-purchase"));
            var ids = new HashSet<Long>();
            for (var response : responses) {
                assertThat(response.statusCode()).isIn(200, 201);
                ids.add(body(response).path("id").asLong());
            }
            assertThat(responses.stream().filter(response -> response.statusCode() == 201).count()).isEqualTo(1);
            assertThat(ids).hasSize(1);
            assertThat(number("SELECT count(*) FROM orders")).isEqualTo(1);
            record("same-purchase", responses);
        }
    }

    @RepeatedTest(value = 3, name = "{displayName} [{currentRepetition}/{totalRepetitions}]") void lastStockAcrossTwoServersAcceptsOneCustomer() throws Exception {
        reset(1);
        try (var left = new Client(first.port, "alice"); var right = new Client(second.port, "bob")) {
            var responses = concurrent(2, index -> (index == 0 ? left : right).buy("last-stock"));
            assertThat(responses.stream().map(HttpResponse::statusCode).sorted().toList()).containsExactly(201, 409);
            assertThat(number("SELECT count(*) FROM orders")).isEqualTo(1);
            record("last-stock", responses);
        }
    }

    @RepeatedTest(value = 3, name = "{displayName} [{currentRepetition}/{totalRepetitions}]") void sameCancellationAcrossTwoServersRestoresOnce() throws Exception {
        try (var left = new Client(first.port, "alice"); var right = new Client(second.port, "alice")) {
            long id = body(left.buy("cancel-purchase")).path("id").asLong();
            var responses = concurrent(20, index -> (index % 2 == 0 ? left : right).cancel(id));
            for (var response : responses) {
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(body(response).path("id").asLong()).isEqualTo(id);
                assertThat(body(response).path("status").asText()).isEqualTo("CANCELLED");
            }
            assertThat(number("SELECT count(*) FROM inventory_movements WHERE kind='RESTORE'")).isEqualTo(1);
            record("same-cancellation", responses);
        }
    }

    @RepeatedTest(value = 3, name = "{displayName} [{currentRepetition}/{totalRepetitions}]") void cancellationAndNewPurchaseAcrossTwoServersRemainConsistent() throws Exception {
        reset(1);
        try (var left = new Client(first.port, "alice"); var right = new Client(second.port, "bob")) {
            long id = body(left.buy("first-purchase")).path("id").asLong();
            var responses = concurrent(2, index -> index == 0 ? left.cancel(id) : right.buy("next-purchase"));
            assertThat(responses.get(0).statusCode()).isEqualTo(200);
            assertThat(responses.get(1).statusCode()).isIn(201, 409);
            assertThat(body(left.send("GET", "/orders/" + id, null, null)).path("status").asText()).isEqualTo("CANCELLED");
            record("cancel-and-purchase", responses);
        }
    }

    private void record(String scenario, List<HttpResponse<String>> responses) throws Exception {
        events.add(Map.of("scenario", scenario, "http", responses.stream().map(HttpResponse::statusCode).toList(),
                "database", snapshot(), "serverPids", List.of(first.pid(), second.pid()),
                "scope", "DB invariants only; admission counters remain per JVM; pool size 4 per JVM"));
    }

    private List<HttpResponse<String>> concurrent(int count, Request request) throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(count)) {
            var futures = new ArrayList<Future<HttpResponse<String>>>();
            for (int index = 0; index < count; index++) {
                int current = index;
                futures.add(pool.submit(() -> { assertThat(start.await(5, TimeUnit.SECONDS)).isTrue(); return request.send(current); }));
            }
            start.countDown();
            var responses = new ArrayList<HttpResponse<String>>();
            for (var future : futures) responses.add(future.get(40, TimeUnit.SECONDS));
            return responses;
        }
    }

    @FunctionalInterface private interface Request { HttpResponse<String> send(int index) throws Exception; }
}

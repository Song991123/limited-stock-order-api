package dev.mentoring.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 고객 요청이 한도를 넘을 때 HTTP 거절, 조회와 후속 주문 확인. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "stock.admission.concurrent-writes=1", "stock.admission.per-customer-per-minute=2",
        "spring.main.banner-mode=off"
})
@ActiveProfiles("demo")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(30)
class ActorAdmissionIntegrationTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    private final List<Map<String, Object>> observations = new ArrayList<>();
    private long productId;
    private Customer alice;
    private Customer bob;

    @DynamicPropertySource
    static void dedicatedDatabase(DynamicPropertyRegistry registry) {
        String url = System.getenv("STOCK_DB_URL");
        if (url == null || !url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/stock_order_test")) {
            throw new IllegalStateException("Use scripts/test.ps1 with its dedicated loopback database");
        }
        registry.add("spring.datasource.url", () -> url);
    }

    @BeforeEach
    void prepareCustomersAndStock() throws Exception {
        assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo("stock_order_test");
        jdbc.execute("TRUNCATE inventory_movements,orders,order_requests,inventories,products RESTART IDENTITY CASCADE");
        productId = jdbc.queryForObject("INSERT INTO products(name) VALUES (?) RETURNING id", Long.class, "Order limit check");
        jdbc.update("INSERT INTO inventories VALUES (?,10,10)", productId);
        alice = new Customer("alice", System.getenv("STOCK_ALICE_PASSWORD"));
        bob = new Customer("bob", System.getenv("STOCK_BOB_PASSWORD"));
    }

    @AfterEach
    void compareOrdersStockAndHistory() {
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM inventories i WHERE available_quantity < 0
                OR available_quantity <> initial_quantity - COALESCE((SELECT sum(quantity)
                    FROM orders o WHERE o.product_id=i.product_id AND o.status='ACCEPTED'),0)
                OR available_quantity <> initial_quantity + COALESCE((SELECT sum(quantity_delta)
                    FROM inventory_movements m WHERE m.product_id=i.product_id),0)
                """, Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_requests WHERE outcome IS NULL", Long.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM orders o JOIN order_requests r ON r.id=o.request_id
                WHERE o.buyer_id<>r.buyer_id OR o.product_id<>r.product_id OR o.quantity<>r.quantity
                OR r.outcome<>'ACCEPTED'
                OR (SELECT count(*) FROM inventory_movements m WHERE m.order_id=o.id AND m.kind='DEBIT')<>1
                OR (SELECT count(*) FROM inventory_movements m WHERE m.order_id=o.id AND m.kind='RESTORE')<>
                   (CASE WHEN o.status='CANCELLED' THEN 1 ELSE 0 END)
                """, Long.class)).isZero();
    }

    @AfterAll
    void saveActualResults() throws Exception {
        Path directory = Path.of(Objects.requireNonNull(System.getenv("STOCK_EVIDENCE_DIR")));
        Files.createDirectories(directory);
        mapper.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("actor-admission.json").toFile(), observations);
    }

    /** 앞선 주문이 DB에서 대기할 때 다른 고객에게 429 반환, 잠금 해제 뒤 같은 구매 키로 접수. */
    @Test
    void busyWriteRejectsAnotherCustomerWithoutBlockingReadsOrReservingKey() throws Exception {
        HttpResponse<String> rejected;
        HttpResponse<String> products;
        HttpResponse<String> first;
        long unreservedRequests;
        try (var lock = DriverManager.getConnection(System.getenv("STOCK_DB_URL"),
                System.getenv("STOCK_DB_USER"), System.getenv("STOCK_DB_PASSWORD"));
             var executor = Executors.newSingleThreadExecutor()) {
            lock.setAutoCommit(false);
            try (var statement = lock.prepareStatement("SELECT product_id FROM inventories WHERE product_id=? FOR UPDATE")) {
                statement.setLong(1, productId);
                statement.executeQuery().close();
            }
            var pending = executor.submit(() -> alice.buy("busy-first"));
            try {
                await().atMost(Duration.ofSeconds(2)).pollInterval(Duration.ofMillis(10)).untilAsserted(() ->
                        assertThat(jdbc.queryForObject("""
                                SELECT count(*) FROM pg_stat_activity
                                WHERE datname=current_database() AND wait_event_type='Lock'
                                """, Integer.class)).isGreaterThanOrEqualTo(1));
                rejected = bob.buy("busy-second");
                assertThat(rejected.statusCode()).isEqualTo(429);
                assertThat(json(rejected).path("code").asText()).isEqualTo("TOO_MANY_REQUESTS");
                assertThat(rejected.headers().firstValue("Retry-After")).hasValue("1");
                unreservedRequests = jdbc.queryForObject(
                        "SELECT count(*) FROM order_requests WHERE request_key='busy-second'", Long.class);
                assertThat(unreservedRequests).isZero();
                products = bob.send("GET", "/products", null, null);
                assertThat(products.statusCode()).isEqualTo(200);
            } finally {
                lock.rollback();
            }
            first = pending.get(10, TimeUnit.SECONDS);
        }
        assertThat(first.statusCode()).isEqualTo(201);
        var retry = bob.buy("busy-second");
        assertThat(retry.statusCode()).isEqualTo(201);
        assertThat(json(retry).path("id").asLong()).isNotEqualTo(json(first).path("id").asLong());
        assertThat(available()).isEqualTo(8);
        assertThat(count("orders")).isEqualTo(2);
        assertThat(count("inventory_movements")).isEqualTo(2);
        observations.add(Map.of("scenario", "concurrent-write-limit", "rejectedHttp", rejected.statusCode(),
                "retryAfter", rejected.headers().firstValue("Retry-After").orElseThrow(),
                "unreservedRequests", unreservedRequests, "readHttp", products.statusCode(),
                "firstHttp", first.statusCode(), "retryHttp", retry.statusCode(),
                "orders", count("orders"), "available", available()));
    }

    /** 같은 구매의 반복도 고객별 횟수에 포함, 다른 고객의 구매와 조회는 계속 허용. */
    @Test
    void customerRateLimitRejectsRetriesWithoutDebitingAgainOrLimitingOtherCustomer() throws Exception {
        var statuses = new ArrayList<Integer>();
        HttpResponse<String> rejected = null;
        Long originalOrderId = null;
        // 분 초기화 시점을 한 번 지나더라도 5회 안에는 한도 초과 응답 확인
        for (int attempt = 0; attempt < 5; attempt++) {
            var response = alice.buy("rate-same-purchase");
            statuses.add(response.statusCode());
            if (response.statusCode() == 429) {
                rejected = response;
                break;
            }
            assertThat(response.statusCode()).isIn(200, 201);
            long returnedOrderId = json(response).path("id").asLong();
            if (originalOrderId == null) originalOrderId = returnedOrderId;
            assertThat(returnedOrderId).isEqualTo(originalOrderId);
        }
        assertThat(rejected).isNotNull();
        assertThat(json(rejected).path("code").asText()).isEqualTo("TOO_MANY_REQUESTS");
        int retryAfter = Integer.parseInt(rejected.headers().firstValue("Retry-After").orElseThrow());
        assertThat(retryAfter).isBetween(1, 60);
        assertThat(count("orders")).isEqualTo(1);
        assertThat(count("order_requests")).isEqualTo(1);
        var ownOrders = alice.send("GET", "/orders", null, null);
        assertThat(ownOrders.statusCode()).isEqualTo(200);
        assertThat(json(ownOrders)).hasSize(1);
        var otherPurchase = bob.buy("other-customer-purchase");
        assertThat(otherPurchase.statusCode()).isEqualTo(201);
        assertThat(available()).isEqualTo(8);
        assertThat(count("orders")).isEqualTo(2);
        assertThat(count("order_requests")).isEqualTo(2);
        assertThat(count("inventory_movements")).isEqualTo(2);
        observations.add(Map.of("scenario", "customer-rate-limit", "httpStatuses", statuses,
                "originalOrderId", originalOrderId, "rejectedHttp", rejected.statusCode(),
                "retryAfterSeconds", retryAfter, "ownListHttp", ownOrders.statusCode(),
                "otherCustomerHttp", otherPurchase.statusCode(), "orders", count("orders"),
                "available", available()));
    }

    private int available() {
        return jdbc.queryForObject("SELECT available_quantity FROM inventories WHERE product_id=?", Integer.class, productId);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private JsonNode json(HttpResponse<String> response) throws Exception {
        return mapper.readTree(response.body());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private final class Customer {
        private final String authorization;
        private final HttpClient http = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .connectTimeout(Duration.ofSeconds(5)).build();
        private final String csrfHeader;
        private final String csrfToken;

        private Customer(String username, String password) throws Exception {
            authorization = "Basic " + Base64.getEncoder().encodeToString(
                    (username + ":" + password).getBytes(StandardCharsets.UTF_8));
            var response = http.send(HttpRequest.newBuilder(uri("/csrf")).header("Authorization", authorization)
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            csrfHeader = json(response).path("headerName").asText();
            csrfToken = json(response).path("token").asText();
        }

        private HttpResponse<String> send(String method, String path, String key, String body) throws Exception {
            var request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10))
                    .header("Authorization", authorization).header(csrfHeader, csrfToken)
                    .header("Content-Type", "application/json");
            if (key != null) request.header("Idempotency-Key", key);
            return http.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }

        private HttpResponse<String> buy(String key) throws Exception {
            return send("POST", "/orders", key, "{\"productId\":" + productId + ",\"quantity\":1}");
        }
    }
}

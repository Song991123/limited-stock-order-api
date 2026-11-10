package dev.mentoring.orders;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

/** 고객의 상품 조회, 주문 재전송, 취소와 재구매를 실제 HTTP 순서로 확인. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "stock.admission.per-customer-per-minute=100000", "spring.main.banner-mode=off"
})
@ActiveProfiles("demo")
@DirtiesContext
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(60)
class ActorOrderJourneyIntegrationTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    private final List<Map<String, Object>> observations = new ArrayList<>();
    private long productId;
    private long otherProductId;
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
    void prepareCustomersAndProducts() throws Exception {
        assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo("stock_order_test");
        jdbc.execute("DROP FUNCTION IF EXISTS test_fail() CASCADE");
        jdbc.execute("TRUNCATE inventory_movements,orders,order_requests,inventories,products RESTART IDENTITY CASCADE");
        productId = addProduct("Journey notebook", 10);
        otherProductId = addProduct("Journey mug", 5);
        alice = new Customer("alice", System.getenv("STOCK_ALICE_PASSWORD"));
        bob = new Customer("bob", System.getenv("STOCK_BOB_PASSWORD"));
    }

    @AfterEach
    void compareOrdersRequestsStockAndHistory() {
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM inventories i WHERE available_quantity<0 OR available_quantity>initial_quantity
                OR available_quantity<>initial_quantity-COALESCE((SELECT sum(quantity) FROM orders o
                    WHERE o.product_id=i.product_id AND o.status='ACCEPTED'),0)
                OR available_quantity<>initial_quantity+COALESCE((SELECT sum(quantity_delta) FROM inventory_movements m
                    WHERE m.product_id=i.product_id),0)
                """, Long.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM order_requests r WHERE outcome IS NULL
                OR (outcome='ACCEPTED' AND (SELECT count(*) FROM orders o WHERE o.request_id=r.id)<>1)
                OR (outcome='OUT_OF_STOCK' AND EXISTS(SELECT 1 FROM orders o WHERE o.request_id=r.id))
                """, Long.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM orders o JOIN order_requests r ON r.id=o.request_id
                WHERE o.buyer_id<>r.buyer_id OR o.product_id<>r.product_id OR o.quantity<>r.quantity
                OR r.outcome<>'ACCEPTED'
                OR (SELECT count(*) FROM inventory_movements m WHERE m.order_id=o.id AND m.kind='DEBIT')<>1
                OR (SELECT count(*) FROM inventory_movements m WHERE m.order_id=o.id AND m.kind='RESTORE')
                    <>(CASE WHEN o.status='CANCELLED' THEN 1 ELSE 0 END)
                OR EXISTS(SELECT 1 FROM inventory_movements m WHERE m.order_id=o.id
                    AND (m.product_id<>o.product_id OR m.quantity_delta<>
                        CASE WHEN m.kind='DEBIT' THEN -o.quantity ELSE o.quantity END))
                """, Long.class)).isZero();
    }

    @AfterAll
    void saveActualResults() throws Exception {
        Path directory = Path.of(Objects.requireNonNull(System.getenv("STOCK_EVIDENCE_DIR")));
        Files.createDirectories(directory);
        mapper.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("actor-order-journeys.json").toFile(), observations);
    }

    /** 상품 확인부터 재전송, 취소와 새 구매까지 같은 고객의 연속 행동 확인. */
    @Test
    void customerPurchasesRetriesCancelsAndPurchasesAgain() throws Exception {
        var catalog = alice.send("GET", "/products", null, null);
        var detail = alice.send("GET", "/products/" + productId, null, null);
        assertThat(catalog.statusCode()).isEqualTo(200);
        assertThat(json(catalog)).hasSize(2);
        assertThat(detail.statusCode()).isEqualTo(200);
        assertThat(json(detail).path("availableQuantity").asInt()).isEqualTo(10);

        var created = alice.buy("journey-first", productId, 2);
        long originalId = createdOrderId(created);
        assertThat(created.headers().firstValue("Location")).hasValue("/orders/" + originalId);
        var repeated = alice.buy("journey-first", productId, 2);
        assertOrder(repeated, 200, originalId, "ACCEPTED");
        assertOrder(alice.send("GET", "/orders/" + originalId, null, null), 200, originalId, "ACCEPTED");
        assertThat(ids(alice.send("GET", "/orders", null, null))).containsExactly(originalId);
        assertThat(available(productId)).isEqualTo(8);

        var cancelled = alice.cancel(originalId);
        var cancelledAgain = alice.cancel(originalId);
        assertOrder(cancelled, 200, originalId, "CANCELLED");
        assertOrder(cancelledAgain, 200, originalId, "CANCELLED");
        assertThat(json(cancelled).path("cancelledAt").isNull()).isFalse();
        assertOrder(alice.send("GET", "/orders/" + originalId, null, null), 200, originalId, "CANCELLED");
        var replayCancelled = alice.buy("journey-first", productId, 2);
        assertOrder(replayCancelled, 200, originalId, "CANCELLED");
        assertThat(available(productId)).isEqualTo(10);
        var changedInput = alice.buy("journey-first", productId, 3);
        assertError(changedInput, 409, "IDEMPOTENCY_KEY_REUSED");

        var newPurchase = alice.buy("journey-new-purchase", productId, 3);
        long newOrderId = createdOrderId(newPurchase);
        assertThat(newOrderId).isNotEqualTo(originalId);
        assertThat(ids(alice.send("GET", "/orders", null, null))).containsExactly(newOrderId, originalId);
        assertThat(available(productId)).isEqualTo(7);
        assertRows(2, 2, 0, 2, 1);
        observations.add(Map.of("scenario", "purchase-retry-cancel-repurchase", "originalOrderId", originalId,
                "replayedOrderId", json(repeated).path("id").asLong(), "cancelledStatus", json(cancelled).path("status").asText(),
                "replayedCancelledStatus", json(replayCancelled).path("status").asText(),
                "changedInputHttp", changedInput.statusCode(), "newOrderId", newOrderId,
                "newPurchaseHttp", newPurchase.statusCode(), "available", available(productId), "database", databaseSummary()));
    }

    /** 품절 기록은 재고가 복구돼도 유지, 새 구매 키만 현재 재고로 다시 판단. */
    @Test
    void rejectedPurchaseNeedsNewKeyAfterAnotherCustomerRestoresStock() throws Exception {
        long firstOrderId = createdOrderId(alice.buy("all-stock", productId, 10));
        var rejected = bob.buy("out-of-stock-purchase", productId, 2);
        assertError(rejected, 409, "OUT_OF_STOCK");
        assertThat(available(productId)).isZero();
        assertThat(ids(bob.send("GET", "/orders", null, null))).isEmpty();
        assertRows(1, 1, 1, 1, 0);
        assertOrder(alice.cancel(firstOrderId), 200, firstOrderId, "CANCELLED");
        assertThat(available(productId)).isEqualTo(10);

        var rejectedAgain = bob.buy("out-of-stock-purchase", productId, 2);
        assertError(rejectedAgain, 409, "OUT_OF_STOCK");
        var changedInput = bob.buy("out-of-stock-purchase", productId, 1);
        assertError(changedInput, 409, "IDEMPOTENCY_KEY_REUSED");
        assertThat(available(productId)).isEqualTo(10);
        var newPurchase = bob.buy("new-after-stock-returned", productId, 2);
        long newOrderId = createdOrderId(newPurchase);
        assertThat(ids(bob.send("GET", "/orders", null, null))).containsExactly(newOrderId);
        assertThat(ids(alice.send("GET", "/orders", null, null))).containsExactly(firstOrderId);
        assertThat(available(productId)).isEqualTo(8);
        assertRows(2, 2, 1, 2, 1);
        observations.add(Map.of("scenario", "out-of-stock-restore-new-purchase", "firstOrderId", firstOrderId,
                "firstRejectionCode", json(rejected).path("code").asText(),
                "replayedRejectionCode", json(rejectedAgain).path("code").asText(),
                "changedInputCode", json(changedInput).path("code").asText(), "newOrderId", newOrderId,
                "newPurchaseHttp", newPurchase.statusCode(), "available", available(productId), "database", databaseSummary()));
    }

    /** 취소와 품절 기록이 섞여도 자기 주문만 페이지별로 반환, 마지막 페이지는 빈 목록. */
    @Test
    void customerPagesContainOwnedOrdersIncludingCancelledButNotRejectedPurchases() throws Exception {
        long first = createdOrderId(alice.buy("page-first", productId, 1));
        long foreign = createdOrderId(bob.buy("page-foreign", productId, 1));
        long second = createdOrderId(alice.buy("page-second", otherProductId, 2));
        long third = createdOrderId(alice.buy("page-third", productId, 1));
        assertOrder(alice.cancel(first), 200, first, "CANCELLED");
        assertError(alice.buy("page-rejected", productId, 1000), 409, "OUT_OF_STOCK");
        var all = ids(alice.send("GET", "/orders?limit=100", null, null));
        assertThat(all).containsExactly(third, second, first).doesNotContain(foreign);
        var pages = new ArrayList<List<Long>>();
        long beforeId = Long.MAX_VALUE;
        for (long expected : List.of(third, second, first)) {
            var page = ids(alice.send("GET", "/orders?limit=1&beforeId=" + beforeId, null, null));
            assertThat(page).containsExactly(expected);
            pages.add(page);
            beforeId = page.getFirst();
        }
        var last = ids(alice.send("GET", "/orders?limit=1&beforeId=" + beforeId, null, null));
        assertThat(last).isEmpty();
        assertThat(ids(bob.send("GET", "/orders", null, null))).containsExactly(foreign);
        assertOrder(alice.send("GET", "/orders/" + first, null, null), 200, first, "CANCELLED");
        assertThat(available(productId)).isEqualTo(8);
        assertThat(available(otherProductId)).isEqualTo(3);
        assertRows(4, 4, 1, 4, 1);
        observations.add(Map.of("scenario", "owned-order-pages", "allOwnedIds", all, "foreignOrderId", foreign,
                "pageIds", pages, "lastPageCount", last.size(), "available", available(productId),
                "otherAvailable", available(otherProductId), "database", databaseSummary()));
    }

    /** 상품이 없을 때 빈 목록, 101개 등록 시 목록 100개와 목록 밖 단건 조회 확인. */
    @Test
    void catalogSupportsEmptyListAndLimitsListingWithoutHidingExistingProductDetails() throws Exception {
        jdbc.execute("TRUNCATE inventory_movements,orders,order_requests,inventories,products RESTART IDENTITY CASCADE");
        var empty = alice.send("GET", "/products", null, null);
        assertThat(empty.statusCode()).isEqualTo(200);
        assertThat(json(empty)).isEmpty();
        jdbc.update("INSERT INTO products(name) SELECT 'Catalog item ' || n FROM generate_series(1,101) AS n");
        jdbc.update("INSERT INTO inventories SELECT id,1,1 FROM products");
        var catalog = alice.send("GET", "/products", null, null);
        assertThat(catalog.statusCode()).isEqualTo(200);
        var body = json(catalog);
        assertThat(body).hasSize(100);
        assertThat(body.get(0).path("id").asLong()).isEqualTo(1);
        assertThat(body.get(99).path("id").asLong()).isEqualTo(100);
        var outsidePage = alice.send("GET", "/products/101", null, null);
        assertThat(outsidePage.statusCode()).isEqualTo(200);
        assertThat(json(outsidePage).path("id").asLong()).isEqualTo(101);
        var missing = alice.send("GET", "/products/" + Long.MAX_VALUE, null, null);
        assertError(missing, 404, "NOT_FOUND");
        assertRows(0, 0, 0, 0, 0);
        observations.add(Map.of("scenario", "catalog-empty-and-limit", "emptyCount", json(empty).size(),
                "storedProducts", count("products"), "returnedCount", body.size(),
                "lastListedId", body.get(99).path("id").asLong(), "outsidePageId", json(outsidePage).path("id").asLong(),
                "outsidePageHttp", outsidePage.statusCode(), "missingHttp", missing.statusCode(), "database", databaseSummary()));
    }

    private long addProduct(String name, int quantity) {
        long id = jdbc.queryForObject("INSERT INTO products(name) VALUES (?) RETURNING id", Long.class, name);
        jdbc.update("INSERT INTO inventories VALUES (?,?,?)", id, quantity, quantity);
        return id;
    }

    private int available(long id) {
        return jdbc.queryForObject("SELECT available_quantity FROM inventories WHERE product_id=?", Integer.class, id);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private long countOutcome(String outcome) {
        return jdbc.queryForObject("SELECT count(*) FROM order_requests WHERE outcome=?", Long.class, outcome);
    }

    private long countMovement(String kind) {
        return jdbc.queryForObject("SELECT count(*) FROM inventory_movements WHERE kind=?", Long.class, kind);
    }

    private Map<String, Object> databaseSummary() {
        return Map.of("orders", count("orders"), "requests", count("order_requests"),
                "acceptedRequests", countOutcome("ACCEPTED"), "rejectedRequests", countOutcome("OUT_OF_STOCK"),
                "debits", countMovement("DEBIT"), "restores", countMovement("RESTORE"),
                "quantityDelta", jdbc.queryForObject("SELECT COALESCE(sum(quantity_delta),0) FROM inventory_movements", Long.class),
                "totalAvailable", jdbc.queryForObject("SELECT COALESCE(sum(available_quantity),0) FROM inventories", Long.class));
    }

    private void assertRows(int orders, int accepted, int rejected, int debits, int restores) {
        assertThat(count("orders")).isEqualTo(orders);
        assertThat(count("order_requests")).isEqualTo(accepted + rejected);
        assertThat(countOutcome("ACCEPTED")).isEqualTo(accepted);
        assertThat(countOutcome("OUT_OF_STOCK")).isEqualTo(rejected);
        assertThat(countMovement("DEBIT")).isEqualTo(debits);
        assertThat(countMovement("RESTORE")).isEqualTo(restores);
        assertThat(count("inventory_movements")).isEqualTo(debits + restores);
    }

    private long createdOrderId(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(json(response).path("status").asText()).isEqualTo("ACCEPTED");
        long id = json(response).path("id").asLong();
        assertThat(id).isPositive();
        return id;
    }

    private void assertOrder(HttpResponse<String> response, int http, long id, String status) throws Exception {
        assertThat(response.statusCode()).isEqualTo(http);
        assertThat(json(response).path("id").asLong()).isEqualTo(id);
        assertThat(json(response).path("status").asText()).isEqualTo(status);
    }

    private void assertError(HttpResponse<String> response, int http, String code) throws Exception {
        assertThat(response.statusCode()).isEqualTo(http);
        assertThat(json(response).path("code").asText()).isEqualTo(code);
    }

    private List<Long> ids(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(200);
        var body = json(response);
        assertThat(body.isArray()).isTrue();
        var result = new ArrayList<Long>();
        for (JsonNode item : body) result.add(item.path("id").asLong());
        return result;
    }

    private JsonNode json(HttpResponse<String> response) throws Exception {
        return mapper.readTree(response.body());
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
            var request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(15))
                    .header("Authorization", authorization).header(csrfHeader, csrfToken)
                    .header("Content-Type", "application/json");
            if (key != null) request.header("Idempotency-Key", key);
            return http.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }

        private HttpResponse<String> buy(String key, long id, int quantity) throws Exception {
            return send("POST", "/orders", key, "{\"productId\":" + id + ",\"quantity\":" + quantity + "}");
        }

        private HttpResponse<String> cancel(long id) throws Exception {
            return send("POST", "/orders/" + id + "/cancel", null, null);
        }
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }
}

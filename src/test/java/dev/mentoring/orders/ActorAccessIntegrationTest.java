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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
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

/** 미인증 요청, 고객별 주문 접근과 세션별 CSRF 검사. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "stock.admission.per-customer-per-minute=100000", "spring.main.banner-mode=off"
})
@DirtiesContext
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(60)
class ActorAccessIntegrationTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    private final List<Map<String, Object>> evidence = new ArrayList<>();
    private String scenario;
    private Client alice;
    private Client bob;
    private long product;

    @DynamicPropertySource static void dedicatedDatabase(DynamicPropertyRegistry registry) {
        var url = System.getenv("STOCK_DB_URL");
        if (url == null || !url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/stock_order_test")) {
            throw new IllegalStateException("Use scripts/test.ps1 with its dedicated loopback database");
        }
        registry.add("spring.datasource.url", () -> url);
    }

    @BeforeEach void fixture(TestInfo info) throws Exception {
        scenario = info.getTestMethod().orElseThrow().getName();
        assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo("stock_order_test");
        jdbc.execute("DROP FUNCTION IF EXISTS test_fail() CASCADE");
        jdbc.execute("TRUNCATE inventory_movements,orders,order_requests,inventories,products RESTART IDENTITY CASCADE");
        product = jdbc.queryForObject("INSERT INTO products(name) VALUES (?) RETURNING id", Long.class,
                "Actor access notebook");
        jdbc.update("INSERT INTO inventories(product_id,initial_quantity,available_quantity) VALUES (?,?,?)",
                product, 10, 10);
        alice = new Client("alice", "alice", password("STOCK_ALICE_PASSWORD"));
        bob = new Client("bob", "bob", password("STOCK_BOB_PASSWORD"));
        alice.issueCsrf();
        bob.issueCsrf();
        observe(Map.of("phase", "fixture", "database", databaseState()));
    }

    @AfterEach void checkConsistency() {
        long inventoryViolations = jdbc.queryForObject("""
                SELECT count(*) FROM inventories i WHERE available_quantity<0 OR available_quantity>initial_quantity
                OR available_quantity<>initial_quantity-COALESCE((SELECT sum(quantity) FROM orders o
                    WHERE o.product_id=i.product_id AND o.status='ACCEPTED'),0)
                OR available_quantity<>initial_quantity+COALESCE((SELECT sum(quantity_delta) FROM inventory_movements m
                    WHERE m.product_id=i.product_id),0)
                """, Long.class);
        long requestViolations = jdbc.queryForObject("""
                SELECT count(*) FROM order_requests r WHERE outcome IS NULL
                OR (outcome='ACCEPTED' AND (SELECT count(*) FROM orders o WHERE o.request_id=r.id)<>1)
                OR (outcome='OUT_OF_STOCK' AND EXISTS(SELECT 1 FROM orders o WHERE o.request_id=r.id))
                """, Long.class);
        long orderViolations = jdbc.queryForObject("""
                SELECT count(*) FROM orders o JOIN order_requests r ON r.id=o.request_id
                WHERE o.buyer_id<>r.buyer_id OR o.product_id<>r.product_id OR o.quantity<>r.quantity
                OR r.outcome<>'ACCEPTED'
                OR (SELECT count(*) FROM inventory_movements m WHERE m.order_id=o.id AND m.kind='DEBIT')<>1
                OR (SELECT count(*) FROM inventory_movements m WHERE m.order_id=o.id AND m.kind='RESTORE')
                    <>(CASE WHEN o.status='CANCELLED' THEN 1 ELSE 0 END)
                OR EXISTS(SELECT 1 FROM inventory_movements m WHERE m.order_id=o.id
                    AND (m.product_id<>o.product_id OR m.quantity_delta<>
                        CASE WHEN m.kind='DEBIT' THEN -o.quantity ELSE o.quantity END))
                """, Long.class);
        observe(Map.of("phase", "after-each", "database", databaseState(),
                "inventoryViolations", inventoryViolations, "requestViolations", requestViolations,
                "orderViolations", orderViolations));
        assertThat(inventoryViolations).isZero();
        assertThat(requestViolations).isZero();
        assertThat(orderViolations).isZero();
    }

    @AfterAll void saveEvidence() throws Exception {
        var directory = Path.of(Objects.requireNonNull(System.getenv("STOCK_EVIDENCE_DIR")));
        Files.createDirectories(directory);
        mapper.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("actor-access.json").toFile(), evidence);
    }

    @Test void anonymousAndWrongPasswordCannotUseAnyApi() throws Exception {
        long orderId = create(alice, "access-owner", 2);
        var before = databaseState();
        var anonymous = new Client("anonymous", null, null);
        var validPassword = password("STOCK_ALICE_PASSWORD");
        var invalidPassword = (validPassword.charAt(0) == 'x' ? "y" : "x") + validPassword.substring(1);
        var wrongPassword = new Client("wrong-password", "alice", invalidPassword);

        for (var client : List.of(anonymous, wrongPassword)) {
            for (var path : List.of("/csrf", "/products", "/products/" + product,
                    "/orders", "/orders/" + orderId)) {
                var response = client.send("GET", path, null, null);
                assertThat(response.statusCode()).as(client.actor + " GET " + path).isEqualTo(401);
                assertThat(response.headers().firstValue("WWW-Authenticate")).hasValueSatisfying(
                        value -> assertThat(value).startsWith("Basic "));
                assertUnchanged(before);
            }
            // CSRF 검사 선행으로 토큰 없는 POST는 인증 검사 전 거절.
            var purchase = client.send("POST", "/orders", "access-denied", payload(1));
            assertThat(purchase.statusCode()).isEqualTo(403);
            assertUnchanged(before);
            var cancellation = client.cancel(orderId);
            assertThat(cancellation.statusCode()).isEqualTo(403);
            assertUnchanged(before);
        }
        assertOrder(alice.send("GET", "/orders/" + orderId, null, null), 200, orderId, "ACCEPTED");
        assertStock(8);
    }

    @Test void anotherCustomerCannotAccessAcceptedOrCancelledOrderEvenWithItsKey() throws Exception {
        String key = "known-alice-key";
        long aliceOrder = create(alice, key, 2);
        var accepted = databaseState();
        assertThat(orderIds(bob.send("GET", "/orders", null, null))).isEmpty();
        assertUnchanged(accepted);
        assertMissing(bob.send("GET", "/orders/" + aliceOrder, null, null));
        assertUnchanged(accepted);
        assertMissing(bob.cancel(aliceOrder));
        assertUnchanged(accepted);

        long absentId = Long.MAX_VALUE;
        assertMissing(bob.send("GET", "/products/" + absentId, null, null));
        assertUnchanged(accepted);
        assertMissing(bob.send("GET", "/orders/" + absentId, null, null));
        assertUnchanged(accepted);
        assertMissing(bob.cancel(absentId));
        assertUnchanged(accepted);

        assertOrder(alice.cancel(aliceOrder), 200, aliceOrder, "CANCELLED");
        assertStock(10);
        var cancelled = databaseState();
        assertMissing(bob.send("GET", "/orders/" + aliceOrder, null, null));
        assertUnchanged(cancelled);
        assertMissing(bob.cancel(aliceOrder));
        assertUnchanged(cancelled);
        assertThat(orderIds(bob.send("GET", "/orders", null, null))).isEmpty();
        assertUnchanged(cancelled);

        long bobOrder = create(bob, key, 2);
        assertThat(bobOrder).isNotEqualTo(aliceOrder);
        assertThat(orderIds(bob.send("GET", "/orders", null, null))).containsExactly(bobOrder);
        assertThat(orderIds(alice.send("GET", "/orders", null, null))).containsExactly(aliceOrder);
        assertOrder(bob.send("GET", "/orders/" + bobOrder, null, null), 200, bobOrder, "ACCEPTED");
        assertOrder(alice.send("GET", "/orders/" + aliceOrder, null, null), 200, aliceOrder, "CANCELLED");
        assertOwner(aliceOrder, "alice");
        assertOwner(bobOrder, "bob");
        assertStock(8);
    }

    @Test void sameKeyCustomersCancelOnlyTheirOwnOrders() throws Exception {
        String key = "shared-purchase-key";
        long aliceOrder = create(alice, key, 2);
        long bobOrder = create(bob, key, 3);
        assertThat(bobOrder).isNotEqualTo(aliceOrder);
        assertStock(5);
        assertThat(orderIds(alice.send("GET", "/orders", null, null))).containsExactly(aliceOrder);
        assertThat(orderIds(bob.send("GET", "/orders", null, null))).containsExactly(bobOrder);
        var before = databaseState();
        assertMissing(alice.cancel(bobOrder));
        assertUnchanged(before);
        assertMissing(bob.cancel(aliceOrder));
        assertUnchanged(before);

        assertOrder(alice.cancel(aliceOrder), 200, aliceOrder, "CANCELLED");
        assertOrder(bob.send("GET", "/orders/" + bobOrder, null, null), 200, bobOrder, "ACCEPTED");
        assertStock(7);
        var afterAliceCancellation = databaseState();
        assertOrder(alice.buy(key, 2), 200, aliceOrder, "CANCELLED");
        assertUnchanged(afterAliceCancellation);

        assertOrder(bob.cancel(bobOrder), 200, bobOrder, "CANCELLED");
        assertOrder(alice.send("GET", "/orders/" + aliceOrder, null, null), 200, aliceOrder, "CANCELLED");
        assertOwner(aliceOrder, "alice");
        assertOwner(bobOrder, "bob");
        assertStock(10);
    }

    @Test void forgedBuyerIdIsRejectedThenCorrectedPurchaseBelongsToCaller() throws Exception {
        long aliceOrder = create(alice, "buyer-field-owner", 1);
        var before = databaseState();
        long aliceBuyer = jdbc.queryForObject("SELECT id FROM buyers WHERE username=?", Long.class, "alice");
        var forgedBody = mapper.writeValueAsString(Map.of("productId", product, "quantity", 2,
                "buyerId", aliceBuyer));
        var forged = bob.send("POST", "/orders", "buyer-field-attempt", forgedBody);
        assertThat(forged.statusCode()).isEqualTo(400);
        assertThat(json(forged).path("code").asText()).isEqualTo("INVALID_INPUT");
        assertUnchanged(before);
        assertThat(orderIds(bob.send("GET", "/orders", null, null))).isEmpty();
        assertUnchanged(before);

        long bobOrder = create(bob, "buyer-field-attempt", 2);
        assertThat(orderIds(bob.send("GET", "/orders", null, null))).containsExactly(bobOrder);
        assertThat(orderIds(alice.send("GET", "/orders", null, null))).containsExactly(aliceOrder);
        assertOwner(aliceOrder, "alice");
        assertOwner(bobOrder, "bob");
        assertStock(7);
    }

    @Test void missingAndIndependentSessionTokensRejectPurchaseAndCancellation() throws Exception {
        long orderId = create(alice, "csrf-owner", 2);
        var secondSession = new Client("alice-independent-session", "alice", password("STOCK_ALICE_PASSWORD"));
        secondSession.issueCsrf();
        boolean independentSessions = !alice.sessionId().equals(secondSession.sessionId());
        observe(Map.of("phase", "independent-sessions", "differentSessions", independentSessions));
        assertThat(independentSessions).isTrue();
        var before = databaseState();

        var missingPurchase = alice.sendWithCsrf("POST", "/orders", "csrf-retry", payload(1), null);
        assertThat(missingPurchase.statusCode()).isEqualTo(403);
        assertUnchanged(before);
        var missingCancellation = alice.sendWithCsrf("POST", "/orders/" + orderId + "/cancel", null, null, null);
        assertThat(missingCancellation.statusCode()).isEqualTo(403);
        assertUnchanged(before);

        var foreignPurchase = alice.sendWithCsrf("POST", "/orders", "csrf-retry", payload(1), secondSession.csrf);
        assertThat(foreignPurchase.statusCode()).isEqualTo(403);
        assertUnchanged(before);
        var foreignCancellation = alice.sendWithCsrf("POST", "/orders/" + orderId + "/cancel",
                null, null, secondSession.csrf);
        assertThat(foreignCancellation.statusCode()).isEqualTo(403);
        assertUnchanged(before);

        long newOrder = create(alice, "csrf-retry", 1);
        assertThat(newOrder).isNotEqualTo(orderId);
        assertOrder(alice.cancel(orderId), 200, orderId, "CANCELLED");
        assertOrder(alice.send("GET", "/orders/" + newOrder, null, null), 200, newOrder, "ACCEPTED");
        assertStock(9);
    }

    @Test void deletingSessionCookieRequiresFreshCsrfBeforeRetry() throws Exception {
        long orderId = create(alice, "cookie-owner", 2);
        var before = databaseState();
        var oldToken = alice.csrf;
        var oldSession = alice.sessionId();
        int cookiesBefore = alice.sessionCookieCount();
        boolean cookiesRemoved = alice.cookies.getCookieStore().removeAll();
        int cookiesAfter = alice.sessionCookieCount();
        observe(Map.of("phase", "delete-session-cookie", "cookiesBefore", cookiesBefore,
                "cookiesAfter", cookiesAfter, "cookiesRemoved", cookiesRemoved));
        assertThat(cookiesBefore).isPositive();
        assertThat(cookiesRemoved).isTrue();
        assertThat(cookiesAfter).isZero();

        var stalePurchase = alice.sendWithCsrf("POST", "/orders", "cookie-retry", payload(1), oldToken);
        assertThat(stalePurchase.statusCode()).isEqualTo(403);
        assertUnchanged(before);
        var staleCancellation = alice.sendWithCsrf("POST", "/orders/" + orderId + "/cancel",
                null, null, oldToken);
        assertThat(staleCancellation.statusCode()).isEqualTo(403);
        assertUnchanged(before);

        alice.issueCsrf();
        boolean sessionReplaced = !oldSession.equals(alice.sessionId());
        observe(Map.of("phase", "new-csrf-session", "sessionReplaced", sessionReplaced));
        assertThat(sessionReplaced).isTrue();
        long newOrder = create(alice, "cookie-retry", 1);
        assertOrder(alice.send("GET", "/orders/" + newOrder, null, null), 200, newOrder, "ACCEPTED");
        assertOrder(alice.cancel(orderId), 200, orderId, "CANCELLED");
        assertStock(9);
    }

    private long create(Client client, String key, int quantity) throws Exception {
        var response = client.buy(key, quantity);
        assertThat(response.statusCode()).isEqualTo(201);
        var body = json(response);
        assertThat(body.path("status").asText()).isEqualTo("ACCEPTED");
        long id = body.path("id").asLong();
        assertThat(id).isPositive();
        assertThat(body.path("productId").asLong()).isEqualTo(product);
        assertThat(body.path("quantity").asInt()).isEqualTo(quantity);
        return id;
    }

    private void assertOrder(HttpResponse<String> response, int http, long id, String status) {
        assertThat(response.statusCode()).isEqualTo(http);
        assertThat(json(response).path("id").asLong()).isEqualTo(id);
        assertThat(json(response).path("status").asText()).isEqualTo(status);
    }

    private void assertMissing(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(json(response).path("code").asText()).isEqualTo("NOT_FOUND");
    }

    private List<Long> orderIds(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(200);
        var body = json(response);
        assertThat(body.isArray()).isTrue();
        var ids = new ArrayList<Long>();
        body.forEach(order -> ids.add(order.path("id").asLong()));
        return ids;
    }

    private void assertOwner(long orderId, String username) {
        long expectedBuyer = jdbc.queryForObject("SELECT id FROM buyers WHERE username=?", Long.class, username);
        long storedBuyer = jdbc.queryForObject("SELECT buyer_id FROM orders WHERE id=?", Long.class, orderId);
        observe(Map.of("phase", "stored-owner", "orderId", orderId, "account", username,
                "accountBuyerId", expectedBuyer, "orderBuyerId", storedBuyer));
        assertThat(storedBuyer).isEqualTo(expectedBuyer);
    }

    private void assertStock(int expected) {
        int available = jdbc.queryForObject("SELECT available_quantity FROM inventories WHERE product_id=?",
                Integer.class, product);
        observe(Map.of("phase", "inventory", "productId", product, "available", available));
        assertThat(available).isEqualTo(expected);
    }

    private void assertUnchanged(Map<String, Object> before) {
        var after = databaseState();
        observe(Map.of("phase", "storage-check", "unchanged", before.equals(after), "database", after));
        assertThat(after).isEqualTo(before);
    }

    private Map<String, Object> databaseState() {
        return Map.of(
                "buyers", jdbc.queryForList("SELECT id,username FROM buyers ORDER BY id"),
                "products", jdbc.queryForList("SELECT id,name FROM products ORDER BY id"),
                "inventories", jdbc.queryForList("SELECT product_id,initial_quantity,available_quantity FROM inventories ORDER BY product_id"),
                "requests", jdbc.queryForList("""
                        SELECT id,buyer_id,request_key,product_id,quantity,outcome,created_at::text AS created_at
                        FROM order_requests ORDER BY id
                        """),
                "orders", jdbc.queryForList("""
                        SELECT id,request_id,buyer_id,product_id,quantity,status,
                            created_at::text AS created_at,cancelled_at::text AS cancelled_at
                        FROM orders ORDER BY id
                        """),
                "movements", jdbc.queryForList("""
                        SELECT id,order_id,product_id,kind,quantity_delta,created_at::text AS created_at
                        FROM inventory_movements ORDER BY id
                        """));
    }

    private String payload(int quantity) throws Exception {
        return mapper.writeValueAsString(Map.of("productId", product, "quantity", quantity));
    }

    private String password(String name) {
        var value = Objects.requireNonNull(System.getenv(name), "Missing " + name);
        assertThat(value.isBlank()).as("Configured test password").isFalse();
        return value;
    }

    private JsonNode json(HttpResponse<String> response) {
        try {
            return mapper.readTree(response.body());
        } catch (Exception error) {
            throw new AssertionError("Expected JSON at HTTP " + response.statusCode());
        }
    }

    private void observe(Map<String, ?> details) {
        evidence.add(Map.of("scenario", scenario, "observations", details));
    }

    private record Csrf(String header, String token) { }

    private final class Client {
        final String actor;
        final String authorization;
        final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        final HttpClient http = HttpClient.newBuilder().cookieHandler(cookies)
                .connectTimeout(Duration.ofSeconds(5)).build();
        Csrf csrf;

        Client(String actor, String username, String password) {
            this.actor = actor;
            authorization = username == null ? null : "Basic " + Base64.getEncoder().encodeToString(
                    (username + ":" + password).getBytes(StandardCharsets.UTF_8));
        }

        void issueCsrf() throws Exception {
            var response = send("GET", "/csrf", null, null);
            assertThat(response.statusCode()).isEqualTo(200);
            var body = json(response);
            csrf = new Csrf(body.path("headerName").asText(), body.path("token").asText());
            assertThat(csrf.header().isBlank()).isFalse();
            assertThat(csrf.token().isBlank()).isFalse();
            assertThat(sessionCookieCount()).isPositive();
        }

        HttpResponse<String> send(String method, String path, String key, String body) throws Exception {
            return sendWithCsrf(method, path, key, body, csrf);
        }

        HttpResponse<String> sendWithCsrf(String method, String path, String key, String body, Csrf token)
                throws Exception {
            var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .timeout(Duration.ofSeconds(25)).header("Accept", "application/json")
                    .header("Content-Type", "application/json");
            if (authorization != null) builder.header("Authorization", authorization);
            if (token != null) builder.header(token.header(), token.token());
            if (key != null) builder.header("Idempotency-Key", key);
            var response = http.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            var details = new LinkedHashMap<String, Object>();
            details.put("phase", "http");
            details.put("actor", actor);
            details.put("method", method);
            details.put("path", path);
            details.put("http", response.statusCode());
            details.put("csrfHeaderPresent", token != null);
            details.put("sessionCookieCount", sessionCookieCount());
            details.put("responseBytes", response.body().getBytes(StandardCharsets.UTF_8).length);
            if (!response.body().isBlank() && response.headers().firstValue("Content-Type")
                    .orElse("").startsWith("application/json")) {
                var responseBody = json(response);
                // CSRF 응답은 토큰 값 대신 발급 여부만 기록.
                details.put("body", path.equals("/csrf") ? Map.of(
                        "headerName", responseBody.path("headerName").asText(),
                        "parameterName", responseBody.path("parameterName").asText(),
                        "tokenIssued", !responseBody.path("token").asText().isBlank()) : responseBody);
            }
            response.headers().firstValue("WWW-Authenticate").ifPresent(value -> details.put("challenge", value));
            observe(details);
            return response;
        }

        HttpResponse<String> buy(String key, int quantity) throws Exception {
            return send("POST", "/orders", key, payload(quantity));
        }

        HttpResponse<String> cancel(long id) throws Exception {
            return send("POST", "/orders/" + id + "/cancel", null, null);
        }

        int sessionCookieCount() {
            return (int) cookies.getCookieStore().getCookies().stream()
                    .filter(cookie -> cookie.getName().equals("JSESSIONID")).count();
        }

        String sessionId() {
            return cookies.getCookieStore().getCookies().stream()
                    .filter(cookie -> cookie.getName().equals("JSESSIONID"))
                    .map(cookie -> cookie.getValue()).findFirst().orElseThrow();
        }
    }
}

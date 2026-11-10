package dev.mentoring.orders;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "stock.admission.per-customer-per-minute=100000", "spring.main.banner-mode=off"
})
@DirtiesContext
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(60)
class OrderHttpIntegrationTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    private final List<Map<String,Object>> evidence = new ArrayList<>();
    private Client alice;
    private Client bob;
    private long product;
    private long other;

    @DynamicPropertySource static void dedicatedDatabase(DynamicPropertyRegistry registry) {
        var url = System.getenv("STOCK_DB_URL");
        if (url == null || !url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/stock_order_test")) {
            throw new IllegalStateException("Use scripts/test.ps1 with its dedicated loopback database");
        }
        registry.add("spring.datasource.url", () -> url);
    }

    @BeforeEach void fixture() throws Exception {
        assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo("stock_order_test");
        jdbc.execute("DROP FUNCTION IF EXISTS test_fail() CASCADE");
        jdbc.execute("TRUNCATE inventory_movements,orders,order_requests,inventories,products RESTART IDENTITY CASCADE");
        product = addProduct("Limited notebook", 10);
        other = addProduct("Limited mug", 10);
        alice = new Client("alice", System.getenv("STOCK_ALICE_PASSWORD"));
        bob = new Client("bob", System.getenv("STOCK_BOB_PASSWORD"));
    }

    @AfterEach void checkConsistency() { invariants(); }
    @AfterAll void saveEvidence() throws Exception {
        var directory = Path.of(Objects.requireNonNull(System.getenv("STOCK_EVIDENCE_DIR")));
        Files.createDirectories(directory);
        mapper.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("http-observations.json").toFile(), evidence);
    }

    @Test void createGetAndList() throws Exception {
        var response = alice.buy("first-purchase", product, 2);
        assertThat(response.statusCode()).isEqualTo(201);
        var body = json(response);
        assertThat(body.path("status").asText()).isEqualTo("ACCEPTED");
        assertThat(response.headers().firstValue("Location")).hasValue("/orders/" + body.path("id").asLong());
        assertThat(json(alice.send("GET", "/orders/" + body.path("id").asLong(), null, null))).isEqualTo(body);
        assertThat(json(alice.send("GET", "/orders", null, null))).hasSize(1);
        assertThat(available()).isEqualTo(8);
        observe("create-get-list", Map.of("http",201,"orders",1,"available",8));
    }

    /** 내부 매개변수 이름을 바꿔도 기존 beforeId/limit API와 ID 응답 필드 유지. */
    @Test void existingQueryNamesStillSelectOneOrderPage() throws Exception {
        long firstOrderId=json(alice.buy("page-first",product,1)).path("id").asLong();
        long secondOrderId=json(alice.buy("page-second",product,1)).path("id").asLong();
        long thirdOrderId=json(alice.buy("page-third",product,1)).path("id").asLong();

        var firstPage=json(alice.send("GET","/orders?limit=1",null,null));
        assertThat(firstPage).hasSize(1);
        assertThat(firstPage.get(0).path("id").asLong()).isEqualTo(thirdOrderId);
        var nextPage=json(alice.send("GET","/orders?limit=1&beforeId="+thirdOrderId,null,null));
        assertThat(nextPage).hasSize(1);
        assertThat(nextPage.get(0).path("id").asLong()).isEqualTo(secondOrderId);
        var lastPage=json(alice.send("GET","/orders?limit=1&beforeId="+secondOrderId,null,null));
        assertThat(lastPage).hasSize(1);
        assertThat(lastPage.get(0).path("id").asLong()).isEqualTo(firstOrderId);
        observe("existing-page-query-names",Map.of("pageSize",1,"pages",3,"available",7));
    }

    @RepeatedTest(value = 3, name = "{displayName} [{currentRepetition}/{totalRepetitions}]")
    void lastStockTwoCustomers(RepetitionInfo repetition) throws Exception {
        stock(1);
        var responses = overlap(List.of(() -> alice.buy("last-a", product, 1), () -> bob.buy("last-b", product, 1)));
        assertThat(responses.stream().map(HttpResponse::statusCode)).containsExactlyInAnyOrder(201,409);
        assertThat(count("orders")).isEqualTo(1);
        assertThat(count("order_requests")).isEqualTo(2);
        assertThat(available()).isZero();
        observe("last-stock-" + repetition.getCurrentRepetition(), Map.of("requests",2,"accepted",1,"outOfStock",1,"available",0));
    }

    @RepeatedTest(value = 3, name = "{displayName} [{currentRepetition}/{totalRepetitions}]")
    void twentyRetriesOnePurchase(RepetitionInfo repetition) throws Exception {
        var operations = new ArrayList<Callable<HttpResponse<String>>>();
        for (int i=0;i<20;i++) operations.add(() -> alice.buy("same-purchase",product,2));
        var responses = overlap(operations);
        assertThat(responses.stream().filter(r->r.statusCode()==201).count()).isEqualTo(1);
        assertThat(responses.stream().filter(r->r.statusCode()==200).count()).isEqualTo(19);
        assertThat(responses.stream().map(r->json(r).path("id").asLong()).distinct().count()).isEqualTo(1);
        assertThat(count("orders")).isEqualTo(1);
        assertThat(count("order_requests")).isEqualTo(1);
        assertThat(available()).isEqualTo(8);
        observe("same-purchase-" + repetition.getCurrentRepetition(),Map.of("requests",20,"accepted",1,"replayed",19,"available",8));
    }

    @Test void sameKeyDifferentValidInputs() throws Exception {
        var responses = overlap(List.of(() -> alice.buy("conflicting-purchase",product,1), () -> alice.buy("conflicting-purchase",product,2)));
        assertThat(responses.stream().map(HttpResponse::statusCode)).containsExactlyInAnyOrder(201,409);
        var conflict = responses.stream().filter(r->r.statusCode()==409).findFirst().orElseThrow();
        assertThat(json(conflict).path("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(count("orders")).isEqualTo(1);
    }

    @Test void newKeyIsNewPurchase() throws Exception {
        var first = alice.buy("purchase-one",product,1);
        var next = alice.buy("purchase-two",product,1);
        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(next.statusCode()).isEqualTo(201);
        assertThat(json(first).path("id").asLong()).isNotEqualTo(json(next).path("id").asLong());
        assertThat(count("orders")).isEqualTo(2);
        assertThat(available()).isEqualTo(8);
    }

    @Test void sameKeyCannotChangeProduct() throws Exception {
        assertThat(alice.buy("one-purchase-one-product",product,1).statusCode()).isEqualTo(201);
        var response=alice.buy("one-purchase-one-product",other,1);
        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(json(response).path("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(count("orders")).isEqualTo(1);
        assertThat(available()).isEqualTo(9);
        assertThat(jdbc.queryForObject("SELECT available_quantity FROM inventories WHERE product_id=?",Integer.class,other)).isEqualTo(10);
    }

    /** 기존 구매 키의 다른 상품 입력은 상품 존재 여부와 관계없이 같은 충돌로 처리. */
    @Test void sameKeyWithUnknownProductStillReportsChangedInput() throws Exception {
        assertThat(alice.buy("existing-purchase-input", product, 1).statusCode()).isEqualTo(201);

        var response = alice.buy("existing-purchase-input", Long.MAX_VALUE, 1);

        observe("same-key-unknown-product", Map.of("http", response.statusCode(),
                "code", json(response).path("code").asText(), "orders", count("orders"), "available", available()));
        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(json(response).path("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(count("orders")).isEqualTo(1);
        assertThat(count("order_requests")).isEqualTo(1);
        assertThat(available()).isEqualTo(9);
    }

    @Test void sameTextKeyDifferentCustomers() throws Exception {
        assertThat(alice.buy("independent-key",product,1).statusCode()).isEqualTo(201);
        assertThat(bob.buy("independent-key",product,1).statusCode()).isEqualTo(201);
        assertThat(count("orders")).isEqualTo(2);
    }

    @Test void orderInsertFailureRollsBackSubtraction() throws Exception {
        fail("orders", "BEFORE INSERT", null);
        assertThat(alice.buy("insert-failure",product,2).statusCode()).isEqualTo(500);
        assertEmptyAndStock(10);
        removeFault();
        assertThat(alice.buy("insert-failure",product,2).statusCode()).isEqualTo(201);
        assertThat(available()).isEqualTo(8);
        observe("rollback-after-subtraction",Map.of("duringFailureAvailable",10,"retryAvailable",8,"orders",1));
    }

    /** 실제 서버 오류에서도 JSON은 오류 코드만 반환하고 헤더에는 로그를 찾을 번호 제공. */
    @Test void serverFailureHasAnErrorReferenceWithoutSqlDetails() throws Exception {
        fail("orders", "BEFORE INSERT", null);

        var response = alice.buy("referenced-storage-failure", product, 2);

        assertThat(response.statusCode()).isEqualTo(500);
        String reference = response.headers().firstValue("X-Error-Id").orElseThrow();
        assertThat(UUID.fromString(reference).toString()).isEqualTo(reference);
        assertThat(json(response).path("code").asText()).isEqualTo("INTERNAL_ERROR");
        assertJsonFields(json(response), "code");
        assertEmptyAndStock(10);
        observe("server-error-reference", Map.of("http", 500, "referencePresent", true, "available", 10));
    }

    @Test void requestFinishFailureRollsBackOrderAndMovement() throws Exception {
        fail("order_requests","BEFORE UPDATE",null);
        assertThat(alice.buy("finish-failure",product,2).statusCode()).isEqualTo(500);
        assertEmptyAndStock(10);
        removeFault();
        assertThat(alice.buy("finish-failure",product,2).statusCode()).isEqualTo(201);
    }

    @Test void debitHistoryFailureRollsBackAll() throws Exception {
        fail("inventory_movements","BEFORE INSERT", "NEW.kind='DEBIT'");
        assertThat(alice.buy("history-failure",product,1).statusCode()).isEqualTo(500);
        assertEmptyAndStock(10);
    }

    @Test void deferredConstraintRejectsUnfinishedRequest() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO order_requests(buyer_id,request_key,product_id,quantity) SELECT id,'unfinished',?,1 FROM buyers WHERE username='alice'",product))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertEmptyAndStock(10);
    }

    @Test void outOfStockResultStaysWithOriginalKey() throws Exception {
        stock(1);
        long id = json(alice.buy("first-owner",product,1)).path("id").asLong();
        assertThat(bob.buy("sold-out",product,1).statusCode()).isEqualTo(409);
        assertThat(alice.cancel(id).statusCode()).isEqualTo(200);
        assertThat(bob.buy("sold-out",product,1).statusCode()).isEqualTo(409);
        assertThat(bob.buy("new-buying-action",product,1).statusCode()).isEqualTo(201);
    }

    @RepeatedTest(value = 3, name = "{displayName} [{currentRepetition}/{totalRepetitions}]")
    void twentyCancellationsRestoreOnce(RepetitionInfo repetition) throws Exception {
        long id = json(alice.buy("cancel-once",product,3)).path("id").asLong();
        var operations = new ArrayList<Callable<HttpResponse<String>>>();
        for(int i=0;i<20;i++) operations.add(() -> alice.cancel(id));
        var responses = overlap(operations);
        assertThat(responses).allMatch(r->r.statusCode()==200);
        assertThat(responses).allMatch(r->json(r).path("status").asText().equals("CANCELLED"));
        assertThat(available()).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_movements WHERE kind='RESTORE'",Long.class)).isEqualTo(1);
        observe("cancel-once-"+repetition.getCurrentRepetition(),Map.of("requests",20,"restorations",1,"available",10));
    }

    @Test void restorationFailureRollsBackCancellation() throws Exception {
        long id=json(alice.buy("restore-failure",product,2)).path("id").asLong();
        fail("inventories","BEFORE UPDATE","NEW.available_quantity>OLD.available_quantity");
        assertThat(alice.cancel(id).statusCode()).isEqualTo(500);
        assertThat(json(alice.send("GET","/orders/"+id,null,null)).path("status").asText()).isEqualTo("ACCEPTED");
        assertThat(available()).isEqualTo(8);
        removeFault();
        assertThat(alice.cancel(id).statusCode()).isEqualTo(200);
        assertThat(available()).isEqualTo(10);
    }

    @Test void restorationHistoryFailureRollsBackStockAndState() throws Exception {
        long id=json(alice.buy("restore-history-failure",product,2)).path("id").asLong();
        fail("inventory_movements","BEFORE INSERT","NEW.kind='RESTORE'");
        assertThat(alice.cancel(id).statusCode()).isEqualTo(500);
        assertThat(available()).isEqualTo(8);
        assertThat(json(alice.send("GET","/orders/"+id,null,null)).path("status").asText()).isEqualTo("ACCEPTED");
    }

    @Test void cancellationAndNewPurchasePreserveStock() throws Exception {
        stock(1);
        long id=json(alice.buy("previous-owner",product,1)).path("id").asLong();
        try(var executor=Executors.newSingleThreadExecutor();var blocker=connection();var observer=connection()) {
            lockInventory(blocker);
            var cancellation=executor.submit(()->alice.cancel(id));
            try {
                await().atMost(Duration.ofSeconds(2)).until(()->waiters(observer)>=1);
                assertThat(bob.buy("before-cancel-commit",product,1).statusCode()).isEqualTo(409);
                assertThat(cancellation.isDone()).isFalse();
                blocker.commit();
                assertThat(cancellation.get(10,TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            } finally { blocker.rollback(); }
        }
        assertThat(available()).isEqualTo(1);
        assertThat(bob.buy("after-cancel-commit",product,1).statusCode()).isEqualTo(201);
        observe("cancel-and-purchase",Map.of("beforeCancelCommitHttp",409,"afterCancelCommitHttp",201,"available",available()));
    }

    @Test void oldPurchaseKeyAfterCancellationDoesNotResurrectOrder() throws Exception {
        long id=json(alice.buy("old-purchase",product,2)).path("id").asLong();
        alice.cancel(id);
        var replay=alice.buy("old-purchase",product,2);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(json(replay).path("id").asLong()).isEqualTo(id);
        assertThat(json(replay).path("status").asText()).isEqualTo("CANCELLED");
        assertThat(count("orders")).isEqualTo(1);
        assertThat(available()).isEqualTo(10);
    }

    @Test void otherCustomerCannotReadOrCancelOrder() throws Exception {
        long id=json(alice.buy("private-order",product,1)).path("id").asLong();
        assertThat(bob.send("GET","/orders/"+id,null,null).statusCode()).isEqualTo(404);
        assertThat(bob.cancel(id).statusCode()).isEqualTo(404);
        assertThat(json(bob.send("GET","/orders",null,null))).hasSize(0);
        assertThat(available()).isEqualTo(9);
    }

    @Test void lockTimeoutIsRetryableAndDoesNotLeaveRequest() throws Exception {
        try(var blocker=connection()) {
            lockInventory(blocker);
            var response=alice.buy("blocked-purchase",product,1);
            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(response.headers().firstValue("Retry-After")).isPresent();
            assertEmptyAndStock(10);
            blocker.rollback();
        }
        assertThat(alice.buy("blocked-purchase",product,1).statusCode()).isEqualTo(201);
        observe("lock-timeout",Map.of("blockedHttp",503,"retryHttp",201,"available",9));
    }

    @Test void unrelatedProductDoesNotWaitForHeldStock() throws Exception {
        try(var blocker=connection()) {
            lockInventory(blocker);
            assertThat(alice.buy("other-product",other,1).statusCode()).isEqualTo(201);
            blocker.rollback();
        }
        assertThat(available()).isEqualTo(10);
    }

    @Test void invalidAndCoercedInputsChangeNothing() throws Exception {
        for(String body: List.of("{}","{\"productId\":1,\"quantity\":0}","{\"productId\":1,\"quantity\":-1}",
                "{\"productId\":1,\"quantity\":1.2}","{\"productId\":1,\"quantity\":\"1\"}",
                "{\"productId\":1,\"quantity\":1,\"buyerId\":2}","{\"productId\":1,\"quantity\":2147483648}")) {
            assertThat(alice.send("POST","/orders","invalid-body",body).statusCode()).isEqualTo(400);
        }
        assertThat(alice.send("POST","/orders",null,payload(product,1)).statusCode()).isEqualTo(400);
        assertThat(alice.send("POST","/orders","bad key",payload(product,1)).statusCode()).isEqualTo(400);
        assertThat(alice.buy("missing-product",9999,1).statusCode()).isEqualTo(404);
        assertEmptyAndStock(10);
    }

    /** 본문 변환이나 필드 검증에 실패한 구매 키를 정상 입력으로 다시 사용할 수 있는지 확인. */
    @Test void invalidOrderBodiesDoNotReservePurchaseKey() throws Exception {
        String validBody = payload(product, 1);
        var invalidBodies = List.of(
                "", "null", "[]", "{}",
                "{\"productId\":null,\"quantity\":1}",
                "{\"productId\":0,\"quantity\":1}",
                "{\"productId\":-1,\"quantity\":1}",
                "{\"productId\":\"1\",\"quantity\":1}",
                "{\"productId\":1.5,\"quantity\":1}",
                "{\"productId\":9223372036854775808,\"quantity\":1}",
                "{\"productId\":1,\"quantity\":null}",
                "{\"productId\":1,\"quantity\":true}",
                "{\"productId\":1,\"quantity\":[]}",
                "{\"productId\":1,\"quantity\":{}}",
                validBody + " {}",
                validBody.substring(0, validBody.length() - 1));

        for (String body : invalidBodies) {
            var response = alice.send("POST", "/orders", "corrected-purchase", body);
            assertThat(response.statusCode()).as(body).isEqualTo(400);
            assertThat(json(response).path("code").asText()).isEqualTo("INVALID_INPUT");
            assertEmptyAndStock(10);
        }

        assertThat(alice.buy("corrected-purchase", product, 1).statusCode()).isEqualTo(201);
        assertThat(count("order_requests")).isEqualTo(1);
        assertThat(available()).isEqualTo(9);
        observe("invalid-bodies-do-not-reserve-key", Map.of(
                "rejectedBodies", invalidBodies.size(), "correctedHttp", 201, "available", 9));
    }

    /** 키의 형식 오류와 같은 구매의 내용 충돌을 서로 다른 응답으로 유지. */
    @Test void purchaseKeyFormatAndLengthAreValidatedBeforeSaving() throws Exception {
        for (String key : List.of("", "bad key", "bad/key", "a".repeat(129))) {
            var response = alice.send("POST", "/orders", key, payload(product, 1));
            assertThat(response.statusCode()).as("key length=%s", key.length()).isEqualTo(400);
            assertThat(json(response).path("code").asText()).isEqualTo("INVALID_REQUEST_KEY");
            assertEmptyAndStock(10);
        }

        assertThat(alice.buy("a", product, 1).statusCode()).isEqualTo(201);
        assertThat(alice.buy("A0_-".repeat(32), product, 1).statusCode()).isEqualTo(201);
        assertThat(count("order_requests")).isEqualTo(2);
        assertThat(available()).isEqualTo(8);
        observe("purchase-key-format-and-length", Map.of(
                "invalidKeys", 4, "shortestAcceptedLength", 1, "longestAcceptedLength", 128));
    }

    /** 양의 정수 입력이라도 재고 확보 여부는 입력 검증이 아닌 DB의 재고 조건으로 판단. */
    @Test void positiveQuantityAndUnknownProductRemainBusinessDecisions() throws Exception {
        var shortage = alice.buy("large-positive-quantity", product, Integer.MAX_VALUE);
        assertThat(shortage.statusCode()).isEqualTo(409);
        assertThat(json(shortage).path("code").asText()).isEqualTo("OUT_OF_STOCK");
        assertThat(alice.buy("large-positive-quantity", product, Integer.MAX_VALUE).statusCode()).isEqualTo(409);

        var unknownProduct = alice.buy("unknown-positive-product", Long.MAX_VALUE, 1);
        assertThat(unknownProduct.statusCode()).isEqualTo(404);
        assertThat(count("order_requests")).isEqualTo(1);
        assertThat(count("orders")).isZero();
        assertThat(count("inventory_movements")).isZero();
        assertThat(available()).isEqualTo(10);
        observe("validation-and-stock-decision", Map.of(
                "positiveQuantityHttp", 409, "unknownProductHttp", 404, "available", 10));
    }

    /** 잘못된 취소 ID나 본문은 기존 접수 상태와 차감된 재고를 바꾸지 않음. */
    @Test void invalidCancellationInputsKeepOrderAndStock() throws Exception {
        long orderId = json(alice.buy("validated-cancellation", product, 2)).path("id").asLong();
        for (String invalidId : List.of("0", "-1", "word", "9223372036854775808")) {
            var response = alice.send("POST", "/orders/" + invalidId + "/cancel", null, null);
            assertThat(response.statusCode()).as(invalidId).isEqualTo(400);
            assertThat(json(response).path("code").asText()).isEqualTo("INVALID_INPUT");
        }
        for (String body : List.of(" ", "null", "{}", "{\"quantity\":999}")) {
            var response = alice.send("POST", "/orders/" + orderId + "/cancel", null, body);
            assertThat(response.statusCode()).as(body).isEqualTo(400);
            assertThat(json(response).path("code").asText()).isEqualTo("UNEXPECTED_BODY");
        }

        assertThat(json(alice.send("GET", "/orders/" + orderId, null, null)).path("status").asText())
                .isEqualTo("ACCEPTED");
        assertThat(available()).isEqualTo(8);
        assertThat(count("order_requests")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_movements WHERE kind='RESTORE'", Long.class))
                .isZero();
        assertThat(alice.cancel(orderId).statusCode()).isEqualTo(200);
        assertThat(available()).isEqualTo(10);
        observe("invalid-cancellation-inputs", Map.of(
                "rejectedRequests", 8, "beforeValidCancelAvailable", 8, "afterValidCancelAvailable", 10));
    }

    /** 컨트롤러를 나눠도 기존 상품 조회 경로와 응답 형식 유지. */
    @Test void productQueriesRemainAvailableAfterControllerSplit() throws Exception {
        var products = alice.send("GET", "/products", null, null);
        assertThat(products.statusCode()).isEqualTo(200);
        assertThat(json(products)).hasSize(2);

        var response = alice.send("GET", "/products/" + product, null, null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response).path("id").asLong()).isEqualTo(product);
        assertThat(json(response).path("availableQuantity").asInt()).isEqualTo(10);
        assertEmptyAndStock(10);
    }

    /** 응답 DTO로 분리한 뒤에도 기존 JSON 필드, 상태와 재전송 결과 유지. */
    @Test void responseDtosKeepExistingHttpFields() throws Exception {
        var created = json(alice.buy("dto-response-fields", product, 2));
        assertJsonFields(created, "id", "productId", "quantity", "status", "createdAt", "cancelledAt");
        assertThat(created.path("cancelledAt").isNull()).isTrue();
        long orderId = created.path("id").asLong();
        assertThat(json(alice.send("GET", "/orders/" + orderId, null, null))).isEqualTo(created);
        assertThat(json(alice.send("GET", "/orders", null, null)).get(0)).isEqualTo(created);

        var cancelled = json(alice.cancel(orderId));
        assertJsonFields(cancelled, "id", "productId", "quantity", "status", "createdAt", "cancelledAt");
        assertThat(cancelled.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(cancelled.path("cancelledAt").isNull()).isFalse();
        assertThat(json(alice.buy("dto-response-fields", product, 2))).isEqualTo(cancelled);

        var productResponse = json(alice.send("GET", "/products/" + product, null, null));
        assertJsonFields(productResponse, "id", "name", "availableQuantity");
        assertThat(productResponse.path("availableQuantity").asInt()).isEqualTo(10);
        var csrfResponse = json(alice.send("GET", "/csrf", null, null));
        assertJsonFields(csrfResponse, "headerName", "parameterName", "token");
        assertThat(csrfResponse.path("token").asText()).isNotBlank();
        var error = alice.send("POST", "/orders", "invalid key", payload(product, 1));
        assertThat(error.statusCode()).isEqualTo(400);
        assertJsonFields(json(error), "code");
        observe("dto-response-json-fields", Map.of(
                "orderFields", 6, "productFields", 3, "csrfFields", 3, "errorFields", 1, "available", 10));
    }

    private void assertJsonFields(JsonNode body, String... expectedFields) {
        var actualFields = new HashSet<String>();
        body.fieldNames().forEachRemaining(actualFields::add);
        assertThat(actualFields).containsExactlyInAnyOrder(expectedFields);
    }

    @Test void requestBodyLimitAppliesToChunkedAndFixedLength() throws Exception {
        String oversized=" ".repeat(9000)+payload(product,1);
        assertThat(alice.send("POST","/orders","large-fixed",oversized).statusCode()).isEqualTo(413);
        var publisher=HttpRequest.BodyPublishers.ofInputStream(()->new java.io.ByteArrayInputStream(oversized.getBytes(StandardCharsets.UTF_8)));
        var request=alice.request(port,"POST","/orders","large-chunked",publisher);
        assertThat(alice.http.send(request,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(413);
        assertEmptyAndStock(10);
    }

    /** 100-continue를 기다리는 클라이언트에 본문 전송 없이 큰 요청을 거절. */
    @Test void knownOversizedExpectContinueRequestDoesNotStartBodyTransmission() throws Exception {
        byte[] bytes = (payload(product, 1) + " ".repeat(8193)).getBytes(StandardCharsets.UTF_8);
        var delegate = HttpRequest.BodyPublishers.ofByteArray(bytes);
        var subscriptions = new AtomicInteger();
        var publisher = new HttpRequest.BodyPublisher() {
            @Override public long contentLength() { return delegate.contentLength(); }
            @Override public void subscribe(java.util.concurrent.Flow.Subscriber<? super java.nio.ByteBuffer> subscriber) {
                subscriptions.incrementAndGet();
                delegate.subscribe(subscriber);
            }
        };
        var request = HttpRequest.newBuilder(uri(port, "/orders")).timeout(Duration.ofSeconds(10))
                .header("Authorization", alice.authorization).header(alice.csrfHeader, alice.csrf)
                .header("Content-Type", "application/json").header("Idempotency-Key", "expect-large")
                .expectContinue(true).POST(publisher).build();

        var response = alice.http.send(request, HttpResponse.BodyHandlers.ofString());

        observe("expect-continue-body-precheck", Map.of("declaredBytes", bytes.length,
                "http", response.statusCode(), "bodySubscriptions", subscriptions.get(),
                "orders", count("orders"), "available", available()));
        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(subscriptions.get()).isZero();
        assertEmptyAndStock(10);
    }

    /** 한도와 같은 8192바이트의 유효한 JSON은 고정 길이와 chunked 모두 접수. */
    @Test void exactBodyLimitAcceptsFixedAndUnknownLengthRequests() throws Exception {
        String base = payload(product, 1);
        byte[] bytes = (base + " ".repeat(8192 - base.getBytes(StandardCharsets.UTF_8).length))
                .getBytes(StandardCharsets.UTF_8);
        assertThat(bytes).hasSize(8192);
        var fixed = alice.http.send(alice.request(port, "POST", "/orders", "exact-fixed",
                HttpRequest.BodyPublishers.ofByteArray(bytes)), HttpResponse.BodyHandlers.ofString());
        var unknown = alice.http.send(alice.request(port, "POST", "/orders", "exact-chunked",
                HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream(bytes))),
                HttpResponse.BodyHandlers.ofString());

        observe("exact-body-limit", Map.of("bytes", bytes.length, "fixedHttp", fixed.statusCode(),
                "unknownLengthHttp", unknown.statusCode(), "orders", count("orders"), "available", available()));
        assertThat(fixed.statusCode()).isEqualTo(201);
        assertThat(unknown.statusCode()).isEqualTo(201);
        assertThat(json(fixed).path("id").asLong()).isNotEqualTo(json(unknown).path("id").asLong());
        assertThat(count("orders")).isEqualTo(2);
        assertThat(available()).isEqualTo(8);
    }

    /** 허용한 크기의 Expect 요청은 100 Continue 후 정상 전송과 접수 유지. */
    @Test void allowedExpectContinueRequestStillTransmitsBodyAndCreatesOrder() throws Exception {
        byte[] bytes = payload(product, 1).getBytes(StandardCharsets.UTF_8);
        var delegate = HttpRequest.BodyPublishers.ofByteArray(bytes);
        var subscriptions = new AtomicInteger();
        var publisher = new HttpRequest.BodyPublisher() {
            @Override public long contentLength() { return delegate.contentLength(); }
            @Override public void subscribe(java.util.concurrent.Flow.Subscriber<? super java.nio.ByteBuffer> subscriber) {
                subscriptions.incrementAndGet();
                delegate.subscribe(subscriber);
            }
        };
        var request = HttpRequest.newBuilder(uri(port, "/orders")).timeout(Duration.ofSeconds(10))
                .header("Authorization", alice.authorization).header(alice.csrfHeader, alice.csrf)
                .header("Content-Type", "application/json").header("Idempotency-Key", "expect-valid")
                .expectContinue(true).POST(publisher).build();

        var response = alice.http.send(request, HttpResponse.BodyHandlers.ofString());

        observe("expect-continue-allowed", Map.of("declaredBytes", bytes.length, "http", response.statusCode(),
                "bodySubscriptions", subscriptions.get(), "orders", count("orders"), "available", available()));
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(subscriptions.get()).isEqualTo(1);
        assertThat(count("orders")).isEqualTo(1);
        assertThat(available()).isEqualTo(9);
    }

    /** MVC가 같은 주문 경로로 읽는 인코딩 URL에도 본문 제한을 동일하게 적용. */
    @Test void encodedOrderPathCannotBypassRequestBodyLimit() throws Exception {
        String oversized = " ".repeat(9000) + payload(product, 1);
        var response = alice.send("POST", "/%6frders", "encoded-large-fixed", oversized);
        observe("encoded-order-path-body-limit", Map.of("http", response.statusCode(),
                "orders", count("orders"), "available", available()));
        assertThat(response.statusCode()).isEqualTo(413);

        var publisher = HttpRequest.BodyPublishers.ofInputStream(
                () -> new java.io.ByteArrayInputStream(oversized.getBytes(StandardCharsets.UTF_8)));
        var request = alice.request(port, "POST", "/%6frders", "encoded-large-chunked", publisher);
        assertThat(alice.http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(413);
        assertEmptyAndStock(10);
        assertThat(alice.send("POST", "/%6frders", "encoded-large-fixed", payload(product, 1)).statusCode())
                .isEqualTo(201);
    }

    /** 중복 JSON 필드는 마지막 값으로 덮어 읽지 않고 저장 전에 거절. */
    @Test void duplicateOrderFieldsDoNotChooseOneValueOrReserveKey() throws Exception {
        var duplicateBodies = List.of(
                "{\"productId\":" + product + ",\"quantity\":1,\"quantity\":2}",
                "{\"productId\":" + product + ",\"quantity\":1,\"quantity\":1}",
                "{\"productId\":" + product + ",\"productId\":" + other + ",\"quantity\":1}");
        for (String body : duplicateBodies) {
            var response = alice.send("POST", "/orders", "duplicate-field-purchase", body);
            observe("duplicate-order-field", Map.of("http", response.statusCode(),
                    "orders", count("orders"), "available", available()));
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(json(response).path("code").asText()).isEqualTo("INVALID_INPUT");
            assertEmptyAndStock(10);
        }
        assertThat(alice.buy("duplicate-field-purchase", product, 1).statusCode()).isEqualTo(201);
        assertThat(available()).isEqualTo(9);
    }

    @Test void formCsrfCannotBypassBodySizeLimit() throws Exception {
        long id=json(alice.buy("form-body-owner",product,1)).path("id").asLong();
        String form="_csrf="+URLEncoder.encode(alice.csrf,StandardCharsets.UTF_8)+"&padding="+"x".repeat(9000);
        var request=HttpRequest.newBuilder(uri(port,"/orders/"+id+"/cancel"))
                .header("Authorization",alice.authorization).header("Content-Type","application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build();
        var response=alice.http.send(request,HttpResponse.BodyHandlers.ofString());
        observe("form-csrf-body-size-guard",Map.of("http",response.statusCode(),"available",available()));
        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(available()).isEqualTo(9);
    }

    @Test void nonemptyFormBodyWithHeaderTokenIsRejected() throws Exception {
        long id=json(alice.buy("form-with-header",product,1)).path("id").asLong();
        var request=HttpRequest.newBuilder(uri(port,"/orders/"+id+"/cancel"))
                .header("Authorization",alice.authorization).header(alice.csrfHeader,alice.csrf)
                .header("Content-Type","application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("quantity=999")).build();
        var response=alice.http.send(request,HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json(response).path("code").asText()).isEqualTo("UNEXPECTED_BODY");
        assertThat(available()).isEqualTo(9);
        observe("nonempty-form-with-csrf-header",Map.of("http",400,"available",9));
    }

    @Test void formTokenDoesNotReplaceRequiredHeaderToken() throws Exception {
        long id=json(alice.buy("header-token-required",product,1)).path("id").asLong();
        String form="_csrf="+URLEncoder.encode(alice.csrf,StandardCharsets.UTF_8);
        var request=HttpRequest.newBuilder(uri(port,"/orders/"+id+"/cancel"))
                .header("Authorization",alice.authorization).header("Content-Type","application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build();
        var response=alice.http.send(request,HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(available()).isEqualTo(9);
    }

    @Test void mvcValidatesPathAndQueryConstraintsWithoutControllerProxy() throws Exception {
        for (String path:List.of("/products/0","/orders/0","/orders?limit=0","/orders?limit=101",
                "/orders?beforeId=0","/orders?limit=word")) {
            var response=alice.send("GET",path,null,null);
            assertThat(response.statusCode()).as(path).isEqualTo(400);
            assertThat(json(response).path("code").asText()).isEqualTo("INVALID_INPUT");
        }
        assertThat(alice.cancel(0).statusCode()).isEqualTo(400);
        assertEmptyAndStock(10);
        observe("mvc-path-and-query-validation",Map.of("invalidRequests",7,"http",400,"available",10));
    }

    @Test void unauthenticatedAndCsrfMissingRequestsAreRejected() throws Exception {
        var bare=HttpClient.newHttpClient();
        assertThat(bare.send(HttpRequest.newBuilder(uri(port,"/products")).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        var request=HttpRequest.newBuilder(uri(port,"/orders")).header("Authorization",alice.authorization)
                .header("Content-Type","application/json").header("Idempotency-Key","without-csrf")
                .POST(HttpRequest.BodyPublishers.ofString(payload(product,1))).build();
        assertThat(bare.send(request,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
        assertEmptyAndStock(10);
    }

    @Test void routingMethodAndMediaTypeErrorsRemainClientErrors() throws Exception {
        assertThat(alice.send("GET","/unknown-route",null,null).statusCode()).isEqualTo(404);
        assertThat(alice.send("PUT","/products",null,"{}").statusCode()).isEqualTo(405);
        var request=HttpRequest.newBuilder(uri(port,"/orders")).header("Authorization",alice.authorization)
                .header(alice.csrfHeader,alice.csrf).header("Content-Type","text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(payload(product,1))).build();
        assertThat(alice.http.send(request,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(415);
        assertEmptyAndStock(10);
    }

    @Test void cancellationUsesStoredQuantity() throws Exception {
        long id=json(alice.buy("quantity-owner",product,2)).path("id").asLong();
        assertThat(alice.send("POST","/orders/"+id+"/cancel",null,"{\"quantity\":999}").statusCode()).isEqualTo(400);
        assertThat(available()).isEqualTo(8);
        assertThat(alice.cancel(id).statusCode()).isEqualTo(200);
        assertThat(available()).isEqualTo(10);
    }

    @Test void emptyCancellationAcceptsMissingOrFormContentType() throws Exception {
        long id=json(alice.buy("empty-cancellation",product,2)).path("id").asLong();
        for(String type:List.of("", "application/x-www-form-urlencoded")) {
            var request=HttpRequest.newBuilder(uri(port,"/orders/"+id+"/cancel")).header("Authorization",alice.authorization)
                    .header(alice.csrfHeader,alice.csrf);
            if(!type.isEmpty()) request.header("Content-Type",type);
            assertThat(alice.http.send(request.POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        }
        assertThat(available()).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_movements WHERE kind='RESTORE'",Long.class)).isEqualTo(1);
    }

    @Test void commitBeforeLostResponseIsReplayedWithoutAnotherDebit() throws Exception {
        try(var proxy=new DropResponseProxy()) {
            var request=alice.request(proxy.port(),"POST","/orders","lost-response",HttpRequest.BodyPublishers.ofString(payload(product,2)));
            assertThatThrownBy(()->alice.http.send(request,HttpResponse.BodyHandlers.ofString())).isInstanceOf(IOException.class);
            assertThat(proxy.forwarded.get()).isEqualTo(1);
            assertThat(proxy.status).isEqualTo(201);
        }
        assertThat(available()).isEqualTo(8);
        var replay=alice.buy("lost-response",product,2);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(count("orders")).isEqualTo(1);
        assertThat(available()).isEqualTo(8);
        observe("commit-then-drop-http-response",Map.of("replayHttp",200,"orders",1,"available",8));
    }

    @Test void lostCancellationResponseRestoresOnce() throws Exception {
        long id=json(alice.buy("lost-cancel",product,2)).path("id").asLong();
        try(var proxy=new DropResponseProxy()) {
            var request=alice.request(proxy.port(),"POST","/orders/"+id+"/cancel",null,HttpRequest.BodyPublishers.noBody());
            assertThatThrownBy(()->alice.http.send(request,HttpResponse.BodyHandlers.ofString())).isInstanceOf(IOException.class);
            assertThat(proxy.forwarded.get()).isEqualTo(1);
            assertThat(proxy.status).isEqualTo(200);
        }
        assertThat(alice.cancel(id).statusCode()).isEqualTo(200);
        assertThat(available()).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_movements WHERE kind='RESTORE'",Long.class)).isEqualTo(1);
    }

    @Test void randomizedParallelOrdersAndCancellationsRemainConsistent() throws Exception {
        var executor=Executors.newFixedThreadPool(8);
        try {
            var operations=new ArrayList<Callable<Void>>();
            for(int i=0;i<40;i++) {
                final int index=i;
                operations.add(()->{
                    var client=(index%2==0?alice:bob);
                    var response=client.buy("mixed-"+index,product,1);
                    assertThat(response.statusCode()).isIn(201,409);
                    if(response.statusCode()==201 && index%3!=0) assertThat(client.cancel(json(response).path("id").asLong()).statusCode()).isEqualTo(200);
                    return null;
                });
            }
            for(var result:executor.invokeAll(operations)) result.get();
        } finally { executor.shutdownNow(); assertThat(executor.awaitTermination(10,TimeUnit.SECONDS)).isTrue(); }
        observe("mixed-parallel-operations",Map.of("buyingActions",40,"orders",count("orders"),"available",available()));
    }

    private List<HttpResponse<String>> overlap(List<Callable<HttpResponse<String>>> operations) throws Exception {
        var executor=Executors.newFixedThreadPool(operations.size());
        var ready=new CountDownLatch(operations.size()); var start=new CountDownLatch(1);
        try(var blocker=connection();var observer=connection()) {
            lockInventory(blocker);
            var futures=new ArrayList<Future<HttpResponse<String>>>();
            for(var operation:operations) futures.add(executor.submit(()->{
                ready.countDown(); if(!start.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("Barrier timeout");
                return operation.call();
            }));
            try {
                assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue(); start.countDown();
                await().atMost(Duration.ofSeconds(2)).pollInterval(Duration.ofMillis(15)).until(()->waiters(observer)>=2);
                int waiters=waiters(observer); blocker.commit();
                var results=new ArrayList<HttpResponse<String>>();
                for(var future:futures) results.add(future.get(25,TimeUnit.SECONDS));
                observe("overlap-barrier",Map.of("clients",operations.size(),"observedPostgresLockWaiters",waiters));
                return results;
            } finally { blocker.rollback(); start.countDown(); }
        } finally { executor.shutdownNow(); assertThat(executor.awaitTermination(20,TimeUnit.SECONDS)).isTrue(); }
    }

    private void lockInventory(Connection connection) throws SQLException {
        connection.setAutoCommit(false);
        try(var statement=connection.prepareStatement("SELECT product_id FROM inventories WHERE product_id=? FOR UPDATE")) {
            statement.setLong(1,product); statement.executeQuery().close();
        }
    }
    private int waiters(Connection observer) throws SQLException {
        try(var statement=observer.createStatement();var rows=statement.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'")) {
            rows.next(); return rows.getInt(1);
        }
    }
    private Connection connection() throws SQLException {
        return DriverManager.getConnection(System.getenv("STOCK_DB_URL"),System.getenv("STOCK_DB_USER"),System.getenv("STOCK_DB_PASSWORD"));
    }
    private long addProduct(String name,int stock) {
        long id=jdbc.queryForObject("INSERT INTO products(name) VALUES (?) RETURNING id",Long.class,name);
        jdbc.update("INSERT INTO inventories VALUES (?,?,?)",id,stock,stock); return id;
    }
    private void stock(int amount) { jdbc.update("UPDATE inventories SET initial_quantity=?,available_quantity=? WHERE product_id=?",amount,amount,product); }
    private int available() { return jdbc.queryForObject("SELECT available_quantity FROM inventories WHERE product_id=?",Integer.class,product); }
    private long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class); }
    private void assertEmptyAndStock(int stock) { assertThat(count("orders")).isZero(); assertThat(count("order_requests")).isZero(); assertThat(count("inventory_movements")).isZero(); assertThat(available()).isEqualTo(stock); }
    private JsonNode json(HttpResponse<String> response) { try { return mapper.readTree(response.body()); } catch(Exception e) { throw new AssertionError(response.statusCode()+": "+response.body(),e); } }
    private String payload(long id,int quantity) { return "{\"productId\":"+id+",\"quantity\":"+quantity+"}"; }
    private URI uri(int serverPort,String path) { return URI.create("http://127.0.0.1:"+serverPort+path); }
    private void observe(String scenario,Map<String,?> details) { evidence.add(Map.of("scenario",scenario,"observations",details)); }

    private void fail(String table,String event,String condition) {
        jdbc.execute("CREATE FUNCTION test_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Injected test storage fault' USING ERRCODE='23514'; END $$");
        jdbc.execute("CREATE TRIGGER injected_fault "+event+" ON "+table+" FOR EACH ROW "+(condition==null?"":"WHEN ("+condition+") ")+"EXECUTE FUNCTION test_fail()");
    }
    private void removeFault() { jdbc.execute("DROP FUNCTION IF EXISTS test_fail() CASCADE"); }
    private void invariants() {
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM inventories i WHERE available_quantity<0 OR available_quantity>initial_quantity
                OR available_quantity<>initial_quantity-COALESCE((SELECT sum(quantity) FROM orders o WHERE o.product_id=i.product_id AND o.status='ACCEPTED'),0)
                OR available_quantity<>initial_quantity+COALESCE((SELECT sum(quantity_delta) FROM inventory_movements m WHERE m.product_id=i.product_id),0)
                """,Long.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM order_requests r WHERE outcome IS NULL
                OR (outcome='ACCEPTED' AND (SELECT count(*) FROM orders o WHERE o.request_id=r.id)<>1)
                OR (outcome='OUT_OF_STOCK' AND EXISTS(SELECT 1 FROM orders o WHERE o.request_id=r.id))
                """,Long.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM orders o JOIN order_requests r ON r.id=o.request_id
                WHERE o.buyer_id<>r.buyer_id OR o.product_id<>r.product_id OR o.quantity<>r.quantity
                OR (SELECT count(*) FROM inventory_movements m WHERE m.order_id=o.id AND m.kind='DEBIT')<>1
                OR (SELECT count(*) FROM inventory_movements m WHERE m.order_id=o.id AND m.kind='RESTORE')<>(CASE WHEN o.status='CANCELLED' THEN 1 ELSE 0 END)
                """,Long.class)).isZero();
    }

    private final class Client {
        final String authorization;
        final HttpClient http;
        final String csrf;
        final String csrfHeader;
        Client(String username,String password) throws Exception {
            authorization="Basic "+Base64.getEncoder().encodeToString((username+":"+password).getBytes(StandardCharsets.UTF_8));
            http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(5)).build();
            var response=http.send(HttpRequest.newBuilder(uri(port,"/csrf")).header("Authorization",authorization).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            csrf=json(response).path("token").asText(); csrfHeader=json(response).path("headerName").asText();
        }
        HttpRequest request(int destination,String method,String path,String key,HttpRequest.BodyPublisher body) {
            var builder=HttpRequest.newBuilder(uri(destination,path)).timeout(Duration.ofSeconds(25)).header("Authorization",authorization)
                    .header(csrfHeader,csrf).header("Content-Type","application/json");
            if(key!=null) builder.header("Idempotency-Key",key);
            return builder.method(method,body).build();
        }
        HttpResponse<String> send(String method,String path,String key,String body) throws Exception {
            return http.send(request(port,method,path,key,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)),HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> buy(String key,long id,int quantity) throws Exception { return send("POST","/orders",key,payload(id,quantity)); }
        HttpResponse<String> cancel(long id) throws Exception { return send("POST","/orders/"+id+"/cancel",null,null); }
    }

    private final class DropResponseProxy implements AutoCloseable {
        final HttpServer proxy=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        final ExecutorService executor=Executors.newCachedThreadPool();
        final AtomicInteger forwarded=new AtomicInteger();
        volatile int status;
        DropResponseProxy() throws IOException {
            proxy.setExecutor(executor);
            proxy.createContext("/",exchange->{
                try {
                    var builder=HttpRequest.newBuilder(uri(port,exchange.getRequestURI().toString())).timeout(Duration.ofSeconds(15));
                    for(String header:List.of("Authorization","Cookie","X-CSRF-TOKEN","Content-Type","Idempotency-Key")) {
                        var value=exchange.getRequestHeaders().getFirst(header); if(value!=null) builder.header(header,value);
                    }
                    var body=exchange.getRequestBody().readAllBytes();
                    var response=HttpClient.newHttpClient().send(builder.method(exchange.getRequestMethod(),HttpRequest.BodyPublishers.ofByteArray(body)).build(),HttpResponse.BodyHandlers.ofString());
                    status=response.statusCode(); forwarded.incrementAndGet();
                } catch(InterruptedException error) { Thread.currentThread().interrupt(); }
                finally { exchange.close(); }
            });
            proxy.start();
        }
        int port() { return proxy.getAddress().getPort(); }
        public void close() { proxy.stop(0); executor.shutdownNow(); }
    }
}

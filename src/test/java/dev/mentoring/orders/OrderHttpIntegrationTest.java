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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "stock.admission.per-customer-per-minute=100000", "spring.main.banner-mode=off"
})
@DirtiesContext
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

    @RepeatedTest(3) void lastStockTwoCustomers(RepetitionInfo repetition) throws Exception {
        stock(1);
        var responses = overlap(List.of(() -> alice.buy("last-a", product, 1), () -> bob.buy("last-b", product, 1)));
        assertThat(responses.stream().map(HttpResponse::statusCode)).containsExactlyInAnyOrder(201,409);
        assertThat(count("orders")).isEqualTo(1);
        assertThat(count("order_requests")).isEqualTo(2);
        assertThat(available()).isZero();
        observe("last-stock-" + repetition.getCurrentRepetition(), Map.of("requests",2,"accepted",1,"outOfStock",1,"available",0));
    }

    @RepeatedTest(3) void twentyRetriesOnePurchase(RepetitionInfo repetition) throws Exception {
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

    @RepeatedTest(3) void twentyCancellationsRestoreOnce(RepetitionInfo repetition) throws Exception {
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

    @Test void requestBodyLimitAppliesToChunkedAndFixedLength() throws Exception {
        String oversized=" ".repeat(9000)+payload(product,1);
        assertThat(alice.send("POST","/orders","large-fixed",oversized).statusCode()).isEqualTo(413);
        var publisher=HttpRequest.BodyPublishers.ofInputStream(()->new java.io.ByteArrayInputStream(oversized.getBytes(StandardCharsets.UTF_8)));
        var request=alice.request(port,"POST","/orders","large-chunked",publisher);
        assertThat(alice.http.send(request,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(413);
        assertEmptyAndStock(10);
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

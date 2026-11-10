package dev.mentoring.orders.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.mentoring.orders.OrdersApplication;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;

/** 두 JVM과 DB 중단 검사에서만 사용하는 서버 실행, HTTP 요청과 DB 대조 도구. */
public final class ReviewFixture {
    public static final ObjectMapper JSON = new ObjectMapper();
    private ReviewFixture() { }

    public static Connection connection() throws SQLException {
        return DriverManager.getConnection(System.getenv("STOCK_DB_URL"), System.getenv("STOCK_DB_USER"), System.getenv("STOCK_DB_PASSWORD"));
    }

    public static void migrate() {
        String url = System.getenv("STOCK_DB_URL");
        if (url == null || !url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/stock_order_test")) {
            throw new IllegalStateException("Dedicated test database required");
        }
        Flyway.configure().dataSource(url, System.getenv("STOCK_DB_USER"), System.getenv("STOCK_DB_PASSWORD")).load().migrate();
    }

    public static void sql(String statement) throws SQLException {
        try (var connection = connection(); var query = connection.createStatement()) { query.execute(statement); }
    }

    public static long number(String statement) throws SQLException {
        try (var connection = connection(); var query = connection.createStatement(); var rows = query.executeQuery(statement)) {
            rows.next(); return rows.getLong(1);
        }
    }

    public static void reset(int stock) throws SQLException {
        sql("DROP FUNCTION IF EXISTS review_pause() CASCADE");
        sql("TRUNCATE inventory_movements, orders, order_requests, inventories, products RESTART IDENTITY CASCADE");
        sql("INSERT INTO products(name) VALUES ('Review product')");
        sql("INSERT INTO inventories VALUES (1," + stock + "," + stock + ")");
    }

    /** 응답과 별개로 저장된 수량, 주문, 구매 결과와 이력의 일치 여부 조회. */
    public static Map<String, Object> snapshot() throws SQLException {
        long inventoryViolations = number("""
                SELECT count(*) FROM inventories i WHERE available_quantity < 0 OR available_quantity > initial_quantity
                OR available_quantity <> initial_quantity - COALESCE((SELECT sum(quantity) FROM orders o WHERE o.product_id=i.product_id AND o.status='ACCEPTED'),0)
                OR available_quantity <> initial_quantity + COALESCE((SELECT sum(quantity_delta) FROM inventory_movements m WHERE m.product_id=i.product_id),0)
                """);
        long requestViolations = number("""
                SELECT count(*) FROM order_requests r WHERE outcome IS NULL
                OR (outcome='ACCEPTED' AND (SELECT count(*) FROM orders o WHERE o.request_id=r.id)<>1)
                OR (outcome='OUT_OF_STOCK' AND EXISTS(SELECT 1 FROM orders o WHERE o.request_id=r.id))
                """);
        long orderViolations = number("""
                SELECT count(*) FROM orders o JOIN order_requests r ON r.id=o.request_id
                WHERE o.buyer_id<>r.buyer_id OR o.product_id<>r.product_id OR o.quantity<>r.quantity
                OR (SELECT count(*) FROM inventory_movements m WHERE m.order_id=o.id AND m.kind='DEBIT')<>1
                OR (SELECT count(*) FROM inventory_movements m WHERE m.order_id=o.id AND m.kind='RESTORE')<>(CASE WHEN o.status='CANCELLED' THEN 1 ELSE 0 END)
                OR EXISTS(SELECT 1 FROM inventory_movements m WHERE m.order_id=o.id AND (m.product_id<>o.product_id OR m.quantity_delta<>(CASE WHEN m.kind='DEBIT' THEN -o.quantity ELSE o.quantity END)))
                """);
        var result = new LinkedHashMap<String, Object>();
        result.put("inventoryViolations", inventoryViolations);
        result.put("requestViolations", requestViolations);
        result.put("orderViolations", orderViolations);
        result.put("orders", number("SELECT count(*) FROM orders"));
        result.put("requests", number("SELECT count(*) FROM order_requests"));
        result.put("available", number("SELECT sum(available_quantity) FROM inventories"));
        result.put("restorations", number("SELECT count(*) FROM inventory_movements WHERE kind='RESTORE'"));
        assertThat(inventoryViolations + requestViolations + orderViolations).isZero();
        return result;
    }

    public static void save(String name, Object events) throws Exception {
        JSON.writerWithDefaultPrettyPrinter().writeValue(Path.of(System.getenv("STOCK_EVIDENCE_DIR"), name).toFile(), events);
    }

    public static JsonNode body(HttpResponse<String> response) throws Exception { return JSON.readTree(response.body()); }

    /** 시작한 JVM만 종료. 애플리케이션 설정과 로그인 경로는 실제 서버를 사용. */
    public static final class Server implements AutoCloseable {
        private final Process process;
        public final int port;
        public Server(String name) throws Exception {
            try (var socket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) { port = socket.getLocalPort(); }
            String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
            String url = System.getenv("STOCK_DB_URL") + "?ApplicationName=" + name;
            process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                    "-cp", System.getProperty("stock.test.classpath"), OrdersApplication.class.getName(),
                    "--server.port=" + port, "--spring.profiles.active=demo", "--spring.main.banner-mode=off",
                    "--spring.datasource.url=" + url, "--stock.admission.per-customer-per-minute=100000",
                    "--spring.datasource.hikari.maximum-pool-size=4")
                    .redirectErrorStream(true).redirectOutput(Path.of(System.getenv("STOCK_EVIDENCE_DIR"), name + ".log").toFile()).start();
            try {
                await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200)).until(() -> {
                    if (!process.isAlive()) throw new IllegalStateException("Owned server exited: " + name);
                    try (var client = new Client(port, "alice", false)) { return client.refreshCsrf() == 200; }
                    catch (java.io.IOException error) { return false; }
                });
            } catch (Throwable failure) { close(); throw failure; }
        }
        public boolean alive() { return process.isAlive(); }
        public long pid() { return process.pid(); }
        public void close() throws Exception {
            if (process.isAlive()) {
                process.destroy();
                if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue(); }
            }
        }
    }

    /** 고객마다 별도 쿠키와 CSRF 토큰 유지. 구매 키는 호출자가 지정. */
    public static final class Client implements AutoCloseable {
        public final int port;
        private final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .connectTimeout(Duration.ofSeconds(2)).build();
        private final String authorization;
        private String csrf = "";
        private String csrfHeader = "X-CSRF-TOKEN";
        public Client(int port, String user) throws Exception { this(port, user, true); }
        private Client(int port, String user, boolean initialize) throws Exception {
            this.port = port;
            authorization = "Basic " + Base64.getEncoder().encodeToString((user + ":" + System.getenv("STOCK_" + user.toUpperCase(Locale.ROOT) + "_PASSWORD")).getBytes(StandardCharsets.UTF_8));
            if (initialize) assertThat(refreshCsrf()).isEqualTo(200);
        }
        public int refreshCsrf() throws Exception {
            var response = send("GET", "/csrf", null, null);
            if (response.statusCode() == 200) { csrf = body(response).path("token").asText(); csrfHeader = body(response).path("headerName").asText(); }
            return response.statusCode();
        }
        public HttpResponse<String> send(String method, String path, String key, String data) throws Exception {
            return sendTo(port, method, path, key, data);
        }
        public HttpResponse<String> sendTo(int destination, String method, String path, String key, String data) throws Exception {
            var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + destination + path)).timeout(Duration.ofSeconds(15))
                    .header("Authorization", authorization).header(csrfHeader, csrf).header("Content-Type", "application/json");
            if (key != null) builder.header("Idempotency-Key", key);
            return client.send(builder.method(method, data == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(data)).build(), HttpResponse.BodyHandlers.ofString());
        }
        public HttpResponse<String> buy(String key) throws Exception { return send("POST", "/orders", key, "{\"productId\":1,\"quantity\":1}"); }
        public HttpResponse<String> cancel(long id) throws Exception { return send("POST", "/orders/" + id + "/cancel", null, null); }
        public void close() { client.close(); }
    }

    /** 서버의 실제 응답을 확인한 뒤 클라이언트에 보내지 않는 검사용 프록시. */
    public static final class LostResponse implements AutoCloseable {
        private final HttpServer proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        private final ExecutorService executor = Executors.newCachedThreadPool();
        public volatile HttpResponse<String> upstream;
        public LostResponse(int destination) throws Exception {
            proxy.setExecutor(executor);
            proxy.createContext("/", exchange -> {
                try (var forward = HttpClient.newHttpClient()) {
                    var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + destination + exchange.getRequestURI())).timeout(Duration.ofSeconds(15));
                    for (String name : List.of("Authorization", "Cookie", "X-CSRF-TOKEN", "Content-Type", "Idempotency-Key")) {
                        String value = exchange.getRequestHeaders().getFirst(name); if (value != null) builder.header(name, value);
                    }
                    upstream = forward.send(builder.method(exchange.getRequestMethod(), HttpRequest.BodyPublishers.ofByteArray(exchange.getRequestBody().readAllBytes())).build(), HttpResponse.BodyHandlers.ofString());
                } catch (Exception error) { throw new java.io.IOException(error); }
                finally { exchange.close(); }
            });
            proxy.start();
        }
        public int port() { return proxy.getAddress().getPort(); }
        public void close() { proxy.stop(0); executor.shutdownNow(); }
    }
}

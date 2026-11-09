package dev.mentoring.orders;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class OrderProcessRecoveryTest {
    private final ObjectMapper json=new ObjectMapper();
    private final List<Map<String,Object>> events=new ArrayList<>();

    @Test @Timeout(180)
    void separateJvmRollbackAndCommittedReplayForPurchaseAndCancellation() throws Exception {
        String url=System.getenv("STOCK_DB_URL");
        if(url==null || !url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/stock_order_test")) throw new IllegalStateException("Dedicated test database required");
        Flyway.configure().dataSource(url,System.getenv("STOCK_DB_USER"),System.getenv("STOCK_DB_PASSWORD")).load().migrate();
        try(var connection=connection();var sql=connection.createStatement()) {
            sql.execute("DROP FUNCTION IF EXISTS test_fail() CASCADE");
            sql.execute("TRUNCATE inventory_movements,orders,order_requests,inventories,products RESTART IDENTITY CASCADE");
            sql.execute("INSERT INTO products(name) VALUES ('Process recovery product')");
            sql.execute("INSERT INTO inventories VALUES (1,10,10)");
        }
        try(var server=new ChildServer();var executor=Executors.newSingleThreadExecutor()) {
            server.start();
            try(var blocker=connection()) {
                blockStock(blocker);
                var request=executor.submit(()->server.send("POST","/orders","process-purchase","{\"productId\":1,\"quantity\":2}"));
                waitForBlockedWrite(); server.kill();
                assertThatThrownBy(()->request.get(10,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
                blocker.rollback();
            }
            assertThat(number("SELECT count(*) FROM orders")).isZero();
            assertThat(number("SELECT count(*) FROM order_requests")).isZero();
            assertThat(stock()).isEqualTo(10);
            events.add(Map.of("stage","purchase-killed-before-commit","orders",0,"available",10));

            server.start();
            var accepted=server.send("POST","/orders","process-purchase","{\"productId\":1,\"quantity\":2}");
            assertThat(accepted.statusCode()).isEqualTo(201);
            long id=json.readTree(accepted.body()).path("id").asLong();
            server.kill(); server.start();
            var replay=server.send("POST","/orders","process-purchase","{\"productId\":1,\"quantity\":2}");
            assertThat(replay.statusCode()).isEqualTo(200);
            assertThat(json.readTree(replay.body()).path("id").asLong()).isEqualTo(id);
            assertThat(stock()).isEqualTo(8);
            events.add(Map.of("stage","purchase-restarted-after-commit","orders",1,"available",8,"replayHttp",200));

            try(var blocker=connection()) {
                blockStock(blocker);
                var cancel=executor.submit(()->server.send("POST","/orders/"+id+"/cancel",null,null));
                waitForBlockedWrite(); server.kill();
                assertThatThrownBy(()->cancel.get(10,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
                blocker.rollback();
            }
            assertThat(number("SELECT count(*) FROM orders WHERE status='ACCEPTED'")).isEqualTo(1);
            assertThat(stock()).isEqualTo(8);
            events.add(Map.of("stage","cancel-killed-before-commit","acceptedOrders",1,"available",8));

            server.start();
            assertThat(server.send("POST","/orders/"+id+"/cancel",null,null).statusCode()).isEqualTo(200);
            assertThat(stock()).isEqualTo(10);
            server.kill(); server.start();
            assertThat(server.send("POST","/orders/"+id+"/cancel",null,null).statusCode()).isEqualTo(200);
            assertThat(stock()).isEqualTo(10);
            assertThat(number("SELECT count(*) FROM inventory_movements WHERE kind='RESTORE'")).isEqualTo(1);
            events.add(Map.of("stage","cancel-restarted-after-commit","restorations",1,"available",10));
        }
        var directory=Path.of(System.getenv("STOCK_EVIDENCE_DIR"));
        json.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("process-recovery.json").toFile(),events);
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(System.getenv("STOCK_DB_URL"),System.getenv("STOCK_DB_USER"),System.getenv("STOCK_DB_PASSWORD"));
    }
    private long number(String query) throws SQLException {
        try(var c=connection();var s=c.createStatement();var rows=s.executeQuery(query)) { rows.next();return rows.getLong(1); }
    }
    private long stock() throws SQLException { return number("SELECT available_quantity FROM inventories WHERE product_id=1"); }
    private void blockStock(Connection connection) throws SQLException {
        connection.setAutoCommit(false);
        try(var sql=connection.createStatement()) { sql.executeQuery("SELECT product_id FROM inventories WHERE product_id=1 FOR UPDATE").close(); }
    }
    private void waitForBlockedWrite() {
        await().atMost(Duration.ofSeconds(2)).pollInterval(Duration.ofMillis(20)).until(()->number("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'")>=1);
    }

    private final class ChildServer implements AutoCloseable {
        private Process process;
        private int port;
        private HttpClient client;
        private String csrf;
        private String header;
        private final String authorization="Basic "+Base64.getEncoder().encodeToString(("alice:"+System.getenv("STOCK_ALICE_PASSWORD")).getBytes(StandardCharsets.UTF_8));
        void start() throws Exception {
            if(process!=null && process.isAlive()) throw new IllegalStateException("Owned server already running");
            try(var socket=new ServerSocket(0,0,InetAddress.getByName("127.0.0.1"))) { port=socket.getLocalPort(); }
            Path log=Path.of(System.getenv("STOCK_EVIDENCE_DIR"),"child-"+events.size()+"-"+port+".log");
            String executable=System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
            String java=Path.of(System.getProperty("java.home"),"bin",executable).toString();
            process=new ProcessBuilder(java,"-cp",System.getProperty("stock.test.classpath"),OrdersApplication.class.getName(),
                    "--server.port="+port,"--spring.main.banner-mode=off","--spring.profiles.active=demo",
                    "--stock.admission.per-customer-per-minute=100000")
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(2)).build();
            await().atMost(Duration.ofSeconds(25)).pollInterval(Duration.ofMillis(150)).until(()->{
                if(!process.isAlive()) throw new IllegalStateException("Owned server exited; inspect "+log.getFileName());
                try {
                    var response=client.send(HttpRequest.newBuilder(uri("/csrf")).timeout(Duration.ofSeconds(2)).header("Authorization",authorization).GET().build(),HttpResponse.BodyHandlers.ofString());
                    if(response.statusCode()!=200) return false;
                    JsonNode token=json.readTree(response.body()); csrf=token.path("token").asText();header=token.path("headerName").asText();return true;
                } catch(java.io.IOException e) { return false; }
            });
        }
        HttpResponse<String> send(String method,String path,String key,String body) throws Exception {
            var request=HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(15)).header("Authorization",authorization)
                    .header(header,csrf).header("Content-Type","application/json");
            if(key!=null) request.header("Idempotency-Key",key);
            return client.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        }
        private URI uri(String path) { return URI.create("http://127.0.0.1:"+port+path); }
        void kill() throws Exception {
            if(process!=null && process.isAlive()) {
                long pid=process.pid();process.destroyForcibly();
                if(!process.waitFor(10,TimeUnit.SECONDS)) throw new IllegalStateException("Owned server did not stop");
                events.add(Map.of("stage","owned-jvm-terminated","pid",pid,"alive",process.isAlive()));
            }
            if(client!=null) { client.close();client=null; }
        }
        public void close() throws Exception { kill(); }
    }
}

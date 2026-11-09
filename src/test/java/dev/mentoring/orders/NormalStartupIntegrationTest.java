package dev.mentoring.orders;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mentoring.orders.config.DemoDataInitializer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 별도 빈 스키마에서 예제 비밀번호 없이 일반 실행과 인증 유지 확인. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.main.banner-mode=off", "stock.demo.alice-password=", "stock.demo.bob-password="
})
@ActiveProfiles("standard")
@DirtiesContext
class NormalStartupIntegrationTest {
    private static final String SCHEMA = "normal_startup_test";
    @LocalServerPort int port;
    @Autowired ApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    @DynamicPropertySource
    static void useDedicatedEmptySchema(DynamicPropertyRegistry registry) throws Exception {
        String url = System.getenv("STOCK_DB_URL");
        if (url == null || !url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/stock_order_test")) {
            throw new IllegalStateException("Dedicated loopback test database required");
        }
        try (var connection = DriverManager.getConnection(url,
                System.getenv("STOCK_DB_USER"), System.getenv("STOCK_DB_PASSWORD"));
                var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + SCHEMA);
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.hikari.schema", () -> SCHEMA);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
    }

    @Test
    void startsWithoutCreatingDemoAccountsOrProducts() throws Exception {
        assertThat(jdbc.queryForObject("SELECT current_schema()", String.class)).isEqualTo(SCHEMA);
        assertThat(context.getBeansOfType(DemoDataInitializer.class)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM buyers", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM products", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventories", Long.class)).isZero();
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + port + "/products")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(401);
        }
        Path evidence = Path.of(Objects.requireNonNull(System.getenv("STOCK_EVIDENCE_DIR")));
        mapper.writerWithDefaultPrettyPrinter().writeValue(evidence.resolve("normal-startup.json").toFile(),
                Map.of("profile", "standard", "demoInitializerPresent", false,
                        "buyers", 0, "products", 0, "unauthenticatedHttp", 401));
    }
}

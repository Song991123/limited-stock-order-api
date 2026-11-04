package dev.mentoring.orders;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class DemoDataInitializer implements ApplicationRunner {
    private final JdbcClient jdbc;
    private final PasswordEncoder passwords;
    private final TransactionTemplate transaction;
    private final String alicePassword;
    private final String bobPassword;

    public DemoDataInitializer(JdbcClient jdbc, PasswordEncoder passwords, TransactionTemplate transaction,
            @Value("${stock.demo.alice-password}") String alicePassword,
            @Value("${stock.demo.bob-password}") String bobPassword) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.transaction = transaction;
        this.alicePassword = alicePassword;
        this.bobPassword = bobPassword;
    }

    // 초기 고객과 상품 등록. 시작 콜백의 저장 경계를 명시적으로 지정
    @Override
    public void run(ApplicationArguments args) {
        transaction.executeWithoutResult(status -> {
            registerCustomer("alice", alicePassword);
            registerCustomer("bob", bobPassword);
            if (jdbc.sql("SELECT count(*) FROM products").query(Long.class).single() == 0) {
                registerProduct("Limited notebook");
                registerProduct("Limited mug");
            }
        });
    }

    private void registerCustomer(String username, String password) {
        if (password.length() < 12) throw new IllegalStateException("Use generated demo passwords");
        jdbc.sql("""
                INSERT INTO buyers(username, password_hash) VALUES (:username, :hash)
                ON CONFLICT (username) DO NOTHING
                """).param("username", username).param("hash", passwords.encode(password)).update();
    }

    private void registerProduct(String name) {
        long id = jdbc.sql("INSERT INTO products(name) VALUES (:name) RETURNING id")
                .param("name", name).query(Long.class).single();
        jdbc.sql("""
                INSERT INTO inventories(product_id, initial_quantity, available_quantity) VALUES (:id, 10, 10)
                """).param("id", id).update();
    }
}

package dev.mentoring.orders;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 로컬 실행용 고객과 상품의 초기 데이터 등록.
 *
 * <p>생성된 비밀번호는 해시로 저장하고 상품이 없을 때만 상품과 초기 재고 등록.
 */
@Component
public class DemoDataInitializer implements ApplicationRunner {
    private final JdbcClient jdbcClient;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate transactionTemplate;
    private final String alicePassword;
    private final String bobPassword;

    /** DB 저장 도구, TransactionTemplate과 실행용 비밀번호 연결. */
    public DemoDataInitializer(JdbcClient jdbcClient, PasswordEncoder passwordEncoder,
            TransactionTemplate transactionTemplate,
            @Value("${stock.demo.alice-password}") String alicePassword,
            @Value("${stock.demo.bob-password}") String bobPassword) {
        this.jdbcClient = jdbcClient;
        this.passwordEncoder = passwordEncoder;
        this.transactionTemplate = transactionTemplate;
        this.alicePassword = alicePassword;
        this.bobPassword = bobPassword;
    }

    /**
     * 서버 시작 시 고객과 상품의 기본 데이터 저장.
     *
     * <p>TransactionTemplate 안에서 함께 저장해 중간에 실패하면 모두 롤백.
     */
    @Override
    public void run(ApplicationArguments args) {
        transactionTemplate.executeWithoutResult(transactionStatus -> {
            registerCustomer("alice", alicePassword);
            registerCustomer("bob", bobPassword);
            if (jdbcClient.sql("SELECT count(*) FROM products").query(Long.class).single() == 0) {
                registerProduct("Limited notebook");
                registerProduct("Limited mug");
            }
        });
    }

    /**
     * 없는 실행 계정만 비밀번호 해시와 함께 등록. 기존 계정의 비밀번호는 덮어쓰지 않음.
     *
     * @param rawPassword 실행 때 생성한 원문 비밀번호, DB 저장 전 해시로 변환
     * @throws IllegalStateException 비밀번호가 최소 길이 12자 미만인 경우
     */
    private void registerCustomer(String username, String rawPassword) {
        if (rawPassword.length() < 12) throw new IllegalStateException("Use generated demo passwords");
        jdbcClient.sql("""
                INSERT INTO buyers(username, password_hash) VALUES (:username, :hash)
                ON CONFLICT (username) DO NOTHING
                """).param("username", username).param("hash", passwordEncoder.encode(rawPassword)).update();
    }

    /** 상품과 재고 10개 등록. 고객 등록과 같은 트랜잭션으로 저장. */
    private void registerProduct(String productName) {
        long productId = jdbcClient.sql("INSERT INTO products(name) VALUES (:name) RETURNING id")
                .param("name", productName).query(Long.class).single();
        jdbcClient.sql("""
                INSERT INTO inventories(product_id, initial_quantity, available_quantity) VALUES (:id, 10, 10)
                """).param("id", productId).update();
    }
}

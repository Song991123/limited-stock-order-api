package dev.mentoring.orders.repository;

import dev.mentoring.orders.domain.Product;
import dev.mentoring.orders.exception.OrderProblem;
import java.util.List;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 상품과 현재 판매 가능 수량 조회. 재고 차감과 복구 SQL은 OrderRepository에서 실행. */
@Repository
public class ProductRepository {
    private static final RowMapper<Product> PRODUCT_ROW_MAPPER = (resultSet, rowNumber) -> new Product(
            resultSet.getLong("id"), resultSet.getString("name"), resultSet.getInt("available_quantity"));
    private final JdbcClient jdbcClient;

    /** 상품 조회에 사용할 JdbcClient 연결. */
    public ProductRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /** 최대 100개 상품과 현재 판매 가능 수량 조회. */
    public List<Product> findProducts() {
        return jdbcClient.sql("""
                SELECT p.id, p.name, i.available_quantity
                FROM products p JOIN inventories i ON i.product_id = p.id
                ORDER BY p.id LIMIT 100
                """).query(PRODUCT_ROW_MAPPER).list();
    }

    /**
     * 상품과 현재 수량 조회. 조회만으로 재고를 예약하지 않음.
     *
     * @throws OrderProblem 상품 또는 연결된 재고 기록이 없는 경우
     */
    public Product findProduct(long productId) {
        return jdbcClient.sql("""
                SELECT p.id, p.name, i.available_quantity
                FROM products p JOIN inventories i ON i.product_id = p.id
                WHERE p.id = :id
                """).param("id", productId).query(PRODUCT_ROW_MAPPER).optional().orElseThrow(OrderProblem::missing);
    }
}

package dev.mentoring.orders.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import org.springframework.jdbc.core.JdbcTemplate;

/** HTTP, 요청 제한과 별도 JVM 검사에서 같은 SQL로 주문, 재고와 증감 이력 대조. */
public final class OrderStorageChecks {
    private static final String INVENTORY_ERRORS = """
            SELECT count(*) FROM inventories i WHERE available_quantity<0 OR available_quantity>initial_quantity
            OR available_quantity<>initial_quantity-COALESCE((SELECT sum(quantity) FROM orders o WHERE o.product_id=i.product_id AND o.status='ACCEPTED'),0)
            OR available_quantity<>initial_quantity+COALESCE((SELECT sum(quantity_delta) FROM inventory_movements m WHERE m.product_id=i.product_id),0)
            """;
    private static final String REQUEST_ERRORS = """
            SELECT count(*) FROM order_requests r WHERE outcome IS NULL
            OR (outcome='ACCEPTED' AND (SELECT count(*) FROM orders o WHERE o.request_id=r.id)<>1)
            OR (outcome='OUT_OF_STOCK' AND EXISTS(SELECT 1 FROM orders o WHERE o.request_id=r.id))
            """;
    private static final String ORDER_ERRORS = """
            SELECT count(*) FROM orders o JOIN order_requests r ON r.id=o.request_id
            WHERE o.buyer_id<>r.buyer_id OR o.product_id<>r.product_id OR o.quantity<>r.quantity OR r.outcome<>'ACCEPTED'
            OR (SELECT count(*) FROM inventory_movements m WHERE m.order_id=o.id AND m.kind='DEBIT')<>1
            OR (SELECT count(*) FROM inventory_movements m WHERE m.order_id=o.id AND m.kind='RESTORE')<>(CASE WHEN o.status='CANCELLED' THEN 1 ELSE 0 END)
            OR EXISTS(SELECT 1 FROM inventory_movements m WHERE m.order_id=o.id AND
                (m.product_id<>o.product_id OR m.quantity_delta<>(CASE WHEN m.kind='DEBIT' THEN -o.quantity ELSE o.quantity END)))
            """;

    private OrderStorageChecks() { }

    /** Spring으로 실행한 HTTP 검사에서 주입받은 DB 연결 사용. */
    public static Counts compare(JdbcTemplate jdbc) {
        return new Counts(jdbc.queryForObject(INVENTORY_ERRORS, Long.class),
                jdbc.queryForObject(REQUEST_ERRORS, Long.class), jdbc.queryForObject(ORDER_ERRORS, Long.class));
    }

    /** 별도 JVM과 DB 복구 검사에서 만든 JDBC 연결 사용. */
    public static Counts compare(Connection connection) throws SQLException {
        return new Counts(count(connection, INVENTORY_ERRORS), count(connection, REQUEST_ERRORS), count(connection, ORDER_ERRORS));
    }

    private static long count(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    /** 전체 수량, 구매 결과와 개별 주문 이력에서 발견한 불일치 수. */
    public record Counts(long inventoryViolations, long requestViolations, long orderViolations) {
        /** 합계가 같아도 개별 주문의 상품과 증감량이 다르면 검사 실패. */
        public void assertConsistent() {
            assertThat(inventoryViolations).as("재고와 주문/이력 합계 불일치").isZero();
            assertThat(requestViolations).as("구매 결과와 주문 수 불일치").isZero();
            assertThat(orderViolations).as("주문과 구매 입력/개별 이력 불일치").isZero();
        }
    }
}

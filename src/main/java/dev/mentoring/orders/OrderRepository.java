package dev.mentoring.orders;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class OrderRepository {
    private static final String ORDER_COLUMNS = "id, product_id, quantity, status, created_at, cancelled_at";
    private static final RowMapper<OrderView> ORDER = (rs, row) -> new OrderView(
            rs.getLong("id"), rs.getLong("product_id"), rs.getInt("quantity"),
            OrderStatus.valueOf(rs.getString("status")), readTime(rs, "created_at"), readTime(rs, "cancelled_at"));
    private static final RowMapper<ProductView> PRODUCT = (rs, row) -> new ProductView(
            rs.getLong("id"), rs.getString("name"), rs.getInt("available_quantity"));

    private final JdbcClient jdbc;

    public OrderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    long findBuyerId(String username) {
        return jdbc.sql("SELECT id FROM buyers WHERE username = :username")
                .param("username", username).query(Long.class).optional().orElseThrow(OrderProblem::missing);
    }

    List<ProductView> findProducts() {
        return jdbc.sql("""
                SELECT p.id, p.name, i.available_quantity
                FROM products p JOIN inventories i ON i.product_id = p.id
                ORDER BY p.id LIMIT 100
                """).query(PRODUCT).list();
    }

    ProductView findProduct(long id) {
        return jdbc.sql("""
                SELECT p.id, p.name, i.available_quantity
                FROM products p JOIN inventories i ON i.product_id = p.id
                WHERE p.id = :id
                """).param("id", id).query(PRODUCT).optional().orElseThrow(OrderProblem::missing);
    }

    // 같은 구매 요청의 중복 반영 차단. 충돌한 요청은 선행 트랜잭션의 확정까지 대기
    Optional<Long> tryRegisterPurchase(long buyerId, String key, CreateOrderRequest input) {
        return jdbc.sql("""
                INSERT INTO order_requests(buyer_id, request_key, product_id, quantity)
                VALUES (:buyer, :key, :product, :quantity)
                ON CONFLICT (buyer_id, request_key) DO NOTHING RETURNING id
                """).param("buyer", buyerId).param("key", key).param("product", input.productId())
                .param("quantity", input.quantity()).query(Long.class).optional();
    }

    RecordedPurchase findRecordedPurchase(long buyerId, String key) {
        return jdbc.sql("""
                SELECT id, product_id, quantity, outcome FROM order_requests
                WHERE buyer_id = :buyer AND request_key = :key
                """).param("buyer", buyerId).param("key", key).query((rs, row) -> {
                    String outcome = rs.getString("outcome");
                    if (outcome == null) throw OrderProblem.invariant();
                    return new RecordedPurchase(rs.getLong("id"), rs.getLong("product_id"),
                            rs.getInt("quantity"), PurchaseOutcome.valueOf(outcome));
                }).optional().orElseThrow(OrderProblem::invariant);
    }

    void finishPurchase(long requestId, PurchaseOutcome outcome) {
        requireOne(jdbc.sql("""
                UPDATE order_requests SET outcome = :outcome
                WHERE id = :id AND outcome IS NULL
                """).param("outcome", outcome.name()).param("id", requestId).update());
    }

    // 재고 확인과 차감을 한 SQL로 처리. 잠금 대기 후에도 DB가 WHERE 조건을 재검사
    boolean tryDecreaseStock(long productId, int quantity) {
        return jdbc.sql("""
                UPDATE inventories SET available_quantity = available_quantity - :quantity
                WHERE product_id = :id AND available_quantity >= :quantity
                """).param("quantity", quantity).param("id", productId).update() == 1;
    }

    OrderView insertOrder(long requestId, long buyerId, CreateOrderRequest input) {
        return jdbc.sql("""
                INSERT INTO orders(request_id, buyer_id, product_id, quantity, status)
                VALUES (:request, :buyer, :product, :quantity, 'ACCEPTED') RETURNING
                """ + ORDER_COLUMNS)
                .param("request", requestId).param("buyer", buyerId).param("product", input.productId())
                .param("quantity", input.quantity()).query(ORDER).single();
    }

    OrderView findOrderByRequest(long requestId, long buyerId) {
        return jdbc.sql("SELECT " + ORDER_COLUMNS + " FROM orders WHERE request_id = :request AND buyer_id = :buyer")
                .param("request", requestId).param("buyer", buyerId).query(ORDER).optional().orElseThrow(OrderProblem::invariant);
    }

    OrderView findOwnedOrder(long id, long buyerId) {
        return jdbc.sql("SELECT " + ORDER_COLUMNS + " FROM orders WHERE id = :id AND buyer_id = :buyer")
                .param("id", id).param("buyer", buyerId).query(ORDER).optional().orElseThrow(OrderProblem::missing);
    }

    List<OrderView> findOwnedOrders(long buyerId, long beforeId, int limit) {
        return jdbc.sql("""
                SELECT
                """ + ORDER_COLUMNS + """
                 FROM orders WHERE buyer_id = :buyer AND id < :before
                ORDER BY id DESC LIMIT :limit
                """).param("buyer", buyerId).param("before", beforeId).param("limit", limit).query(ORDER).list();
    }

    // ACCEPTED에서 CANCELLED로 바뀐 요청만 재고 복원으로 진행
    Optional<OrderView> tryCancelOwnedOrder(long id, long buyerId) {
        return jdbc.sql("""
                UPDATE orders SET status = 'CANCELLED', cancelled_at = CURRENT_TIMESTAMP
                WHERE id = :id AND buyer_id = :buyer AND status = 'ACCEPTED' RETURNING
                """ + ORDER_COLUMNS).param("id", id).param("buyer", buyerId).query(ORDER).optional();
    }

    void restoreStock(OrderView order) {
        requireOne(jdbc.sql("""
                UPDATE inventories SET available_quantity = available_quantity + :quantity
                WHERE product_id = :id AND available_quantity <= initial_quantity - :quantity
                """)
                .param("quantity", order.quantity()).param("id", order.productId()).update());
    }

    void appendMovement(OrderView order, MovementKind kind, int delta) {
        requireOne(jdbc.sql("""
                INSERT INTO inventory_movements(order_id, product_id, kind, quantity_delta)
                VALUES (:order, :product, :kind, :delta)
                """).param("order", order.id()).param("product", order.productId())
                .param("kind", kind.name()).param("delta", delta).update());
    }

    private static void requireOne(int rows) {
        if (rows != 1) throw OrderProblem.invariant();
    }

    private static Instant readTime(ResultSet rs, String name) throws SQLException {
        var value = rs.getObject(name, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}

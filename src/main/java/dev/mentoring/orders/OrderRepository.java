package dev.mentoring.orders;

import static dev.mentoring.orders.OrderModels.*;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class OrderRepository {
    private final JdbcClient jdbc;
    private static final String ORDER_COLUMNS = "id, product_id, quantity, status, created_at, cancelled_at";
    private static final RowMapper<Order> ORDER = (rs, row) -> new Order(rs.getLong("id"), rs.getLong("product_id"),
            rs.getInt("quantity"), rs.getString("status"), rs.getObject("created_at", OffsetDateTime.class).toInstant(),
            nullableTime(rs, "cancelled_at"));

    public OrderRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    long buyer(String username) {
        return jdbc.sql("SELECT id FROM buyers WHERE username = :username").param("username", username)
                .query(Long.class).optional().orElseThrow(ApiException::missing);
    }

    public List<Product> products() {
        return jdbc.sql("SELECT p.id, p.name, i.available_quantity FROM products p JOIN inventories i ON i.product_id=p.id ORDER BY p.id LIMIT 100")
                .query((rs, row) -> new Product(rs.getLong(1), rs.getString(2), rs.getInt(3))).list();
    }

    public Product product(long id) {
        return jdbc.sql("SELECT p.id, p.name, i.available_quantity FROM products p JOIN inventories i ON i.product_id=p.id WHERE p.id=:id")
                .param("id", id).query((rs, row) -> new Product(rs.getLong(1), rs.getString(2), rs.getInt(3)))
                .optional().orElseThrow(ApiException::missing);
    }

    Optional<Long> claim(long buyer, String key, Purchase input) {
        return jdbc.sql("""
                INSERT INTO order_requests(buyer_id,request_key,product_id,quantity)
                VALUES (:buyer,:key,:product,:quantity)
                ON CONFLICT (buyer_id,request_key) DO NOTHING RETURNING id
                """).param("buyer", buyer).param("key", key).param("product", input.productId())
                .param("quantity", input.quantity()).query(Long.class).optional();
    }

    Request existingRequest(long buyer, String key) {
        return jdbc.sql("SELECT id,product_id,quantity,outcome FROM order_requests WHERE buyer_id=:buyer AND request_key=:key")
                .param("buyer", buyer).param("key", key).query((rs, row) -> new Request(rs.getLong(1), rs.getLong(2), rs.getInt(3), rs.getString(4)))
                .optional().orElseThrow(ApiException::invariant);
    }

    boolean subtract(long product, int quantity) {
        return jdbc.sql("UPDATE inventories SET available_quantity=available_quantity-:quantity WHERE product_id=:id AND available_quantity>=:quantity")
                .param("quantity", quantity).param("id", product).update() == 1;
    }

    Order insert(long request, long buyer, Purchase input) {
        return jdbc.sql("INSERT INTO orders(request_id,buyer_id,product_id,quantity,status) VALUES (:request,:buyer,:product,:quantity,'ACCEPTED') RETURNING " + ORDER_COLUMNS)
                .param("request", request).param("buyer", buyer).param("product", input.productId())
                .param("quantity", input.quantity()).query(ORDER).single();
    }

    void finish(long request, String outcome) {
        requireOne(jdbc.sql("UPDATE order_requests SET outcome=:outcome WHERE id=:id AND outcome IS NULL")
                .param("outcome", outcome).param("id", request).update());
    }

    Order byRequest(long request, long buyer) {
        return jdbc.sql("SELECT " + ORDER_COLUMNS + " FROM orders WHERE request_id=:request AND buyer_id=:buyer")
                .param("request", request).param("buyer", buyer).query(ORDER).optional().orElseThrow(ApiException::invariant);
    }

    Order owned(long id, long buyer) {
        return jdbc.sql("SELECT " + ORDER_COLUMNS + " FROM orders WHERE id=:id AND buyer_id=:buyer")
                .param("id", id).param("buyer", buyer).query(ORDER).optional().orElseThrow(ApiException::missing);
    }

    List<Order> ownedList(long buyer, long before, int limit) {
        return jdbc.sql("SELECT " + ORDER_COLUMNS + " FROM orders WHERE buyer_id=:buyer AND id<:before ORDER BY id DESC LIMIT :limit")
                .param("buyer", buyer).param("before", before).param("limit", limit).query(ORDER).list();
    }

    Optional<Order> markCancelled(long id, long buyer) {
        return jdbc.sql("UPDATE orders SET status='CANCELLED',cancelled_at=CURRENT_TIMESTAMP WHERE id=:id AND buyer_id=:buyer AND status='ACCEPTED' RETURNING " + ORDER_COLUMNS)
                .param("id", id).param("buyer", buyer).query(ORDER).optional();
    }

    void restore(Order order) {
        requireOne(jdbc.sql("UPDATE inventories SET available_quantity=available_quantity+:quantity WHERE product_id=:id AND available_quantity<=initial_quantity-:quantity")
                .param("quantity", order.quantity()).param("id", order.productId()).update());
    }

    void movement(Order order, String kind, int delta) {
        requireOne(jdbc.sql("INSERT INTO inventory_movements(order_id,product_id,kind,quantity_delta) VALUES (:order,:product,:kind,:delta)")
                .param("order", order.id()).param("product", order.productId()).param("kind", kind).param("delta", delta).update());
    }

    private static void requireOne(int rows) { if (rows != 1) throw ApiException.invariant(); }
    private static java.time.Instant nullableTime(ResultSet rs, String name) throws SQLException {
        var value = rs.getObject(name, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}

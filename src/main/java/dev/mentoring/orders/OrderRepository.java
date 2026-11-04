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

/**
 * 주문 정보, 구매 요청과 재고 증감 이력의 DB 저장 및 조회.
 *
 * <p>OrderService와 같은 트랜잭션으로 저장. SQL 조건과 유일성 제약으로 중복 차감과 복구 방지.
 */
@Repository
public class OrderRepository {
    private static final String ORDER_COLUMNS = "id, product_id, quantity, status, created_at, cancelled_at";
    private static final RowMapper<OrderView> ORDER_ROW_MAPPER = (resultSet, rowNumber) -> new OrderView(
            resultSet.getLong("id"), resultSet.getLong("product_id"), resultSet.getInt("quantity"),
            OrderStatus.valueOf(resultSet.getString("status")),
            readNullableInstant(resultSet, "created_at"), readNullableInstant(resultSet, "cancelled_at"));
    private static final RowMapper<ProductView> PRODUCT_ROW_MAPPER = (resultSet, rowNumber) -> new ProductView(
            resultSet.getLong("id"), resultSet.getString("name"), resultSet.getInt("available_quantity"));

    private final JdbcClient jdbcClient;

    /** DB 조회와 저장에 사용할 JdbcClient 연결. */
    public OrderRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * 로그인 계정명으로 구매자 ID 조회.
     *
     * @throws OrderProblem 구매자 기록이 없는 경우
     */
    long findBuyerId(String username) {
        return jdbcClient.sql("SELECT id FROM buyers WHERE username = :username")
                .param("username", username).query(Long.class).optional().orElseThrow(OrderProblem::missing);
    }

    /** 최대 100개 상품과 현재 판매 가능 수량 조회. */
    List<ProductView> findProducts() {
        return jdbcClient.sql("""
                SELECT p.id, p.name, i.available_quantity
                FROM products p JOIN inventories i ON i.product_id = p.id
                ORDER BY p.id LIMIT 100
                """).query(PRODUCT_ROW_MAPPER).list();
    }

    /**
     * 상품과 현재 재고 조회. 조회만으로 재고를 예약하지 않음.
     *
     * @throws OrderProblem 상품 또는 연결된 재고 기록이 없는 경우
     */
    ProductView findProduct(long productId) {
        return jdbcClient.sql("""
                SELECT p.id, p.name, i.available_quantity
                FROM products p JOIN inventories i ON i.product_id = p.id
                WHERE p.id = :id
                """).param("id", productId).query(PRODUCT_ROW_MAPPER).optional().orElseThrow(OrderProblem::missing);
    }

    /**
     * 구매 요청 등록. 같은 고객과 구매 키는 한 번만 저장.
     *
     * <p>다른 요청이 같은 키를 저장 중이면 그 요청의 커밋 또는 롤백까지 대기.
     * 먼저 저장된 기록이 현재 INSERT에서 보이지 않을 수 있어 다음 SELECT로 다시 조회.
     *
     * @return 새로 등록한 요청 ID, 같은 키가 이미 저장돼 있으면 빈 Optional
     */
    Optional<Long> tryRegisterPurchase(long buyerId, String idempotencyKey, CreateOrderRequest orderRequest) {
        return jdbcClient.sql("""
                INSERT INTO order_requests(buyer_id, request_key, product_id, quantity)
                VALUES (:buyer, :key, :product, :quantity)
                ON CONFLICT (buyer_id, request_key) DO NOTHING RETURNING id
                """).param("buyer", buyerId).param("key", idempotencyKey).param("product", orderRequest.productId())
                .param("quantity", orderRequest.quantity()).query(Long.class).optional();
    }

    /**
     * 같은 키로 이미 저장한 상품, 수량과 최초 처리 결과 조회.
     *
     * @throws OrderProblem 요청 기록이 없거나 저장된 처리 결과가 비어 있는 경우
     */
    RecordedPurchase findRecordedPurchase(long buyerId, String idempotencyKey) {
        return jdbcClient.sql("""
                SELECT id, product_id, quantity, outcome FROM order_requests
                WHERE buyer_id = :buyer AND request_key = :key
                """).param("buyer", buyerId).param("key", idempotencyKey).query((resultSet, rowNumber) -> {
                    String outcome = resultSet.getString("outcome");
                    if (outcome == null) throw OrderProblem.invariant();
                    return new RecordedPurchase(resultSet.getLong("id"), resultSet.getLong("product_id"),
                            resultSet.getInt("quantity"), PurchaseOutcome.valueOf(outcome));
                }).optional().orElseThrow(OrderProblem::invariant);
    }

    /**
     * 구매 요청에 접수 성공 또는 재고 부족 결과 저장.
     *
     * @throws OrderProblem 요청이 없거나 이미 결과가 기록된 경우
     */
    void finishPurchase(long purchaseRequestId, PurchaseOutcome outcome) {
        requireExactlyOneRow(jdbcClient.sql("""
                UPDATE order_requests SET outcome = :outcome
                WHERE id = :id AND outcome IS NULL
                """).param("outcome", outcome.name()).param("id", purchaseRequestId).update());
    }

    /**
     * 충분한 재고가 있을 때만 수량 차감.
     *
     * <p>재고 확인과 차감을 한 UPDATE로 처리. 다른 요청을 기다렸다면 바뀐 재고로 WHERE 조건 재검사.
     *
     * @return 1건 차감했으면 true, 재고를 확보하지 못했으면 false
     */
    boolean tryDecreaseStock(long productId, int quantity) {
        return jdbcClient.sql("""
                UPDATE inventories SET available_quantity = available_quantity - :quantity
                WHERE product_id = :id AND available_quantity >= :quantity
                """).param("quantity", quantity).param("id", productId).update() == 1;
    }

    /**
     * 재고 차감에 성공한 요청의 주문 정보 저장. 주문 상태는 ACCEPTED.
     *
     * @param purchaseRequestId 같은 트랜잭션에서 선등록한 요청 ID
     * @return DB가 생성한 주문 ID와 접수 시각을 포함한 주문
     */
    OrderView insertOrder(long purchaseRequestId, long buyerId, CreateOrderRequest orderRequest) {
        return jdbcClient.sql("""
                INSERT INTO orders(request_id, buyer_id, product_id, quantity, status)
                VALUES (:request, :buyer, :product, :quantity, 'ACCEPTED') RETURNING
                """ + ORDER_COLUMNS)
                .param("request", purchaseRequestId).param("buyer", buyerId).param("product", orderRequest.productId())
                .param("quantity", orderRequest.quantity()).query(ORDER_ROW_MAPPER).single();
    }

    /**
     * 접수에 성공한 구매 요청 ID로 주문 정보 조회.
     *
     * @throws OrderProblem 구매 요청은 접수 성공인데 해당 고객의 주문이 없는 경우
     */
    OrderView findOrderByRequest(long purchaseRequestId, long buyerId) {
        return jdbcClient.sql("SELECT " + ORDER_COLUMNS
                + " FROM orders WHERE request_id = :request AND buyer_id = :buyer")
                .param("request", purchaseRequestId).param("buyer", buyerId)
                .query(ORDER_ROW_MAPPER).optional().orElseThrow(OrderProblem::invariant);
    }

    /**
     * 주문 ID와 구매자 ID가 모두 일치하는 주문 조회.
     *
     * @throws OrderProblem 주문이 없거나 소유자가 다른 경우
     */
    OrderView findOwnedOrder(long orderId, long buyerId) {
        return jdbcClient.sql("SELECT " + ORDER_COLUMNS + " FROM orders WHERE id = :id AND buyer_id = :buyer")
                .param("id", orderId).param("buyer", buyerId)
                .query(ORDER_ROW_MAPPER).optional().orElseThrow(OrderProblem::missing);
    }

    /**
     * 내 주문 목록 조회. 마지막으로 조회한 주문보다 작은 ID부터 다음 페이지 조회.
     *
     * @param beforeOrderId 이 ID보다 작은 주문부터 조회
     * @param pageSize 반환할 최대 주문 수
     */
    List<OrderView> findOwnedOrders(long buyerId, long beforeOrderId, int pageSize) {
        return jdbcClient.sql("""
                SELECT
                """ + ORDER_COLUMNS + """
                 FROM orders WHERE buyer_id = :buyer AND id < :before
                ORDER BY id DESC LIMIT :limit
                """).param("buyer", buyerId).param("before", beforeOrderId)
                .param("limit", pageSize).query(ORDER_ROW_MAPPER).list();
    }

    /**
     * 자기 주문이 ACCEPTED일 때만 CANCELLED로 변경.
     *
     * @return 이번 요청이 상태를 바꾼 주문, 이미 취소됐거나 자기 주문이 아니면 빈 Optional
     */
    Optional<OrderView> tryCancelOwnedOrder(long orderId, long buyerId) {
        return jdbcClient.sql("""
                UPDATE orders SET status = 'CANCELLED', cancelled_at = CURRENT_TIMESTAMP
                WHERE id = :id AND buyer_id = :buyer AND status = 'ACCEPTED' RETURNING
                """ + ORDER_COLUMNS).param("id", orderId).param("buyer", buyerId).query(ORDER_ROW_MAPPER).optional();
    }

    /**
     * 취소한 주문의 수량만큼 재고를 다시 더함.
     *
     * @param cancelledOrder 이번 트랜잭션에서 취소 상태로 바꾼 주문
     * @throws OrderProblem 재고 기록이 없거나 복구 후 수량이 초기 수량을 넘는 경우
     */
    void restoreStock(OrderView cancelledOrder) {
        requireExactlyOneRow(jdbcClient.sql("""
                UPDATE inventories SET available_quantity = available_quantity + :quantity
                WHERE product_id = :id AND available_quantity <= initial_quantity - :quantity
                """)
                .param("quantity", cancelledOrder.quantity()).param("id", cancelledOrder.productId()).update());
    }

    /**
     * 주문별 재고 증감 이력 저장. 주문 ID와 이력 종류의 유일성 제약으로 중복 기록 방지.
     *
     * @param quantityDelta 차감은 음수, 복원은 양수인 수량 변화
     */
    void appendMovement(OrderView order, MovementKind movementKind, int quantityDelta) {
        requireExactlyOneRow(jdbcClient.sql("""
                INSERT INTO inventory_movements(order_id, product_id, kind, quantity_delta)
                VALUES (:order, :product, :kind, :delta)
                """).param("order", order.id()).param("product", order.productId())
                .param("kind", movementKind.name()).param("delta", quantityDelta).update());
    }

    /**
     * DB에서 실제로 한 건을 변경했는지 확인.
     *
     * @throws OrderProblem 예상과 다른 변경 건수
     */
    private static void requireExactlyOneRow(int affectedRows) {
        if (affectedRows != 1) throw OrderProblem.invariant();
    }

    /**
     * DB에 저장된 시간을 Instant로 변환. 취소 시각이 없으면 null 반환.
     *
     * @throws SQLException 지정한 컬럼을 시각으로 읽지 못한 경우
     */
    private static Instant readNullableInstant(ResultSet resultSet, String columnName) throws SQLException {
        var timestamp = resultSet.getObject(columnName, OffsetDateTime.class);
        return timestamp == null ? null : timestamp.toInstant();
    }
}

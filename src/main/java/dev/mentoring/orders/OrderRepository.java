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
 * 주문, 구매 요청과 상품 재고의 SQL 저장소.
 *
 * <p>쓰기 메서드는 서비스의 같은 트랜잭션에 참여. 조건부 갱신과 유일성 제약으로 중복 차감/복원 방지.
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

    /** Spring이 현재 트랜잭션의 연결을 공유하는 JdbcClient 주입. */
    public OrderRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * 인증 계정명에 대응하는 내부 구매자 ID 조회.
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
     * 상품 존재와 현재 수량 조회. 조회 결과 자체는 재고 예약이 아님.
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
     * 고객과 구매 키의 요청 기록 선등록.
     *
     * <p>같은 키의 INSERT가 겹치면 선행 트랜잭션 종료까지 대기할 수 있음.
     * 등록 0건은 현재 문장의 조회 시점과 다를 수 있어, 기존 결과는 다음 SELECT로 조회.
     *
     * @return 새로 등록한 요청 ID, 이미 확정된 같은 키가 있으면 빈 Optional
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
     * 같은 키로 먼저 확정된 구매 입력과 최초 결과 조회.
     *
     * @throws OrderProblem 기록이 없거나 확정된 기록의 결과가 비어 있는 경우
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
     * 현재 트랜잭션의 미완료 구매 요청에 최종 결과 저장.
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
     * <p>확인과 차감을 한 UPDATE에 결합. PostgreSQL이 행 잠금 대기 뒤 WHERE 조건을 재검사.
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
     * 재고를 확보한 구매 요청에 ACCEPTED 주문 생성.
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
     * 접수 결과가 ACCEPTED인 구매 요청에 연결된 자기 주문 조회.
     *
     * @throws OrderProblem 요청에 대응하는 자기 주문이 없는 정합성 오류
     */
    OrderView findOrderByRequest(long purchaseRequestId, long buyerId) {
        return jdbcClient.sql("SELECT " + ORDER_COLUMNS
                + " FROM orders WHERE request_id = :request AND buyer_id = :buyer")
                .param("request", purchaseRequestId).param("buyer", buyerId)
                .query(ORDER_ROW_MAPPER).optional().orElseThrow(OrderProblem::invariant);
    }

    /**
     * 주문 ID와 소유 고객을 함께 검사한 단건 조회.
     *
     * @throws OrderProblem 주문이 없거나 소유자가 다른 경우
     */
    OrderView findOwnedOrder(long orderId, long buyerId) {
        return jdbcClient.sql("SELECT " + ORDER_COLUMNS + " FROM orders WHERE id = :id AND buyer_id = :buyer")
                .param("id", orderId).param("buyer", buyerId)
                .query(ORDER_ROW_MAPPER).optional().orElseThrow(OrderProblem::missing);
    }

    /**
     * 자기 주문의 커서 기반 목록 조회.
     *
     * @param beforeOrderId 포함하지 않을 상한 주문 ID
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
     * 취소된 주문에 저장된 수량만 재고로 복원.
     *
     * @param cancelledOrder 이번 트랜잭션에서 취소 상태로 바꾼 주문
     * @throws OrderProblem 재고 기록이 없거나 초기 수량을 초과하는 복원인 경우
     */
    void restoreStock(OrderView cancelledOrder) {
        requireExactlyOneRow(jdbcClient.sql("""
                UPDATE inventories SET available_quantity = available_quantity + :quantity
                WHERE product_id = :id AND available_quantity <= initial_quantity - :quantity
                """)
                .param("quantity", cancelledOrder.quantity()).param("id", cancelledOrder.productId()).update());
    }

    /**
     * 주문의 재고 차감/복원 이력 저장. 주문과 종류의 유일성 제약으로 중복 이력 제한.
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
     * 반드시 한 기록을 바꿔야 하는 저장의 실제 변경 건수 확인.
     *
     * @throws OrderProblem 예상과 다른 변경 건수
     */
    private static void requireExactlyOneRow(int affectedRows) {
        if (affectedRows != 1) throw OrderProblem.invariant();
    }

    /**
     * 시간대가 있는 DB 시각을 Instant로 변환. 아직 취소하지 않은 시각의 null 보존.
     *
     * @throws SQLException 지정한 컬럼을 시각으로 읽지 못한 경우
     */
    private static Instant readNullableInstant(ResultSet resultSet, String columnName) throws SQLException {
        var timestamp = resultSet.getObject(columnName, OffsetDateTime.class);
        return timestamp == null ? null : timestamp.toInstant();
    }
}

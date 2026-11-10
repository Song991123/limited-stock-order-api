package dev.mentoring.orders.service;

import dev.mentoring.orders.domain.MovementKind;
import dev.mentoring.orders.domain.Order;
import dev.mentoring.orders.domain.OrderPlacement;
import dev.mentoring.orders.domain.OrderStatus;
import dev.mentoring.orders.domain.PurchaseOutcome;
import dev.mentoring.orders.exception.OrderProblem;
import dev.mentoring.orders.repository.OrderRepository;
import dev.mentoring.orders.repository.ProductRepository;
import dev.mentoring.orders.repository.model.RecordedPurchase;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문 접수와 취소 처리.
 *
 * <p>주문 정보, 재고 수량과 재고 증감 이력을 함께 저장. HTTP 응답은 컨트롤러에서 생성.
 * Service는 객체 등록용이며, 저장의 원자성은 외부에서 호출하는 public 메서드의 Transactional로 적용.
 */
@Service
public class OrderService {
    private static final Pattern IDEMPOTENCY_KEY_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;

    /**
     * 주문 처리에 사용할 저장소 지정.
     *
     * @param orderRepository 같은 DataSource를 사용하는 주문 SQL 저장소
     * @param productRepository 주문 전 상품 존재를 확인할 조회 저장소
     */
    public OrderService(OrderRepository orderRepository, ProductRepository productRepository) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
    }

    /**
     * 신규 주문 접수. 같은 구매의 재요청은 재고를 다시 차감하지 않음.
     *
     * <p>구매 요청 기록을 새로 등록한 경우에만 재고 차감. 메서드 실행을 마친 뒤 Spring이 커밋하고,
     * 저장 중 예외가 발생하면 요청 등록부터 모두 롤백.
     * READ_COMMITTED로 충돌 대기 뒤 새 SELECT에서 상대의 확정 결과 조회.
     * rollbackFor는 checked 예외도 롤백할 정책이며, 잠금 자체를 대신하지 않음.
     *
     * @param username 인증된 로그인 계정명
     * @param idempotencyKey 같은 구매를 다시 요청할 때 유지할 Idempotency-Key 값
     * @param productId 주문 상품 ID, 1 이상
     * @param quantity 주문 수량, 1 이상
     * @return 새 주문, 원래 주문의 현재 상태 또는 처음 저장한 재고 부족 결과
     * @throws OrderProblem 잘못된 키, 없는 상품 또는 같은 키의 다른 입력
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public OrderPlacement placeOrder(String username, String idempotencyKey, long productId, int quantity) {
        requirePositiveId(productId);
        if (quantity < 1) throw OrderProblem.invalidInput();
        validateIdempotencyKey(idempotencyKey);
        long buyerId = orderRepository.findBuyerId(username);

        // 기존 키의 입력 대조를 상품 조회보다 먼저 수행해 충돌 응답을 일관되게 유지
        var existingPurchase = orderRepository.findRecordedPurchase(buyerId, idempotencyKey);
        if (existingPurchase.isPresent()) {
            return replayPurchase(existingPurchase.orElseThrow(), buyerId, productId, quantity);
        }
        productRepository.findProduct(productId);

        // 앞선 조회에서 없어도 다른 요청이 등록할 수 있으므로 UNIQUE INSERT로 최종 판정
        var registeredRequestId = orderRepository.tryRegisterPurchase(buyerId, idempotencyKey, productId, quantity);
        if (registeredRequestId.isEmpty()) {
            var committedPurchase = orderRepository.findRecordedPurchase(buyerId, idempotencyKey)
                    .orElseThrow(OrderProblem::invariant);
            return replayPurchase(committedPurchase, buyerId, productId, quantity);
        }
        return createOrder(registeredRequestId.orElseThrow(), buyerId, productId, quantity);
    }

    /**
     * 같은 구매 키의 상품과 수량을 대조해 재전송 결과 반환. 재고는 다시 차감하지 않음.
     *
     * <p>입력이 다르면 예외로 이번 요청을 롤백. 기존에 확정된 주문과 재고는 유지.
     *
     * @return 취소 상태도 포함한 원래 주문의 현재 상태, 또는 처음 저장한 재고 부족 결과
     * @throws OrderProblem 같은 키에 다른 상품이나 수량을 보낸 경우
     */
    private OrderPlacement replayPurchase(RecordedPurchase recordedPurchase, long buyerId, long productId, int quantity) {
        if (!recordedPurchase.hasSameOrderDetails(productId, quantity)) {
            throw new OrderProblem(OrderProblem.Reason.IDEMPOTENCY_KEY_REUSED);
        }
        return switch (recordedPurchase.outcome()) {
            case ACCEPTED -> new OrderPlacement.Replayed(
                    orderRepository.findOrderByRequest(recordedPurchase.id(), buyerId));
            case OUT_OF_STOCK -> new OrderPlacement.OutOfStock();
        };
    }

    /**
     * 재고 차감과 주문 정보 저장.
     *
     * <p>재고 부족 결과도 DB에 저장. 같은 키로 다시 요청해도 같은 결과를 주기 위해 예외로 처리하지 않음.
     * 주문 접수 메서드와 같은 트랜잭션으로 저장하며 따로 커밋하지 않음.
     *
     * @param purchaseRequestId 이번 트랜잭션에서 등록한 구매 요청 기록 ID
     */
    private OrderPlacement createOrder(long purchaseRequestId, long buyerId, long productId, int quantity) {
        if (!orderRepository.tryDecreaseStock(productId, quantity)) {
            orderRepository.finishPurchase(purchaseRequestId, PurchaseOutcome.OUT_OF_STOCK);
            return new OrderPlacement.OutOfStock();
        }

        var createdOrder = orderRepository.insertOrder(purchaseRequestId, buyerId, productId, quantity);
        orderRepository.appendMovement(createdOrder, MovementKind.DEBIT, -quantity);
        orderRepository.finishPurchase(purchaseRequestId, PurchaseOutcome.ACCEPTED);
        return new OrderPlacement.Created(createdOrder);
    }

    /**
     * 주문 취소와 재고 복구. 취소 상태, 재고 수량과 복구 이력을 함께 저장.
     *
     * <p>ACCEPTED에서 CANCELLED로 바꾼 요청만 재고를 다시 더함. 이미 취소됐다면 결과만 반환.
     *
     * @param username 인증된 로그인 계정명
     * @param orderId 취소할 주문 ID
     * @return 취소된 자기 주문
     * @throws OrderProblem 자기 주문이 없거나 복구할 수량이 맞지 않는 경우
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public Order cancelOrder(String username, long orderId) {
        requirePositiveId(orderId);
        long buyerId = orderRepository.findBuyerId(username);
        var cancelledOrder = orderRepository.tryCancelOwnedOrder(orderId, buyerId);
        if (cancelledOrder.isEmpty()) {
            return findCancelledOrder(orderId, buyerId);
        }

        var order = cancelledOrder.orElseThrow();
        orderRepository.restoreStock(order);
        orderRepository.appendMovement(order, MovementKind.RESTORE, order.quantity());
        return order;
    }

    /**
     * 취소 UPDATE가 바꾼 주문이 없으면 자기 주문을 조회. 이미 취소된 주문만 반환.
     *
     * @throws OrderProblem 주문이 없거나 예상과 달리 취소 상태가 아닌 경우
     */
    private Order findCancelledOrder(long orderId, long buyerId) {
        var existingOrder = orderRepository.findOwnedOrder(orderId, buyerId);
        if (existingOrder.status() != OrderStatus.CANCELLED) {
            throw OrderProblem.invariant();
        }
        return existingOrder;
    }

    /**
     * 로그인 고객 소유의 주문 조회.
     *
     * @throws OrderProblem 주문이 없거나 다른 고객의 주문인 경우
     */
    @Transactional(readOnly = true)
    public Order getOrder(String username, long orderId) {
        requirePositiveId(orderId);
        return orderRepository.findOwnedOrder(orderId, orderRepository.findBuyerId(username));
    }

    /**
     * 로그인 고객의 주문을 ID 내림차순으로 조회.
     *
     * @param beforeOrderId 이 ID보다 작은 주문부터 조회, 다음 페이지에는 직전 페이지의 마지막 ID 사용
     * @param pageSize 한 번에 반환할 최대 주문 수
     * @return 최대 pageSize개의 자기 주문
     */
    @Transactional(readOnly = true)
    public List<Order> listOrders(String username, long beforeOrderId, int pageSize) {
        requirePositiveId(beforeOrderId);
        if (pageSize < 1 || pageSize > 100) throw OrderProblem.invalidInput();
        return orderRepository.findOwnedOrders(orderRepository.findBuyerId(username), beforeOrderId, pageSize);
    }

    /** 컨트롤러를 거치지 않은 호출에서도 0 이하 ID를 저장소에 전달하지 않도록 검사. */
    private static void requirePositiveId(long id) {
        if (id < 1) throw OrderProblem.invalidInput();
    }

    /**
     * 구매 키의 길이와 허용 문자 검사. 중복 여부 판정은 DB 유일성 제약에서 수행.
     *
     * @throws OrderProblem 키가 없거나 영문/숫자/밑줄/하이픈 1~128자 형식이 아닌 경우
     */
    private static void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || !IDEMPOTENCY_KEY_PATTERN.matcher(idempotencyKey).matches()) {
            throw new OrderProblem(OrderProblem.Reason.INVALID_REQUEST_KEY);
        }
    }
}

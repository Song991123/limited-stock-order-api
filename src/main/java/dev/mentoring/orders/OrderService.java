package dev.mentoring.orders;

import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문 접수와 취소 처리.
 *
 * <p>주문 정보, 재고 수량과 재고 증감 이력을 함께 저장. HTTP 응답은 컨트롤러에서 생성.
 */
@Service
public class OrderService {
    private static final Pattern IDEMPOTENCY_KEY_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    private final OrderRepository orderRepository;

    /**
     * 주문 SQL 저장소 연결.
     *
     * @param orderRepository 같은 DataSource를 사용하는 주문 SQL 저장소
     */
    public OrderService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    /**
     * 신규 주문 접수. 같은 구매를 다시 요청하면 기존 처리 결과 반환.
     *
     * <p>새 요청만 재고 차감. 메서드 실행을 마친 뒤 Spring이 커밋하고,
     * 저장 중 예외가 발생하면 요청 등록부터 모두 롤백.
     *
     * @param username 인증된 로그인 계정명
     * @param idempotencyKey 같은 구매의 재시도에서 유지할 키
     * @param orderRequest MVC 입력 검증을 통과한 상품과 주문 수량
     * @return 신규 접수, 기존 주문 또는 재고 부족 결과
     * @throws OrderProblem 잘못된 키, 없는 상품 또는 같은 키의 다른 입력
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public OrderPlacement placeOrder(String username, String idempotencyKey, CreateOrderRequest orderRequest) {
        validateIdempotencyKey(idempotencyKey);
        long buyerId = orderRepository.findBuyerId(username);
        orderRepository.findProduct(orderRequest.productId());

        // 구매 요청부터 등록해 같은 키로 재고를 두 번 차감하지 않도록 처리
        var registeredRequestId = orderRepository.tryRegisterPurchase(buyerId, idempotencyKey, orderRequest);
        if (registeredRequestId.isEmpty()) {
            return replayPurchase(buyerId, idempotencyKey, orderRequest);
        }
        return createOrder(registeredRequestId.orElseThrow(), buyerId, orderRequest);
    }

    /**
     * 저장된 구매 요청과 상품/수량을 비교한 뒤 기존 처리 결과 반환. 재고는 다시 차감하지 않음.
     *
     * @return 취소됐다면 취소 상태를 포함한 원래 주문, 또는 원래의 재고 부족 결과
     * @throws OrderProblem 같은 키에 다른 상품이나 수량을 보낸 경우
     */
    private OrderPlacement replayPurchase(long buyerId, String idempotencyKey, CreateOrderRequest orderRequest) {
        var recordedPurchase = orderRepository.findRecordedPurchase(buyerId, idempotencyKey);
        if (!recordedPurchase.hasSameOrderDetails(orderRequest)) {
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
    private OrderPlacement createOrder(long purchaseRequestId, long buyerId, CreateOrderRequest orderRequest) {
        if (!orderRepository.tryDecreaseStock(orderRequest.productId(), orderRequest.quantity())) {
            orderRepository.finishPurchase(purchaseRequestId, PurchaseOutcome.OUT_OF_STOCK);
            return new OrderPlacement.OutOfStock();
        }

        var createdOrder = orderRepository.insertOrder(purchaseRequestId, buyerId, orderRequest);
        orderRepository.appendMovement(createdOrder, MovementKind.DEBIT, -orderRequest.quantity());
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
    public OrderView cancelOrder(String username, long orderId) {
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
     * 취소할 주문을 바꾸지 못했을 때 이미 취소된 주문인지 확인.
     *
     * @throws OrderProblem 주문이 없거나 예상과 달리 취소 상태가 아닌 경우
     */
    private OrderView findCancelledOrder(long orderId, long buyerId) {
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
    public OrderView getOrder(String username, long orderId) {
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
    public List<OrderView> listOrders(String username, long beforeOrderId, int pageSize) {
        return orderRepository.findOwnedOrders(orderRepository.findBuyerId(username), beforeOrderId, pageSize);
    }

    /**
     * 판매 상품 목록 조회.
     *
     * @return 최대 100개 상품과 조회 시점의 판매 가능 수량
     */
    public List<ProductView> listProducts() {
        return orderRepository.findProducts();
    }

    /**
     * 상품과 현재 수량 조회. 조회한 수량을 주문 때까지 예약하지 않음.
     *
     * @throws OrderProblem 상품이 없는 경우
     */
    public ProductView getProduct(long productId) {
        return orderRepository.findProduct(productId);
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

package dev.mentoring.orders;

import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {
    private static final Pattern REQUEST_KEY = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    private final OrderRepository repository;

    public OrderService(OrderRepository repository) {
        this.repository = repository;
    }

    // 주문 한 건의 저장 경계. 정상 반환 뒤 프록시 커밋, 예외 발생 시 전체 롤백
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public OrderPlacement purchase(String username, String key, CreateOrderRequest input) {
        validateRequestKey(key);
        long buyerId = repository.findBuyerId(username);
        repository.findProduct(input.productId());

        // 같은 구매 키의 선등록. 새 요청만 재고 변경으로 진행
        var requestId = repository.tryRegisterPurchase(buyerId, key, input);
        if (requestId.isEmpty()) {
            return replayPurchase(buyerId, key, input);
        }
        return createOrder(requestId.orElseThrow(), buyerId, input);
    }

    private OrderPlacement replayPurchase(long buyerId, String key, CreateOrderRequest input) {
        var existing = repository.findRecordedPurchase(buyerId, key);
        if (!existing.matches(input)) {
            throw new OrderProblem(OrderProblem.Reason.IDEMPOTENCY_KEY_REUSED);
        }
        return switch (existing.outcome()) {
            case ACCEPTED -> new OrderPlacement.Replayed(repository.findOrderByRequest(existing.id(), buyerId));
            case OUT_OF_STOCK -> new OrderPlacement.OutOfStock();
        };
    }

    private OrderPlacement createOrder(long requestId, long buyerId, CreateOrderRequest input) {
        if (!repository.tryDecreaseStock(input.productId(), input.quantity())) {
            repository.finishPurchase(requestId, PurchaseOutcome.OUT_OF_STOCK);
            return new OrderPlacement.OutOfStock();
        }

        var order = repository.insertOrder(requestId, buyerId, input);
        repository.appendMovement(order, MovementKind.DEBIT, -input.quantity());
        repository.finishPurchase(requestId, PurchaseOutcome.ACCEPTED);
        return new OrderPlacement.Created(order);
    }

    // 취소 상태, 재고 복원, 복원 이력을 같은 트랜잭션에서 확정
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public OrderView cancel(String username, long id) {
        long buyerId = repository.findBuyerId(username);
        var changed = repository.tryCancelOwnedOrder(id, buyerId);
        if (changed.isEmpty()) {
            return findCancelledOrder(id, buyerId);
        }

        var order = changed.orElseThrow();
        repository.restoreStock(order);
        repository.appendMovement(order, MovementKind.RESTORE, order.quantity());
        return order;
    }

    private OrderView findCancelledOrder(long id, long buyerId) {
        var existing = repository.findOwnedOrder(id, buyerId);
        if (existing.status() != OrderStatus.CANCELLED) {
            throw OrderProblem.invariant();
        }
        return existing;
    }

    @Transactional(readOnly = true)
    public OrderView get(String username, long id) {
        return repository.findOwnedOrder(id, repository.findBuyerId(username));
    }

    @Transactional(readOnly = true)
    public List<OrderView> list(String username, long beforeId, int limit) {
        return repository.findOwnedOrders(repository.findBuyerId(username), beforeId, limit);
    }

    public List<ProductView> listProducts() {
        return repository.findProducts();
    }

    public ProductView getProduct(long id) {
        return repository.findProduct(id);
    }

    private static void validateRequestKey(String key) {
        if (key == null || !REQUEST_KEY.matcher(key).matches()) {
            throw new OrderProblem(OrderProblem.Reason.INVALID_REQUEST_KEY);
        }
    }
}

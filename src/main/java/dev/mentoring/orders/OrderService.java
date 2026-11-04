package dev.mentoring.orders;

import static dev.mentoring.orders.OrderModels.*;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {
    private final OrderRepository repository;
    public OrderService(OrderRepository repository) { this.repository = repository; }

    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public PurchaseResult purchase(String username, String key, Purchase input) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{1,128}")) throw new ApiException(400, "INVALID_REQUEST_KEY");
        long buyer = repository.buyer(username);
        repository.product(input.productId());
        var claimed = repository.claim(buyer, key, input);
        if (claimed.isEmpty()) {
            var existing = repository.existingRequest(buyer, key);
            if (existing.productId() != input.productId() || existing.quantity() != input.quantity()) {
                throw new ApiException(409, "IDEMPOTENCY_KEY_REUSED");
            }
            return switch (existing.outcome() == null ? "" : existing.outcome()) {
                case "ACCEPTED" -> new PurchaseResult(200, repository.byRequest(existing.id(), buyer), null);
                case "OUT_OF_STOCK" -> new PurchaseResult(409, null, "OUT_OF_STOCK");
                default -> throw ApiException.invariant();
            };
        }
        long request = claimed.orElseThrow();
        if (!repository.subtract(input.productId(), input.quantity())) {
            repository.finish(request, "OUT_OF_STOCK");
            return new PurchaseResult(409, null, "OUT_OF_STOCK");
        }
        var order = repository.insert(request, buyer, input);
        repository.movement(order, "DEBIT", -input.quantity());
        repository.finish(request, "ACCEPTED");
        return new PurchaseResult(201, order, null);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public Order cancel(String username, long id) {
        long buyer = repository.buyer(username);
        var changed = repository.markCancelled(id, buyer);
        if (changed.isEmpty()) {
            var existing = repository.owned(id, buyer);
            if (!existing.status().equals("CANCELLED")) throw ApiException.invariant();
            return existing;
        }
        var order = changed.orElseThrow();
        repository.restore(order);
        repository.movement(order, "RESTORE", order.quantity());
        return order;
    }

    @Transactional(readOnly = true)
    public Order get(String username, long id) { return repository.owned(id, repository.buyer(username)); }
    @Transactional(readOnly = true)
    public List<Order> list(String username, long before, int limit) {
        return repository.ownedList(repository.buyer(username), before, limit);
    }
}

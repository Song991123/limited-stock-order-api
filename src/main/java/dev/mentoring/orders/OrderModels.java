package dev.mentoring.orders;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;

public final class OrderModels {
    private OrderModels() {}

    public record Purchase(@NotNull @Positive Long productId, @NotNull @Positive Integer quantity) {}
    public record Product(long id, String name, int availableQuantity) {}
    public record Order(long id, long productId, int quantity, String status, Instant createdAt, Instant cancelledAt) {}
    public record PurchaseResult(int status, Order order, String error) {}
    record Request(long id, long productId, int quantity, String outcome) {}
}

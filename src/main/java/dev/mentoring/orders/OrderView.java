package dev.mentoring.orders;

import java.time.Instant;

public record OrderView(
        long id,
        long productId,
        int quantity,
        OrderStatus status,
        Instant createdAt,
        Instant cancelledAt) {}

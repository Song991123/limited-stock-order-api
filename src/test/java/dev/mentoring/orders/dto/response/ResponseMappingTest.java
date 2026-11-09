package dev.mentoring.orders.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mentoring.orders.domain.Order;
import dev.mentoring.orders.domain.OrderStatus;
import dev.mentoring.orders.domain.Product;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 내부 주문과 상품을 응답 DTO로 바꿀 때 값과 상태, 시각 유지 확인. */
class ResponseMappingTest {
    @Test
    void mapsAcceptedOrderWithoutACancellationTime() {
        Instant createdAt = Instant.parse("2026-11-09T10:00:00Z");
        var order = new Order(7, 3, 2, OrderStatus.ACCEPTED, createdAt, null);

        assertThat(OrderResponse.from(order))
                .isEqualTo(new OrderResponse(7, 3, 2, OrderStatus.ACCEPTED, createdAt, null));
    }

    @Test
    void mapsCancelledOrderWithoutChangingTimesOrQuantity() {
        Instant createdAt = Instant.parse("2026-11-09T10:00:00Z");
        Instant cancelledAt = Instant.parse("2026-11-09T10:01:00Z");
        var order = new Order(7, 3, 2, OrderStatus.CANCELLED, createdAt, cancelledAt);

        assertThat(OrderResponse.from(order))
                .isEqualTo(new OrderResponse(7, 3, 2, OrderStatus.CANCELLED, createdAt, cancelledAt));
    }

    @Test
    void mapsProductWithItsCurrentAvailableQuantity() {
        assertThat(ProductResponse.from(new Product(3, "Limited notebook", 8)))
                .isEqualTo(new ProductResponse(3, "Limited notebook", 8));
    }
}

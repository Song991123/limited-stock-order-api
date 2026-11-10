package dev.mentoring.orders.dto.response;

import dev.mentoring.orders.domain.Order;
import dev.mentoring.orders.domain.OrderStatus;
import java.time.Instant;

/**
 * 주문 접수, 조회와 취소의 HTTP 응답. 내부 주문 정보와 분리해 기존 JSON 필드 유지.
 *
 * @param id 주문 ID
 * @param productId 주문한 상품 ID
 * @param quantity 주문 수량
 * @param status 현재 접수 또는 취소 상태
 * @param createdAt 주문 생성 시각
 * @param cancelledAt 취소 시각, 접수 상태면 null
 */
public record OrderResponse(long id, long productId, int quantity, OrderStatus status,
        Instant createdAt, Instant cancelledAt) {
    /** 서비스가 반환한 주문을 외부에 보낼 응답으로 변환. */
    public static OrderResponse from(Order order) {
        return new OrderResponse(order.id(), order.productId(), order.quantity(), order.status(),
                order.createdAt(), order.cancelledAt());
    }
}

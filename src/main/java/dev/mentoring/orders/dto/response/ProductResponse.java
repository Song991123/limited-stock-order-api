package dev.mentoring.orders.dto.response;

import dev.mentoring.orders.domain.Product;

/**
 * 상품 조회 HTTP 응답. 조회한 수량을 주문 때까지 예약하지 않음.
 *
 * @param id 상품 ID
 * @param name 상품명
 * @param availableQuantity 조회 시점의 판매 가능 수량
 */
public record ProductResponse(long id, String name, int availableQuantity) {
    /** 조회한 상품을 외부에 보낼 응답으로 변환. */
    public static ProductResponse from(Product product) {
        return new ProductResponse(product.id(), product.name(), product.availableQuantity());
    }
}

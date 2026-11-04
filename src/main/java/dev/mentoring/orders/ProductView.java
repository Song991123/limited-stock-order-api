package dev.mentoring.orders;

/**
 * 상품 조회 응답. 조회한 수량은 주문 때까지 예약되지 않음.
 *
 * @param id 상품 ID
 * @param name 상품명
 * @param availableQuantity 조회 시점의 판매 가능 수량
 */
public record ProductView(long id, String name, int availableQuantity) {}

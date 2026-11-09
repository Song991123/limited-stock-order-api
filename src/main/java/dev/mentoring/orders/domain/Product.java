package dev.mentoring.orders.domain;

/**
 * 상품과 조회 시점의 판매 가능 수량. HTTP 응답은 ProductResponse로 따로 변환.
 *
 * @param id 상품 ID
 * @param name 상품명
 * @param availableQuantity 조회 시점의 판매 가능 수량
 */
public record Product(long id, String name, int availableQuantity) {}

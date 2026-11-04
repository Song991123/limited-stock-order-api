package dev.mentoring.orders;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 주문 요청에 담긴 상품 ID와 수량. 구매자는 로그인 계정으로 확인.
 *
 * @param productId 주문할 상품의 양의 정수 ID
 * @param quantity 주문할 수량, 1 이상 정수
 */
public record CreateOrderRequest(
        @NotNull @Positive Long productId,
        @NotNull @Positive Integer quantity) {}

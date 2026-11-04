package dev.mentoring.orders;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 주문 접수 JSON 입력. 구매자는 본문 대신 로그인 계정에서 확인.
 *
 * @param productId 주문할 상품의 양의 정수 ID
 * @param quantity 확보할 양의 정수 수량
 */
public record CreateOrderRequest(
        @NotNull @Positive Long productId,
        @NotNull @Positive Integer quantity) {}

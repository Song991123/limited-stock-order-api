package dev.mentoring.orders.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 주문 요청에 담긴 상품 ID와 수량. 구매자는 로그인 계정으로 확인.
 *
 * <p>누락된 값을 0과 구별하려고 Long과 Integer 사용. 필수값은 NotNull, 양수는 Positive로 각각 검사.
 *
 * @param productId 주문할 상품의 양의 정수 ID
 * @param quantity 주문할 수량, 1 이상 정수
 */
public record CreateOrderRequest(
        @NotNull @Positive Long productId,
        @NotNull @Positive Integer quantity) {}

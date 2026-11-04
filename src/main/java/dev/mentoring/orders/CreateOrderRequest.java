package dev.mentoring.orders;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

// 주문 입력. 구매자는 본문이 아니라 인증 정보에서 확인
public record CreateOrderRequest(
        @NotNull @Positive Long productId,
        @NotNull @Positive Integer quantity) {}

package dev.mentoring.orders;

import java.time.Instant;

/**
 * 주문 조회와 접수/취소 응답에 사용하는 주문 정보.
 *
 * @param id DB가 발급한 주문 ID, 재전송도 같은 주문에 연결
 * @param productId 주문 상품 ID
 * @param quantity 주문한 수량, 취소 시 이 수량만큼 재고 복구
 * @param status 현재 접수 또는 취소 상태
 * @param createdAt 주문 생성 시각
 * @param cancelledAt 취소 시각, 아직 취소하지 않았으면 null
 */
public record OrderView(
        long id,
        long productId,
        int quantity,
        OrderStatus status,
        Instant createdAt,
        Instant cancelledAt) {}

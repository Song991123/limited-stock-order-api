package dev.mentoring.orders.repository.model;

import dev.mentoring.orders.domain.PurchaseOutcome;

/**
 * DB에서 조회한 구매 요청의 상품, 수량과 최초 처리 결과. API 응답으로 직접 반환하지 않음.
 *
 * @param id order_requests의 요청 기록 ID, 주문 ID와 구분
 * @param productId 구매 대상 상품 ID
 * @param quantity 최초 요청 수량
 * @param outcome 최초 주문 성공 또는 재고 부족 결과, 주문 취소 후에도 유지
 */
public record RecordedPurchase(long id, long productId, int quantity, PurchaseOutcome outcome) {
    /** 같은 구매 키의 재전송이 상품과 수량도 유지했는지 판정. */
    public boolean hasSameOrderDetails(long requestedProductId, int requestedQuantity) {
        return productId == requestedProductId && quantity == requestedQuantity;
    }
}

package dev.mentoring.orders;

/**
 * 이미 처리한 구매 요청의 상품, 수량과 처리 결과.
 *
 * @param id order_requests의 요청 기록 ID, 주문 ID와 구분
 * @param productId 구매 대상 상품 ID
 * @param quantity 최초 요청 수량
 * @param outcome 최초 주문 성공 또는 재고 부족 결과, 주문 취소 후에도 유지
 */
record RecordedPurchase(long id, long productId, int quantity, PurchaseOutcome outcome) {
    /** 같은 구매 키의 재전송이 상품과 수량도 유지했는지 판정. */
    boolean hasSameOrderDetails(CreateOrderRequest orderRequest) {
        return productId == orderRequest.productId() && quantity == orderRequest.quantity();
    }
}

package dev.mentoring.orders;

// 최초 주문 요청의 결과. 주문 취소 뒤에도 같은 키의 접수 결과는 유지
enum PurchaseOutcome {
    ACCEPTED,
    OUT_OF_STOCK
}

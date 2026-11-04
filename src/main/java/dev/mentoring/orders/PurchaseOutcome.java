package dev.mentoring.orders;

/** 최초 구매 요청의 접수 결과. 재고 복구나 주문 취소 뒤에도 같은 키의 결과 유지. */
enum PurchaseOutcome {
    /** 최초 요청에서 주문 생성. 현재 주문은 이후 취소됐을 수도 있음. */
    ACCEPTED,
    /** 최초 요청에서 재고 부족. 재고 회복 뒤에도 같은 키에 같은 거절 반환. */
    OUT_OF_STOCK
}

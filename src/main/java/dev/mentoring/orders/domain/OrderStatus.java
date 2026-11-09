package dev.mentoring.orders.domain;

/** 주문 상태. 구매 요청의 최초 결과는 PurchaseOutcome에 따로 기록. */
public enum OrderStatus {
    /** 주문 접수 완료. 주문한 수량만큼 재고 차감. */
    ACCEPTED,
    /** 주문 취소와 재고 복구 완료. */
    CANCELLED
}

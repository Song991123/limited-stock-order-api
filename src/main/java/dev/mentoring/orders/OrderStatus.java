package dev.mentoring.orders;

/** 주문의 현재 상태. 최초 요청의 결과를 나타내는 PurchaseOutcome과 구분. */
public enum OrderStatus {
    /** 재고를 확보한 접수 주문. */
    ACCEPTED,
    /** 주문 취소와 수량 복원을 함께 확정한 상태. */
    CANCELLED
}

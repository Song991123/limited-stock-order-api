package dev.mentoring.orders;

/** 주문별 재고 증감 이력의 구분. 같은 주문과 종류의 이력은 최대 한 건. */
enum MovementKind {
    /** 주문 접수에 따른 음수 수량 변화. */
    DEBIT,
    /** 취소에 따른 양수 수량 복원. */
    RESTORE
}

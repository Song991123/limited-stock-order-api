package dev.mentoring.orders.domain;

/** 재고 증감 이력의 종류. 같은 주문에 같은 종류를 두 번 기록하지 않음. */
public enum MovementKind {
    /** 주문 수량만큼 재고 차감. 수량은 음수로 기록. */
    DEBIT,
    /** 취소 수량만큼 재고 복구. 수량은 양수로 기록. */
    RESTORE
}

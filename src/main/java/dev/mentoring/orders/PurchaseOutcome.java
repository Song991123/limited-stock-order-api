package dev.mentoring.orders;

/** 구매 요청의 최초 처리 결과. 나중에 재고가 늘거나 주문을 취소해도 이 기록은 바꾸지 않음. */
enum PurchaseOutcome {
    /** 주문 생성 성공. 이후 취소 여부는 주문 상태에서 확인. */
    ACCEPTED,
    /** 재고 부족으로 주문 실패. 나중에 재고가 늘어도 같은 키의 요청에는 같은 실패 결과 반환. */
    OUT_OF_STOCK
}

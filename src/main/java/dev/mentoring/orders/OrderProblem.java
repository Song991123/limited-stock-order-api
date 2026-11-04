package dev.mentoring.orders;

/** 주문 처리 중 발견한 입력/소유권/정합성 문제. HTTP 상태 변환은 예외 처리기에서 수행. */
public class OrderProblem extends RuntimeException {
    /** 외부 응답 코드와 연결하는 문제 구분. */
    public enum Reason {
        /** 상품 또는 자기 주문을 찾지 못함. */
        NOT_FOUND,
        /** 구매 키 누락 또는 형식 오류. */
        INVALID_REQUEST_KEY,
        /** 같은 구매 키에 다른 상품/수량을 전송. */
        IDEMPOTENCY_KEY_REUSED,
        /** 요청/주문/재고가 만족해야 할 저장 조건 위반. */
        INVARIANT_VIOLATION,
        /** 본문 없는 취소 API에 불필요한 본문 전송. */
        UNEXPECTED_BODY
    }

    private final Reason reason;

    /** 내부 자료나 SQL 대신 문제 구분만 예외에 보관. */
    public OrderProblem(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    /** 응답 코드로 사용할 문제 구분 반환. */
    public Reason reason() {
        return reason;
    }

    /** 상품 또는 자기 주문을 찾지 못했을 때 사용할 예외 생성. */
    static OrderProblem missing() {
        return new OrderProblem(Reason.NOT_FOUND);
    }

    /** 정상 처리로 숨기면 안 되는 저장 정합성 위반 예외 생성. */
    static OrderProblem invariant() {
        return new OrderProblem(Reason.INVARIANT_VIOLATION);
    }
}

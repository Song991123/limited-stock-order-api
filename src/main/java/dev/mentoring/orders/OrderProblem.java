package dev.mentoring.orders;

/** 주문 처리 중 발생한 오류. 잘못된 요청값, 다른 고객의 주문 접근, 저장 결과 불일치 등을 구분. */
public class OrderProblem extends RuntimeException {
    /** 응답에 사용할 오류 종류. */
    public enum Reason {
        /** 상품 또는 자기 주문을 찾지 못함. */
        NOT_FOUND,
        /** 구매 키 누락 또는 형식 오류. */
        INVALID_REQUEST_KEY,
        /** 같은 구매 키에 다른 상품/수량을 전송. */
        IDEMPOTENCY_KEY_REUSED,
        /** 구매 요청, 주문 또는 재고의 저장 결과가 맞지 않음. */
        INVARIANT_VIOLATION,
        /** 본문 없는 취소 API에 불필요한 본문 전송. */
        UNEXPECTED_BODY
    }

    private final Reason reason;

    /** 상세 입력값이나 SQL 대신 오류 종류만 저장. */
    public OrderProblem(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    /** 응답 코드에 사용할 오류 종류 반환. */
    public Reason reason() {
        return reason;
    }

    /** 상품 또는 자기 주문을 찾지 못했을 때 사용할 예외 생성. */
    static OrderProblem missing() {
        return new OrderProblem(Reason.NOT_FOUND);
    }

    /** 저장 결과가 맞지 않을 때 사용할 예외 생성. */
    static OrderProblem invariant() {
        return new OrderProblem(Reason.INVARIANT_VIOLATION);
    }
}

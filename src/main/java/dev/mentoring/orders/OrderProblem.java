package dev.mentoring.orders;

public class OrderProblem extends RuntimeException {
    public enum Reason {
        NOT_FOUND,
        INVALID_REQUEST_KEY,
        IDEMPOTENCY_KEY_REUSED,
        INVARIANT_VIOLATION,
        UNEXPECTED_BODY
    }

    private final Reason reason;

    public OrderProblem(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    static OrderProblem missing() {
        return new OrderProblem(Reason.NOT_FOUND);
    }

    static OrderProblem invariant() {
        return new OrderProblem(Reason.INVARIANT_VIOLATION);
    }
}

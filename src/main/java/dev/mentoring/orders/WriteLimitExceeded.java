package dev.mentoring.orders;

public class WriteLimitExceeded extends RuntimeException {
    private final int retryAfterSeconds;

    WriteLimitExceeded(int retryAfterSeconds) {
        super("Write admission limit exceeded");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int retryAfterSeconds() {
        return retryAfterSeconds;
    }
}

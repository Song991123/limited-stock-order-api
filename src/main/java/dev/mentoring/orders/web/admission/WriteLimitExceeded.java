package dev.mentoring.orders.web.admission;

/** 요청 수가 한도를 넘었을 때 발생하는 예외. 다시 요청할 때까지 기다릴 초도 저장. */
public class WriteLimitExceeded extends RuntimeException {
    private final int retryAfterSeconds;

    /**
     * 다시 요청할 때까지 기다릴 시간 저장.
     * @param retryAfterSeconds 기다릴 양의 정수 초, 이 시간이 지나도 성공을 보장하지는 않음
     */
    WriteLimitExceeded(int retryAfterSeconds) {
        super("Write admission limit exceeded");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /** Retry-After 응답 헤더에 사용할 초 단위 대기 시간 반환. */
    public int retryAfterSeconds() {
        return retryAfterSeconds;
    }
}

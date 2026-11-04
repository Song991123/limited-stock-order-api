package dev.mentoring.orders;

/** 쓰기 진입 한도 초과와 다음 시도까지 기다릴 초 단위 시간. */
public class WriteLimitExceeded extends RuntimeException {
    private final int retryAfterSeconds;

    /**
     * 한도 검사기가 계산한 재시도 대기 시간 보관.
     * @param retryAfterSeconds 다음 시도까지 기다릴 양의 정수 초, 성공 예약은 아님
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

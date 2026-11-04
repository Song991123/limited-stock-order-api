package dev.mentoring.orders;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 한 JVM의 쓰기 동시 진입 수와 고객별 요청 빈도 제한.
 *
 * <p>대기열을 늘리지 않고 한도 초과 요청 거절. 재고와 주문의 정합성은 별도 DB 규칙에서 보장.
 */
@Component
public class WriteAdmission {
    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final long NANOS_PER_MINUTE = 60 * NANOS_PER_SECOND;

    private final Semaphore writePermits;
    private final int maxRequestsPerCustomerPerMinute;
    private final ConcurrentHashMap<String, RateWindow> customerRateWindows = new ConcurrentHashMap<>();

    /**
     * 로컬 실행 정책값으로 쓰기 한도 구성.
     *
     * @param maxConcurrentWrites 동시에 실행할 최대 쓰기 호출 수
     * @param maxRequestsPerCustomerPerMinute 고객별 고정 1분 구간의 최대 호출 수
     * @throws IllegalArgumentException 한도가 양수가 아닌 경우
     */
    public WriteAdmission(@Value("${stock.admission.concurrent-writes}") int maxConcurrentWrites,
                          @Value("${stock.admission.per-customer-per-minute}") int maxRequestsPerCustomerPerMinute) {
        if (maxConcurrentWrites < 1 || maxRequestsPerCustomerPerMinute < 1) {
            throw new IllegalArgumentException("Admission limits must be positive");
        }
        this.writePermits = new Semaphore(maxConcurrentWrites);
        this.maxRequestsPerCustomerPerMinute = maxRequestsPerCustomerPerMinute;
    }

    /**
     * 동시 진입과 고객별 빈도를 확인한 뒤 쓰기 작업 실행.
     *
     * <p>작업의 성공/실패와 관계없이 finally에서 동시 진입 권한 반환.
     * 고객별 호출 수는 실패한 업무 요청도 포함하며, 같은 고객의 작업 자체를 직렬화하지 않음.
     *
     * @param username 인증된 계정명, 고객별 카운터 구분 기준
     * @param writeOperation 동기 실행할 주문 또는 취소 처리
     * @param <T> 쓰기 작업의 반환 타입
     * @return 쓰기 작업의 결과
     * @throws WriteLimitExceeded 동시 호출 또는 고객별 요청 수가 한도에 도달한 경우
     */
    public <T> T executeWrite(String username, Supplier<T> writeOperation) {
        if (!writePermits.tryAcquire()) throw new WriteLimitExceeded(1);
        try {
            var customerWindow = customerRateWindows.computeIfAbsent(username, ignored -> new RateWindow());
            customerWindow.checkAndCountRequest(System.nanoTime(), maxRequestsPerCustomerPerMinute);
            return writeOperation.get();
        } finally {
            writePermits.release();
        }
    }

    /**
     * 고객 한 명의 고정 1분 구간과 호출 수.
     *
     * <p>단조 시계를 구간으로 나눠 시스템 시각 변경의 영향 배제. 구간 경계의 순간 집중은 평탄화하지 않음.
     */
    static final class RateWindow {
        private long windowIndex = Long.MIN_VALUE;
        private int requestCount;

        /**
         * 구간 변경 확인, 한도 검사와 호출 수 증가를 같은 모니터에서 수행.
         *
         * @param currentTimeNanos System.nanoTime 기준 시각, 벽시계 시각이 아님
         * @param maxRequests 현재 구간에서 허용할 최대 호출 수
         * @throws WriteLimitExceeded 한도 소진, 다음 구간까지 남은 초를 올림해 전달
         */
        synchronized void checkAndCountRequest(long currentTimeNanos, int maxRequests) {
            long currentWindowIndex = Math.floorDiv(currentTimeNanos, NANOS_PER_MINUTE);
            if (currentWindowIndex != windowIndex) {
                windowIndex = currentWindowIndex;
                requestCount = 0;
            }
            if (requestCount >= maxRequests) {
                long remainingWindowNanos = NANOS_PER_MINUTE - Math.floorMod(currentTimeNanos, NANOS_PER_MINUTE);
                int retryAfterSeconds = (int) ((remainingWindowNanos + NANOS_PER_SECOND - 1) / NANOS_PER_SECOND);
                throw new WriteLimitExceeded(retryAfterSeconds);
            }
            requestCount++;
        }
    }
}

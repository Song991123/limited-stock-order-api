package dev.mentoring.orders.web.admission;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 주문 접수와 취소 요청의 동시 처리 수, 고객별 분당 요청 수 제한.
 *
 * <p>실행 중인 서버 프로그램 하나에만 적용. 한도를 넘으면 대기시키지 않고 거절하며, 재고 중복 차감은 DB에서 별도로 검사.
 */
@Component
public class WriteAdmission {
    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final long NANOS_PER_MINUTE = 60 * NANOS_PER_SECOND;

    private final Semaphore writePermits;
    private final int maxRequestsPerCustomerPerMinute;
    private final ConcurrentHashMap<String, RateWindow> customerRateWindows = new ConcurrentHashMap<>();

    /**
     * 설정값으로 동시 처리 수와 고객별 분당 요청 한도 초기화.
     *
     * @param maxConcurrentWrites 동시에 실행할 최대 쓰기 호출 수
     * @param maxRequestsPerCustomerPerMinute 1분마다 초기화하는 고객별 최대 요청 횟수
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
     * 요청 수를 확인한 뒤 주문 접수 또는 취소 실행.
     *
     * <p>성공/실패와 관계없이 finally에서 Semaphore.release() 호출.
     * 실행 중 실패한 요청도 분당 횟수에 포함. 같은 고객의 요청도 허용된 동시 처리 수 안에서 함께 실행 가능.
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
     * 고객별 1분 동안 받은 요청 수 기록.
     *
     * <p>System.nanoTime() 값을 1분 단위로 나눠 요청 수 초기화.
     * 컴퓨터 시각을 바꿔도 영향이 없지만, 초기화 직전과 직후에 요청이 몰리는 현상은 막지 못함.
     */
    static final class RateWindow {
        private long windowIndex = Long.MIN_VALUE;
        private int requestCount;

        /**
         * 요청 수를 초기화할 시각인지 확인하고 횟수 계산. synchronized로 검사 중 다른 스레드의 동시 변경 방지.
         *
         * @param currentTimeNanos System.nanoTime()으로 읽은 값
         * @param maxRequests 요청 수를 초기화하기 전까지 허용할 최대 횟수
         * @throws WriteLimitExceeded 한도 초과, 요청 수를 초기화할 때까지 기다릴 초도 함께 전달
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

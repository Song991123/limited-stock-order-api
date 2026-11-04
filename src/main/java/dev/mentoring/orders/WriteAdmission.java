package dev.mentoring.orders;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class WriteAdmission {
    private static final long SECOND_NANOS = 1_000_000_000L;
    private static final long MINUTE_NANOS = 60 * SECOND_NANOS;

    private final Semaphore slots;
    private final int perCustomerPerMinute;
    private final ConcurrentHashMap<String, RateWindow> windows = new ConcurrentHashMap<>();

    public WriteAdmission(@Value("${stock.admission.concurrent-writes}") int concurrent,
                          @Value("${stock.admission.per-customer-per-minute}") int perCustomerPerMinute) {
        if (concurrent < 1 || perCustomerPerMinute < 1) {
            throw new IllegalArgumentException("Admission limits must be positive");
        }
        this.slots = new Semaphore(concurrent);
        this.perCustomerPerMinute = perCustomerPerMinute;
    }

    // 한 서버 안의 진입 제한. 정합성은 DB 조건과 트랜잭션으로 별도 보장
    public <T> T execute(String customer, Supplier<T> operation) {
        if (!slots.tryAcquire()) throw new WriteLimitExceeded(1);
        try {
            var window = windows.computeIfAbsent(customer, ignored -> new RateWindow());
            window.reserve(System.nanoTime(), perCustomerPerMinute);
            return operation.get();
        } finally {
            slots.release();
        }
    }

    // 고객별 고정 1분 구간. 거절 시 다음 구간까지 남은 초를 응답에 전달
    static final class RateWindow {
        private long minute = Long.MIN_VALUE;
        private int count;

        synchronized void reserve(long nowNanos, int limit) {
            long currentMinute = Math.floorDiv(nowNanos, MINUTE_NANOS);
            if (currentMinute != minute) {
                minute = currentMinute;
                count = 0;
            }
            if (count >= limit) {
                long remaining = MINUTE_NANOS - Math.floorMod(nowNanos, MINUTE_NANOS);
                int seconds = (int) ((remaining + SECOND_NANOS - 1) / SECOND_NANOS);
                throw new WriteLimitExceeded(seconds);
            }
            count++;
        }
    }
}

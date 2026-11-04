package dev.mentoring.orders;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class WriteAdmission {
    private final Semaphore slots;
    private final int rate;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private static final class Window { long minute; int count; }

    public WriteAdmission(@Value("${stock.admission.concurrent-writes}") int concurrent,
                          @Value("${stock.admission.per-customer-per-minute}") int rate) {
        if (concurrent < 1 || rate < 1) throw new IllegalArgumentException("Admission limits must be positive");
        this.slots = new Semaphore(concurrent); this.rate = rate;
    }

    public <T> T execute(String customer, Supplier<T> operation) {
        if (!slots.tryAcquire()) throw new ApiException(429, "TOO_MANY_REQUESTS");
        try {
            var window = windows.computeIfAbsent(customer, ignored -> new Window());
            synchronized (window) {
                long minute = System.nanoTime() / 60_000_000_000L;
                if (minute != window.minute) { window.minute = minute; window.count = 0; }
                if (window.count >= rate) throw new ApiException(429, "TOO_MANY_REQUESTS");
                window.count++;
            }
            return operation.get();
        } finally { slots.release(); }
    }
}

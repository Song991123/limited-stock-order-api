package dev.mentoring.orders.web.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import dev.mentoring.orders.exception.ApiExceptionHandler;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class WriteAdmissionTest {
    @Test
    void rateIsPerCustomer() {
        var gate = new WriteAdmission(2, 1);
        assertThat(gate.executeOrder("alice", () -> 1)).isEqualTo(1);
        assertThatThrownBy(() -> gate.executeOrder("alice", () -> 2)).isInstanceOf(WriteLimitExceeded.class);
        assertThat(gate.executeOrder("bob", () -> 3)).isEqualTo(3);
    }

    @Test
    void failureReleasesSlot() {
        var gate = new WriteAdmission(1, 10);
        assertThatThrownBy(() -> gate.executeOrder("alice", () -> {
            throw new IllegalStateException();
        })).isInstanceOf(IllegalStateException.class);
        assertThat(gate.executeOrder("alice", () -> 1)).isEqualTo(1);
    }

    @Test
    void occupiedSlotRejectsRatherThanGrowingQueue() throws Exception {
        var gate = new WriteAdmission(1, 10);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        try (var pool = Executors.newSingleThreadExecutor()) {
            var first = pool.submit(() -> gate.executeOrder("alice", () -> {
                entered.countDown();
                waitForRelease(release);
                return 1;
            }));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> gate.executeOrder("bob", () -> 2))
                        .isInstanceOfSatisfying(WriteLimitExceeded.class,
                                error -> assertThat(error.retryAfterSeconds()).isEqualTo(1));
            } finally {
                release.countDown();
            }
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(1);
        }
    }

    @Test
    void rateRetryAfterUsesRemainingWindowRoundedUp() {
        var clock = new AtomicLong(TimeUnit.SECONDS.toNanos(5));
        var window = new WriteAdmission.RateWindow(clock::get);
        window.checkAndCountRequest(1);
        assertThatThrownBy(() -> window.checkAndCountRequest(1))
                .isInstanceOfSatisfying(WriteLimitExceeded.class,
                        error -> assertThat(error.retryAfterSeconds()).isEqualTo(55));
        clock.set(TimeUnit.SECONDS.toNanos(59) + 1);
        assertThatThrownBy(() -> window.checkAndCountRequest(1))
                .isInstanceOfSatisfying(WriteLimitExceeded.class,
                        error -> assertThat(error.retryAfterSeconds()).isEqualTo(1));
    }

    @Test
    void newMinuteResetsTheCustomerCount() {
        var clock = new AtomicLong();
        var window = new WriteAdmission.RateWindow(clock::get);
        window.checkAndCountRequest(1);
        clock.set(1);
        assertThatThrownBy(() -> window.checkAndCountRequest(1)).isInstanceOf(WriteLimitExceeded.class);
        clock.set(TimeUnit.MINUTES.toNanos(1));
        window.checkAndCountRequest(1);
    }

    @Test
    void negativeMonotonicTimeStillProducesPositiveRetryAfter() {
        var clock = new AtomicLong(-1);
        var window = new WriteAdmission.RateWindow(clock::get);
        window.checkAndCountRequest(1);
        assertThatThrownBy(() -> window.checkAndCountRequest(1))
                .isInstanceOfSatisfying(WriteLimitExceeded.class,
                        error -> assertThat(error.retryAfterSeconds()).isEqualTo(1));
        clock.set(0);
        window.checkAndCountRequest(1);
    }

    @Test
    void rejectionResponseCarriesTheCalculatedRetryAfter() {
        var response = new ApiExceptionHandler().handleWriteLimit(new WriteLimitExceeded(55));
        assertThat(response.getStatusCode().value()).isEqualTo(429);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("55");
        assertThat(response.getBody().code()).isEqualTo("TOO_MANY_REQUESTS");
    }

    @Test
    void orderAndCancellationHaveIndependentCustomerLimits() {
        var gate = new WriteAdmission(1, 1);
        assertThat(gate.executeOrder("alice", () -> "order")).isEqualTo("order");
        assertThatThrownBy(() -> gate.executeOrder("alice", () -> "retry")).isInstanceOf(WriteLimitExceeded.class);
        assertThat(gate.executeCancellation("alice", () -> "cancel")).isEqualTo("cancel");
        assertThatThrownBy(() -> gate.executeCancellation("alice", () -> "retry")).isInstanceOf(WriteLimitExceeded.class);
        assertThat(gate.executeOrder("bob", () -> "order")).isEqualTo("order");
        assertThat(gate.executeCancellation("bob", () -> "cancel")).isEqualTo("cancel");
    }

    @Test
    void failedCancellationCountsOnlyAgainstCancellationAndReleasesItsSlot() {
        var gate = new WriteAdmission(1, 1);
        assertThatThrownBy(() -> gate.executeCancellation("alice", () -> {
            throw new IllegalStateException("Cancellation failed");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(gate.executeOrder("alice", () -> 1)).isEqualTo(1);
        assertThatThrownBy(() -> gate.executeCancellation("alice", () -> 2)).isInstanceOf(WriteLimitExceeded.class);
        assertThat(gate.executeCancellation("bob", () -> 3)).isEqualTo(3);
    }

    @Test
    void orderAndCancellationShareTheSameConcurrentWriteSlots() throws Exception {
        for (boolean orderFirst : new boolean[]{true, false}) {
            var gate = new WriteAdmission(1, 10);
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            java.util.function.Supplier<Integer> holdSlot = () -> {
                entered.countDown();
                waitForRelease(release);
                return 1;
            };
            try (var pool = Executors.newSingleThreadExecutor()) {
                var first = pool.submit(() -> orderFirst ? gate.executeOrder("alice", holdSlot)
                        : gate.executeCancellation("alice", holdSlot));
                try {
                    assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                    assertThatThrownBy(() -> {
                        if (orderFirst) gate.executeCancellation("bob", () -> 2);
                        else gate.executeOrder("bob", () -> 2);
                    }).isInstanceOfSatisfying(WriteLimitExceeded.class,
                                    error -> assertThat(error.retryAfterSeconds()).isEqualTo(1));
                } finally {
                    release.countDown();
                }
                assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(1);
                assertThat(orderFirst ? gate.executeCancellation("bob", () -> 2)
                        : gate.executeOrder("bob", () -> 2)).isEqualTo(2);
            }
        }
    }

    @Test
    void concurrentRateChecksDoNotExceedTheWindowLimit() throws Exception {
        var window = new WriteAdmission.RateWindow(() -> TimeUnit.SECONDS.toNanos(5));
        var ready = new CountDownLatch(20);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(20)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int index = 0; index < 20; index++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    waitForRelease(start);
                    try {
                        window.checkAndCountRequest(5);
                        return true;
                    } catch (WriteLimitExceeded rejected) {
                        return false;
                    }
                }));
            }
            try {
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                start.countDown();
            }
            int allowed = 0;
            for (var future : futures) if (future.get(5, TimeUnit.SECONDS)) allowed++;
            assertThat(allowed).isEqualTo(5);
        }
    }

    @Test
    void delayedTimeSampleMustNotEraseTheNewMinuteCount() throws Exception {
        var clock = new AtomicLong(TimeUnit.MINUTES.toNanos(1) - 1);
        var reference = new AtomicReference<WriteAdmission.RateWindow>();
        var window = new WriteAdmission.RateWindow(() -> {
            assertThat(Thread.holdsLock(reference.get())).as("시각 읽기는 고객별 잠금 안에서 실행").isTrue();
            return clock.get();
        });
        reference.set(window);
        var waitingThread = new AtomicReference<Thread>();
        var entered = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            java.util.concurrent.Future<Boolean> delayed;
            synchronized (window) {
                delayed = pool.submit(() -> {
                    waitingThread.set(Thread.currentThread());
                    entered.countDown();
                    try {
                        window.checkAndCountRequest(1);
                        return true;
                    } catch (WriteLimitExceeded rejected) {
                        return false;
                    }
                });
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                await().atMost(java.time.Duration.ofSeconds(2))
                        .until(() -> waitingThread.get().getState() == Thread.State.BLOCKED);
                clock.set(TimeUnit.MINUTES.toNanos(1));
                window.checkAndCountRequest(1);
            }
            assertThat(delayed.get(5, TimeUnit.SECONDS)).isFalse();
            assertThatThrownBy(() -> window.checkAndCountRequest(1)).isInstanceOf(WriteLimitExceeded.class);
        }
    }

    private static void waitForRelease(CountDownLatch release) {
        try {
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Release timeout");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
        }
    }
}

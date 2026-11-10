package dev.mentoring.orders.web.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mentoring.orders.exception.ApiExceptionHandler;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
        var window = new WriteAdmission.RateWindow();
        window.checkAndCountRequest(TimeUnit.SECONDS.toNanos(5), 1);
        assertThatThrownBy(() -> window.checkAndCountRequest(TimeUnit.SECONDS.toNanos(5), 1))
                .isInstanceOfSatisfying(WriteLimitExceeded.class,
                        error -> assertThat(error.retryAfterSeconds()).isEqualTo(55));
        assertThatThrownBy(() -> window.checkAndCountRequest(TimeUnit.SECONDS.toNanos(59) + 1, 1))
                .isInstanceOfSatisfying(WriteLimitExceeded.class,
                        error -> assertThat(error.retryAfterSeconds()).isEqualTo(1));
    }

    @Test
    void newMinuteResetsTheCustomerCount() {
        var window = new WriteAdmission.RateWindow();
        window.checkAndCountRequest(0, 1);
        assertThatThrownBy(() -> window.checkAndCountRequest(1, 1)).isInstanceOf(WriteLimitExceeded.class);
        window.checkAndCountRequest(TimeUnit.MINUTES.toNanos(1), 1);
    }

    @Test
    void negativeMonotonicTimeStillProducesPositiveRetryAfter() {
        var window = new WriteAdmission.RateWindow();
        window.checkAndCountRequest(-1, 1);
        assertThatThrownBy(() -> window.checkAndCountRequest(-1, 1))
                .isInstanceOfSatisfying(WriteLimitExceeded.class,
                        error -> assertThat(error.retryAfterSeconds()).isEqualTo(1));
        window.checkAndCountRequest(0, 1);
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
        var window = new WriteAdmission.RateWindow();
        var ready = new CountDownLatch(20);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(20)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int index = 0; index < 20; index++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    waitForRelease(start);
                    try {
                        window.checkAndCountRequest(TimeUnit.SECONDS.toNanos(5), 5);
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

    private static void waitForRelease(CountDownLatch release) {
        try {
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Release timeout");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
        }
    }
}

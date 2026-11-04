package dev.mentoring.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class WriteAdmissionTest {
    @Test
    void rateIsPerCustomer() {
        var gate = new WriteAdmission(2, 1);
        assertThat(gate.executeWrite("alice", () -> 1)).isEqualTo(1);
        assertThatThrownBy(() -> gate.executeWrite("alice", () -> 2)).isInstanceOf(WriteLimitExceeded.class);
        assertThat(gate.executeWrite("bob", () -> 3)).isEqualTo(3);
    }

    @Test
    void failureReleasesSlot() {
        var gate = new WriteAdmission(1, 10);
        assertThatThrownBy(() -> gate.executeWrite("alice", () -> {
            throw new IllegalStateException();
        })).isInstanceOf(IllegalStateException.class);
        assertThat(gate.executeWrite("alice", () -> 1)).isEqualTo(1);
    }

    @Test
    void occupiedSlotRejectsRatherThanGrowingQueue() throws Exception {
        var gate = new WriteAdmission(1, 10);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        try (var pool = Executors.newSingleThreadExecutor()) {
            var first = pool.submit(() -> gate.executeWrite("alice", () -> {
                entered.countDown();
                waitForRelease(release);
                return 1;
            }));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> gate.executeWrite("bob", () -> 2))
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

    private static void waitForRelease(CountDownLatch release) {
        try {
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Release timeout");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
        }
    }
}

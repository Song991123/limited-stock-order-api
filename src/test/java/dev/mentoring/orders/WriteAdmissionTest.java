package dev.mentoring.orders;

import static org.assertj.core.api.Assertions.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class WriteAdmissionTest {
    @Test void rateIsPerCustomer() {
        var gate=new WriteAdmission(2,1);
        assertThat(gate.execute("alice",()->1)).isEqualTo(1);
        assertThatThrownBy(()->gate.execute("alice",()->2)).isInstanceOf(ApiException.class);
        assertThat(gate.execute("bob",()->3)).isEqualTo(3);
    }
    @Test void failureReleasesSlot() {
        var gate=new WriteAdmission(1,10);
        assertThatThrownBy(()->gate.execute("alice",()->{throw new IllegalStateException();})).isInstanceOf(IllegalStateException.class);
        assertThat(gate.execute("alice",()->1)).isEqualTo(1);
    }
    @Test void occupiedSlotRejectsRatherThanGrowingQueue() throws Exception {
        var gate=new WriteAdmission(1,10); var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        try(var pool=Executors.newSingleThreadExecutor()) {
            var first=pool.submit(()->gate.execute("alice",()->{ entered.countDown(); try { if(!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException(); } catch(InterruptedException e) { throw new IllegalStateException(e); } return 1; }));
            try { assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue(); assertThatThrownBy(()->gate.execute("bob",()->2)).isInstanceOf(ApiException.class); }
            finally { release.countDown(); }
            assertThat(first.get(5,TimeUnit.SECONDS)).isEqualTo(1);
        }
    }
}

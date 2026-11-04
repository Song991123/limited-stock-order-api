package dev.mentoring.orders;

import java.util.Objects;

// 서비스의 판단 결과. HTTP 상태 코드는 컨트롤러에서 결정
public sealed interface OrderPlacement {
    record Created(OrderView order) implements OrderPlacement {
        public Created { Objects.requireNonNull(order); }
    }

    record Replayed(OrderView order) implements OrderPlacement {
        public Replayed { Objects.requireNonNull(order); }
    }

    record OutOfStock() implements OrderPlacement {}
}

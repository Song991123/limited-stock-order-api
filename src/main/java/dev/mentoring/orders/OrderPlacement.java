package dev.mentoring.orders;

import java.util.Objects;

/**
 * 주문 서비스의 접수 판단 결과.
 *
 * <p>신규/재전송에는 주문을 포함하고 재고 부족에는 포함하지 않도록 타입으로 구분.
 * HTTP 상태는 이 타입 밖의 컨트롤러에서 결정.
 */
public sealed interface OrderPlacement {
    /**
     * 이번 요청에서 새 주문 생성.
     * @param order 신규 접수 주문
     */
    record Created(OrderView order) implements OrderPlacement {
        /** 주문 없는 신규 접수 결과 생성 방지. */
        public Created {
            Objects.requireNonNull(order);
        }
    }

    /**
     * 같은 구매의 기존 주문 반환.
     * @param order 원래 주문의 현재 상태, 취소됐다면 CANCELLED 포함
     */
    record Replayed(OrderView order) implements OrderPlacement {
        /** 주문 없는 재전송 결과 생성 방지. */
        public Replayed {
            Objects.requireNonNull(order);
        }
    }

    /** 재고를 확보하지 못한 확정 결과. 새 주문과 추가 차감 없음. */
    record OutOfStock() implements OrderPlacement {}
}

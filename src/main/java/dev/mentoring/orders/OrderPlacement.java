package dev.mentoring.orders;

import java.util.Objects;

/**
 * 주문 접수 결과. 새 주문, 기존 주문, 재고 부족으로 구분.
 *
 * <p>새 주문과 기존 주문에는 주문 정보를 포함. 재고 부족에는 주문 정보가 없음.
 * HTTP 상태 코드는 컨트롤러에서 추가.
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

    /** 재고 부족으로 주문 실패. 주문을 만들거나 재고를 차감하지 않음. */
    record OutOfStock() implements OrderPlacement {}
}

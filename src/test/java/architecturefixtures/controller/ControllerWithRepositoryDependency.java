package architecturefixtures.controller;

import dev.mentoring.orders.domain.Order;
import dev.mentoring.orders.repository.OrderRepository;

/** 저장소를 직접 사용하는 컨트롤러 위반 예제. 제품 클래스가 아닌 구조 검사 전용. */
public class ControllerWithRepositoryDependency {
    public Order readOrder(OrderRepository repository) {
        return repository.findOwnedOrder(1, 1);
    }
}

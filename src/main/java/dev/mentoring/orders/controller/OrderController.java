package dev.mentoring.orders.controller;

import dev.mentoring.orders.domain.OrderPlacement;
import dev.mentoring.orders.dto.request.CreateOrderRequest;
import dev.mentoring.orders.dto.response.OrderResponse;
import dev.mentoring.orders.exception.OrderProblem;
import dev.mentoring.orders.service.OrderService;
import dev.mentoring.orders.web.admission.WriteAdmission;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.io.IOException;
import java.net.URI;
import java.security.Principal;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 주문 접수, 내 주문 조회와 취소 요청 처리.
 *
 * <p>로그인 고객과 요청값을 서비스로 전달하고 처리 결과를 HTTP 응답으로 반환.
 * RestController로 Java 반환값을 JSON 본문에 연결. DB 저장은 주입받은 서비스에 위임.
 */
@RestController
public class OrderController {
    private final OrderService orderService;
    private final WriteAdmission writeAdmission;

    /** 주문 처리 서비스와 요청 수 제한 기능 연결. */
    public OrderController(OrderService orderService, WriteAdmission writeAdmission) {
        this.orderService = orderService;
        this.writeAdmission = writeAdmission;
    }

    /**
     * 구매 키와 주문 입력을 받아 신규 접수 또는 기존 결과 반환.
     *
     * <p>RequestBody로 JSON을 변환한 뒤 Valid로 DTO의 필수값과 양수 조건 검사.
     * 구매 키는 서비스에서 형식과 재사용 여부를 확인해 직접 호출에도 같은 규칙 적용.
     *
     * @param principal Spring Security가 확인한 로그인 고객
     * @param idempotencyKey 같은 구매의 재시도에서 유지할 Idempotency-Key 헤더
     * @param orderRequest 상품 ID와 양의 정수 수량
     * @return 신규 201, 같은 구매 재전송 200, 재고 부족 409
     */
    @PostMapping("/orders")
    public ResponseEntity<OrderResponse> placeOrder(Principal principal,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest orderRequest) {
        return writeAdmission.executeOrder(principal.getName(),
                () -> toOrderResponse(orderService.placeOrder(principal.getName(), idempotencyKey,
                        orderRequest.productId(), orderRequest.quantity())));
    }

    /**
     * 내 주문 목록 조회.
     *
     * @param beforeOrderId beforeId 쿼리 값, 이 ID보다 작은 주문부터 조회
     * @param pageSize limit 쿼리의 최대 반환 개수, 허용 범위 1~100
     */
    @GetMapping("/orders")
    public List<OrderResponse> listOrders(Principal principal,
            @RequestParam(name = "beforeId", defaultValue = "9223372036854775807") @Min(1) long beforeOrderId,
            @RequestParam(name = "limit", defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        return orderService.listOrders(principal.getName(), beforeOrderId, pageSize)
                .stream().map(OrderResponse::from).toList();
    }

    /** 자기 주문 한 건 조회. 다른 고객의 주문은 404로 처리. */
    @GetMapping("/orders/{id}")
    public OrderResponse getOrder(Principal principal, @PathVariable("id") @Min(1) long orderId) {
        return OrderResponse.from(orderService.getOrder(principal.getName(), orderId));
    }

    /**
     * 본문 없는 자기 주문 취소 요청 처리.
     *
     * <p>MVC가 폼 본문을 바꿔 읽을 수 있어 서버가 받은 본문을 직접 확인.
     * 재고에 더할 수량은 요청에서 받지 않고 저장된 주문 수량 사용.
     *
     * @param orderId 취소할 주문 ID
     * @param request 크기 검사를 통과한 원본 요청
     * @return 취소된 자기 주문, 다시 요청해도 재고를 더하지 않고 같은 주문 반환
     * @throws IOException 요청 본문을 읽지 못한 경우
     * @throws OrderProblem 불필요한 본문이 있거나 자기 주문이 없는 경우
     */
    @PostMapping("/orders/{id}/cancel")
    public OrderResponse cancelOrder(Principal principal, @PathVariable("id") @Min(1) long orderId,
            HttpServletRequest request) throws IOException {
        if (request.getInputStream().read() != -1) {
            throw new OrderProblem(OrderProblem.Reason.UNEXPECTED_BODY);
        }
        return writeAdmission.executeCancellation(principal.getName(),
                () -> OrderResponse.from(orderService.cancelOrder(principal.getName(), orderId)));
    }

    /**
     * 주문 처리 결과로 HTTP 응답 생성. DB 커밋 성공 후 실행.
     *
     * <p>재고 부족은 DB 커밋 후 예외로 전달해 공통 오류 처리기에서 409로 응답.
     */
    private ResponseEntity<OrderResponse> toOrderResponse(OrderPlacement placementResult) {
        return switch (placementResult) {
            case OrderPlacement.Created created -> ResponseEntity
                    .created(URI.create("/orders/" + created.order().id())).body(OrderResponse.from(created.order()));
            case OrderPlacement.Replayed replayed -> ResponseEntity.ok(OrderResponse.from(replayed.order()));
            case OrderPlacement.OutOfStock ignored -> throw new OrderProblem(OrderProblem.Reason.OUT_OF_STOCK);
        };
    }
}

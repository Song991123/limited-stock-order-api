package dev.mentoring.orders;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.io.IOException;
import java.net.URI;
import java.security.Principal;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 상품 조회, 주문 접수와 취소의 HTTP 진입점.
 *
 * <p>인증 계정과 검증한 입력을 서비스로 전달하고, 커밋된 업무 결과를 HTTP 상태와 본문으로 변환.
 */
@RestController
public class OrderController {
    private final OrderService orderService;
    private final WriteAdmission writeAdmission;

    /** 주문 처리 서비스와 쓰기 진입 제한 연결. */
    public OrderController(OrderService orderService, WriteAdmission writeAdmission) {
        this.orderService = orderService;
        this.writeAdmission = writeAdmission;
    }

    /** 변경 요청의 헤더에 사용할 현재 세션의 CSRF 토큰 반환. */
    @GetMapping("/csrf")
    public CsrfToken getCsrfToken(CsrfToken csrfToken) {
        return csrfToken;
    }

    /** 상품 목록과 판매 가능 수량 조회. */
    @GetMapping("/products")
    public List<ProductView> listProducts() {
        return orderService.listProducts();
    }

    /** 경로의 상품 ID로 상품과 현재 수량 조회. */
    @GetMapping("/products/{id}")
    public ProductView getProduct(@PathVariable("id") @Min(1) long productId) {
        return orderService.getProduct(productId);
    }

    /**
     * 구매 키와 주문 입력을 받아 신규 접수 또는 기존 결과 반환.
     *
     * @param principal Spring Security가 확인한 로그인 고객
     * @param idempotencyKey 같은 구매의 재시도에서 유지할 Idempotency-Key 헤더
     * @param orderRequest 상품 ID와 양의 정수 수량
     * @return 신규 201, 같은 구매 재전송 200, 재고 부족 409
     */
    @PostMapping("/orders")
    public ResponseEntity<?> placeOrder(Principal principal,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest orderRequest) {
        return writeAdmission.executeWrite(principal.getName(),
                () -> toOrderResponse(orderService.placeOrder(principal.getName(), idempotencyKey, orderRequest)));
    }

    /**
     * 자기 주문의 목록 조회. Java 이름과 외부 쿼리 이름을 명시적으로 구분.
     *
     * @param beforeOrderId beforeId 쿼리의 제외 상한, 기본값은 long 최댓값
     * @param pageSize limit 쿼리의 최대 반환 개수, 허용 범위 1~100
     */
    @GetMapping("/orders")
    public List<OrderView> listOrders(Principal principal,
            @RequestParam(name = "beforeId", defaultValue = "9223372036854775807") @Min(1) long beforeOrderId,
            @RequestParam(name = "limit", defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        return orderService.listOrders(principal.getName(), beforeOrderId, pageSize);
    }

    /** 자기 주문 한 건 조회. 다른 고객의 주문은 404로 처리. */
    @GetMapping("/orders/{id}")
    public OrderView getOrder(Principal principal, @PathVariable("id") @Min(1) long orderId) {
        return orderService.getOrder(principal.getName(), orderId);
    }

    /**
     * 본문 없는 자기 주문 취소 요청 처리.
     *
     * <p>MVC 폼 변환은 본문을 다시 구성할 수 있어, 크기 검사를 마친 원본 스트림으로 빈 본문 판정.
     * 복원 수량은 요청에서 받지 않고 저장된 주문에서 사용.
     *
     * @param orderId 취소할 주문 ID
     * @param request 크기 검사를 통과한 원본 요청
     * @return 취소된 자기 주문, 재요청도 추가 복원 없이 같은 주문 반환
     * @throws IOException 요청 본문을 읽지 못한 경우
     * @throws OrderProblem 불필요한 본문이 있거나 자기 주문이 없는 경우
     */
    @PostMapping("/orders/{id}/cancel")
    public OrderView cancelOrder(Principal principal, @PathVariable("id") @Min(1) long orderId,
            HttpServletRequest request) throws IOException {
        if (request.getInputStream().read() != -1) {
            throw new OrderProblem(OrderProblem.Reason.UNEXPECTED_BODY);
        }
        return writeAdmission.executeWrite(principal.getName(),
                () -> orderService.cancelOrder(principal.getName(), orderId));
    }

    /**
     * 서비스 프록시의 커밋 성공 뒤 업무 결과를 HTTP 응답으로 변환.
     *
     * <p>재고 부족을 서비스 예외로 던지지 않아 해당 키의 거절 기록도 유지.
     */
    private ResponseEntity<?> toOrderResponse(OrderPlacement placementResult) {
        return switch (placementResult) {
            case OrderPlacement.Created created -> ResponseEntity
                    .created(URI.create("/orders/" + created.order().id())).body(created.order());
            case OrderPlacement.Replayed replayed -> ResponseEntity.ok(replayed.order());
            case OrderPlacement.OutOfStock ignored -> ResponseEntity.status(409)
                    .body(new ApiExceptionHandler.ErrorBody("OUT_OF_STOCK"));
        };
    }
}

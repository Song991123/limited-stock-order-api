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

@RestController
public class OrderController {
    private final OrderService service;
    private final WriteAdmission admission;

    public OrderController(OrderService service, WriteAdmission admission) {
        this.service = service;
        this.admission = admission;
    }

    @GetMapping("/csrf")
    public CsrfToken csrf(CsrfToken token) {
        return token;
    }

    @GetMapping("/products")
    public List<ProductView> products() {
        return service.listProducts();
    }

    @GetMapping("/products/{id}")
    public ProductView product(@PathVariable @Min(1) long id) {
        return service.getProduct(id);
    }

    @PostMapping("/orders")
    public ResponseEntity<?> purchase(Principal principal,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody CreateOrderRequest input) {
        return admission.execute(principal.getName(),
                () -> purchaseResponse(service.purchase(principal.getName(), key, input)));
    }

    @GetMapping("/orders")
    public List<OrderView> orders(Principal principal,
            @RequestParam(defaultValue = "9223372036854775807") @Min(1) long beforeId,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return service.list(principal.getName(), beforeId, limit);
    }

    @GetMapping("/orders/{id}")
    public OrderView get(Principal principal, @PathVariable @Min(1) long id) {
        return service.get(principal.getName(), id);
    }

    @PostMapping("/orders/{id}/cancel")
    public OrderView cancel(Principal principal, @PathVariable @Min(1) long id,
            HttpServletRequest request) throws IOException {
        // 폼 변환 결과가 아니라 크기 검사를 마친 원본 본문의 존재 여부 확인
        if (request.getInputStream().read() != -1) {
            throw new OrderProblem(OrderProblem.Reason.UNEXPECTED_BODY);
        }
        return admission.execute(principal.getName(), () -> service.cancel(principal.getName(), id));
    }

    // DB 커밋을 마친 서비스 결과를 HTTP 응답으로 변환
    private ResponseEntity<?> purchaseResponse(OrderPlacement result) {
        return switch (result) {
            case OrderPlacement.Created created -> ResponseEntity
                    .created(URI.create("/orders/" + created.order().id())).body(created.order());
            case OrderPlacement.Replayed replayed -> ResponseEntity.ok(replayed.order());
            case OrderPlacement.OutOfStock ignored -> ResponseEntity.status(409)
                    .body(new ApiExceptionHandler.ErrorBody("OUT_OF_STOCK"));
        };
    }
}

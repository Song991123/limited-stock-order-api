package dev.mentoring.orders;

import static dev.mentoring.orders.OrderModels.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.security.Principal;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
public class OrderController {
    private final OrderRepository products;
    private final OrderService service;
    private final WriteAdmission admission;
    public OrderController(OrderRepository products, OrderService service, WriteAdmission admission) {
        this.products = products; this.service = service; this.admission = admission;
    }

    @GetMapping("/csrf") public CsrfToken csrf(CsrfToken token) { return token; }
    @GetMapping("/products") public List<Product> products() { return products.products(); }
    @GetMapping("/products/{id}") public Product product(@PathVariable @Min(1) long id) { return products.product(id); }

    @PostMapping("/orders")
    public ResponseEntity<?> purchase(Principal principal, @RequestHeader(name="Idempotency-Key", required=false) String key,
                                      @Valid @RequestBody Purchase input) {
        return admission.execute(principal.getName(), () -> {
            var result = service.purchase(principal.getName(), key, input);
            if (result.error() != null) return ResponseEntity.status(result.status()).body(new ApiExceptionHandler.ErrorBody(result.error()));
            if (result.status() == 201) return ResponseEntity.created(URI.create("/orders/" + result.order().id())).body(result.order());
            return ResponseEntity.ok(result.order());
        });
    }

    @GetMapping("/orders")
    public List<Order> orders(Principal principal, @RequestParam(defaultValue="9223372036854775807") @Min(1) long beforeId,
                              @RequestParam(defaultValue="20") @Min(1) @Max(100) int limit) {
        return service.list(principal.getName(), beforeId, limit);
    }
    @GetMapping("/orders/{id}")
    public Order get(Principal principal, @PathVariable @Min(1) long id) { return service.get(principal.getName(), id); }
    @PostMapping("/orders/{id}/cancel")
    public Order cancel(Principal principal, @PathVariable @Min(1) long id, @RequestBody(required=false) byte[] body) {
        if (body != null && body.length > 0) throw new ApiException(400, "UNEXPECTED_BODY");
        return admission.execute(principal.getName(), () -> service.cancel(principal.getName(), id));
    }
}

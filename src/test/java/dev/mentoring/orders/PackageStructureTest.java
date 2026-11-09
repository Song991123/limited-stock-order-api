package dev.mentoring.orders;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mentoring.orders.controller.CsrfController;
import dev.mentoring.orders.controller.OrderController;
import dev.mentoring.orders.controller.ProductController;
import dev.mentoring.orders.domain.Order;
import dev.mentoring.orders.domain.OrderPlacement;
import dev.mentoring.orders.domain.Product;
import dev.mentoring.orders.dto.request.CreateOrderRequest;
import dev.mentoring.orders.dto.response.CsrfResponse;
import dev.mentoring.orders.dto.response.OrderResponse;
import dev.mentoring.orders.dto.response.ProductResponse;
import dev.mentoring.orders.repository.OrderRepository;
import dev.mentoring.orders.repository.ProductRepository;
import dev.mentoring.orders.repository.model.RecordedPurchase;
import dev.mentoring.orders.service.OrderService;
import dev.mentoring.orders.service.ProductService;
import jakarta.validation.Valid;
import java.lang.reflect.Type;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.web.csrf.CsrfToken;

/** 서비스와 저장소의 자료형에 HTTP DTO가 섞이는 회귀 확인. */
class PackageStructureTest {
    @Test
    void internalTypesDoNotExposeHttpDtosInTheirSignatures() {
        var internalTypes = List.of(OrderService.class, ProductService.class,
                OrderRepository.class, ProductRepository.class, RecordedPurchase.class,
                Order.class, Product.class, OrderPlacement.class,
                OrderPlacement.Created.class, OrderPlacement.Replayed.class, OrderPlacement.OutOfStock.class);

        for (Class<?> type : internalTypes) {
            var signatures = new ArrayList<Type>();
            for (var field : type.getDeclaredFields()) signatures.add(field.getGenericType());
            for (var constructor : type.getDeclaredConstructors()) {
                signatures.addAll(List.of(constructor.getGenericParameterTypes()));
            }
            for (var method : type.getDeclaredMethods()) {
                signatures.add(method.getGenericReturnType());
                signatures.addAll(List.of(method.getGenericParameterTypes()));
            }
            assertThat(signatures).as(type.getName()).allSatisfy(signature ->
                    assertThat(signature.getTypeName()).doesNotContain(
                            "dev.mentoring.orders.dto.", "dev.mentoring.orders.controller.",
                            "org.springframework.http.", "jakarta.servlet."));
        }
    }

    @Test
    void controllersKeepRequestValidationAndReturnResponseDtos() throws Exception {
        var create = OrderController.class.getDeclaredMethod("placeOrder",
                Principal.class, String.class, CreateOrderRequest.class);
        assertThat(create.getParameters()[2].isAnnotationPresent(Valid.class)).isTrue();
        assertThat(create.getGenericReturnType().getTypeName()).contains(OrderResponse.class.getName())
                .doesNotContain("?");
        assertThat(OrderController.class.getDeclaredMethod("getOrder", Principal.class, long.class).getReturnType())
                .isEqualTo(OrderResponse.class);
        assertThat(ProductController.class.getDeclaredMethod("getProduct", long.class).getReturnType())
                .isEqualTo(ProductResponse.class);
        assertThat(CsrfController.class.getDeclaredMethod("getCsrfToken", CsrfToken.class).getReturnType())
                .isEqualTo(CsrfResponse.class);
    }
}

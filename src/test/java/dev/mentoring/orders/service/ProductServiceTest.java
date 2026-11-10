package dev.mentoring.orders.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.mentoring.orders.domain.Product;
import dev.mentoring.orders.exception.OrderProblem;
import dev.mentoring.orders.repository.ProductRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 상품 조회의 입력 검사와 내부 상품 정보 반환 확인. */
@ExtendWith(MockitoExtension.class)
class ProductServiceTest {
    @Mock ProductRepository productRepository;
    private ProductService productService;

    @BeforeEach
    void createService() {
        productService = new ProductService(productRepository);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void rejectsInvalidIdWithoutCallingRepository(long productId) {
        assertThatThrownBy(() -> productService.getProduct(productId))
                .isInstanceOfSatisfying(OrderProblem.class,
                        error -> assertThat(error.reason()).isEqualTo(OrderProblem.Reason.INVALID_INPUT));
        verifyNoInteractions(productRepository);
    }

    @Test
    void returnsTheCurrentProduct() {
        var product = new Product(30, "Limited notebook", 8);
        when(productRepository.findProduct(30)).thenReturn(product);

        assertThat(productService.getProduct(30)).isEqualTo(product);
    }

    @Test
    void returnsTheCurrentProducts() {
        var products = List.of(new Product(30, "Limited notebook", 8));
        when(productRepository.findProducts()).thenReturn(products);

        assertThat(productService.listProducts()).isEqualTo(products);
    }
}

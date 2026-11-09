package dev.mentoring.orders.controller;

import dev.mentoring.orders.dto.response.ProductResponse;
import dev.mentoring.orders.service.ProductService;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 주문 전 상품 목록과 판매 가능 수량 조회. 조회만으로 재고를 확보하지 않음. */
@RestController
public class ProductController {
    private final ProductService productService;

    /** 상품 조회 서비스 연결. */
    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    /** 상품 목록과 조회 시점의 판매 가능 수량 반환. */
    @GetMapping("/products")
    public List<ProductResponse> listProducts() {
        return productService.listProducts().stream().map(ProductResponse::from).toList();
    }

    /** 양의 정수 상품 ID로 상품과 현재 수량 조회. */
    @GetMapping("/products/{id}")
    public ProductResponse getProduct(@PathVariable("id") @Min(1) long productId) {
        return ProductResponse.from(productService.getProduct(productId));
    }
}

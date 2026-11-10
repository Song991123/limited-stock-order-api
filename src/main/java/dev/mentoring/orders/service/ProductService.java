package dev.mentoring.orders.service;

import dev.mentoring.orders.domain.Product;
import dev.mentoring.orders.exception.OrderProblem;
import dev.mentoring.orders.repository.ProductRepository;
import java.util.List;
import org.springframework.stereotype.Service;

/** 주문 전 상품 목록과 현재 판매 가능 수량 조회. 주문 접수와 취소는 OrderService에서 처리. */
@Service
public class ProductService {
    private final ProductRepository productRepository;

    /** 상품 조회 저장소 연결. */
    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    /** 최대 100개 상품과 조회 시점의 판매 가능 수량 반환. */
    public List<Product> listProducts() {
        return productRepository.findProducts();
    }

    /**
     * 상품과 현재 수량 조회. 조회한 수량을 주문 때까지 예약하지 않음.
     *
     * @throws OrderProblem 상품 ID가 양수가 아니거나 상품이 없는 경우
     */
    public Product getProduct(long productId) {
        if (productId < 1) throw OrderProblem.invalidInput();
        return productRepository.findProduct(productId);
    }
}

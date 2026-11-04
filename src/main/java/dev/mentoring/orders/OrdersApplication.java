package dev.mentoring.orders;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 주문 API 서버의 시작점과 같은 패키지 아래 Spring 구성요소 탐색. */
@SpringBootApplication
public class OrdersApplication {
    /**
     * 외부 설정으로 Spring Boot 애플리케이션 시작.
     * @param args 실행 시 전달한 설정 인자
     */
    public static void main(String[] args) {
        SpringApplication.run(OrdersApplication.class, args);
    }
}

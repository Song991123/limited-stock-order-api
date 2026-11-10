package dev.mentoring.orders.controller;

import dev.mentoring.orders.dto.response.CsrfResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 주문과 취소 요청의 헤더에 사용할 현재 세션의 CSRF 토큰 조회. */
@RestController
public class CsrfController {
    /** Spring Security가 제공한 세션 토큰과 헤더 이름 반환. */
    @GetMapping("/csrf")
    public CsrfResponse getCsrfToken(CsrfToken csrfToken) {
        return new CsrfResponse(csrfToken.getHeaderName(), csrfToken.getParameterName(), csrfToken.getToken());
    }
}

package dev.mentoring.orders.web.csrf;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.function.Supplier;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;

/**
 * CSRF 토큰을 요청 헤더에서만 확인.
 *
 * <p>토큰의 생성과 XOR 마스킹 처리는 Spring Security에 맡기고, URL과 폼 파라미터로 토큰을 받는 경로만 제외.
 */
public final class HeaderCsrfTokenRequestHandler implements CsrfTokenRequestHandler {
    private final XorCsrfTokenRequestAttributeHandler tokenHandler = new XorCsrfTokenRequestAttributeHandler();

    /** 토큰을 요청 속성에 공개하는 기본 처리와 지연 조회 유지. */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> deferredToken) {
        tokenHandler.handle(request, response, deferredToken);
    }

    /** 헤더가 없거나 비어 있으면 거절. 헤더 값의 마스킹 해제와 검증용 값 추출은 기본 처리기 사용. */
    @Override
    public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
        String header = request.getHeader(csrfToken.getHeaderName());
        if (header == null || header.isBlank()) return null;
        return tokenHandler.resolveCsrfTokenValue(request, csrfToken);
    }
}

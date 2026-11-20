package dev.mentoring.orders.web.csrf;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DefaultCsrfToken;

/** 실제 토큰 처리기와 Servlet 요청 대역으로 헤더 제한과 기존 마스킹 유지 확인. */
class HeaderCsrfTokenRequestHandlerTest {
    private final HeaderCsrfTokenRequestHandler handler = new HeaderCsrfTokenRequestHandler();
    private final CsrfToken storedToken = new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "test-session-token");

    @Test
    void maskedHeaderIsDecodedUsingTheDefaultHandler() {
        var request = new MockHttpServletRequest();
        var exposed = exposeToken(request);
        assertThat(exposed.getToken()).isNotEqualTo(storedToken.getToken());
        request.addHeader(storedToken.getHeaderName(), exposed.getToken());
        assertThat(handler.resolveCsrfTokenValue(request, storedToken)).isEqualTo(storedToken.getToken());
    }

    @Test
    void requestParameterCannotReplaceTheHeader() {
        var request = new MockHttpServletRequest();
        request.setParameter(storedToken.getParameterName(), exposeToken(request).getToken());
        assertThat(handler.resolveCsrfTokenValue(request, storedToken)).isNull();
    }

    @Test
    void blankHeaderCannotFallBackToTheParameter() {
        for (String value : new String[]{"", " "}) {
            var request = new MockHttpServletRequest();
            request.setParameter(storedToken.getParameterName(), exposeToken(request).getToken());
            request.addHeader(storedToken.getHeaderName(), value);
            assertThat(handler.resolveCsrfTokenValue(request, storedToken)).isNull();
        }
    }

    @Test
    void validHeaderIsNotReplacedByAnInvalidParameter() {
        var request = new MockHttpServletRequest();
        request.addHeader(storedToken.getHeaderName(), exposeToken(request).getToken());
        request.setParameter(storedToken.getParameterName(), "not-a-valid-token");
        assertThat(handler.resolveCsrfTokenValue(request, storedToken)).isEqualTo(storedToken.getToken());
    }

    @Test
    void malformedHeaderIsNotAcceptedAsAnUnmaskedToken() {
        var request = new MockHttpServletRequest();
        request.addHeader(storedToken.getHeaderName(), storedToken.getToken());
        assertThat(handler.resolveCsrfTokenValue(request, storedToken)).isNull();
    }

    @Test
    void exposingTheTokenStillDefersLoadingUntilItIsRead() {
        var request = new MockHttpServletRequest();
        var loaded = new AtomicInteger();
        handler.handle(request, new MockHttpServletResponse(), () -> {
            loaded.incrementAndGet();
            return storedToken;
        });
        assertThat(loaded).hasValue(0);
        var exposed = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        assertThat(exposed.getToken()).isNotBlank();
        assertThat(loaded).hasValue(1);
    }

    private CsrfToken exposeToken(MockHttpServletRequest request) {
        handler.handle(request, new MockHttpServletResponse(), () -> storedToken);
        return (CsrfToken) request.getAttribute(CsrfToken.class.getName());
    }
}

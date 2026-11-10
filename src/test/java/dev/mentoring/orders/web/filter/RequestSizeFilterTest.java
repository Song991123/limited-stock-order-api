package dev.mentoring.orders.web.filter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Servlet 경로 선택 검사. 인코딩 URL의 실제 매핑은 HTTP 통합 검사에서 확인. */
class RequestSizeFilterTest {
    private final RequestSizeFilter filter = new RequestSizeFilter();

    @ParameterizedTest
    @ValueSource(strings = {"/orders", "/orders/1/cancel"})
    void contextPathDoesNotExcludeOrderRequests(String servletPath) throws Exception {
        var request = new MockHttpServletRequest("POST", "/shop" + servletPath);
        request.setContextPath("/shop");
        request.setServletPath(servletPath);
        request.setContent(new byte[8193]);
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(chain.getRequest()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/orders-extra", "/products"})
    void similarPrefixIsNotAnOrderRoute(String servletPath) {
        var request = new MockHttpServletRequest("POST", servletPath);
        request.setServletPath(servletPath);
        assertThat(filter.shouldNotFilter(request)).isTrue();
    }

    @Test
    void readRequestIsNotTreatedAsAnOrderWrite() {
        var request = new MockHttpServletRequest("GET", "/orders");
        request.setServletPath("/orders");
        assertThat(filter.shouldNotFilter(request)).isTrue();
    }
}

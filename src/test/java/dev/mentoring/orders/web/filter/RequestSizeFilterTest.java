package dev.mentoring.orders.web.filter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import jakarta.servlet.ServletInputStream;

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

    /** 헤더에서 한도 초과를 알 수 있으면 요청 스트림을 열지 않고 거절. */
    @ParameterizedTest(name = "{displayName} [{index}] contentLength={0}")
    @ValueSource(longs = {8193L, Long.MAX_VALUE})
    void oversizedDeclaredLengthIsRejectedWithoutOpeningBody(long contentLength) throws Exception {
        var request = new MockHttpServletRequest("POST", "/orders") {
            @Override public long getContentLengthLong() { return contentLength; }
            @Override public ServletInputStream getInputStream() {
                throw new AssertionError("Known oversized body must not be opened");
            }
        };
        request.setServletPath("/orders");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).isEqualTo("{\"code\":\"REQUEST_TOO_LARGE\"}");
        assertThat(chain.getRequest()).isNull();
    }

    /** 작은 길이 값이나 길이 미상이어도 실제 스트림의 8KiB 제한은 유지. */
    @ParameterizedTest(name = "{displayName} [{index}] contentLength={0}")
    @ValueSource(longs = {-1L, 0L, 1L, 8192L})
    void actualBytesRemainLimitedWhenDeclaredLengthDoesNotExceedLimit(long contentLength) throws Exception {
        var request = new MockHttpServletRequest("POST", "/orders") {
            @Override public long getContentLengthLong() { return contentLength; }
        };
        request.setServletPath("/orders");
        request.setContent(new byte[8193]);
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(chain.getRequest()).isNull();
    }
}

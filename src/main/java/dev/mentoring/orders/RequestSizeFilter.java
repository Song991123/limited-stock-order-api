package dev.mentoring.orders;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 주문 요청의 본문 크기 검사.
 *
 * <p>문자 인코딩 설정 후 실행하며 폼 처리와 보안 필터가 본문을 읽기 전에 크기 검사.
 * 검사한 본문은 뒤에서 다시 읽을 수 있도록 보관.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RequestSizeFilter extends OncePerRequestFilter {
    private static final int MAX_REQUEST_BODY_BYTES = 8192;

    /** 주문 POST 요청이 아니면 크기 검사 생략. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getMethod().equals("POST") || !request.getRequestURI().startsWith("/orders");
    }

    /**
     * 요청 본문이 8KiB를 넘는지 확인.
     *
     * <p>8KiB보다 한 바이트 더 읽어 확인하므로 Content-Length가 없는 chunked 요청도 검사 가능.
     * 크기를 넘으면 413으로 응답하고 주문 처리는 실행하지 않음.
     *
     * @throws ServletException 뒤의 필터 또는 Servlet 처리 실패
     * @throws IOException 본문 읽기 또는 응답 쓰기 실패
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        var bodyBytes = request.getInputStream().readNBytes(MAX_REQUEST_BODY_BYTES + 1);
        if (bodyBytes.length > MAX_REQUEST_BODY_BYTES) {
            response.setStatus(413);
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"REQUEST_TOO_LARGE\"}");
            return;
        }
        filterChain.doFilter(new CachedBodyRequest(request, bodyBytes), response);
    }

    /**
     * 크기를 검사한 본문을 뒤에서 다시 읽을 수 있도록 보관.
     *
     * <p>폼 파라미터는 다시 만들지 않으므로 CSRF 토큰은 요청 헤더로 받아야 함.
     */
    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] cachedBody;

        /** 크기 제한을 통과한 원본 본문 보관. */
        private CachedBodyRequest(HttpServletRequest request, byte[] bodyBytes) {
            super(request);
            this.cachedBody = bodyBytes;
        }

        /** 보관한 본문을 처음부터 읽을 수 있는 스트림 반환. */
        @Override
        public ServletInputStream getInputStream() {
            return new CachedBodyStream(cachedBody);
        }

        /** 요청의 문자 인코딩으로 본문 읽기. 인코딩이 없으면 UTF-8 사용. */
        @Override
        public BufferedReader getReader() throws IOException {
            String characterEncoding = getCharacterEncoding();
            return new BufferedReader(new InputStreamReader(getInputStream(),
                    characterEncoding == null ? StandardCharsets.UTF_8.name() : characterEncoding));
        }

        /** 헤더 값이 아닌 실제로 검사한 본문의 바이트 수 반환. */
        @Override
        public int getContentLength() { return cachedBody.length; }

        /** 실제로 검사한 본문의 바이트 수를 long으로 반환. */
        @Override
        public long getContentLengthLong() { return cachedBody.length; }
    }

    /** 보관한 본문을 읽는 ServletInputStream 구현. */
    private static final class CachedBodyStream extends ServletInputStream {
        private final ByteArrayInputStream bodyStream;

        /** 본문을 처음부터 읽을 스트림 생성. */
        private CachedBodyStream(byte[] bodyBytes) {
            bodyStream = new ByteArrayInputStream(bodyBytes);
        }

        /** 다음 바이트 또는 스트림 끝의 -1 반환. */
        @Override
        public int read() { return bodyStream.read(); }

        /** 보관한 본문을 모두 읽었는지 판정. */
        @Override
        public boolean isFinished() { return bodyStream.available() == 0; }

        /** 본문이 메모리에 있어 바로 읽을 수 있으므로 true 반환. */
        @Override
        public boolean isReady() { return true; }

        /**
         * 비동기 읽기 등록 거절. 이 API는 동기 본문 처리만 지원.
         *
         * @throws IllegalStateException 비동기 리스너 등록 시도
         */
        @Override
        public void setReadListener(ReadListener listener) {
            throw new IllegalStateException("Synchronous input only");
        }
    }
}

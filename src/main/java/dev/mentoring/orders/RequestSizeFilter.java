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

@Component
// 문자 인코딩 설정 뒤, 폼 파싱과 보안 필터 앞에서 원본 본문 크기 검사
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RequestSizeFilter extends OncePerRequestFilter {
    private static final int MAX_BYTES = 8192;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getMethod().equals("POST") || !request.getRequestURI().startsWith("/orders");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        var body = request.getInputStream().readNBytes(MAX_BYTES + 1);
        if (body.length > MAX_BYTES) {
            response.setStatus(413);
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"REQUEST_TOO_LARGE\"}");
            return;
        }
        chain.doFilter(new CachedBodyRequest(request, body), response);
    }

    // 크기 검사로 읽은 본문을 MVC에 전달. CSRF 토큰은 폼이 아니라 요청 헤더로 수신
    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        private CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            return new CachedBodyStream(body);
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            return new BufferedReader(new InputStreamReader(getInputStream(),
                    encoding == null ? StandardCharsets.UTF_8.name() : encoding));
        }

        @Override
        public int getContentLength() { return body.length; }

        @Override
        public long getContentLengthLong() { return body.length; }
    }

    private static final class CachedBodyStream extends ServletInputStream {
        private final ByteArrayInputStream bytes;

        private CachedBodyStream(byte[] body) {
            bytes = new ByteArrayInputStream(body);
        }

        @Override
        public int read() { return bytes.read(); }

        @Override
        public boolean isFinished() { return bytes.available() == 0; }

        @Override
        public boolean isReady() { return true; }

        @Override
        public void setReadListener(ReadListener listener) {
            throw new IllegalStateException("Synchronous input only");
        }
    }
}

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
 * 주문 POST 요청의 원본 본문을 폼 파싱과 보안 검사 전에 크기 제한.
 *
 * <p>문자 인코딩 설정 다음 순서에서 실행. CSRF 폼 파싱이 본문을 먼저 소비해 제한을 우회하지 않게 배치.
 * 허용한 본문은 뒤의 MVC와 취소 API가 읽을 수 있도록 보관.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RequestSizeFilter extends OncePerRequestFilter {
    private static final int MAX_REQUEST_BODY_BYTES = 8192;

    /** 주문 경로의 POST만 검사 대상으로 선택. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getMethod().equals("POST") || !request.getRequestURI().startsWith("/orders");
    }

    /**
     * 본문을 최대 허용 크기보다 한 바이트 더 읽어 초과 여부 확인.
     *
     * <p>Content-Length만 믿지 않아 길이를 미리 모르는 chunked 요청에도 같은 제한 적용.
     * 초과 요청은 413으로 끝내고 뒤의 필터와 주문 처리로 전달하지 않음.
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
     * 검사한 본문을 다시 읽을 수 있게 제공하는 요청 래퍼.
     *
     * <p>폼 파라미터를 재구성하는 래퍼가 아니므로 CSRF 토큰은 요청 헤더로 수신.
     */
    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] cachedBody;

        /** 크기 제한을 통과한 원본 본문 보관. */
        private CachedBodyRequest(HttpServletRequest request, byte[] bodyBytes) {
            super(request);
            this.cachedBody = bodyBytes;
        }

        /** 요청할 때마다 검사한 본문의 시작 위치에서 읽는 동기 스트림 생성. */
        @Override
        public ServletInputStream getInputStream() {
            return new CachedBodyStream(cachedBody);
        }

        /** 원본 문자 인코딩으로 보관한 본문을 읽고, 인코딩이 없으면 UTF-8 사용. */
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

    /** 보관한 바이트 배열을 제공하는 동기 Servlet 입력 스트림. */
    private static final class CachedBodyStream extends ServletInputStream {
        private final ByteArrayInputStream bodyStream;

        /** 요청별 읽기 위치를 가진 바이트 스트림 생성. */
        private CachedBodyStream(byte[] bodyBytes) {
            bodyStream = new ByteArrayInputStream(bodyBytes);
        }

        /** 다음 바이트 또는 스트림 끝의 -1 반환. */
        @Override
        public int read() { return bodyStream.read(); }

        /** 보관한 본문을 모두 읽었는지 판정. */
        @Override
        public boolean isFinished() { return bodyStream.available() == 0; }

        /** 메모리에서 동기 읽기가 가능하므로 항상 준비 상태 반환. */
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

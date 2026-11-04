package dev.mentoring.orders;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(0)
public class RequestSizeFilter extends OncePerRequestFilter {
    private static final int MAX_BYTES = 8192;
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getMethod().equals("POST") || !request.getRequestURI().startsWith("/orders");
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        var body = request.getInputStream().readNBytes(MAX_BYTES + 1);
        if (body.length > MAX_BYTES) {
            response.setStatus(413); response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"REQUEST_TOO_LARGE\"}"); return;
        }
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream() {
                var bytes = new ByteArrayInputStream(body);
                return new ServletInputStream() {
                    public int read() { return bytes.read(); }
                    public boolean isFinished() { return bytes.available() == 0; }
                    public boolean isReady() { return true; }
                    public void setReadListener(ReadListener listener) { throw new IllegalStateException("Synchronous input only"); }
                };
            }
            @Override public int getContentLength() { return body.length; }
            @Override public long getContentLengthLong() { return body.length; }
        }, response);
    }
}

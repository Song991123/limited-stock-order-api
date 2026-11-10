package dev.mentoring.orders.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;

/** 로그인 정보가 틀린 경우와 로그인 저장소가 실패한 경우의 응답 구분. */
class AuthenticationFailureTest {
    @Test void wrongPasswordKeepsTheBasicAuthenticationChallenge() throws Exception {
        var response = new MockHttpServletResponse();
        new SecurityConfiguration().authenticationEntryPoint(new ObjectMapper()).commence(
                new MockHttpServletRequest(), response, new BadCredentialsException("private-password"));
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).startsWith("Basic ");
        assertThat(response.getHeader("X-Error-Id")).isNull();
        assertThat(response.getContentAsString()).doesNotContain("private-password");
    }

    @Test void unavailableAuthenticationStoreReturnsSafeRetryableError() throws Exception {
        var response = new MockHttpServletResponse();
        new SecurityConfiguration().authenticationEntryPoint(new ObjectMapper()).commence(new MockHttpServletRequest(), response,
                new InternalAuthenticationServiceException("private-password",
                        new DataAccessResourceFailureException("SELECT password", new SQLException("private-data", "08006"))));
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Retry-After")).isEqualTo("1");
        assertThat(response.getHeader("X-Error-Id")).isNotBlank();
        assertThat(response.getHeader("WWW-Authenticate")).isNull();
        assertThat(response.getContentAsString()).isEqualTo("{\"code\":\"DATABASE_UNAVAILABLE\"}");
    }

    @Test void unexpectedAuthenticationServiceErrorIsNotWrongPassword() throws Exception {
        var response = new MockHttpServletResponse();
        new SecurityConfiguration().authenticationEntryPoint(new ObjectMapper()).commence(new MockHttpServletRequest(), response,
                new InternalAuthenticationServiceException("private-data", new IllegalStateException("SELECT password")));
        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentAsString()).isEqualTo("{\"code\":\"INTERNAL_ERROR\"}");
    }
}

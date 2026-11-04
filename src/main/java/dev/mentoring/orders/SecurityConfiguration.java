package dev.mentoring.orders;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 로그인 계정 조회, 비밀번호 검증과 API 보안 필터 구성.
 *
 * <p>Bean 메서드끼리 직접 호출하지 않고 매개변수로 의존성을 전달해 설정 프록시 생략.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {
    /** 원문 비밀번호 대신 BCrypt 해시를 저장/비교할 인코더 등록. */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * DB 계정명과 비밀번호 해시로 Spring Security의 인증 정보 구성.
     *
     * <p>반환한 조회 함수가 계정을 찾지 못하면 UsernameNotFoundException 전파.
     */
    @Bean
    UserDetailsService userDetailsService(JdbcClient jdbcClient) {
        return username -> jdbcClient.sql("SELECT username, password_hash FROM buyers WHERE username = :username")
                .param("username", username).query((resultSet, rowNumber) -> User
                        .withUsername(resultSet.getString("username"))
                        .password(resultSet.getString("password_hash")).roles("CUSTOMER").build())
                .optional().orElseThrow(() -> new UsernameNotFoundException("Unknown customer"));
    }

    /**
     * 모든 API의 HTTP Basic 인증과 변경 요청의 세션 CSRF 검사 구성.
     *
     * <p>ERROR 디스패치는 원래 오류 응답을 허용하기 위한 예외이며 일반 API 경로의 인증 면제가 아님.
     *
     * @throws Exception 보안 필터 구성을 만들지 못한 경우
     */
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity httpSecurity) throws Exception {
        return httpSecurity.authorizeHttpRequests(authorization -> authorization
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults())
                .csrf(Customizer.withDefaults())
                .requestCache(cache -> cache.disable())
                .formLogin(form -> form.disable())
                .build();
    }
}

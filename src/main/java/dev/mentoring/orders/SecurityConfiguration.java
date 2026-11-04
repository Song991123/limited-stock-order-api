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
 * 로그인 계정 확인, 비밀번호 비교와 CSRF 검사 설정.
 *
 * <p>Bean 메서드끼리 직접 호출하지 않아 proxyBeanMethods는 false 사용.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {
    /** 원문 비밀번호 대신 BCrypt 해시를 저장/비교할 인코더 등록. */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * DB에 저장된 계정명과 비밀번호 해시로 로그인 정보 조회.
     *
     * <p>로그인할 계정이 없으면 조회 함수에서 UsernameNotFoundException 발생.
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
     * 모든 API에 HTTP Basic 인증 적용. POST 요청에는 세션과 CSRF 토큰도 확인.
     *
     * <p>ERROR 처리는 원래 오류 응답을 보내기 위해 허용. 일반 API의 인증을 생략하는 설정은 아님.
     *
     * @throws Exception 보안 필터를 설정하지 못한 경우
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

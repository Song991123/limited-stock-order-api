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

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService users(JdbcClient jdbc) {
        return username -> jdbc.sql("SELECT username, password_hash FROM buyers WHERE username = :username")
                .param("username", username).query((rs, row) -> User.withUsername(rs.getString("username"))
                        .password(rs.getString("password_hash")).roles("CUSTOMER").build())
                .optional().orElseThrow(() -> new UsernameNotFoundException("Unknown customer"));
    }

    // 모든 API 인증, 변경 요청의 세션 CSRF 검사. 폼 로그인 대신 HTTP Basic 사용
    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults())
                .csrf(Customizer.withDefaults())
                .requestCache(cache -> cache.disable())
                .formLogin(form -> form.disable())
                .build();
    }
}

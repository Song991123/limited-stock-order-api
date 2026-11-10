package dev.mentoring.orders.security;

import dev.mentoring.orders.repository.CustomerAccountRepository;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/** 저장된 계정 정보를 Spring Security의 로그인 정보로 변환. 비밀번호 비교는 인증 제공자가 수행. */
@Service
public class CustomerUserDetailsService implements UserDetailsService {
    private final CustomerAccountRepository customerAccountRepository;

    /** 로그인 정보를 읽을 계정 저장소 연결. */
    public CustomerUserDetailsService(CustomerAccountRepository customerAccountRepository) {
        this.customerAccountRepository = customerAccountRepository;
    }

    /**
     * 계정명으로 로그인 정보 조회. 기존 CUSTOMER 권한과 비밀번호 해시 유지.
     *
     * @throws UsernameNotFoundException 계정이 없는 경우. DB 장애는 이 예외로 바꾸지 않음.
     */
    @Override
    public UserDetails loadUserByUsername(String username) {
        var account = customerAccountRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("Unknown customer"));
        return User.withUsername(account.username()).password(account.passwordHash()).roles("CUSTOMER").build();
    }
}

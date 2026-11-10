package dev.mentoring.orders.repository;

import dev.mentoring.orders.repository.model.LoginAccount;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 로그인 계정과 비밀번호 해시 조회. 비밀번호 비교와 인증 응답은 담당하지 않음. */
@Repository
public class CustomerAccountRepository {
    private final JdbcClient jdbcClient;

    /** 계정 조회에 사용할 JdbcClient 연결. */
    public CustomerAccountRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * 로그인 계정명에 해당하는 저장 정보 조회. 계정이 없으면 빈 Optional 반환.
     *
     * <p>DB 오류는 호출자에게 그대로 전달. 저장소 장애를 계정 없음으로 바꾸지 않음.
     */
    public Optional<LoginAccount> findByUsername(String username) {
        return jdbcClient.sql("SELECT username, password_hash FROM buyers WHERE username = :username")
                .param("username", username).query((resultSet, rowNumber) -> new LoginAccount(
                        resultSet.getString("username"), resultSet.getString("password_hash"))).optional();
    }
}

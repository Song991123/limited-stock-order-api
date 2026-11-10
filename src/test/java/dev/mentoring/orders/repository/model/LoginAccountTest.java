package dev.mentoring.orders.repository.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 계정 정보의 문자열 출력에 계정명과 비밀번호 해시가 노출되지 않는지 확인. */
class LoginAccountTest {
    @Test
    void printingAccountDoesNotExposeCredentials() {
        var account = new LoginAccount("private-account", "private-password-hash");

        assertThat(account.toString()).doesNotContain(account.username(), account.passwordHash());
    }
}

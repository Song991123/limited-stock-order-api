package dev.mentoring.orders.repository.model;

/**
 * 로그인에 사용할 계정명과 저장된 비밀번호 해시. HTTP 응답에 사용하지 않음.
 *
 * @param username 저장된 로그인 계정명
 * @param passwordHash BCrypt로 저장한 비밀번호 해시
 */
public record LoginAccount(String username, String passwordHash) {
    /** 문자열 출력에 계정명과 비밀번호 해시가 포함되지 않도록 처리. */
    @Override
    public String toString() {
        return "LoginAccount[credentials redacted]";
    }
}

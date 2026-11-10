package dev.mentoring.orders.dto.response;

/**
 * API 오류 응답. SQL, 비밀번호와 요청 원문은 포함하지 않음.
 *
 * @param code 클라이언트가 오류 종류를 구별할 고정 코드
 */
public record ErrorResponse(String code) {}

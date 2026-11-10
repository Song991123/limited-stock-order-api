package dev.mentoring.orders.dto.response;

/**
 * 변경 요청에 사용할 CSRF 토큰과 전송할 헤더 이름.
 *
 * @param headerName 토큰을 넣을 요청 헤더 이름
 * @param parameterName Spring Security의 기본 파라미터 이름, 이 API에서는 헤더로 전송
 * @param token 현재 세션에서 사용할 CSRF 토큰
 */
public record CsrfResponse(String headerName, String parameterName, String token) {}

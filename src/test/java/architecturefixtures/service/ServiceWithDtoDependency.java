package architecturefixtures.service;

import dev.mentoring.orders.dto.response.ErrorResponse;

/** 메서드 내부에서만 HTTP DTO를 사용하는 위반 예제. 구조 검사에서 거절되는지 확인하는 용도. */
public class ServiceWithDtoDependency {
    public void createResponseInsideMethod() {
        new ErrorResponse("fixture");
    }
}

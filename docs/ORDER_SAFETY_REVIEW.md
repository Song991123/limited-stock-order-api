# 주문 입력과 재요청 처리 점검

## 재현한 문제와 수정

실제 HTTP와 전용 PostgreSQL에서 아래 세 조건을 먼저 검사했습니다. 기존 HTTP 검사 47건은 통과했지만 새 검사 3건은 실패했습니다.

| 조건 | 수정 전 관찰 | 원인과 수정 |
| --- | --- | --- |
| `/%6frders`에 8KiB 초과 주문 전송 | 201, 주문 1건과 재고 9 | 필터는 원문 URI, MVC는 해석한 경로를 사용. 필터도 `getServletPath()`로 대상 선택 |
| 기존 구매 키로 없는 상품 요청 | 404 `NOT_FOUND`, 원래 주문 유지 | 상품 존재를 기존 키보다 먼저 검사. 기존 구매 조회와 입력 대조를 앞에 배치 |
| 수량 필드가 두 번 있는 JSON | 500, 주문 0건과 재고 10 | DTO 값 검증 전에 JSON 변환 실패. Jackson 중복 필드 검사로 입력 오류 400 처리 |

인코딩 URL 문제는 본문 제한 누락입니다. 인증이나 고객 소유권 우회를 확인한 것은 아닙니다. 중복 필드도 주문이 잘못 저장된 결과가 아니라 잘못된 입력을 서버 오류로 응답한 결함입니다.

[수정 전후 관찰 값](../evidence/verification/order-safety-comparison.json)에 실패한 세 조건과 수정 뒤 응답을 나란히 볼 수 있습니다.

수정 후에는 인코딩 경로의 고정 길이/chunked 본문 모두 413, 중복 JSON 필드는 같은 값이어도 400을 요구합니다. 거절한 요청은 구매 키를 저장하지 않아 정상 입력으로 다시 접수할 수 있습니다. 기존 키의 다른 상품은 409, 새 키의 없는 상품은 404로 구분합니다. 최종 통과 수와 관찰 값은 [검증 기록](VERIFICATION.md)과 [HTTP 결과](../evidence/verification/observations.json)에서 확인할 수 있습니다.

## 재요청의 조회 순서를 바꾼 이유

기존 구매가 있으면 저장된 입력을 먼저 대조합니다. 기록이 없을 때 상품을 확인하고 구매 기록 등록을 시도합니다. 그 사이 다른 요청이 등록할 수 있으므로 조회만으로 신규 구매라고 확정하지 않습니다.

동시 등록은 계속 `(buyer_id, request_key)` UNIQUE로 판정합니다. 등록이 생략되면 별도 SELECT로 확정된 기록을 다시 읽습니다. READ COMMITTED에서는 각 SQL이 시작할 때 커밋된 기록을 읽으므로 이 재조회가 필요합니다. 새 구매에는 조회 한 번이 추가되며, 성능 이득으로 주장하지 않습니다. [PostgreSQL 충돌과 조회 시점](https://www.postgresql.org/docs/16/transaction-iso.html#XACT-READ-COMMITTED)

## 어노테이션 선택

| 코드 | 적용 효과와 이 프로젝트에서의 이유 |
| --- | --- |
| 서비스의 `@Transactional` | 요청 기록, 재고, 주문과 이력을 함께 확정. 저장소 메서드마다 따로 커밋하면 부분 저장이 남을 수 있어 접수와 취소 단위에 적용 |
| `isolation = READ_COMMITTED` | 충돌 대기 뒤 새 SELECT에서 확정 결과 조회. 재고 경쟁은 조건부 UPDATE가 처리하며 이 격리 수준만으로 중복을 막지는 않음 |
| `rollbackFor = Exception.class` | 처리 또는 전파 선언이 필요한 checked 예외도 롤백. 현재 주된 JDBC 오류는 기본 롤백 대상인 RuntimeException 계열 |
| `@Valid`와 `@NotNull @Positive` | 변환된 요청 객체의 누락값과 0 이하 수량 거절. JSON 문법/중복 필드는 Jackson, 직접 서비스 호출의 값 조건은 서비스에서 검사 |
| 필터의 `@Order` | 문자 인코딩 설정 뒤, 폼/보안 필터가 본문을 소비하기 전에 크기 검사. 고객 간 선착순과 무관 |
| `@Profile("demo")` | 예제 계정과 상품을 데모에서만 생성. 일반 실행에는 예제 비밀번호 불필요 |

서비스의 private 함수는 이미 열린 트랜잭션 안에서 실행합니다. 같은 클래스 내부 호출에 어노테이션을 더 붙여도 별도 트랜잭션이 생기지 않습니다. 재고 부족 결과는 정상 반환해 커밋한 뒤 컨트롤러가 409로 바꿉니다. 서비스에서 롤백 예외로 던지면 같은 키에 보존할 거절 결과도 사라집니다. [Spring 트랜잭션 적용 방식](https://docs.spring.io/spring-framework/reference/6.2/data-access/transaction/declarative/annotations.html)

## 유지한 보장과 남은 비용

- 재고 차감, 주문 취소의 조건부 UPDATE와 이력 UNIQUE는 유지. 요청 도착 순서나 선착순은 보장하지 않음.
- 조회/취소의 고객 조건과 CSRF 검사 유지. 구매 키는 인증 정보가 아니며 새로운 키를 계속 만드는 남용까지 막지 못함.
- 요청 한도는 한 JVM의 공통 동시 쓰기와 고객별 구매/취소 각각의 고정 1분 횟수. 분당 구간 전후의 집중과 인증 요청 비용은 남음.
- 커밋 실패나 응답 유실을 성공으로 단정하지 않고 같은 키로 결과 확인. 서버의 자동 재시도는 없음.
- 기록 보존 한도, 여러 서버의 공유 한도, 장시간 부하와 운영 보안은 별도 검증 필요. 일반 공개용 인증/배포 구성을 완성했다는 뜻은 아님.

현재 문제는 한 DB의 짧은 주문/취소 처리여서 Redis 잠금, 비동기 대기열이나 JPA를 추가하지 않았습니다. DB가 지키는 수량 조건과 원자성을 유지하면서 재현한 오류의 입력 경로를 수정했습니다. 잠금 방식의 성능 우열은 측정하지 않았습니다.

사용자 행동 검사 뒤에는 큰 요청을 읽기 전에 거절하고 구매 재시도로 취소까지 막히지 않도록 횟수를 나눴습니다. 재고 SQL과 트랜잭션은 유지했습니다. [요청 처리 개선](REQUEST_FLOW_IMPROVEMENTS.md)에서 수정 전후 조건과 비용을 비교합니다.

## GitHub 검사

`feature/*`의 push와 `develop`/`main` 대상 PR에서 Java 21과 임시 PostgreSQL 16으로 전체 검사, JAR 빌드와 Javadoc을 실행합니다. 외부 액션은 공식 저장소의 커밋으로 고정하고 GitHub 토큰은 읽기 권한만 사용합니다. CI 계정은 해당 검사 컨테이너에만 쓰는 예제 값입니다. 서버 배포는 하지 않습니다.

로컬 검사와 GitHub Actions 실행은 별개의 결과입니다. Actions가 실제로 끝났는지는 PR의 검사 상태에서 확인해야 합니다. [GitHub 서비스 컨테이너](https://docs.github.com/en/actions/tutorials/use-containerized-services), [Gradle CI 설정](https://docs.gradle.org/current/userguide/github-actions.html)

## 참고한 명세

- [Jakarta Servlet 요청 경로](https://jakarta.ee/specifications/servlet/6.0/jakarta-servlet-spec-6.0.html): 컨테이너의 경로 해석과 매핑.
- [Jackson 중복 필드 검사](https://fasterxml.github.io/jackson-core/javadoc/2.14/com/fasterxml/jackson/core/StreamReadFeature.html#STRICT_DUPLICATE_DETECTION): 필드 이름이 반복되면 파싱 중 거절.
- [Spring Boot Jackson 설정](https://docs.spring.io/spring-boot/3.5/appendix/application-properties/index.html#application-properties.json.spring.jackson.parser): 기존 파서 옵션으로 적용. 직접 JSON 파서를 만들지 않음.

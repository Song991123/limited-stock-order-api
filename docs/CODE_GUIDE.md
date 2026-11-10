# 코드 읽기와 어노테이션

고객이 상품 2개를 주문하면 재고를 2개 줄이고 주문을 만듭니다. 같은 구매 요청을 다시 보내도 추가로 차감하지 않습니다. 취소도 한 번만 재고를 돌려줍니다. 이 세 동작을 기준으로 코드를 읽으면 됩니다.

## 읽는 순서

| 파일 | 확인할 동작 |
| --- | --- |
| [CreateOrderRequest](../src/main/java/dev/mentoring/orders/dto/request/CreateOrderRequest.java) | 주문 JSON과 필수값, 양수 검증 조건 |
| [OrderController](../src/main/java/dev/mentoring/orders/controller/OrderController.java) | 요청 DTO 검증, 서비스 호출과 응답 DTO 변환 |
| [OrderService](../src/main/java/dev/mentoring/orders/service/OrderService.java) | 주문 접수와 취소, 재고 수량과 증감 이력 저장 |
| [OrderRepository](../src/main/java/dev/mentoring/orders/repository/OrderRepository.java) | 실제 SQL, 재고와 상태를 바꾸는 조건 |
| [OrderResponse](../src/main/java/dev/mentoring/orders/dto/response/OrderResponse.java) | 내부 주문 정보를 기존 JSON 필드로 변환 |
| [V1 마이그레이션](../src/main/resources/db/migration/V1__orders_and_inventory.sql) | 중복 키, 수량 범위와 미완료 요청 커밋 제한 |
| [WriteAdmission](../src/main/java/dev/mentoring/orders/web/admission/WriteAdmission.java) | 구매/취소 횟수는 고객별로 분리, 전체 동시 쓰기는 함께 제한 |
| [RequestBodyConfiguration](../src/main/java/dev/mentoring/orders/config/RequestBodyConfiguration.java) | 헤더 검사 전에 본문 전송을 허용하지 않도록 100 Continue 응답 시점 지정 |
| [RequestSizeFilter](../src/main/java/dev/mentoring/orders/web/filter/RequestSizeFilter.java) | 다른 필터가 읽기 전 본문 크기 검사 |
| [TransactionConfiguration](../src/main/java/dev/mentoring/orders/config/TransactionConfiguration.java) | 같은 DataSource의 트랜잭션 관리자 지정, 커밋 오류 보존 |
| [DatabaseFailures](../src/main/java/dev/mentoring/orders/exception/DatabaseFailures.java) | 인증과 주문에서 공통으로 쓰는 DB 오류 분류 |
| [CustomerAccountRepository](../src/main/java/dev/mentoring/orders/repository/CustomerAccountRepository.java) | 로그인 계정과 저장된 비밀번호 해시 조회 |
| [CustomerUserDetailsService](../src/main/java/dev/mentoring/orders/security/CustomerUserDetailsService.java) | 계정 조회 결과를 Spring Security의 로그인 정보로 변환 |

요청과 응답 DTO는 `dto.request`와 `dto.response`, 내부 주문 정보는 `domain`, DB 구매 기록 조회 결과는 `repository.model`에 있습니다. 컨트롤러, 서비스와 저장소도 각각의 패키지로 구분했습니다. [입력 검증과 코드 구성](VALIDATION_AND_STRUCTURE.md)에서 나눈 이유와 입력별 검사 위치를 확인할 수 있습니다.

## 주문 한 건의 처리

1. 크기 필터가 알려진 길이로 큰 요청을 먼저 거절하고 나머지는 실제 본문을 읽어 검사합니다. 허용한 본문은 뒤에서 읽을 수 있게 보관합니다.
2. Spring Security가 계정과 CSRF 헤더를 검사하고, MVC가 상품과 수량의 입력 조건을 검사합니다.
3. 컨트롤러가 쓰기 한도를 확인한 뒤 상품 ID와 수량으로 서비스의 `placeOrder`를 호출합니다. 서비스도 양수 조건을 검사해 직접 호출된 경우를 보호합니다. 요청 DTO 자체는 서비스에 넘기지 않습니다. 구매자 ID는 로그인 계정에서 구하고, 구매 키는 같은 구매를 다시 요청할 때 유지합니다.
4. 서비스는 고객과 구매 키의 기존 기록을 먼저 대조합니다. 기록이 없으면 상품을 확인하고 등록합니다. 조회 후 다른 요청이 먼저 등록할 수 있어 DB 유일성 제약은 유지하며, 등록이 생략되면 새 SELECT로 확정된 기록을 읽습니다. 같은 입력에는 원래 주문의 현재 상태 또는 처음의 재고 부족 결과를 반환하고, 다른 입력은 거절합니다.
5. 구매 요청 기록을 새로 등록한 경우에만 `tryDecreaseStock`으로 재고를 차감합니다. 남은 수량이 충분할 때만 UPDATE가 해당 상품의 재고 기록을 바꿉니다.
6. 차감에 성공하면 주문, 차감 이력과 접수 결과를 같은 트랜잭션에 저장합니다. 재고 부족이면 주문을 만들지 않고 구매 요청에 재고 부족 결과를 저장합니다.
7. 서비스 메서드가 정상 반환하면 Spring 프록시가 커밋합니다. 컨트롤러는 신규 주문에 201, 기존 주문에 200과 `OrderResponse`를 반환합니다. 재고 부족은 커밋 후 공통 오류 처리기로 보내 409와 오류 코드를 반환합니다.

서비스의 `OrderPlacement`는 `Created`, `Replayed`, `OutOfStock` 중 하나입니다. 예전의 숫자 상태와 nullable 주문/오류 조합 대신, 가능한 결과만 표현합니다. `sealed interface`는 결과 종류를 제한하고 컨트롤러의 `switch`가 모두 처리하게 하는 Java 문법입니다.

원래 주문의 현재 상태를 반환하므로, 취소 후 같은 구매 키를 보내면 취소된 주문을 받습니다. 처음 재고가 부족했던 키는 나중에 재고가 늘어도 처음의 재고 부족 결과를 반환합니다.

## 동시성 제어의 역할

| 장치 | 막는 문제 | 막지 못하는 문제 |
| --- | --- | --- |
| 고객/구매 키 UNIQUE | 같은 구매를 두 번 등록 | 새 키로 보낸 별도 구매 |
| 재고 조건부 UPDATE | 남은 수량보다 많이 차감 | 먼저 도착한 고객의 우선권 |
| `status = 'ACCEPTED'` 취소 UPDATE | 같은 주문의 두 번 복구 | 모든 주문을 한 번에 취소하는 전역 규칙 |
| 서비스 트랜잭션 | 주문만 남거나 재고만 바뀌는 부분 저장 | 평범한 SELECT 결과의 독점 사용 |
| 수량 CHECK, 이력 UNIQUE | 잘못된 수량과 중복 이력의 커밋 | 올바른 업무 순서 자체 |
| Semaphore와 고객별 카운터 | 서버 프로그램 하나에 너무 많은 요청이 동시에 실행되는 상황 | 여러 서버가 공유하는 한도와 재고 중복 차감 |

재고가 1개일 때 A와 B가 각각 1개를 주문하면 A의 UPDATE가 재고 기록을 잠급니다. B가 같은 기록을 갱신하려면 기다립니다. A가 커밋한 뒤 DB가 B의 `수량 >= 1`을 다시 검사하므로 B는 차감하지 못합니다. 잠금이 없는 방식도, 도착 순서대로 처리하는 대기열도 아닙니다. [PostgreSQL의 갱신과 조건 재검사](https://www.postgresql.org/docs/16/transaction-iso.html#XACT-READ-COMMITTED)

## 주문과 재고를 함께 저장하는 설정

```java
@Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
public OrderPlacement placeOrder(String username, String idempotencyKey, long productId, int quantity)
```

- `@Transactional`: 주문 정보, 재고와 이력을 트랜잭션 하나로 저장합니다. 중간에 오류가 나면 함께 되돌리도록 주문 접수와 취소에 각각 적용했습니다.
- `READ_COMMITTED`: 각 SQL이 시작하기 전에 커밋된 데이터를 읽습니다. 같은 키의 저장을 기다린 뒤 새 SELECT를 실행하면 다른 요청이 커밋한 결과를 읽을 수 있습니다. 재고 경쟁은 조건부 UPDATE로 따로 처리합니다.
- `rollbackFor = Exception.class`: 호출자가 처리하거나 `throws`로 전파를 선언해야 하는 checked 예외도 롤백합니다. 현재 JDBC 저장 오류는 주로 `RuntimeException` 계열이라 기본 롤백 대상입니다. 이 설정은 checked 예외까지 포함하려는 정책입니다.
- `readOnly = true`: 주문 조회에 적용한 읽기 전용 트랜잭션 설정입니다. 고객 소유권은 이 설정이 아니라 SQL의 `buyer_id` 조건으로 검사합니다.

컨트롤러가 Spring이 주입한 서비스를 호출하므로 트랜잭션 프록시를 통과합니다. 서비스 내부의 private 메서드는 이미 열린 트랜잭션 안에서 실행합니다. 내부 메서드에 새 어노테이션을 붙이는 것만으로 별도 트랜잭션이 생기는 것은 아닙니다. 커밋 실패도 호출자에게 예외로 전달돼 성공 응답을 만들지 않습니다. [Spring 트랜잭션 프록시와 기본값](https://docs.spring.io/spring-framework/reference/6.2/data-access/transaction/declarative/annotations.html)

`demo` 프로필로 시작할 때만 `ApplicationRunner.run`에서 예제 고객과 상품을 저장합니다. `TransactionTemplate` 안에서 함께 저장하므로 하나라도 실패하면 모두 롤백합니다. 일반 실행에는 초기 데이터 등록기가 없고 예제 비밀번호도 요구하지 않습니다.

## 요청과 객체 등록의 어노테이션

| 어노테이션 | 이 코드에서 맡은 역할과 선택 이유 |
| --- | --- |
| `@SpringBootApplication` | 서버 시작과 같은 패키지 아래 구성요소 탐색 |
| `@RestController`, `@GetMapping`, `@PostMapping` | URL/HTTP 메서드를 Java 메서드에 연결하고 반환 객체를 응답 본문으로 전달 |
| `@RequestBody`, `@RequestHeader` | 주문 JSON과 구매 키를 각각 받음. 키와 입력은 재시도 때 유지 |
| `@PathVariable`, `@RequestParam` | 주문 ID, 목록 기준 ID와 조회 개수 수신 |
| `@Valid` | 요청 객체 안의 입력 조건 검사. 자체적으로 양수나 null 금지를 뜻하지 않음 |
| `@NotNull`, `@Positive` | 누락/null과 0 이하를 각각 거절. 소수/문자열 숫자는 Jackson 설정으로 별도 거절 |
| `@Min`, `@Max` | 경로 ID와 목록 개수 범위 검사. Spring MVC의 내장 메서드 검증 사용 |
| `@Service`, `@Repository` | 주문 처리와 SQL 저장소를 Spring 관리 객체로 등록. 자체적으로 잠금을 걸지는 않음 |
| `@Component` | 크기 필터, 쓰기 한도와 초기 데이터 등록기를 관리 객체로 등록 |
| `@Profile("demo")` | 로컬 예제용 초기화. demo 프로필이 활성화됐을 때 예제 데이터 등록기 사용 |
| `@Configuration(proxyBeanMethods = false)`, `@Bean` | 비밀번호 인코더, 인증 오류 응답과 보안 필터 체인 등록. Bean 메서드끼리 직접 호출하지 않아 설정 클래스 프록시는 생략. 계정 조회 클래스는 `@Repository`, 로그인 정보 변환 클래스는 `@Service`로 등록 |
| `@Value` | 설정 파일의 쓰기 한도와 실행 시 생성한 비밀번호 주입 |
| `@RestControllerAdvice`, `@ExceptionHandler` | 입력/업무/DB 오류를 같은 API 오류 형식으로 변환 |
| `@Order` | Servlet 필터의 실행 순서 지정. 고객 주문의 처리 순서와는 무관 |
| `@Override` | 상위 타입의 메서드를 구현하는지 컴파일러가 확인 |

컨트롤러의 클래스 단위 `@Validated`는 제거했습니다. Spring 6.1 이후 MVC가 직접 제공하는 경로/쿼리 검증을 사용해 별도 AOP 검증 경로를 겹치지 않게 했습니다. 본문의 `@Valid`는 유지합니다. 실제 HTTP로 잘못된 경로와 쿼리 7개를 보내 400을 확인했습니다. [Spring MVC 입력 검증](https://docs.spring.io/spring-framework/reference/6.2/web/webmvc/mvc-controller/ann-validation.html)

생성자는 클래스마다 하나입니다. Spring이 생성자 매개변수로 의존성을 주입하므로 별도 `@Autowired`를 추가하지 않았습니다. 입력/응답의 `record`, 상태의 `enum`은 Java 문법이며 Spring 어노테이션이 아닙니다.

## 본문 크기와 필터 순서

`@Order(Ordered.HIGHEST_PRECEDENCE + 1)`로 문자 인코딩 설정 뒤, 폼 처리와 보안 필터 앞에서 크기를 검사합니다. 기존 `@Order(0)`에서는 CSRF 검사가 폼을 먼저 읽어 큰 본문이 빈 본문처럼 넘어왔습니다. 무조건 최우선에 두면 인코딩 설정보다 먼저 읽게 되므로 한 단계 뒤로 배치했습니다. [Spring Boot의 필터 순서 주의사항](https://docs.spring.io/spring-boot/3.5/reference/web/servlet.html#web.servlet.embedded-container.servlets-filters-listeners)

검사 대상은 원문 URI가 아니라 컨테이너가 해석한 `getServletPath()`로 고릅니다. 원문 `/%6frders`도 실제 주문 경로여서 같은 8KiB 제한을 적용합니다. JSON의 중복 필드는 Jackson의 `strict-duplicate-detection`으로 변환 전에 거절합니다. `@Valid`는 파싱이 끝난 DTO의 값만 검사하므로 이 두 검사를 대신하지 못합니다.

취소 API는 MVC의 폼 변환 대신 검사한 원본 본문을 직접 확인합니다. 본문이 한 바이트라도 있으면 400입니다. CSRF 토큰은 세션 쿠키와 요청 헤더로 보내며 폼 본문으로 대신하지 않습니다.

알려진 길이가 큰 경우 스트림을 열기 전에 413을 반환합니다. Tomcat의 100 Continue도 본문 읽기 시점에 보내도록 설정해 전송 허용을 기다리는 큰 요청을 먼저 거절합니다. 크기를 허용한 요청과 chunked는 기존처럼 실제 바이트를 검사합니다.

## 취소와 재시도

`tryCancelOwnedOrder`는 로그인 고객의 ACCEPTED 주문만 바꿉니다. 상태를 바꾼 요청만 저장된 수량을 복원하고 RESTORE 이력을 저장합니다. 이미 취소된 주문은 현재 결과만 반환합니다.

[이름과 주석의 작성 기준](NAMING_AND_JAVADOC.md)에 변수/메서드의 구분, 실제 바꾼 이름의 이유와 Javadoc을 읽는 방법을 정리했습니다.

동시에 실행할 수 있는 요청 수를 넘으면 429와 `Retry-After: 1`을 반환합니다. 구매와 취소의 분당 횟수는 각각 계산하며 해당 횟수가 초기화될 때까지 기다릴 초를 알려줍니다. 구매 한도를 소진해도 취소 한도는 별도지만 공통 동시 처리 제한은 유지합니다. DB 연결이나 잠금 문제의 503도 성공으로 처리하지 않습니다. 응답을 받지 못했다면 같은 구매 키, 취소는 같은 주문 ID로 결과를 확인합니다.

## 오류 응답과 로그

주문 컨트롤러의 정상 응답은 `ResponseEntity<OrderResponse>`입니다. 오류 응답은 `ApiExceptionHandler`가 `ErrorResponse`로 변환합니다. 재고 부족을 서비스 안에서 예외로 던지면 저장한 거절 결과까지 롤백되므로, 커밋 후 컨트롤러에서 오류로 바꿉니다.

공통 오류 처리기의 500과 503 응답에는 `X-Error-Id`가 있습니다. 같은 번호와 오류 코드, 예외 종류, 허용한 SQLSTATE만 로그로 남깁니다. 비밀번호, 요청 원문과 SQL이 담길 수 있는 예외 메시지와 전체 예외 객체는 기록하지 않습니다. 이 검사는 오류 번호 연결과 노출 여부 확인이며 전체 서비스의 추적 시스템을 검증한 것은 아닙니다.

인증은 MVC 앞의 보안 필터에서 실행됩니다. 따라서 계정 조회 DB가 실패한 응답은 `AuthenticationEntryPoint`에서도 구분합니다. 비밀번호 오류는 401을 유지하고 저장소 연결 장애는 503으로 반환합니다. `DatabaseFailures`가 원인, SQLSTATE와 원래 트랜잭션 예외를 확인하며 예외 원문은 응답하지 않습니다.

계정 SQL은 `CustomerAccountRepository`, 로그인 정보 변환은 `CustomerUserDetailsService`가 맡습니다. `SecurityConfiguration`에는 인증 방식과 오류 응답 설정을 유지합니다. 계정이 없을 때와 DB가 실패했을 때의 예외를 섞지 않으며, 저장된 해시와 CUSTOMER 권한도 바꾸지 않았습니다. `LoginAccount`는 인증용 조회 결과로, 문자열 출력에 계정명과 비밀번호 해시를 넣지 않습니다.

## 구조 검사

`PackageStructureTest`가 ArchUnit으로 제품 클래스 파일을 자동 탐색합니다. 컨트롤러가 저장소를 직접 호출하거나 서비스가 JDBC를 사용하는 경우, 내부 타입이 HTTP DTO를 사용하는 경우 등을 거절합니다. 메서드 안에서만 DTO를 생성하는 위반 예제도 검사해 필드나 메서드 선언만 읽던 누락을 보완했습니다. 검사 전용 예제와 ArchUnit은 실행 JAR에 포함하지 않습니다.

`ApiExceptionHandler`는 Spring의 오류 상태뿐 아니라 `Allow`, `Accept`와 지정된 `Retry-After`도 보존합니다. 상태만 복사하면 클라이언트가 허용 메서드나 지원 형식을 알 수 없었습니다. 본문을 JSON 오류 코드로 바꾸므로 원래 본문의 길이와 형식은 복사하지 않고, 로그에 남긴 오류 번호도 덮어쓰지 않습니다.

## 고객별 목록 인덱스

`orders_buyer_page(buyer_id, id DESC)`는 자기 주문을 ID 내림차순으로 찾는 목록 조회용입니다. 주문 10만 건을 고객 1,000명에 나눈 조건에서, 이 인덱스가 없으면 PK를 거꾸로 읽으며 다른 고객의 주문 약 2만 건을 제외했습니다. 인덱스가 있으면 고객 ID와 이전 페이지의 마지막 ID를 검색 조건으로 사용해 20건을 찾았습니다.

고객 2명 조건에서는 인덱스가 있어도 PostgreSQL이 PK를 선택했습니다. 모든 조회에 복합 인덱스가 유리하다는 뜻은 아닙니다. 고객별 목록을 위한 기존 인덱스는 유지하고, 새 인덱스나 강제 실행 계획은 추가하지 않았습니다. 합성 데이터의 SQL 실행 계획이며 HTTP 속도나 쓰기 비용 비교는 아닙니다.

## 멘토링 핵심 답변

| 질문 | 짧은 답변과 근거 |
| --- | --- |
| 왜 JDBC를 골랐나요? | 수량 조건을 둔 SQL과 실제 변경 건수가 핵심입니다. JdbcClient로 그 조건을 직접 표현했습니다. JPA가 느리다고 판단한 것은 아닙니다. |
| 트랜잭션만 붙이면 되나요? | 부분 저장은 막지만 같은 수량을 읽는 경쟁을 없애지는 않습니다. 재고 조건부 UPDATE와 구매 키 UNIQUE가 각각 필요합니다. |
| 조건부 UPDATE는 락이 없나요? | 같은 재고 기록을 바꾸는 요청은 DB에서 기다립니다. 먼저 요청이 끝나면 바뀐 수량으로 조건을 다시 검사합니다. |
| 같은 구매 키가 동시에 오면요? | 한 요청만 등록하고, 충돌한 요청은 확정 결과를 새 SELECT로 읽습니다. 다른 상품이나 수량이면 거절합니다. |
| 취소는 왜 한 번만 복구되나요? | ACCEPTED를 CANCELLED로 실제 바꾼 요청만 저장된 수량을 더합니다. 상태와 복구 이력도 같은 트랜잭션입니다. |
| 왜 DTO를 나눴나요? | JSON 필드와 내부 주문 자료를 따로 바꾸기 위해서입니다. 컨트롤러가 변환하며 서비스와 SQL 저장소는 HTTP DTO를 받지 않습니다. |
| DB가 꺼지면 자동으로 주문을 다시 실행하나요? | 자동 실행하지 않습니다. 서버의 연결이 복구된 뒤 고객이 같은 키로 결과를 확인합니다. 확정 여부를 모른다고 새 키를 만들지 않습니다. |
| 트랜잭션 관리자를 왜 명시했나요? | 실제 DB 중단에서 커밋 오류가 롤백 오류에 가려졌습니다. 표준 DataSourceTransactionManager로 원래 SQL 오류를 보존했습니다. 저장 범위와 격리 수준은 그대로입니다. |
| 인증 코드를 왜 나눴나요? | 보안 설정에서 SQL을 제거하고, 저장소는 계정 조회, UserDetailsService는 로그인 정보 변환을 맡게 했습니다. 비밀번호 비교와 인증 응답은 기존 처리를 유지했습니다. |
| 새 클래스도 구조 검사를 받나요? | 제품 패키지의 컴파일된 클래스 파일을 자동으로 읽고 패키지별 규칙을 적용합니다. 메서드 안에서만 잘못된 타입을 쓰는 경우도 검사합니다. |
| 어느 범위까지 확인했나요? | 정규 172건과 별도 DB 중단 15건을 구분합니다. 목록 인덱스의 SQL 계획 24개와 k6는 이전 코드의 기록이며 이번에 다시 측정하지 않았습니다. |

[백엔드 점검표](BACKEND_REVIEW.md)에 유지한 구성과 수정 이유, 남은 한계를 모았습니다.

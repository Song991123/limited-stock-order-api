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
| [WriteAdmission](../src/main/java/dev/mentoring/orders/web/admission/WriteAdmission.java) | 동시에 처리할 요청 수와 고객별 분당 요청 수 제한 |
| [RequestSizeFilter](../src/main/java/dev/mentoring/orders/web/filter/RequestSizeFilter.java) | 다른 필터가 읽기 전 본문 크기 검사 |

요청과 응답 DTO는 `dto.request`와 `dto.response`, 내부 주문 정보는 `domain`, DB 구매 기록 조회 결과는 `repository.model`에 있습니다. 컨트롤러, 서비스와 저장소도 각각의 패키지로 구분했습니다. [입력 검증과 코드 구성](VALIDATION_AND_STRUCTURE.md)에서 나눈 이유와 입력별 검사 위치를 확인할 수 있습니다.

## 주문 한 건의 처리

1. 크기 필터가 본문을 읽어 8KiB 초과 요청을 거절합니다. 허용한 본문은 뒤에서 읽을 수 있게 보관합니다.
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
| `@Configuration(proxyBeanMethods = false)`, `@Bean` | 비밀번호 인코더, 계정 조회와 보안 필터 체인 등록. Bean 메서드끼리 직접 호출하지 않아 설정 클래스 프록시는 생략 |
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

## 취소와 재시도

`tryCancelOwnedOrder`는 로그인 고객의 ACCEPTED 주문만 바꿉니다. 상태를 바꾼 요청만 저장된 수량을 복원하고 RESTORE 이력을 저장합니다. 이미 취소된 주문은 현재 결과만 반환합니다.

[이름과 주석의 작성 기준](NAMING_AND_JAVADOC.md)에 변수/메서드의 구분, 실제 바꾼 이름의 이유와 Javadoc을 읽는 방법을 정리했습니다.

동시에 실행할 수 있는 요청 수를 넘으면 429와 `Retry-After: 1`을 반환합니다. 고객별 분당 횟수를 넘으면 횟수가 초기화될 때까지 기다릴 초를 알려줍니다. 시간이 지나도 재고나 서버 상황에 따라 실패할 수 있습니다. DB 연결이나 잠금 문제의 503도 성공으로 처리하지 않습니다. 응답을 받지 못했다면 같은 구매 키, 취소는 같은 주문 ID로 결과를 확인합니다.

## 오류 응답과 로그

주문 컨트롤러의 정상 응답은 `ResponseEntity<OrderResponse>`입니다. 오류 응답은 `ApiExceptionHandler`가 `ErrorResponse`로 변환합니다. 재고 부족을 서비스 안에서 예외로 던지면 저장한 거절 결과까지 롤백되므로, 커밋 후 컨트롤러에서 오류로 바꿉니다.

공통 오류 처리기의 500과 503 응답에는 `X-Error-Id`가 있습니다. 같은 번호와 오류 코드, 예외 종류, 허용한 SQLSTATE만 로그로 남깁니다. 비밀번호, 요청 원문과 SQL이 담길 수 있는 예외 메시지와 전체 예외 객체는 기록하지 않습니다. 이 검사는 오류 번호 연결과 노출 여부 확인이며 전체 서비스의 추적 시스템을 검증한 것은 아닙니다.

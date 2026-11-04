# 코드 읽기와 어노테이션

고객이 상품 2개를 주문하면 재고를 2개 줄이고 주문을 만듭니다. 같은 구매 요청을 다시 보내도 추가로 차감하지 않습니다. 취소도 한 번만 재고를 돌려줍니다. 이 세 동작을 기준으로 코드를 읽으면 됩니다.

## 읽는 순서

| 파일 | 확인할 동작 |
| --- | --- |
| [OrderController](../src/main/java/dev/mentoring/orders/OrderController.java) | 요청 입력, 로그인 고객, 접수 결과의 HTTP 응답 |
| [OrderService](../src/main/java/dev/mentoring/orders/OrderService.java) | 새 구매와 재전송 구분, 주문과 취소의 저장 경계 |
| [OrderRepository](../src/main/java/dev/mentoring/orders/OrderRepository.java) | 실제 SQL, 재고와 상태를 바꾸는 조건 |
| [V1 마이그레이션](../src/main/resources/db/migration/V1__orders_and_inventory.sql) | 중복 키, 수량 범위와 미완료 요청 커밋 제한 |
| [WriteAdmission](../src/main/java/dev/mentoring/orders/WriteAdmission.java) | 쓰기 동시 진입 수와 고객별 요청 수 제한 |
| [RequestSizeFilter](../src/main/java/dev/mentoring/orders/RequestSizeFilter.java) | 다른 필터가 읽기 전 본문 크기 검사 |

## 주문 한 건의 처리

1. 크기 필터가 본문을 읽어 8KiB 초과 요청을 거절합니다. 허용한 본문은 뒤에서 읽을 수 있게 보관합니다.
2. Spring Security가 계정과 CSRF 헤더를 검사하고, MVC가 상품과 수량의 입력 조건을 검사합니다.
3. 컨트롤러가 쓰기 한도를 확인한 뒤 서비스의 `placeOrder`를 호출합니다. 구매자 ID는 본문이 아니라 로그인 계정에서 구합니다.
4. 서비스는 고객과 구매 키의 요청 기록을 먼저 등록합니다. 같은 키가 이미 있으면 상품과 수량을 대조해 원래 주문 또는 거절 결과를 반환합니다.
5. 새 요청만 `tryDecreaseStock`으로 이동합니다. 남은 수량이 충분할 때만 UPDATE가 1건을 바꿉니다.
6. 차감 성공이면 주문, 차감 이력과 접수 결과를 같은 트랜잭션에 저장합니다. 재고 부족이면 거절 결과만 저장합니다.
7. 서비스 메서드가 정상 반환하면 Spring 프록시가 커밋합니다. 커밋 성공 후 컨트롤러가 신규 201, 재전송 200, 재고 부족 409로 변환합니다.

서비스의 `OrderPlacement`는 `Created`, `Replayed`, `OutOfStock` 중 하나입니다. 예전의 숫자 상태와 nullable 주문/오류 조합 대신, 가능한 결과만 표현합니다. `sealed interface`는 결과 종류를 제한하고 컨트롤러의 `switch`가 모두 처리하게 하는 Java 문법입니다.

## 동시성 제어의 역할

| 장치 | 막는 문제 | 막지 못하는 문제 |
| --- | --- | --- |
| 고객/구매 키 UNIQUE | 같은 구매를 두 번 등록 | 새 키로 보낸 별도 구매 |
| 재고 조건부 UPDATE | 남은 수량보다 많이 차감 | 먼저 도착한 고객의 우선권 |
| `status = 'ACCEPTED'` 취소 UPDATE | 같은 주문의 두 번 복구 | 모든 주문을 한 번에 취소하는 전역 규칙 |
| 서비스 트랜잭션 | 주문만 남거나 재고만 바뀌는 부분 저장 | 평범한 SELECT 결과의 독점 사용 |
| 수량 CHECK, 이력 UNIQUE | 잘못된 수량과 중복 이력의 커밋 | 올바른 업무 순서 자체 |
| Semaphore와 고객별 카운터 | 한 JVM 안의 과도한 쓰기 진입 | 여러 서버가 공유하는 한도와 DB 정합성 |

재고가 1개일 때 A와 B가 각각 1개를 주문하면 A의 UPDATE가 재고 기록을 잠급니다. B가 같은 기록을 갱신하려면 기다립니다. A가 커밋한 뒤 DB가 B의 `수량 >= 1`을 다시 검사하므로 B는 차감하지 못합니다. 잠금이 없는 방식도, 도착 순서대로 처리하는 대기열도 아닙니다. [PostgreSQL의 갱신과 조건 재검사](https://www.postgresql.org/docs/16/transaction-iso.html#XACT-READ-COMMITTED)

## 저장 경계의 어노테이션

```java
@Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
public OrderPlacement placeOrder(String username, String idempotencyKey, CreateOrderRequest orderRequest)
```

- `@Transactional`: 이 서비스 호출의 SQL을 같은 DB 트랜잭션에 참여시킵니다. 접수와 취소에 각각 적용해 원자적으로 확정할 범위를 드러냅니다.
- `READ_COMMITTED`: 각 SQL이 시작할 때 확정된 자료를 읽습니다. 동일 키의 등록 충돌 뒤 별도 SELECT로 선행 결과를 읽는 흐름과 맞춥니다. 이것만으로 수량 경쟁이 해결되지는 않습니다.
- `rollbackFor = Exception.class`: checked 예외도 롤백 대상으로 포함합니다. 현재 저장 오류는 주로 unchecked 예외라 기본 규칙으로도 롤백되지만, checked 예외가 전파될 때의 경계도 명시합니다.
- `readOnly = true`: 주문 조회에 적용한 읽기 전용 트랜잭션 설정입니다. 고객 소유권은 이 설정이 아니라 SQL의 `buyer_id` 조건으로 검사합니다.

컨트롤러가 Spring이 주입한 서비스를 호출하므로 트랜잭션 프록시를 통과합니다. 서비스 내부의 private 메서드는 이미 열린 트랜잭션 안에서 실행합니다. 내부 메서드에 새 어노테이션을 붙이는 것만으로 별도 트랜잭션이 생기는 것은 아닙니다. 커밋 실패도 호출자에게 예외로 전달돼 성공 응답을 만들지 않습니다. [Spring 트랜잭션 프록시와 기본값](https://docs.spring.io/spring-framework/reference/6.2/data-access/transaction/declarative/annotations.html)

초기 데이터는 `ApplicationRunner.run`의 `TransactionTemplate` 콜백 안에서 저장합니다. 주문 API의 저장 경계와 별개이며, 콜백에 포함된 저장을 함께 확정합니다.

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
| `@Configuration(proxyBeanMethods = false)`, `@Bean` | 비밀번호 인코더, 계정 조회와 보안 필터 체인 등록. Bean 메서드끼리 직접 호출하지 않아 설정 클래스 프록시는 생략 |
| `@Value` | 설정 파일의 쓰기 한도와 실행 시 생성한 비밀번호 주입 |
| `@RestControllerAdvice`, `@ExceptionHandler` | 입력/업무/DB 오류를 같은 API 오류 형식으로 변환 |
| `@Order` | Servlet 필터의 실행 순서 지정. 고객 주문의 처리 순서와는 무관 |
| `@Override` | 상위 타입의 메서드를 구현하는지 컴파일러가 확인 |

컨트롤러의 클래스 단위 `@Validated`는 제거했습니다. Spring 6.1 이후 MVC가 직접 제공하는 경로/쿼리 검증을 사용해 별도 AOP 검증 경로를 겹치지 않게 했습니다. 본문의 `@Valid`는 유지합니다. 실제 HTTP로 잘못된 경로와 쿼리 7개를 보내 400을 확인했습니다. [Spring MVC 입력 검증](https://docs.spring.io/spring-framework/reference/6.2/web/webmvc/mvc-controller/ann-validation.html)

생성자는 클래스마다 하나입니다. Spring이 생성자 매개변수로 의존성을 주입하므로 별도 `@Autowired`를 추가하지 않았습니다. 입력/응답의 `record`, 상태의 `enum`은 Java 문법이며 Spring 어노테이션이 아닙니다.

## 본문 크기와 필터 순서

`@Order(Ordered.HIGHEST_PRECEDENCE + 1)`로 문자 인코딩 설정 뒤, 폼 처리와 보안 필터 앞에서 크기를 검사합니다. 기존 `@Order(0)`에서는 CSRF 검사가 폼을 먼저 읽어 큰 본문이 빈 본문처럼 넘어왔습니다. 무조건 최우선에 두면 인코딩 설정보다 먼저 읽게 되므로 한 단계 뒤로 배치했습니다. [Spring Boot의 필터 순서 주의사항](https://docs.spring.io/spring-boot/3.5/reference/web/servlet.html#web.servlet.embedded-container.servlets-filters-listeners)

취소 API는 MVC의 폼 변환 대신 검사한 원본 본문을 직접 확인합니다. 본문이 한 바이트라도 있으면 400입니다. CSRF 토큰은 세션 쿠키와 요청 헤더로 보내며 폼 본문으로 대신하지 않습니다.

## 취소와 재시도

`tryCancelOwnedOrder`는 로그인 고객의 ACCEPTED 주문만 바꿉니다. 상태를 바꾼 요청만 저장된 수량을 복원하고 RESTORE 이력을 저장합니다. 이미 취소된 주문은 현재 결과만 반환합니다.

[이름과 주석의 작성 기준](NAMING_AND_JAVADOC.md)에 변수/메서드의 구분, 실제 바꾼 이름의 이유와 Javadoc을 읽는 방법을 정리했습니다.

동시 진입 한도 초과에는 429와 `Retry-After: 1`, 고객별 고정 1분 한도 초과에는 다음 구간까지 남은 초를 올림해 반환합니다. 이 값은 재시도 안내이며, 그때 성공한다는 예약은 아닙니다. DB 잠금/연결 일시 오류의 503도 성공으로 처리하지 않습니다. 응답을 잃으면 같은 구매 키, 취소는 같은 주문 ID로 확인합니다.

# 입력 검증과 코드 구성

고객이 상품과 수량을 보내면 재고를 확보하고 주문을 접수합니다. 취소할 때는 저장된 주문 수량만 재고에 돌려놓습니다. HTTP로 주고받는 DTO, 주문 처리에 쓰는 내부 자료형과 SQL 조회 결과를 구분했습니다.

## 코드를 나눈 기준

```text
dev.mentoring.orders
  OrdersApplication
  controller          주문, 상품과 CSRF 조회 API
  dto
    request           CreateOrderRequest
    response          OrderResponse, ProductResponse, CsrfResponse, ErrorResponse
  service             OrderService, ProductService
  repository          주문 저장 SQL, 상품 조회 SQL
    model             RecordedPurchase: 구매 기록, LoginAccount: 로그인 계정 조회 결과
  security            CustomerUserDetailsService: 로그인 정보를 Spring Security에 전달
  domain              Order, Product, 주문 상태와 처리 결과
  exception           업무 예외와 HTTP 오류 응답 처리
  web
    filter            본문 크기 검사
    admission         쓰기 요청 수 제한
  config              인증 방식과 오류 응답 설정, 로컬 실행용 초기 데이터
```

컨트롤러는 요청 DTO를 검증한 뒤 상품 ID와 수량을 서비스에 전달합니다. 서비스는 내부 주문 정보를 반환하고, 컨트롤러가 응답 DTO로 바꿉니다. 서비스와 저장소는 HTTP DTO를 사용하지 않습니다.

주문 접수와 취소는 `OrderService`에 유지했습니다. 같은 주문, 재고와 증감 이력을 함께 저장하기 때문입니다. 상품 조회는 `ProductService`와 `ProductRepository`로 옮겼습니다. 주문 전 상품 존재 확인도 같은 조회 저장소를 사용합니다. 주문과 취소의 트랜잭션 설정, 재고 SQL과 저장 순서는 바꾸지 않았습니다.

이 폴더 구성이 Spring의 필수 규칙인 것은 아닙니다. 작은 주문 서버에서 역할을 바로 찾기 위한 선택입니다. 실행 클래스는 최상위 패키지에 유지해 하위 구성요소를 탐색하게 했습니다. [Spring Boot 코드 구성](https://docs.spring.io/spring-boot/3.5/reference/using/structuring-your-code.html)

## 인증 조회의 역할 분리

`SecurityConfiguration`에 있던 계정 조회 SQL을 `CustomerAccountRepository`로 옮겼습니다. 같은 SQL과 계정명 매개변수 바인딩을 유지하며, 조회 결과는 HTTP 응답용 DTO가 아닌 `LoginAccount`로 받습니다. 이 객체를 문자열로 출력해도 계정명과 비밀번호 해시는 포함하지 않습니다.

`CustomerUserDetailsService`는 저장소의 계정 정보를 Spring Security의 `UserDetails`로 바꿉니다. 비밀번호 비교는 기존 BCrypt와 인증 제공자가 담당합니다. 계정이 없으면 `UsernameNotFoundException`, DB가 실패하면 원래 저장소 예외를 전달해 401과 503을 구분하는 기존 응답을 유지합니다. [Spring Security 계정 조회](https://docs.spring.io/spring-security/reference/6.5/servlet/authentication/passwords/user-details-service.html)

보안 설정은 인증 방식과 오류 응답 설정을 맡고, 계정 조회와 로그인 정보 변환은 각각의 클래스가 맡습니다. 주문과 취소의 SQL, 트랜잭션과 저장 순서는 그대로입니다.

## DTO와 내부 자료형

| 자료형 | 위치와 용도 |
| --- | --- |
| `CreateOrderRequest` | `dto.request`: 주문 JSON의 상품 ID와 수량, 입력 검증 조건 |
| `OrderResponse`, `ProductResponse` | `dto.response`: 외부에 보낼 주문과 상품 필드 |
| `CsrfResponse`, `ErrorResponse` | `dto.response`: CSRF 조회와 오류 응답 필드 |
| `Order`, `Product` | `domain`: 서비스와 저장소가 사용하는 주문과 상품 정보 |
| `OrderPlacement` | `domain`: 신규 접수, 기존 주문 반환과 재고 부족 결과 |
| `RecordedPurchase` | `repository.model`: 같은 구매인지 대조할 DB 조회 결과. HTTP 응답으로 직접 반환하지 않음 |
| `LoginAccount` | `repository.model`: 로그인 계정명과 비밀번호 해시. 인증 처리에만 사용 |

기존에는 요청 DTO가 SQL 저장소까지 전달되고, 응답용 `OrderView`가 내부 주문 정보로도 쓰였습니다. 이제 외부 JSON 필드와 내부 처리용 자료형을 따로 변경할 수 있습니다. DTO를 단순히 다른 폴더로 옮기는 데서 끝내지 않은 이유입니다. [DTO와 내부 객체의 변환](https://martinfowler.com/eaaCatalog/dataTransferObject.html)

주문 입력은 상품 ID와 수량 두 값이므로 서비스에 그대로 전달했습니다. 별도 명령 객체나 자동 매핑 라이브러리는 추가하지 않았습니다. 응답은 `OrderResponse.from(order)`처럼 값이 어디에서 오는지 보이는 변환 메서드를 사용합니다. 내부 `Order`는 JDBC 조회 결과를 담는 Java record이며 JPA 엔티티가 아닙니다.

## 입력별 검사 위치

| 입력 | 검사 위치와 규칙 | 거절 응답 |
| --- | --- | --- |
| 주문의 상품 ID와 수량 | `CreateOrderRequest`의 `@NotNull`, `@Positive`, 컨트롤러의 `@Valid` | 400 `INVALID_INPUT` |
| 소수, 문자열 숫자, 범위 밖 정수, 알 수 없는/중복 필드, JSON 뒤 추가 값 | Jackson 변환 설정 | 400 `INVALID_INPUT` |
| `Idempotency-Key` | `OrderService.validateIdempotencyKey`: 영문, 숫자, 밑줄과 하이픈 1~128자 | 400 `INVALID_REQUEST_KEY` |
| 상품 ID, 주문 ID, 목록 기준 ID와 조회 개수 | 컨트롤러의 `@Min`, `@Max`와 MVC 형 변환 | 400 `INVALID_INPUT` |
| 취소 요청의 본문 | `OrderController.cancelOrder`: 한 바이트라도 있으면 거절 | 400 `UNEXPECTED_BODY` |
| 주문과 취소 본문 크기 | `RequestSizeFilter`: 8KiB 초과 거절 | 413 `REQUEST_TOO_LARGE` |
| 로그인 계정과 변경 요청의 CSRF 토큰 | Spring Security | 401 또는 403 |

`Long`과 `Integer`로 받아 누락된 값과 0을 구별합니다. `@Positive`는 null을 거절하지 않으므로 `@NotNull`도 필요합니다. `@Valid`는 이 조건을 실행하며, 그 자체가 양수 조건은 아닙니다. [Jakarta Positive 정의](https://jakarta.ee/specifications/bean-validation/3.0/apidocs/jakarta/validation/constraints/Positive.html)

본문은 `@Valid`로, 경로와 쿼리는 MVC 내장 메서드 검증으로 검사합니다. 클래스에 `@Validated`를 다시 붙이지 않았습니다. 오류 처리기는 두 경로의 검증 예외를 모두 400으로 변환합니다. [Spring MVC 입력 검증](https://docs.spring.io/spring-framework/reference/6.2/web/webmvc/mvc-controller/ann-validation.html)

DTO의 어노테이션은 객체를 생성하기만 해도 자동 실행되지 않습니다. HTTP 입력은 MVC가 검사하고, 서비스도 ID와 수량, 목록 조회 범위를 저장소 호출 전에 검사합니다. 서비스를 직접 호출해도 같은 값 조건을 지키기 위해서입니다. 요청 JSON의 누락과 형 변환은 여전히 MVC가 맡습니다.

구매 키 형식 검사는 서비스에 유지했습니다. 구매 키는 주문 처리에도 필요하고, 잘못된 키에는 기존의 `INVALID_REQUEST_KEY` 응답을 유지하기 위해서입니다. 키의 형식 검사와 중복 판정은 다릅니다. 형식에 맞는 키도 같은 고객이 다른 상품이나 수량에 다시 쓰면 409입니다.

## 입력 검증과 재고 판단의 차이

새 구매 키로 요청한 상품이 없으면 404입니다. 기존 구매 키에 다른 상품을 보내면 존재 여부와 관계없이 키 충돌 409입니다. 수량이 남은 재고보다 많으면 409 `OUT_OF_STOCK`입니다. 재고는 요청이 겹치면 달라지므로, 입력 어노테이션이 아니라 DB의 조건부 UPDATE에서 판단합니다.

```sql
UPDATE inventories
SET available_quantity = available_quantity - :quantity
WHERE product_id = :id AND available_quantity >= :quantity;
```

임의의 최대 주문 수량은 추가하지 않았습니다. 현재 수량 타입의 최댓값도 유효한 양수이지만, 재고가 부족하면 주문을 만들지 않고 재고 부족 결과를 저장합니다. 취소 수량은 입력으로 받지 않고 원래 주문에서 읽습니다.

## 주문과 취소의 읽는 순서

주문은 `controller/OrderController.placeOrder`에서 시작합니다. 본문 검증 후 서비스가 키 형식과 기존 구매를 확인합니다. 기존 기록은 입력을 대조해 원래 결과를 반환하고, 기록이 없으면 상품 확인과 등록을 진행합니다. 동시 등록의 충돌은 유일성 제약과 별도 재조회로 처리합니다. 새로 등록한 요청만 재고, 주문과 이력을 저장합니다. 커밋에 성공한 뒤 컨트롤러가 응답 DTO와 HTTP 상태를 만듭니다.

취소는 `controller/OrderController.cancelOrder`에서 주문 ID와 빈 본문을 확인합니다. 서비스가 로그인 고객의 접수 주문을 취소 상태로 바꾼 경우에만 저장된 수량을 복구합니다. 취소 상태, 재고와 복구 이력은 함께 커밋합니다. 이미 취소된 주문에는 수량을 더하지 않습니다.

잘못된 입력을 고쳐 다시 요청할 수 있으므로 입력 거절만으로 구매 키를 사용한 것으로 저장하지 않습니다. 반면 재고 부족은 유효한 구매 요청의 처리 결과이므로 같은 키의 재요청에도 그 결과를 반환합니다.

## 테스트와 설명할 범위

`CreateOrderRequestValidationTest`는 누락과 0 이하를 어느 어노테이션이 거절하는지 분리해서 확인합니다. 이 단위 검사만으로 HTTP 연결까지 확인한 것은 아닙니다.

`ResponseMappingTest`는 주문과 상품을 응답 DTO로 바꿀 때 값이 유지되는지 검사합니다. `PackageStructureTest`는 ArchUnit 1.5.1로 `dev.mentoring.orders` 아래 제품 클래스 파일을 자동으로 읽습니다. 고정한 클래스 목록 대신 패키지별 규칙을 적용하므로, 새 클래스와 메서드 내부에서 사용한 타입도 검사합니다. [ArchUnit의 클래스 탐색과 의존 관계 검사](https://www.archunit.org/userguide/html/000_Index.html)

컨트롤러의 저장소 직접 사용, 서비스의 SQL 실행, 내부 자료형의 HTTP 의존, 저장소의 인증 프레임워크 의존과 설정 클래스의 JDBC 쿼리 혼용을 검사합니다. 기존 경로의 요청 검증과 응답 DTO 확인은 별도로 유지합니다. 패키지 이름과 지정한 의존 규칙을 확인하는 검사이며, 업무 규칙의 정확성을 대신하지 않습니다.

`architecturefixtures`의 위반 예제는 `src/test`에만 둡니다. 자동 탐색으로 발견한 서비스가 메서드 안에서만 HTTP DTO를 쓰는 경우와 컨트롤러가 저장소를 직접 쓰는 경우를 실제로 거절하는지 확인합니다. ArchUnit도 테스트 의존성이며 실행 JAR에 포함하지 않습니다.

`OrderServiceTest`와 `ProductServiceTest`는 MVC 없이 서비스를 호출해 입력 거절과 처리 순서를 확인합니다. 저장소 대역을 사용한 검사이므로 실제 커밋과 롤백의 근거로 사용하지 않습니다. `NormalStartupIntegrationTest`는 별도 빈 스키마와 실제 HTTP로 예제 데이터 없는 실행, 계정과 상품 0건, 미인증 요청 401을 확인합니다.

`OrderHttpIntegrationTest`는 실제 HTTP와 PostgreSQL을 연결해 잘못된 입력의 응답과 저장 결과를 함께 확인합니다. 입력 거절 뒤 같은 키로 정상 주문이 가능한지, 잘못된 취소 뒤에도 접수 상태와 차감된 수량이 남는지 검사합니다. 기존의 동시 주문, 재전송, 중복 취소, SQL 오류와 응답 유실 검사도 유지합니다.

실행 수와 실제 관찰 결과는 [검증 기록](VERIFICATION.md)에서 확인할 수 있습니다. 패키지 분리 자체를 성능 개선이나 운영 장애 해결로 설명하지 않습니다. 두 JVM의 DB 중복 방지는 검사했고, 여러 서버의 공통 요청 한도와 운영 수용량은 미검증입니다.

## 멘토링 답변

“요청 DTO, 응답 DTO와 내부 주문 정보를 구분했습니다. 컨트롤러에서 입력을 검증하고 필요한 값을 서비스에 전달합니다. 서비스와 SQL은 HTTP DTO를 모르며, 처리 후 컨트롤러가 응답 DTO로 바꿉니다. 상품 조회도 주문 처리에서 분리했습니다. 기존 재고 차감과 취소 트랜잭션은 유지하고, JSON 필드와 동시 요청 처리 결과가 같은지 검사했습니다.”

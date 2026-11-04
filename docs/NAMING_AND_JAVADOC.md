# 이름과 주석의 작성 기준

## 조사 결과

변수까지 동사로 짓는 규칙은 아닙니다. NAVER의 공개 Java 규칙은 메서드를 동사 중심으로, 변수를 lowerCamelCase로 구분합니다. Google Java Style도 메서드는 행동, 필드는 대상 중심의 이름을 권합니다. 국내 전체의 단일 표준으로 일반화하지 않고 두 지침의 겹치는 기준을 적용했습니다. [NAVER Java 규칙](https://github.com/naver/hackday-conventions-java/blob/master/src/java-rules.adoc), [Google 이름 규칙](https://google.github.io/styleguide/javaguide.html#s5-naming)

| 대상 | 작성 기준 | 이 프로젝트의 예 |
| --- | --- | --- |
| 클래스 | 맡은 역할을 나타내는 명사 | `OrderRepository`, `RequestSizeFilter` |
| 일반 메서드 | 행동과 대상 | `placeOrder`, `cancelOrder`, `restoreStock` |
| 조회 | 조회 대상과 필요한 범위 | `findOwnedOrder`, `listOrders` |
| 조건부 변경 | 변경 시도와 결과 판정 가능성 | `tryDecreaseStock`, `tryCancelOwnedOrder` |
| boolean 반환 | 질문으로 읽히는 조건 | `hasSameOrderDetails`, `isRetryableDatabaseFailure` |
| 변수/매개변수 | 담긴 대상, 필요할 때 범위와 단위 | `idempotencyKey`, `beforeOrderId`, `retryAfterSeconds` |
| 시간 상수 | 값의 단위와 변환 의미 | `NANOS_PER_SECOND` |
| record 접근자 | Java가 생성하는 필드 접근 이름 유지 | `order.id()`, `order.status()` |

`find`, `try`, `get`의 구체적인 보장까지 Java가 강제하지는 않습니다. 예를 들어 이 저장소의 `findOwnedOrder`는 없으면 예외를 던지고, `tryCancelOwnedOrder`는 상태를 바꾸지 못하면 빈 Optional을 반환합니다. 접두사만 믿지 않도록 반환과 실패 조건을 Javadoc에 명시했습니다.

## 이름의 길이

단어를 많이 붙이는 것보다 어떤 개념을 구분할지 먼저 정합니다. 순서는 대상 개념 선택, 프로젝트 용어 선택, 이름 조합입니다. 명명 실험 연구도 이 과정을 제안했고, 후속 연구는 단순히 더 길게 쓰라고 요청하는 것만으로는 같은 개선을 얻지 못했다고 보고했습니다. 이 연구 결과를 코드 이해도 향상 수치로 가져오지는 않습니다. [How Developers Choose Names](https://arxiv.org/abs/2103.07487), [명명 실험의 재현과 확장](https://arxiv.org/abs/2402.10022)

- `key`는 어떤 키인지 모르므로 `idempotencyKey`로 변경.
- 구매 요청 기록 ID와 주문 ID는 다르므로 `purchaseRequestId`, `orderId`로 구분.
- `registeredRequestId`는 이번에 새로 등록한 요청의 ID를 나타냄. 빈 Optional이면 기존 결과 조회로 이동.
- `changed`는 무엇이 바뀌었는지 모르므로 `cancelledOrder`로 변경.
- `remaining`은 시간인지 수량인지 모르므로 `remainingWindowNanos`로 변경.
- 반환 개수는 `pageSize`, 상한 주문 ID는 `beforeOrderId`로 구분.
- `jdbcClient`, `passwordEncoder`처럼 널리 쓰이는 기술 용어는 유지.

문장 전체를 이름으로 만들지는 않습니다. 예를 들어 `remainingNanosecondsUntilTheCurrentCustomerWindowEnds`보다 `remainingWindowNanos`를 사용하고, 계산식의 범위는 `RateWindow` 안에 둡니다. 길이가 아니라 문맥 안에서 대상을 혼동하지 않는지가 기준입니다.

## 바꾼 이름과 선택 이유

| 기존 이름 | 적용한 이름 | 구분하려는 의미 |
| --- | --- | --- |
| 서비스 `purchase`, `cancel`, `get`, `list` | `placeOrder`, `cancelOrder`, `getOrder`, `listOrders` | 서비스 메서드만 읽어도 주문에 대한 행동이 드러남 |
| `service`, `repository` | `orderService`, `orderRepository` | 의존성의 업무 대상 |
| `key`, `input` | `idempotencyKey`, `orderRequest` | 인증 키/DB PK와 구매 키, HTTP 요청과 주문 입력 구분 |
| `requestId` | `purchaseRequestId` | 주문 ID와 구매 요청 기록 ID 구분 |
| `existing` | `recordedPurchase`, `existingOrder` | 최초 구매 결과와 현재 주문 상태 구분 |
| `ORDER`, `PRODUCT` | `ORDER_ROW_MAPPER`, `PRODUCT_ROW_MAPPER` | 데이터 한 건이 아니라 SQL 결과 변환기라는 역할 |
| `jdbc`, `rs`, `name` | `jdbcClient`, `resultSet`, `columnName` | 조회 도구, 결과 행과 컬럼명 구분 |
| `slots`, `windows` | `writePermits`, `customerRateWindows` | 동시 진입 권한과 고객별 빈도 구간 구분 |
| `concurrent`, `perCustomerPerMinute` | `maxConcurrentWrites`, `maxRequestsPerCustomerPerMinute` | 현재 사용량이 아닌 허용 상한 |
| `minute`, `count`, `remaining` | `windowIndex`, `requestCount`, `remainingWindowNanos` | 구간 번호, 요청 수와 시간 단위 |
| `execute`, `reserve` | `executeWrite`, `checkAndCountRequest` | 작업 실행과 원자적인 한도 검사/카운트 구분 |
| 예외 처리기의 `order`, `database` | `handleOrderProblem`, `handleDatabaseFailure` | 조회가 아니라 오류 응답 처리라는 행동 |
| `purchaseResponse` | `toOrderResponse` | 업무 결과를 HTTP 응답으로 바꾸는 변환 |

`OrderProblem.missing()`과 `invariant()`는 해당 예외를 생성하는 짧은 정적 팩토리입니다. record의 `reason()` 같은 접근자나 예외 팩토리까지 기계적으로 `get`/`create`를 덧붙이지 않았습니다. 실제 반환 의미는 각 선언의 Javadoc에서 확인할 수 있습니다.

## API 이름과 Java 이름

이름 정리로 외부 요청 규격을 바꾸지 않았습니다.

```java
@GetMapping("/products/{id}")
public ProductView getProduct(@PathVariable("id") @Min(1) long productId)
```

URL의 `{id}`는 그대로 두고 Java에서는 `productId`로 읽습니다. 주문은 `orderId`를 사용합니다. `beforeId`, `limit` 쿼리도 각각 `beforeOrderId`, `pageSize`에 명시적으로 연결했습니다. JSON의 `id`, `productId`, `quantity`와 DB 컬럼명은 유지했습니다.

## 클래스와 메서드 Javadoc

클래스 역할은 import 뒤, 어노테이션과 클래스 선언 바로 앞에 둡니다. 파일 첫 줄에 떨어뜨려 쓰는 설명보다 IDE의 클래스 정보와 생성 문서에 연결됩니다. Java 21의 `/** ... */` 형식을 사용합니다. [JDK 21 문서 주석 명세](https://docs.oracle.com/en/java/javase/21/docs/specs/javadoc/doc-comment-spec.html)

```java
/**
 * 자기 주문의 취소 상태, 재고 복원과 복원 이력을 함께 저장.
 *
 * <p>ACCEPTED에서 CANCELLED로 바뀐 요청만 수량을 복원.
 * 이미 취소됐다면 추가 복원 없이 반환.
 *
 * @param orderId 취소할 주문 ID
 * @return 취소된 자기 주문
 * @throws OrderProblem 자기 주문이 없거나 재고 복원 조건이 깨진 경우
 */
```

첫 줄은 역할, 다음 문단은 중요한 조건입니다. `@param`, `@return`, `@throws`에는 식별자 번역을 반복하지 않고 범위, 단위, 빈 값과 실패의 뜻을 적습니다. 상속 메서드는 정해진 이름을 유지하며 이 구현에서 달라지는 처리만 설명합니다.

주문 접수에는 새 요청만 차감한다는 조건과 커밋 시점을, 취소에는 상태를 바꾼 요청만 복원한다는 조건을 적었습니다. 요청의 최초 결과와 주문의 현재 상태도 서로 다르다고 명시했습니다. 클래스, 메서드, record와 enum 설명은 코드 가까이에 두고 연구 출처와 명명 비교는 이 문서로 분리했습니다.

구현 주석은 코드에서 바로 알기 어려운 선택 이유에 사용합니다. 우아한형제들 기술블로그의 팀 사례도 대상이 불명확한 문제는 이름으로, 선택 이유가 사라지는 문제는 가까운 주석이나 테스트로 남기는 방식을 설명합니다. 특정 팀의 경험이며 회사 전체의 강제 규칙으로 해석하지 않습니다. [팀 문화의 탄생](https://techblog.woowahan.com/2677/)

`// 시작`, `// 끝`, 가공의 작성자/수정 이력, 모든 대입문을 한국어로 옮긴 주석은 넣지 않았습니다. 메서드와 빈 줄이 단락을 나누고 Javadoc이 그 단락의 역할을 설명합니다. `~합니다`, `~입니다` 대신 짧은 설명형 문구를 사용합니다.

## 문서 생성과 검사

전체 검사 스크립트는 테스트, 실행 JAR와 Javadoc을 함께 생성합니다.

```powershell
.\scripts\test.ps1 -IsolatedDatabase
```

생성 문서는 `build/docs/javadoc/index.html`에서 클래스와 메서드별로 읽을 수 있습니다. JDK doclint로 잘못된 태그, 문법과 문서 내 참조를 검사하고 경고도 빌드 실패로 처리합니다. 단순 필드/자동 생성 접근자의 누락 경고는 제외합니다. doclint 통과는 설명의 정확성을 보증하지 않으므로 실제 HTTP/DB 검사와 따로 확인합니다.

선언별 주석 존재는 JDK의 JavaCompiler/DocTrees로 따로 확인했습니다. 주 코드 19개 파일, 타입 선언 27개와 직접 작성한 메서드/생성자 76개 모두 Javadoc이 있었습니다. 자동 생성된 record 접근자까지 사람이 주석을 작성했다고 세지 않았습니다. [주석 검사 기록](../evidence/verification/documentation.json)에 범위를 남깁니다.

주석과 이름은 저장 경계, 재고 조건, 유일성 제약이나 API 응답을 바꾸는 수정이 아닙니다. 같은 구매의 중복 처리, 취소 복원과 종료 뒤 재시도는 기존 회귀 검사로 확인하고, 기존 쿼리 이름의 페이지 조회도 추가로 검사합니다. 읽기 쉬워졌다는 판단을 성능 개선율이나 사람의 독립 평가 결과로 표현하지 않습니다.

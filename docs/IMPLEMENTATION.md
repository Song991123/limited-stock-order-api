# 핵심 코드와 선택 이유

## 주문 재전송 처리

[OrderService.placeOrder](../src/main/java/dev/mentoring/orders/OrderService.java)는 인증 계정, 요청 키와 입력을 확인한 뒤 요청을 먼저 등록합니다. 같은 고객과 키에는 DB 유일성 제약을 적용합니다. 이미 있으면 별도 SELECT로 상품과 수량을 대조하고 원래 결과를 사용합니다.

[OrderRepository.tryRegisterPurchase](../src/main/java/dev/mentoring/orders/OrderRepository.java)은 `ON CONFLICT DO NOTHING RETURNING id`를 사용합니다. 같은 키의 등록이 겹치면 DB가 먼저 진행된 요청의 확정을 기다립니다. 새 요청만 재고 차감으로 내려갑니다. 같은 상품의 별도 구매는 새 키를 사용하므로 허용합니다.

## 마지막 재고 처리

재고를 읽은 뒤 계산한 값을 저장하지 않고, 수량 조건과 차감을 같은 UPDATE에 둡니다. 변경 1건을 확인한 요청만 주문과 차감 이력을 저장합니다. PostgreSQL에서 같은 기록의 갱신을 기다린 뒤 수량 조건을 다시 확인합니다. [PostgreSQL 동시 UPDATE](https://www.postgresql.org/docs/16/transaction-iso.html#XACT-READ-COMMITTED)

SELECT FOR UPDATE 뒤 검사하는 방법과 JPA 버전 검사도 대안입니다. 현재는 상품 한 종류의 수량 조건을 직접 표현하고 변경 건수로 판단할 수 있어 Spring JDBC를 선택했습니다. 잠금 대기와 같은 상품에 집중되는 쓰기 비용은 남습니다. 방식별 속도 우위는 측정하지 않았습니다.

## 주문과 요청 결과의 동시 저장

서비스 메서드의 트랜잭션 안에서 요청 등록, 재고 차감, 주문과 이력, 요청 결과를 함께 확정합니다. SQL 오류는 전부 롤백합니다. 서비스는 `OrderPlacement`로 신규/재전송/재고 부족을 구분하고, 커밋 성공 뒤 Controller가 HTTP 상태로 변환합니다. 재고 부족도 요청 거절 결과를 커밋한 뒤 409를 반환합니다.

[V1 마이그레이션](../src/main/resources/db/migration/V1__orders_and_inventory.sql)에는 수량 CHECK, 요청 키와 증감 이력의 UNIQUE도 있습니다. 결과를 채우지 않은 요청이 실수로 커밋되는 것을 막기 위해, 커밋 시점에 결과를 확인하는 지연 제약 트리거를 추가했습니다. PostgreSQL 의존성이 늘어나는 비용이 있으며 실제 DB 검사에서 미완료 요청의 직접 커밋을 거절하는지 확인했습니다.

## 취소와 재고 복구

`OrderService.cancelOrder`는 자기 주문이 ACCEPTED일 때만 CANCELLED로 바꿉니다. 실제 상태를 바꾼 요청만 저장된 수량을 복구하고 RESTORE 이력을 남깁니다. 이미 취소된 주문에는 추가 수량을 더하지 않습니다.

취소 상태, 수량과 복구 이력도 같은 트랜잭션입니다. 복구 수량의 상한 또는 이력 저장에서 오류가 나면 접수 상태와 기존 수량으로 롤백합니다. 별도 재처리 스케줄러 없이 원래 주문 ID로 결과를 확인합니다.

## 로그인과 요청값 검사

Spring Security의 계정 인증과 CSRF 검사를 유지하고, 주문 조회/취소 SQL에 고객 ID 조건을 넣습니다. 클라이언트가 다른 고객 ID나 복구 수량을 보내 결정하게 하지 않습니다. 문자열 숫자, 소수 수량과 알 수 없는 필드를 거절합니다.

[RequestSizeFilter](../src/main/java/dev/mentoring/orders/RequestSizeFilter.java)는 폼 처리와 보안 필터가 본문을 읽기 전에 크기를 검사합니다. 8KiB를 초과하면 고정 길이와 chunked 요청 모두 거절합니다. 취소 요청은 본문이 비어 있어야 합니다.

[WriteAdmission](../src/main/java/dev/mentoring/orders/WriteAdmission.java)은 동시 쓰기를 제한하고 계정별 고정 1분 구간의 요청 수를 셉니다. 고객별 한도 초과에는 다음 구간까지 남은 초를 `Retry-After`로 반환합니다. 요청 키의 멱등성과 남용 제한은 다른 역할입니다.

## 코드 읽기

[코드 해설](CODE_GUIDE.md)에 주문 요청을 받아 저장하기까지의 순서와 어노테이션 역할을 정리했습니다. [동시성 선택과 코드 정리](REFACTORING.md)에는 대안 비교, 바꾼 코드와 본문 크기 검사에서 발견한 오류를 적었습니다.

## 실행과 검증 도구

JUnit 5, 실제 HTTP와 별도 PostgreSQL 16.15를 사용했습니다. 동시에 보냈다는 시간 추정 대신 별도 DB 연결로 재고를 잠그고, `pg_stat_activity`에서 대기 요청을 확인한 뒤 해제합니다. SQL 트리거 오류, HTTP 응답 차단 대역과 별도 JVM 강제 종료는 검사 코드에서만 사용합니다.

Windows 전용 DB 준비/실행 도구를 서버 로직과 분리했습니다. Node.js는 로컬 DB의 생성과 종료를 담당하고, HTTP 서버와 주문 처리는 Java로 실행합니다. DB 이름, 경로, 포트와 실행 계정을 전용으로 관리합니다. [검증 기록](VERIFICATION.md)에 관찰 결과와 미측정 범위를 구분했습니다.

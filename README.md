# 한정 재고 상품 주문 접수 서버 | Spring Boot 백엔드 세미 프로젝트

한정 판매 상품을 구매하려는 고객이 남은 수량을 확인하고 주문하는 백엔드입니다. 주문을 접수하면 재고를 확보하고, 고객이 취소하면 다시 판매할 수량으로 돌려놓습니다. 서로 다른 고객이 마지막 상품을 동시에 주문하는 상황을 다룹니다.

![사용자와 요청](docs/design/diagrams/01-actors.png)

## 주요 기능

- 상품과 남은 수량 조회, 주문 접수, 자기 주문 목록과 상태 조회, 주문 취소.
- 같은 구매의 재전송은 원래 주문 반환. 다른 구매는 남은 재고 안에서 접수.
- 주문, 재고 차감과 증감 이력을 함께 저장하고, 취소도 상태와 재고를 함께 복구.
- 인증 계정별 주문 접근, CSRF 검사, 입력과 요청 크기 제한, 쓰기 요청 한도.

## 마지막 재고 처리

고객 A와 B가 남은 상품 1개를 각각 주문하면 한 주문만 수락합니다. PostgreSQL에서 `남은 수량 >= 주문 수량`일 때만 차감하는 UPDATE를 실행하고, 실제 차감에 성공한 요청이 주문을 만듭니다.

같은 구매의 재전송은 고객 ID와 `Idempotency-Key`의 유일성 제약으로 구별합니다. 취소는 접수 상태를 바꾼 요청만 수량을 돌려줍니다. 각 변경은 짧은 DB 트랜잭션으로 확정합니다.

[재고 경쟁, 재전송과 취소 그림](docs/design/README.md)과 [핵심 코드와 선택 이유](docs/IMPLEMENTATION.md)에 처리 순서와 비용을 정리했습니다.

직접 코드를 읽을 때는 [코드와 어노테이션 해설](docs/CODE_GUIDE.md) 순서로 따라가면 됩니다. [대안 비교와 코드 정리](docs/REFACTORING.md)에 유지한 동시성 방식, 바꾼 부분과 확인한 실패 조건을 모았습니다.

클래스와 메서드 선언에는 역할과 실패 조건을 Javadoc으로 적었습니다. [이름과 주석의 작성 기준](docs/NAMING_AND_JAVADOC.md)에서 명명 이유를 보고, 전체 검사 후 `build/docs/javadoc/index.html`에서 코드 설명을 찾아볼 수 있습니다.

## 실행 방법

Windows, Java 21, PowerShell 7, Node.js와 PostgreSQL 16 실행 파일을 사용합니다. 빌드는 Gradle Wrapper 8.14.3, 서버는 Spring Boot 3.5.16과 Spring JDBC로 구성했습니다.

프로젝트 폴더를 PowerShell 7에서 열고 실행합니다.

```powershell
# 처음 준비할 때 PostgreSQL의 bin, lib, share가 있는 폴더를 지정합니다.
# .tools/pgsql에 준비돼 있으면 이 설정을 생략합니다.
# $env:STOCK_PG_SOURCE = 'C:\Program Files\PostgreSQL\16'

# 전용 DB에서 전체 검사 후 실행 파일 생성
.\scripts\test.ps1 -IsolatedDatabase

# 서버 시작, 주문과 취소 예제, 종료
.\scripts\start-demo.ps1 -Port 18086
.\scripts\demo-request.ps1
.\scripts\stop-demo.ps1 -StopDatabase
```

`start-demo.ps1`은 로컬 백그라운드 서버를 시작합니다. 주소는 기본 `http://127.0.0.1:18086`이며 사용 중이면 다른 포트를 지정합니다. 예제 스크립트는 주문, 같은 키의 재전송과 두 번의 취소를 실행한 뒤 재고가 원래 수량인지 보여줍니다.

DB 실행 파일만 복사해 새 클러스터를 준비합니다. 계정 비밀번호와 프로세스 기록은 `.runtime`에 자동 생성하며 Git에서 제외합니다. 검사 DB는 실행마다 새로 만들고 종료합니다. 데모의 주문과 DB 자료는 종료 후에도 보존됩니다.

## API

| 경로 | 역할 |
| --- | --- |
| `GET /csrf` | 변경 요청에 사용할 CSRF 토큰 |
| `GET /products`, `GET /products/{id}` | 상품과 현재 판매 가능 수량 |
| `POST /orders` | 구매 키, 상품과 수량으로 접수 |
| `GET /orders`, `GET /orders/{id}` | 로그인 고객의 주문 목록과 상태 |
| `POST /orders/{id}/cancel` | 자기 주문 취소와 재고 복구 |

새 주문은 201, 같은 구매의 재조회와 취소는 200입니다. 재고 부족과 같은 키의 다른 입력은 409로 구분합니다. [API 명세](docs/API.md)에 응답과 재시도 규칙을 적었습니다.

## 검증 결과

실제 HTTP와 PostgreSQL에서 동시 주문, 20건의 같은 구매 재전송과 중복 취소, SQL 오류 주입, 응답 유실과 별도 JVM 종료/재시작을 검사했습니다. 반복과 단위 검사를 포함한 최종 48건이 모두 통과했으며, 수량뿐 아니라 주문, 요청 결과와 차감/복구 이력도 함께 대조했습니다.

[검증 기록](docs/VERIFICATION.md)에 실행 수, 대표 결과와 확인 범위를 남깁니다. 잠금 방식별 성능과 운영 수용량 비교는 후속 측정 항목입니다.

## 개발 흐름

`feature/*`에서 구현과 관련 검사를 진행하고 `develop`에 통합합니다. 실행 검증한 버전을 `main`에 반영합니다. 초기 설계와 후속 비교 계획은 [설계 문서](docs/design/IMPLEMENTATION_PLAN.md)에 보관합니다.

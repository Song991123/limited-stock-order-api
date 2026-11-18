'use strict';

/** 실제 주문 코드에 연결할 기술, 어노테이션과 요청 흐름 해설. 대안과 미검증 범위는 구현 사실과 구분. */

const java = 'src/main/java/dev/mentoring/orders/';
const test = 'src/test/java/dev/mentoring/orders/';
const docs = 'https://docs.spring.io/spring-framework/reference/6.2/';

const technologies = {
  transaction: {
    title: '트랜잭션', description: '주문 한 건에 필요한 재고, 주문과 이력을 함께 확정하거나 함께 되돌리는 저장 단위입니다.',
    why: '재고만 줄고 주문이 없거나, 취소 상태만 바뀌고 수량이 복구되지 않는 부분 저장을 막습니다. 주문 접수와 취소에는 각각 별도 트랜잭션을 적용합니다.',
    alternatives: '저장을 따로 커밋하면 실패 후 앞선 저장은 남습니다. TransactionTemplate으로 같은 범위를 명시할 수도 있지만, public 서비스 메서드 전체에 적용하므로 @Transactional을 사용합니다.',
    limit: '동시 요청 자체를 한 줄로 세우지 않습니다. 같은 구매에는 유일성 제약, 재고에는 수량 조건이 따로 필요합니다. 커밋 중 연결 유실은 롤백 확정이 아닙니다.',
    source: docs + 'data-access/transaction/declarative/annotations.html',
    files: [java + 'service/OrderService.java', java + 'config/TransactionConfiguration.java']
  },
  idempotency: {
    title: '같은 구매의 재요청과 멱등성', description: '응답을 못 받아 같은 구매를 다시 요청해도 주문과 재고 차감을 추가하지 않는 처리입니다. 고객과 Idempotency-Key를 함께 비교합니다.',
    why: '상품과 수량만으로는 재전송과 별도 구매를 구분할 수 없습니다. 같은 구매는 키를 유지하고 새 구매는 새 키를 사용합니다.',
    alternatives: '버튼 비활성화나 JVM 메모리 검사만으로는 응답 유실, 다른 서버로의 재요청을 막지 못합니다. DB UNIQUE가 최종 중복 판정을 맡습니다.',
    limit: '같은 키에 다른 상품이나 수량을 보내면 거절합니다. 원래 재고 부족 결과와 취소 주문도 유지합니다. 키의 자동 삭제는 구현하지 않았습니다.',
    source: 'https://www.postgresql.org/docs/16/sql-insert.html', files: [java + 'service/OrderService.java', java + 'repository/OrderRepository.java']
  },
  'conditional-update': {
    title: '조건부 UPDATE와 재고 경쟁', description: '남은 수량이 주문 수량 이상일 때만 재고를 차감합니다. 수량 확인과 차감을 한 SQL에서 수행합니다.',
    why: '재고 1개를 두 고객이 함께 조회해도 차감에 성공한 한 요청만 주문을 만듭니다. 같은 재고를 바꾸는 요청은 PostgreSQL에서 기다린 뒤 조건을 다시 검사합니다.',
    alternatives: 'SELECT FOR UPDATE로 먼저 잠근 뒤 확인하거나, JPA @Version으로 충돌을 찾고 재시도할 수도 있습니다. 이 서버의 수량 조건은 UPDATE와 변경 건수로 직접 표현했습니다.',
    limit: '잠금이 없는 방식이 아니며 요청 도착 순서를 보장하지 않습니다. 방식별 속도 우위는 측정하지 않았습니다. 취소도 접수 상태를 실제 바꾼 요청만 복구합니다.',
    source: 'https://www.postgresql.org/docs/16/transaction-iso.html#XACT-READ-COMMITTED', files: [java + 'repository/OrderRepository.java']
  },
  jdbc: {
    title: 'Spring JDBC와 JdbcClient', description: 'SQL에 값을 바인딩하고 조회 결과를 Java 객체로 받는 API입니다. SQL 실행과 매개변수 처리는 Spring JDBC가 맡습니다.',
    why: '재고 차감 조건, 같은 키 등록과 실제 변경 건수를 직접 확인하기 위해 사용합니다. :buyer 같은 값 자리는 param으로 전달합니다.',
    alternatives: 'JPA는 객체의 변경 감지와 관계 매핑에 장점이 있습니다. 이 프로젝트는 조건부 SQL 중심이므로 JDBC를 골랐으며 JPA가 느리다고 판단한 결과는 아닙니다.',
    limit: 'SQL과 RowMapper는 직접 관리합니다. 값 바인딩이 동적인 테이블 이름까지 안전하게 만들어 주지는 않습니다.',
    source: docs + 'data-access/jdbc/core.html', files: [java + 'repository/OrderRepository.java', java + 'repository/ProductRepository.java']
  },
  mvc: {
    title: 'Spring MVC와 REST API', description: 'HTTP 경로와 메서드를 컨트롤러에 연결합니다. 이 서버는 화면 템플릿 대신 JSON을 반환합니다.',
    why: '컨트롤러는 요청 변환, 검증과 응답을 맡고, 주문 처리와 SQL은 서비스 및 저장소로 나눕니다.',
    alternatives: '컨트롤러 안에서 SQL까지 실행할 수도 있지만 HTTP 형식과 주문 저장 순서가 한곳에 섞입니다. 별도 View 템플릿은 JSON API에 추가하지 않았습니다.',
    limit: 'Controller, Service, Repository는 역할을 나눈 구성이며 세 이름을 MVC의 세 요소와 일대일로 대응시키는 것은 아닙니다.',
    source: docs + 'web/webmvc/mvc-controller.html', files: [java + 'controller/OrderController.java']
  },
  dto: {
    title: '요청 DTO와 응답 DTO', description: 'HTTP로 받을 필드와 보낼 필드만 담는 객체입니다. 내부 주문 정보 Order와 별도로 둡니다.',
    why: 'JSON 필드가 바뀌는 일과 서비스의 자료형 변경을 분리합니다. 입력 DTO는 상품 ID와 수량을 담고 컨트롤러가 응답 DTO로 변환합니다.',
    alternatives: '내부 객체를 그대로 반환하면 내부 필드가 API에 노출될 수 있습니다. 두 입력값을 전달하는 데 별도 명령 객체나 자동 매핑 라이브러리는 추가하지 않았습니다.',
    limit: 'DTO에 어노테이션을 붙였다고 생성자에서 자동 검증하지는 않습니다. HTTP 요청에서는 MVC가 @Valid로 검사합니다.',
    source: 'https://martinfowler.com/eaaCatalog/dataTransferObject.html', files: [java + 'dto/request/CreateOrderRequest.java', java + 'dto/response/OrderResponse.java']
  },
  validation: {
    title: '입력 검증', description: '누락과 null은 @NotNull, 양수 조건은 @Positive로 정의합니다. MVC가 요청을 받을 때 이 조건을 실행합니다.',
    why: '잘못된 값은 저장소에 보내기 전에 거절합니다. 서비스를 직접 호출할 때도 ID, 수량과 목록 조회 범위를 검사합니다.',
    alternatives: '컨트롤러의 수동 if 문만 쓰면 값 조건과 응답 처리가 섞입니다. JSON 중복 필드나 소수 변환은 DTO 검증 이전의 Jackson 설정이 담당합니다.',
    limit: '양수라고 재고가 충분한 것은 아닙니다. 변하는 재고 수량은 DB의 조건부 UPDATE에서 검사합니다.',
    source: docs + 'web/webmvc/mvc-controller/ann-validation.html', files: [java + 'dto/request/CreateOrderRequest.java', java + 'service/OrderService.java']
  },
  security: {
    title: '인증과 주문 소유자 확인', description: 'Spring Security가 로그인 계정을 확인합니다. 주문 소유자는 JSON 입력이 아니라 인증된 계정에서 정합니다.',
    why: '계정 조회 SQL, 로그인 정보 변환과 인증 설정을 나눠 각 역할을 별도로 관리합니다. 자기 주문 조건은 조회와 취소 SQL에도 포함합니다.',
    alternatives: '요청 본문의 고객 ID를 믿거나 컨트롤러마다 비밀번호를 비교하면 권한 검사 누락이 생길 수 있습니다. 공통 인증 처리는 Spring Security에 맡깁니다.',
    limit: 'HTTP Basic은 외부 공개 시 TLS가 필요합니다. 인증 시도 자체의 요청 제한은 별도 과제입니다. 주문/취소의 쓰기 제한과 구분합니다.',
    source: 'https://docs.spring.io/spring-security/reference/6.5/servlet/authentication/passwords/user-details-service.html', files: [java + 'config/SecurityConfiguration.java', java + 'security/CustomerUserDetailsService.java', java + 'repository/CustomerAccountRepository.java']
  },
  csrf: {
    title: 'CSRF 토큰', description: '주문과 취소를 변경하는 요청에서 세션과 함께 보낸 CSRF 토큰을 확인합니다.',
    why: '브라우저가 인증 정보를 자동으로 붙이는 환경에서 다른 사이트가 변경 요청을 보내는 상황에 대비합니다. GET /csrf로 토큰을 받습니다.',
    alternatives: '모든 상태를 토큰 헤더로만 관리하는 별도 인증 구조에서는 정책이 달라질 수 있습니다. 이 서버의 Basic 인증과 세션 구성을 유지한 채 CSRF를 적용합니다.',
    limit: '토큰이 유효해도 다른 고객의 주문을 취소할 수는 없습니다. 소유자 검사는 SQL에서 따로 수행합니다.',
    source: 'https://docs.spring.io/spring-security/reference/6.5/servlet/exploits/csrf.html', files: [java + 'controller/CsrfController.java', java + 'config/SecurityConfiguration.java']
  },
  bcrypt: {
    title: 'BCrypt 비밀번호 해시', description: '복호화할 암호문 대신 비밀번호 검증용 해시를 저장합니다. 입력 비밀번호의 비교는 Spring Security가 맡습니다.',
    why: '원문 비밀번호를 DB에 저장하지 않습니다. CustomerUserDetailsService는 저장된 해시를 전달하며 직접 비밀번호를 비교하지 않습니다.',
    alternatives: 'Argon2, scrypt와 PBKDF2 같은 비밀번호 해시도 있습니다. SHA-256을 한 번 적용하는 방식과는 목적과 계산 비용이 다릅니다.',
    limit: '비밀번호 해시가 모든 인증 공격을 막지는 않습니다. TLS, 로그인 시도 제한과 해시 값의 로그 노출 방지가 따로 필요합니다.',
    source: 'https://docs.spring.io/spring-security/reference/6.5/features/authentication/password-storage.html', files: [java + 'config/SecurityConfiguration.java', java + 'repository/model/LoginAccount.java']
  },
  admission: {
    title: '주문과 취소의 요청 제한', description: '고객별 분당 주문/취소 횟수와 서버 프로그램 하나의 동시 쓰기 수를 제한합니다.',
    why: '구매 재시도가 많아도 취소의 횟수 한도까지 소진하지 않게 각각 계산합니다. 두 작업의 공통 동시 처리 수는 Semaphore로 제한합니다.',
    alternatives: 'Redis 같은 공유 저장소를 쓰면 여러 서버가 같은 한도를 계산할 수 있습니다. 현재 카운터와 세마포어는 JVM 하나에만 적용합니다.',
    limit: '요청을 저장해 나중에 실행하는 대기열이 아닙니다. 거절은 429와 Retry-After로 안내하며 재고 정합성은 DB가 따로 지킵니다.',
    source: 'https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/Semaphore.html', files: [java + 'web/admission/WriteAdmission.java']
  },
  'body-size': {
    title: '요청 본문 크기와 필터 순서', description: '알려진 길이가 8KiB를 넘으면 본문을 열기 전에 거절합니다. 길이를 모르면 실제 바이트를 제한합니다.',
    why: '폼 처리나 보안 필터가 먼저 본문을 읽으면 크기 검사가 늦을 수 있어 인코딩 필터 뒤, 다른 본문 처리 앞에서 실행합니다.',
    alternatives: 'Content-Length 헤더만 확인하면 chunked 전송을 놓칩니다. 컨트롤러에서만 검사하면 앞선 필터가 이미 큰 요청을 읽을 수 있습니다.',
    limit: '이 제한은 주문과 취소 경로에 적용합니다. 정상 요청의 전송 시간이나 운영 수용량을 개선했다고 측정한 결과는 아닙니다.',
    source: 'https://docs.spring.io/spring-boot/3.5/reference/web/servlet.html', files: [java + 'web/filter/RequestSizeFilter.java', java + 'config/RequestBodyConfiguration.java']
  },
  'error-handling': {
    title: '오류 응답과 재시도', description: '입력 오류, 재고 부족, 요청 제한과 DB 장애를 구분해 응답합니다. 예외 원문과 비밀번호를 응답하지 않습니다.',
    why: '잘못된 로그인 정보와 계정 조회 DB 장애를 같은 오류로 숨기지 않습니다. 응답을 잃거나 커밋 여부를 모르면 같은 구매 키나 주문 ID로 결과를 확인합니다.',
    alternatives: '모든 예외를 500이나 성공 응답으로 바꾸면 클라이언트가 행동을 정할 수 없습니다. 인증 오류는 MVC 이전의 보안 처리에서도 구분해야 합니다.',
    limit: '일시 오류 응답 자체가 자동 재시도를 실행하지는 않습니다. 새 키로 구매를 반복하지 않고 원래 키를 유지해야 합니다.',
    source: 'https://www.rfc-editor.org/rfc/rfc9110.html', files: [java + 'exception/ApiExceptionHandler.java', java + 'exception/DatabaseFailures.java']
  },
  flyway: {
    title: 'Flyway와 DB 제약', description: '버전이 붙은 SQL로 테이블과 제약을 준비합니다. FK, CHECK와 UNIQUE가 잘못된 저장을 DB에서도 거절합니다.',
    why: '같은 구매 키와 증감 이력의 중복, 수량 범위 위반을 막습니다. 처리 중에는 결과가 비어 있을 수 있어 결과 누락은 커밋 시점의 지연 제약 트리거로 검사합니다.',
    alternatives: '애플리케이션 if 문만으로는 다른 저장 경로나 동시 요청의 오류를 막기 어렵습니다. 즉시 NOT NULL은 결과를 아직 모르는 요청 선등록 순서와 맞지 않습니다.',
    limit: '제약만으로 주문과 이력의 모든 업무 순서를 자동 보장하지는 않습니다. 실제 HTTP/DB 검사로 대조하며 지연 제약 트리거는 PostgreSQL 의존성이 있습니다.',
    source: 'https://www.postgresql.org/docs/16/sql-createtrigger.html', files: ['src/main/resources/db/migration/V1__orders_and_inventory.sql']
  },
  testing: {
    title: '단위 검사와 실제 HTTP/DB 검사', description: '대역으로 서비스 분기를 확인하는 검사와, 서버 및 PostgreSQL에 실제 요청하는 검사를 나눕니다.',
    why: '저장소 대역만으로는 실제 유일성 충돌이나 커밋/롤백을 확인할 수 없습니다. 잠금 대기, SQL 오류 주입과 응답 차단으로 실패 조건을 재현합니다.',
    alternatives: 'sleep만으로 동시 실행을 추정하지 않고 별도 연결의 잠금과 pg_stat_activity로 실제 대기를 확인합니다.',
    limit: '정규 172건과 별도 DB 중단 15건은 과거 실행 기록입니다. 이 HTML을 만드는 작업은 주문 API나 DB를 다시 실행하지 않습니다.',
    source: 'https://docs.junit.org/5.11.4/user-guide/', files: [test + 'OrderHttpIntegrationTest.java', test + 'DatabaseRecoveryIntegrationTest.java']
  },
  architecture: {
    title: 'ArchUnit 구조 검사', description: '제품 클래스 파일을 읽어 Controller, Service와 Repository 사이의 지정한 의존 규칙을 검사합니다.',
    why: '파일을 나눈 뒤에도 서비스가 HTTP DTO를 쓰거나 컨트롤러가 저장소를 바로 호출하는 변경을 찾습니다. 메서드 내부의 타입 사용도 읽습니다.',
    alternatives: '고정한 클래스 목록의 메서드 선언만 확인하면 새 클래스나 메서드 내부 사용을 놓칠 수 있습니다. ArchUnit으로 제품 패키지를 자동 탐색합니다.',
    limit: '코드 의존 규칙을 검사하는 도구이지 주문 결과의 정확성을 증명하는 도구는 아닙니다. 라이브러리와 위반 예제는 테스트에만 사용합니다.',
    source: 'https://www.archunit.org/userguide/html/000_Index.html', files: [test + 'PackageStructureTest.java']
  },
  configuration: { title: '일반 실행과 예제 설정', description: 'DB 접속과 제한값은 설정에서 받고 예제 데이터는 demo 프로필에서만 준비합니다.', why: '일반 실행에 예제 계정과 상품을 자동 등록하지 않기 위해 나눕니다.', alternatives: '코드에 비밀번호를 고정하는 대신 실행 환경의 값을 주입합니다.', limit: '실행용 비밀 값과 원시 로그는 HTML과 Git에 포함하지 않습니다.', files: ['src/main/resources/application.yml', java + 'config/DemoDataInitializer.java'] },
  java: { title: 'Java 21 자료형과 문법', description: 'record로 값을 담고 enum으로 상태를 제한합니다. sealed interface로 주문 처리 결과의 종류를 제한합니다.', why: '신규 접수, 재전송과 재고 부족을 구분해 처리되지 않은 결과를 컴파일 단계에서 찾습니다.', alternatives: '숫자 상태와 null 조합 대신 가능한 결과를 명시합니다. var는 정적 타입 추론이며 동적 타입이 아닙니다.', limit: 'record나 enum이 DB 저장이나 스레드 안전성을 자동으로 보장하지는 않습니다.', source: 'https://docs.oracle.com/en/java/javase/21/language/index.html', files: [java + 'domain/OrderPlacement.java'] },
  build: { title: 'Gradle과 CI', description: 'Java 21로 컴파일하고 검사, 실행 JAR와 Javadoc을 만듭니다.', why: '버전과 실행 명령을 고정하고 PR에서도 같은 검사 흐름을 실행합니다.', alternatives: '개발 PC에서 수동 실행한 기록만 남기는 대신 CI 보고서도 보관합니다.', limit: 'DB 프로세스를 중단하는 검사는 일반 CI와 별도입니다. 코드 해설 검사는 기존 JUnit 수에 더하지 않습니다.', files: ['build.gradle', '.github/workflows/verify.yml'] },
  tooling: { title: '격리 실행과 검증 도구', description: 'PowerShell과 Node.js 도구가 전용 로컬 DB 준비, 시험 서버 실행과 결과 보관을 맡습니다.', why: '검사마다 다른 DB와 포트를 사용하고 직접 시작한 프로세스만 종료합니다.', alternatives: '기존 프로젝트 DB나 설치형 앱을 검사 대상으로 사용하지 않습니다.', limit: '도구의 성공 여부와 주문 API의 정확성 검사는 별개입니다. 실행 비밀 값은 .runtime에 두며 이 HTML에 담지 않습니다.', files: ['scripts/test.ps1', 'scripts/local-db.cjs'] }
};

const a = (tech, effect, why, alternatives, limit) => ({ tech, effect, why, alternatives, limit });
const annotations = {
  SpringBootApplication: a('build', '서버 시작 설정과 하위 패키지 구성요소 탐색을 함께 적용합니다.', '최상위 패키지에 실행 클래스를 두어 컨트롤러, 서비스와 저장소를 탐색합니다.', '@Configuration, @EnableAutoConfiguration, @ComponentScan을 따로 조합할 수도 있습니다.', '이 표시만으로 주문의 트랜잭션이나 중복 처리가 생기지는 않습니다.'),
  RestController: a('mvc', '요청 메서드의 반환 객체를 응답 본문으로 보냅니다.', 'HTML 화면 대신 JSON API를 제공합니다.', '@Controller와 @ResponseBody를 함께 사용할 수도 있습니다.', '저장과 비밀번호 비교는 이 클래스의 역할이 아닙니다.'),
  GetMapping: a('mvc', 'GET 경로를 메서드에 연결합니다.', '상품, 주문과 CSRF 토큰 조회에 사용합니다.', '@RequestMapping(method = GET)으로도 표현할 수 있습니다.', '메서드 이름만으로 URL이 정해지는 것은 아닙니다.'),
  PostMapping: a('mvc', 'POST 경로를 메서드에 연결합니다.', '주문 접수와 취소처럼 상태를 바꾸는 요청을 받습니다.', '@RequestMapping(method = POST)도 가능합니다.', 'POST라는 이유만으로 같은 요청의 중복 반영을 막지는 않습니다.'),
  RequestBody: a('dto', 'HTTP 본문을 요청 객체로 변환합니다.', '주문 JSON에서 상품 ID와 수량을 받습니다.', '수동 JSON 파싱 대신 MVC와 Jackson의 변환을 사용합니다.', '값 조건은 @Valid가 따로 실행하고 형식 오류는 변환 단계에서 거절합니다.'),
  RequestHeader: a('idempotency', '지정한 헤더 값을 메서드 매개변수로 받습니다.', 'Idempotency-Key를 본문의 상품/수량과 구분해 받습니다.', '본문에 키를 넣는 설계도 가능하지만 기존 API 명세는 헤더를 사용합니다.', 'required=false는 키 검사를 생략한다는 뜻이 아닙니다. 서비스가 누락과 형식을 검사합니다.'),
  PathVariable: a('mvc', 'URL 경로 안의 값을 메서드에 전달합니다.', '조회하거나 취소할 주문 ID를 받습니다.', '쿼리나 본문으로 받을 수도 있지만 자원을 가리키는 현재 URL을 유지합니다.', 'ID를 안다고 다른 고객의 주문에 접근할 수는 없습니다.'),
  RequestParam: a('mvc', 'URL 쿼리를 읽고 기본값을 적용합니다.', 'limit와 beforeId를 명시해 Java 변수 이름이 바뀌어도 API 이름을 유지합니다.', '단순 목록 조건 두 개이므로 별도 검색 객체를 추가하지 않았습니다.', '형 변환과 @Min/@Max 범위 검사는 별도로 수행합니다.'),
  Valid: a('validation', 'MVC가 요청 DTO의 중첩 검증 조건을 실행하게 합니다.', '@NotNull과 @Positive를 실제 HTTP 입력에서 검사합니다.', '직접 Validator를 호출할 수도 있습니다.', '@Valid 자체는 양수 조건이 아니며 new DTO(...)만으로 자동 실행되지 않습니다.'),
  NotNull: a('validation', 'null을 거절하는 조건을 정의합니다.', '누락된 상품 ID와 수량을 0과 구분합니다.', 'primitive의 기본값 0만 받으면 누락과 명시적인 0이 섞입니다.', '@Positive만으로는 null을 거절하지 않습니다.'),
  Positive: a('validation', '0보다 큰 값만 허용하는 조건을 정의합니다.', '상품 ID와 주문 수량의 기본 조건입니다.', '서비스의 직접 호출에는 별도 if 검사도 유지합니다.', 'null 거절과 정수 JSON 형식, 재고 충분 여부를 대신하지 않습니다.'),
  Min: a('validation', '숫자의 최솟값을 정의합니다.', '경로 ID, 목록 기준 ID와 조회 개수를 제한합니다.', '컨트롤러 수동 비교 대신 MVC 내장 메서드 검증을 사용합니다.', 'DB 재고가 변하는 문제를 해결하지는 않습니다.'),
  Max: a('validation', '숫자의 최댓값을 정의합니다.', '한 번에 조회할 주문 수를 제한합니다.', '저장소 LIMIT도 함께 사용합니다.', '입력 범위 조건과 목록 접근 권한은 별개입니다.'),
  Service: a('mvc', '클래스를 Spring 관리 객체로 등록합니다.', '주문 규칙이나 인증 정보 변환의 역할을 드러냅니다.', '@Component도 등록은 가능하지만 역할이 덜 드러납니다.', '@Service 자체가 트랜잭션을 열거나 잠금을 잡지는 않습니다.'),
  Repository: a('jdbc', 'SQL 저장소 클래스를 Spring 관리 객체로 등록합니다.', '조회와 저장 SQL을 HTTP 처리에서 분리합니다.', '@Component도 가능하지만 저장소라는 역할을 표시했습니다.', '값을 바인딩하고 수량 조건을 검사하는 SQL은 직접 작성합니다.'),
  Transactional: a('transaction', '외부에서 Spring 프록시를 통해 호출할 때 트랜잭션을 적용합니다.', '주문 또는 취소의 여러 저장을 함께 확정합니다.', 'TransactionTemplate으로 명시하는 방법도 있습니다.', '같은 클래스 내부 호출만으로 새 트랜잭션을 열지는 않습니다. READ_COMMITTED, readOnly와 rollbackFor의 효과도 구분해야 합니다.'),
  Configuration: a('configuration', 'Spring 객체를 만드는 설정 클래스로 등록합니다.', '보안 필터, 비밀번호 인코더와 트랜잭션 관리자를 구성합니다.', '설정 파일이나 별도 자동 설정으로 구성할 수도 있습니다.', 'proxyBeanMethods=false는 설정 메서드 간 호출용 프록시를 생략하는 옵션이며 서비스 트랜잭션 프록시와 다릅니다.'),
  Bean: a('configuration', '메서드 반환값을 Spring 관리 객체로 등록합니다.', '직접 생성한 인코더, 필터 체인과 트랜잭션 관리자를 사용합니다.', '직접 만든 클래스는 @Component 계열로 탐색시킬 수도 있습니다.', '계정 조회 SQL을 설정 메서드에 섞지 않습니다.'),
  Component: a('configuration', '클래스를 Spring 관리 객체로 탐색합니다.', '요청 필터, 제한 기능과 예제 등록기를 주입해 사용합니다.', '설정 클래스의 @Bean으로 직접 등록할 수도 있습니다.', '필터 실행 순서나 demo 전용 여부는 다른 설정이 정합니다.'),
  Profile: a('configuration', '지정한 실행 프로필에서만 이 구성요소를 등록합니다.', 'demo에서만 예제 고객과 상품을 준비합니다.', '일반 실행에 무조건 등록하면 예제 데이터가 섞일 수 있습니다.', '프로필과 비밀 값 관리, 접근 권한 검사는 서로 다른 역할입니다.'),
  Value: a('configuration', '환경 설정의 값을 생성자 매개변수 등에 주입합니다.', '동시 처리 한도와 예제 비밀번호를 코드에 고정하지 않습니다.', '설정값이 많아지면 @ConfigurationProperties로 묶을 수도 있습니다.', '비밀 값의 실제 내용을 로그나 HTML에 넣지 않습니다.'),
  RestControllerAdvice: a('error-handling', 'MVC 컨트롤러에서 발생한 예외의 공통 응답을 처리합니다.', '오류 JSON과 상태/헤더 처리를 한곳에 둡니다.', '컨트롤러마다 try/catch를 반복하는 방법보다 중복을 줄입니다.', 'MVC 앞의 인증 필터 오류는 보안 설정에서 따로 처리합니다.'),
  ExceptionHandler: a('error-handling', '지정한 예외 종류를 처리할 메서드를 선택합니다.', '입력, 업무 오류와 DB 장애를 구분합니다.', '모든 예외를 하나로 묶으면 잘못된 재시도나 오류 숨김이 생길 수 있습니다.', '예외 메시지와 SQL 원문을 그대로 응답하지 않습니다.'),
  Order: a('body-size', 'Spring 구성요소의 적용 순서를 지정합니다.', '본문 크기 필터를 다른 본문 처리보다 먼저 실행합니다.', '기본 순서를 쓰면 폼/보안 필터가 먼저 읽을 수 있습니다.', '고객 주문의 FIFO 처리 순서와는 관계없습니다.'),
  Override: a('java', '상위 타입의 메서드를 올바르게 구현하는지 컴파일러가 확인합니다.', '필터, UserDetailsService와 toString 구현의 오타를 찾습니다.', '생략해도 재정의는 가능하지만 잘못된 서명을 놓칠 수 있습니다.', '런타임에 별도의 권한이나 트랜잭션을 부여하는 표시는 아닙니다.'),
  FunctionalInterface: a('java', '추상 메서드가 하나인 함수형 인터페이스인지 컴파일러가 확인합니다.', '두 서버 검사에서 보낼 요청 한 건을 람다로 전달합니다.', '구현 클래스를 만들거나 함수형 인터페이스 표시를 생략할 수도 있습니다.', '이 표시가 비동기 실행이나 스레드 안전성을 제공하지는 않습니다.'),
  SpringBootTest: a('testing', 'Spring Boot 설정을 읽어 검사할 애플리케이션 구성을 만듭니다.', '이 저장소는 RANDOM_PORT로 실제 HTTP 서버를 열어 인증부터 DB 저장까지 검사합니다.', '서비스 단위 검사는 Mockito 대역으로, MVC 일부만은 슬라이스 검사로 줄일 수도 있습니다.', '이 어노테이션의 기본 설정은 RANDOM_PORT가 아닙니다. DB의 종류와 대역 사용도 별도 설정에 따릅니다.'),
  DirtiesContext: a('testing', '지정한 시점에 검사에 사용한 Spring 구성을 캐시에서 제거하고 닫습니다.', '상태를 가진 횟수 제한기나 서버 설정이 다른 검사에 남지 않도록 합니다.', '상태를 직접 초기화해 구성을 재사용할 수도 있지만 빠뜨릴 항목을 관리해야 합니다.', 'DB 기록을 자동으로 원상 복구하는 기능이 아닙니다. AFTER_EACH_TEST_METHOD는 메서드마다 재구성하므로 시간이 더 듭니다.'),
  ActiveProfiles: a('testing', 'Spring 검사에서 사용할 프로필을 선택합니다.', '예제 고객이 필요한 demo 검사와 예제 데이터가 없어야 하는 standard 검사를 구분합니다.', '속성이나 다른 설정 클래스로 검사 구성을 나눌 수도 있습니다.', '검사 프로필은 운영에서 어떤 프로필을 사용했는지 증명하지 않습니다.'),
  LocalServerPort: a('testing', '검사 서버가 실제로 사용한 포트를 필드에 넣습니다.', 'RANDOM_PORT의 임의 포트로 HTTP 요청을 보냅니다.', '포트를 고정하면 다른 실행과 충돌할 수 있습니다.', 'PostgreSQL 포트가 아니라 검사 HTTP 서버의 포트입니다.'),
  Autowired: a('testing', 'Spring에 등록된 객체를 해당 필드나 매개변수에 주입합니다.', '통합 검사에서 실제 JdbcTemplate과 ObjectMapper를 받아 응답과 DB 값을 대조합니다.', '제품 코드는 단일 생성자 주입을 사용합니다. 생성자 하나에는 이 표시가 없어도 됩니다.', '이 표시가 객체를 대역으로 만들거나 SQL을 자동 실행하지는 않습니다.'),
  DynamicPropertySource: a('testing', '검사 구성을 만들 때 동적으로 정한 속성을 등록합니다.', '검사별로 만든 DB 스키마와 접속 설정을 주입합니다.', '고정 속성 파일도 가능하지만 겹쳐 실행할 검사 데이터를 분리하기 어렵습니다.', '프로덕션 설정을 변경하는 기능이 아닙니다. static 메서드와 속성을 제공하는 함수를 구분합니다.'),
  Timeout: a('testing', '검사가 허용한 실행 시간을 넘으면 실패하게 합니다.', '잠금이나 응답 대기 때문에 검사가 끝없이 멈추는 것을 제한합니다.', '개별 대기 호출에도 timeout을 둘 수 있으며 이 코드도 함께 사용합니다.', '기본 단위는 초입니다. DB lock_timeout이나 서버의 요청 처리 기한을 설정하는 표시는 아닙니다.'),
  ParameterizedTest: a('testing', '입력 목록의 각 값으로 검사 메서드를 따로 실행합니다.', '0, 음수, 큰 본문 등 여러 입력에 같은 처리 규칙을 검사합니다.', '입력마다 @Test를 만들 수도 있지만 같은 준비와 단언이 반복됩니다.', '실행 조건 수에는 각 입력의 실행이 포함됩니다. 반복 검사와는 구분합니다.'),
  MethodSource: a('testing', '지정한 메서드가 반환한 값을 매개변수 검사의 입력으로 사용합니다.', '상품과 수량처럼 여러 값의 조합과 기대 오류를 함께 전달합니다.', '값 하나만 필요하면 @ValueSource가 더 단순합니다.', '입력을 만드는 함수가 실제 검증 결과를 대신하지 않습니다. 검사 본문의 단언이 필요합니다.'),
  ValueSource: a('testing', '지정한 단일 값 목록을 매개변수 검사에 전달합니다.', '경로, 수량과 본문 길이의 여러 값을 같은 검사로 확인합니다.', '여러 열의 조합은 @MethodSource나 CSV 입력으로 표현할 수 있습니다.', '값을 나열하는 것만으로 거절이나 DB 불변 여부를 확인한 것은 아닙니다.'),
  ExtendWith: a('testing', 'JUnit 실행에 지정한 확장 기능을 연결합니다.', 'MockitoExtension으로 대역 초기화와 사용 검사를 연결합니다.', 'Mockito 객체를 직접 만들거나 세션을 열고 닫을 수도 있습니다.', '이 코드의 Mockito 확장은 실제 Spring 서버와 DB를 실행하지 않습니다.'),
  Mock: a('testing', 'Mockito가 해당 타입의 검사 대역을 준비합니다.', '저장소 응답을 제어해 서비스의 분기와 호출을 검사합니다.', '실제 DB 통합 검사는 SQL과 제약까지 확인하지만 준비 비용이 더 듭니다.', '대역 호출 확인을 실제 커밋 성공이나 DB 정합성 검사로 표현하지 않습니다.'),
  Test: a('testing', 'JUnit 검사 메서드를 등록합니다.', '한 조건의 기대 결과를 단언합니다.', '반복 조건은 @RepeatedTest로 구분합니다.', '메서드 이름만으로 실제 DB 사용 여부가 정해지지 않습니다. 준비 코드와 대역 사용을 확인합니다.'),
  RepeatedTest: a('testing', '같은 검사 조건을 정해진 횟수만큼 반복합니다.', '동시 실행과 장애 재현을 반복해 확인합니다.', '반복 실행 수를 테스트 케이스 수와 구분해 기록합니다.', '세 번 통과가 모든 스케줄이나 운영 부하를 증명하지는 않습니다.'),
  BeforeAll: a('testing', '검사 클래스의 실행 전에 공통 자원을 준비합니다.', '전용 서버와 DB 연결을 마련합니다.', '검사별 초기화는 @BeforeEach로 나눕니다.', '실제 앱의 시작 스케줄러가 아닙니다.'),
  AfterAll: a('testing', '검사 클래스가 끝나면 공통 자원을 정리합니다.', '관찰 기록을 보관하고 직접 실행한 서버를 종료합니다.', '각 검사의 임시 자원은 try-with-resources로 정리할 수도 있습니다.', '다른 프로젝트의 프로세스를 종료하지 않습니다.'),
  BeforeEach: a('testing', '각 검사 전에 데이터를 준비합니다.', '앞선 검사 결과에 의존하지 않도록 초기 재고를 맞춥니다.', '공유된 변경 데이터를 그대로 쓰면 실행 순서에 따라 결과가 바뀔 수 있습니다.', '검사의 준비 단계이며 운영 데이터 초기화 기능은 아닙니다.'),
  AfterEach: a('testing', '각 검사가 끝나면 임시 자원과 오류 주입을 정리합니다.', '다음 검사에 잠금이나 검사 전용 트리거가 남지 않게 합니다.', 'finally와 자원 자동 정리도 함께 사용합니다.', '실패한 검사에서도 정리 대상을 전용 자원으로 제한합니다.'),
  Tag: a('testing', '검사에 분류 이름을 붙입니다.', 'DB 프로세스를 중단하는 검사를 database-recovery로 구분합니다.', '별도 Gradle 작업으로 선택 실행합니다.', '태그만 붙인다고 일반 검사에서 제외되지 않습니다. build.gradle의 include/exclude 설정이 필요합니다.'),
  TestInstance: a('testing', 'JUnit 검사 인스턴스의 생명주기를 정합니다.', 'PER_CLASS에서는 서버와 관찰 목록을 같은 검사 객체에서 공유합니다.', '기본 PER_METHOD는 메서드마다 검사 객체를 새로 만듭니다.', '검사 데이터 초기화와 자원 정리는 여전히 필요합니다.'),
  TempDir: a('testing', '검사 전용 임시 폴더를 제공합니다.', '일반 작업 폴더와 임시 파일을 구분합니다.', '직접 만든 임시 폴더라면 경로와 정리 범위를 따로 확인해야 합니다.', '운영 파일을 테스트 대상으로 삭제하지 않습니다.'),
  SuppressWarnings: a('java', '지정한 컴파일 경고를 해당 범위에서 숨깁니다.', '의도한 사용에 대해서만 좁게 적용합니다.', '원인을 수정할 수 있으면 경고를 숨기기보다 코드를 고칩니다.', '오류나 안전성 문제가 해결됐다는 보장은 아닙니다.')
};

// 단계 배열은 제목, 원문 경로, 메서드 이름, 동작 설명 순서. branches는 순차 단계가 아닌 대체 경로.
const flows = [
  { id: 'order', title: '주문 접수', steps: [
    ['요청 본문 크기 확인', java + 'web/filter/RequestSizeFilter.java', 'doFilterInternal', '다른 필터가 본문을 읽기 전에 허용한 길이인지 검사합니다.'],
    ['로그인과 CSRF 확인', java + 'config/SecurityConfiguration.java', 'securityFilterChain', 'Spring Security가 로그인 정보와 상태 변경 요청의 CSRF 토큰을 검사합니다.'],
    ['입력 DTO 검사', java + 'controller/OrderController.java', 'placeOrder', '상품 ID와 수량을 검사하고 인증된 고객과 구매 키를 서비스에 전달합니다.'],
    ['같은 구매 확인', java + 'service/OrderService.java', 'placeOrder', '기존 키를 대조하고 새 구매 요청을 먼저 등록합니다.'],
    ['남은 재고 차감', java + 'repository/OrderRepository.java', 'tryDecreaseStock', '남은 수량이 충분할 때만 차감합니다.'],
    ['주문과 결과 저장', java + 'service/OrderService.java', 'createOrder', '주문과 차감 이력, 구매 결과를 같은 트랜잭션으로 확정합니다.'],
    ['응답 생성', java + 'controller/OrderController.java', 'toOrderResponse', '커밋을 마친 뒤 신규/기존 주문 또는 재고 부족 응답을 반환합니다.']
  ] },
  { id: 'retry', title: '같은 구매 재요청', steps: [
    ['원래 구매 키 유지', java + 'controller/OrderController.java', 'placeOrder', '응답을 잃은 클라이언트는 같은 계정, 구매 키, 상품과 수량으로 다시 요청합니다.'],
    ['조회와 등록 결과 구분', java + 'service/OrderService.java', 'placeOrder', '기록이 없으면 등록합니다. 같은 키의 다른 요청을 기다린 경우, 상대가 커밋하면 기존 기록을 읽고 롤백하면 이번 등록이 성공할 수 있습니다.'],
    ['기존 기록이 있으면 입력과 결과 확인', java + 'service/OrderService.java', 'replayPurchase', '같은 키에 다른 상품이나 수량이면 거절합니다. 최초 결과가 재고 부족이면 주문을 조회하지 않고 부족 결과를 반환합니다.'],
    ['최초 성공이면 현재 주문 반환', java + 'repository/OrderRepository.java', 'findOrderByRequest', '기존 구매가 성공했던 경우에는 원래 주문의 현재 상태를 읽습니다. 이미 취소됐다면 취소된 주문을 반환하고 다시 차감하지 않습니다.'],
    ['이번 등록이 성공하면 신규 처리', java + 'service/OrderService.java', 'createOrder', '앞선 요청이 롤백했거나 처음부터 기록이 없으면 재고를 확인해 새 주문 또는 부족 결과를 저장합니다. 새 주문의 응답은 201입니다.']
  ] },
  { id: 'cancel', title: '주문 취소', steps: [
    ['자기 주문 취소 요청', java + 'controller/OrderController.java', 'cancelOrder', '주문 ID와 빈 본문을 확인합니다. 복구할 수량을 고객에게 받지 않습니다.'],
    ['자기 주문 상태 변경 시도', java + 'repository/OrderRepository.java', 'tryCancelOwnedOrder', '접수 상태(ACCEPTED)인 자기 주문만 취소 상태(CANCELLED)로 바꾸고, 이번 요청이 실제로 상태를 바꿨는지 확인합니다.']
  ], branches: [
    { id: 'first', title: '처음 취소', condition: '이번 요청이 주문을 접수 상태에서 취소 상태로 바꾼 경우', steps: [
      ['주문 수량만큼 재고 복구', java + 'service/OrderService.java', 'cancelOrder', '저장된 주문 수량을 재고에 더하고 복구 이력(RESTORE)을 함께 저장합니다.'],
      ['함께 커밋한 뒤 결과 반환', java + 'controller/OrderController.java', 'cancelOrder', '취소 상태, 재고 복구와 이력을 함께 커밋한 뒤 취소된 주문을 응답합니다.']
    ] },
    { id: 'again', title: '재취소', condition: '주문이 이미 취소되어 이번 요청은 상태를 바꾸지 못한 경우', steps: [
      ['이미 취소된 자기 주문 확인', java + 'service/OrderService.java', 'findCancelledOrder', '자기 주문을 다시 읽어 이미 취소 상태인지 확인합니다.'],
      ['재고를 더하지 않고 결과 반환', java + 'service/OrderService.java', 'cancelOrder', '기존 취소 주문을 반환합니다. 재고 복구와 복구 이력 저장은 다시 실행하지 않습니다.']
    ] }
  ], note: '주문이 없거나 다른 고객의 주문이면 거절합니다. 처음 취소의 저장 중 오류는 취소 상태와 재고 복구를 함께 되돌립니다.' },
  { id: 'login', title: '로그인 정보 확인', steps: [
    ['인증 설정', java + 'config/SecurityConfiguration.java', 'securityFilterChain', 'HTTP Basic과 CSRF, 인증 실패 응답을 설정합니다.'],
    ['계정 SQL 조회', java + 'repository/CustomerAccountRepository.java', 'findByUsername', '계정명을 SQL 값으로 바인딩해 저장된 계정과 해시를 읽습니다.'],
    ['로그인 정보 변환', java + 'security/CustomerUserDetailsService.java', 'loadUserByUsername', 'UserDetails로 변환합니다. 비밀번호 비교는 Spring Security/BCrypt가 맡습니다.']
  ] }
];

module.exports = { technologies, annotations, flows, project: {
  title: '한정 재고 상품 주문 접수 서버', subtitle: 'Spring Boot 백엔드 세미 프로젝트',
  purpose: '고객이 한정 판매 상품을 주문하면 수량을 확보하고, 자기 주문을 취소하면 원래 주문 수량을 재고에 돌려놓는 서버입니다.',
  actor: '같은 상품을 구매하는 고객 A와 B가 마지막 재고를 두고 경쟁합니다. 주문이나 취소 응답을 받지 못한 고객은 같은 구매 키 또는 주문 ID로 결과를 다시 확인합니다.',
  stack: 'Java 21 / Spring Boot 3.5.16 / Spring JDBC / Spring Security / PostgreSQL / Flyway',
  invariant: '남은 재고 = 초기 재고 - 취소되지 않은 주문 수량 합계',
  scope: '동시 주문과 재전송, 중복 취소, 저장 실패에 대한 정합성 검사를 위한 프로젝트입니다. 실제 요청은 HTTP 클라이언트로 실행했습니다.',
  limits: ['여러 서버가 공유하는 요청 한도와 운영 수용량은 미검증입니다.', '호스트 전원 차단과 잠금 방식별 성능 비교는 검사하지 않았습니다.', '구매 키 자동 삭제는 구현하지 않았으며 보존량 한도는 정하지 않았습니다.', '외부 공개 운영에는 TLS와 인증 시도 제한이 별도로 필요합니다.']
} };

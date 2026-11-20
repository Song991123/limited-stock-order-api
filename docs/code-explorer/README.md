# 원본 코드와 줄별 해설

[HTML 코드 해설](index.html)을 브라우저에서 연다. 서버를 실행하거나 인터넷에 연결하지 않아도 폴더 탐색, 검색, 코드와 설명을 읽을 수 있다. GitHub의 HTML 원문 화면에서는 실행되지 않으므로 로컬 저장소의 파일을 연다.

## 수록 내용

- 왼쪽은 실제 소스 경로, 오른쪽은 원본 코드와 같은 줄의 해설.
- 파일의 역할, 메서드별 처리, 기술과 어노테이션의 효과, 선택 이유와 대안.
- 고객의 주문, 같은 구매의 재요청, 취소와 인증 흐름 및 기존 설계 그림.
- 저장된 서비스 검사 결과와 미검증 범위. 문서 생성이나 화면 검사와는 별도.
- 실행과 결과: 서버 실행 명령, 실제 HTTP 시연의 단계별 결과, 로컬 JUnit 보고서와 실행 JAR 링크.

원문은 `src`의 Java, SQL, 설정 파일과 최상위 `scripts`, 시연 보고서 도구인 `scripts/demo`, 빌드 설정과 CI 설정을 수록한다. 빌드 산출물, 외부 라이브러리, 실행 비밀번호와 `.runtime`, 원시 검증 자료는 제외한다. 이 HTML을 만드는 도구 자체는 해설 대상 서비스 코드와 구분해 `scripts/code-explorer`에 둔다.

Java 메서드와 어노테이션의 위치는 JDK 21의 구문 분석기로 읽는다. 애플리케이션을 실행하거나 클래스에 연결하지 않는다. 원문의 문자와 줄은 그대로 수록하며, OS에 따른 BOM과 CRLF만 LF로 정규화한다. 줄 설명은 파일별 JSON과 공통 구문 설명을 조합한다.

## 코드 수정 후 갱신

JDK 21과 Node.js 22 이상이 필요하다. 추가 npm 설치는 없다.

```sh
# 원문을 다시 수록. 바뀐 파일의 기존 줄 해설은 비활성화되고 검토 필요로 표시
node scripts/build-code-explorer.cjs

# notes/*.json에서 해당 파일의 해설을 수정하고 원문과 대조한 뒤 실행
node scripts/build-code-explorer.cjs --accept-reviewed src/main/java/dev/mentoring/orders/service/OrderService.java

# 생성기 검사와 갱신 누락 확인
node --test scripts/code-explorer/builder.test.cjs scripts/demo/report.test.cjs
pwsh -NoProfile -File scripts/demo/request.test.ps1
node scripts/build-code-explorer.cjs --check
```

소스 표시와 메서드 위치는 다시 생성할 때 자동으로 바뀐다. 설명의 의미까지 자동으로 검토하는 것은 아니다. `--accept-reviewed`는 사람이 원문과 해설을 확인한 사실을 기록하는 명령이며, 검사 우회용 승인이 아니다. 파일 경로를 생략하면 전체 파일을 승인하므로 최초 작성 등 전체를 실제로 검토한 경우에만 사용한다.

CI는 원문과 해설 해시, 미수록 파일, 잘못된 줄 번호와 생성 HTML의 차이를 확인한다. 소스를 바꾸고 HTML 갱신을 빠뜨리면 실패한다. 브라우저에서 열어 둔 HTML이 파일 변경을 실시간으로 감시하지는 않는다. 재생성과 새로고침이 필요하다.

검토가 필요한 파일은 파일 설명과 메서드 요약도 표시하지 않는다. 다른 파일의 호출 설명에서도 해당 요약을 재사용하지 않으며, 현재 구문과 공통 설명만 제공한다. SQL 파일과 Java 문자열 밖의 조건문을 SQL로 분류하지 않는다. 구문 분석 대상이 아닌 실행 스크립트 안의 SQL은 확인한 줄 해설로 설명한다.

## 파일 구성

| 파일 | 역할 |
| --- | --- |
| `index.html` | 배포하는 단일 HTML. 원문, 해설, 아이콘과 그림 포함 |
| `notes/*.json` | 파일 목적, 메서드, 기술 선택과 줄별 설명 |
| `reviewed-sources.json` | 대조를 마친 원문과 해설의 해시 |
| `source/` | 화면 HTML, CSS와 동작 코드 |
| `vendor/lucide-icons.json` | 사용한 Lucide 아이콘과 라이선스 |
| `../../scripts/build-code-explorer.cjs` | 생성과 최신성 검사 |
| `../../scripts/code-explorer/SourceIndex.java` | JDK 구문 분석기로 메서드와 어노테이션 위치 추출 |

검증 범위 탭의 정규 JUnit 수와 분류는 `evidence/verification/latest.json`의 클래스별 결과에서 읽는다. 과거 DB 중단 검사 15건은 별도 기록이다. 실행과 결과 탭의 로컬 보고서는 실제로 다시 실행해 생성한 파일에 연결한다. 이 문서의 생성기 검사와 화면 검사는 서비스 검사 수에 더하지 않는다. 해시 일치는 설명의 정확성, 주문 동작이나 운영 성능을 보증하지 않는다.

생성기와 브라우저 확인 결과는 [문서 검사](VALIDATION.md)를 확인한다. 로컬 전체 검사와 데모 실행 뒤 Playwright의 별도 세션에서 `playwright-cli run-code --filename=scripts/code-explorer/browser-check.js`로 화면을 검사한다. 로컬 결과 링크도 실제 보고서로 열어 확인하므로 두 보고서가 먼저 있어야 한다. 해당 세션에 로컬 파일 읽기를 허용하고 사용 중인 브라우저에는 연결하지 않는다. 캡처는 `.runtime/code-explorer`에 저장한다.

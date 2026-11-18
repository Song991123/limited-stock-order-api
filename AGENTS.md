# 코드와 HTML 해설 갱신

이 저장소의 주문과 취소 코드는 `docs/code-explorer/index.html`에서 원문과 줄별 해설로 함께 읽는다. 소스 코드를 바꾸면 HTML 해설도 같은 변경에 포함한다.

## 변경 순서

1. 바뀐 파일의 동작과 호출 관계를 확인한다. `docs/code-explorer/notes/*.json`의 해당 파일 목적, 메서드, 줄별 설명과 선택 이유를 수정한다.
2. 공통 기술이나 어노테이션의 사용 목적이 바뀌면 `scripts/code-explorer/content.cjs`도 수정한다. 새 파일에는 해설 항목을 추가하고, 삭제한 파일의 해설은 제거한다.
3. 변경한 원문과 해설을 직접 대조한 뒤 `node scripts/build-code-explorer.cjs --accept-reviewed <저장소 기준 파일 경로>`로 해당 파일의 검토 기준을 갱신한다. 줄 번호만 옮기거나 검사를 통과시키려고 검토 기준을 일괄 승인하지 않는다.
4. `node scripts/build-code-explorer.cjs`로 HTML을 다시 만든다. 생성된 HTML의 원문을 직접 편집하지 않는다.
5. `node --test scripts/code-explorer/builder.test.cjs`와 `node scripts/build-code-explorer.cjs --check`를 실행한다. 화면을 바꿨으면 검색, 파일 이동과 작은 화면 표시도 확인한다.

## 작성 기준

- 프로그램을 처음 보는 사람을 기준으로 대상, 입력, 조건, 저장 대상과 결과를 적는다. 코드 한 줄마다 같은 메서드 요약을 반복하지 않는다.
- 어노테이션은 실제 효과, 이 코드에서 쓴 이유, 다른 방법과 주의점을 구분한다. 사용 사실만으로 성능 우위나 완전한 안전성을 주장하지 않는다.
- 한국어 용어는 `API 명세`, `처리 규칙`, `보장 범위`처럼 구체적으로 쓴다. 번역체와 보고형 문구, 가운데점과 불필요한 굵은 글씨는 피한다.
- 기존 검사 기록, 새로 실행한 검사와 미검증 범위를 구분한다. HTML 생성과 문서 검사를 주문 서버나 DB의 새 실행 결과로 세지 않는다.
- 비밀번호, 개인 경로, `.runtime`과 원시 실행 자료는 HTML에 넣지 않는다. Bubli와 노션은 이 문서 갱신의 수정 대상이 아니다.

갱신 방법과 수록 범위는 `docs/code-explorer/README.md`를 따른다.

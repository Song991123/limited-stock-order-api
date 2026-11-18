'use strict';

/** 원문 구문과 검토된 해설로 줄별 설명과 코드 강조 생성. */
const { annotations } = require('./content.cjs');
const escape = text => String(text).replace(/[&<>"']/g, value => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[value]));
const short = (text, length = 110) => text.length > length ? text.slice(0, length) + '…' : text;
const basicCalls = {
  param: 'SQL의 이름 붙은 값 자리에 매개변수를 바인딩합니다. SQL 문장에 입력 문자열을 직접 이어 붙이지 않습니다.',
  sql: '실행할 SQL을 JdbcClient에 전달합니다. 이 호출만으로 아직 조회나 갱신이 실행된 것은 아닙니다.',
  query: '조회 결과를 지정한 자료형이나 RowMapper로 읽을 준비를 합니다.',
  update: '갱신 SQL을 실행하고 실제 바뀐 기록 수를 받습니다.',
  optional: '조회가 0건이면 빈 Optional, 1건이면 그 값을 받습니다. 빈 값의 뜻은 호출한 메서드의 처리 규칙에 따릅니다.',
  single: '조회 결과 한 건을 받습니다. 0건 또는 여러 건이면 정상 한 건 조회로 처리하지 않습니다.',
  list: '조회 결과를 목록으로 받습니다.',
  orElseThrow: '값이 없으면 지정한 예외로 처리합니다. 정상 결과로 null을 숨기지 않습니다.',
  isPresent: 'Optional에 값이 있는지 확인합니다.',
  isEmpty: '값이나 목록이 비어 있는지 확인합니다. 다음 분기에서 비어 있는 이유를 구분합니다.',
  withUsername: '저장된 계정명으로 Spring Security의 로그인 정보 생성을 시작합니다.',
  password: '비밀번호 비교에 사용할 저장된 해시를 로그인 정보에 넣습니다. 여기서 비교하지 않습니다.',
  roles: '로그인 정보에 CUSTOMER 등의 역할을 지정합니다.',
  getName: '인증된 Principal의 계정명이나 지정한 객체의 이름을 읽습니다.',
  getString: 'SQL 조회 결과의 지정한 문자열 열을 읽습니다.',
  getLong: 'SQL 조회 결과의 지정한 bigint 값을 long으로 읽습니다.',
  getInt: 'SQL 조회 결과의 지정한 정수 값을 읽습니다.',
  getObject: 'SQL 조회 값을 지정한 Java 자료형으로 읽습니다.',
  getInputStream: '요청 본문을 읽을 스트림을 받습니다. 큰 본문을 먼저 읽지 않도록 앞선 길이 검사를 구분합니다.',
  setHeader: '응답 헤더에 지정한 값을 설정합니다.',
  header: '응답 또는 요청에 지정한 헤더를 연결합니다.',
  setStatus: 'HTTP 응답 상태를 설정합니다.',
  writeValue: '객체를 JSON으로 변환해 응답 스트림에 기록합니다.',
  isEqualTo: '실제로 읽은 값이 기대한 값과 같은지 단언합니다. 다르면 이 검사는 실패합니다.',
  isTrue: '검사한 조건이 true인지 단언합니다.',
  isFalse: '검사한 조건이 false인지 단언합니다.',
  isZero: '실제 값이 0인지 단언합니다.',
  hasSize: '조회한 목록이나 컬렉션의 원소 수를 검사합니다.',
  contains: '실제 결과에 필요한 값이 들어 있는지 검사합니다.',
  containsExactly: '결과의 값과 순서가 기대한 값과 정확히 같은지 검사합니다.',
  doesNotContain: '결과에 금지한 내용이 없는지 검사합니다.',
  assertThat: '응답이나 DB 조회 등으로 얻은 값을 단언 대상으로 선택합니다.',
  assertThatThrownBy: '지정한 실행이 예상한 예외로 거절되는지 검사합니다.',
  mock: '실제 객체 대신 호출과 반환값을 통제할 검사 대역을 만듭니다. 실제 DB 실행이 아닙니다.',
  when: '검사 대역이 특정 호출에 돌려줄 결과를 지정합니다.',
  verify: '검사 대역에 필요한 호출이 있었는지 확인합니다. 저장이 실제 커밋됐다는 뜻은 아닙니다.',
  readValue: 'JSON 문자열을 지정한 객체로 읽습니다.',
  readTree: '실제 응답 JSON을 필드별로 읽을 수 있는 트리로 파싱합니다.',
  statusCode: '실제로 받은 HTTP 응답의 상태를 읽습니다.',
  body: 'HTTP 응답 본문 또는 전송할 응답 본문을 사용합니다.',
  firstValue: '응답 헤더의 첫 값을 읽습니다. 헤더가 없을 수 있어 Optional로 받습니다.',
  await: '장벽이나 관찰 조건이 충족될 때까지 기다립니다. 운영 대기열과는 구분합니다.',
  atMost: '검사에서 조건을 기다릴 최대 시간을 지정합니다.',
  until: '검사 코드가 관찰하는 조건이 참이 될 때까지 반복 확인합니다.',
  submit: '검사 작업을 별도 실행기에 제출해 다른 요청과 겹치게 실행합니다.',
  shutdownNow: '이 검사에서 만든 실행기에 종료를 요청합니다.',
  close: '직접 연 스트림, 연결이나 서버 자원을 정리합니다.',
  execute: '지정한 SQL이나 제한된 작업을 실행합니다. 어떤 객체에 호출했는지와 메서드 목적을 함께 봅니다.',
  executeUpdate: '검사 SQL 갱신을 실행하고 변경 건수를 받습니다.',
  executeQuery: '검사 SQL 조회를 실행합니다.',
  commit: '현재 DB 연결의 미확정 변경을 커밋합니다.',
  rollback: '현재 DB 연결의 미확정 변경을 롤백합니다.',
  setAutoCommit: '검사 연결의 자동 커밋 여부를 설정합니다.',
  createStatement: '이 DB 연결에서 검사 SQL을 실행할 Statement를 만듭니다.',
  connect: '지정한 시험 서버나 DB에 연결합니다.',
  send: '실제 HTTP 요청을 보내고 응답을 받습니다. 검사용 클라이언트의 호출입니다.',
  encode: '예제 등록 시 원문 대신 저장할 비밀번호 해시를 계산합니다.',
  println: '검사 또는 도구의 결과를 출력합니다.',
  print: '지정한 정보를 출력합니다.',
  build: '앞에서 지정한 값으로 객체 구성을 마칩니다.',
  map: '각 값을 필요한 다른 형태로 변환합니다.',
  toList: '변환한 값을 목록으로 모읍니다.',
  stream: '목록의 원소를 순서대로 변환할 Stream을 만듭니다.',
  of: '지정한 값으로 불변 목록, 맵 또는 시간 값을 만듭니다.',
  valueOf: '저장된 이름을 해당 enum 또는 문자열 자료형으로 변환합니다.',
  name: 'enum의 저장용 이름을 사용합니다.',
  now: '지정한 시계의 현재 시각을 읽습니다.',
  add: '검사 관찰 값이나 결과를 목록에 추가합니다.',
  addAll: '여러 검사 결과를 목록에 모읍니다.',
  matches: '전체 값이 지정한 정규식의 형식에 맞는지 확인합니다.',
  matcher: '검사할 문자열에 정규식을 적용합니다.',
  pattern: '입력 검사에 사용할 패턴을 지정합니다.',
  compile: '여러 입력 검사에 재사용할 정규식을 만듭니다.',
  getLogger: '해당 클래스의 로그 기록기를 받습니다.',
  error: '허용한 오류 코드와 참조 번호 등으로 오류를 기록합니다. 예외 원문과 SQL을 노출하지 않습니다.'
};

/** 현재 선언 위치와 표시할 메서드 요약 연결. */
function prepareMethods(syntax, note) {
  return (syntax.symbols || []).filter(item => item.kind === 'method').map(item => ({ ...item,
    title: note.methods?.[item.name]?.title || item.name,
    purpose: note.methods?.[item.name]?.purpose || '선언과 메서드 본문을 원문에서 확인합니다.'
  }));
}

/** SQL 파일과 Java 문자열 안의 줄만 SQL로 분류해 if 조건문의 잘못된 설명 방지. */
function isSqlContext({ path: file, line, code, syntax }) {
  if (file.endsWith('.sql')) return true;
  if (!file.endsWith('.java')) return false;
  const column = code.search(/\S/) + 1;
  return (syntax.events || []).some(item => item.kind === 'literal' && item.name === 'STRING_LITERAL'
    && (item.line < line || item.line === line && item.column <= column)
    && (item.endLine > line || item.endLine === line && item.endColumn > column));
}

/** 검토된 줄 해설을 먼저 적용하고 나머지는 현재 구문으로 설명. */
function explain(ctx) {
  const { line, code, syntax, note, reviewed, methods, path: file } = ctx;
  const text = code.trim();
  const method = methods.filter(item => item.line <= line && item.endLine >= line).sort((a, b) => (a.endLine - a.line) - (b.endLine - b.line))[0];
  const result = (description, kind = 'syntax', tech = [], links = []) => ({ description, kind, tech, links, method: method?.name || null });
  if (!text) return result('', 'blank');
  if (reviewed && note.lines?.[String(line)]) {
    const annotation = (syntax.events || []).find(item => item.kind === 'annotation' && item.line === line);
    return result(note.lines[String(line)], 'specific', annotation && annotations[annotation.name] ? [annotations[annotation.name].tech] : []);
  }
  if (/^(?:\/\/|\/\*|\*|\*\/|--|#)/.test(text) && !text.startsWith('#{')) {
    if (/^\*?\s*@param\b/.test(text)) return result('메서드가 받는 값의 의미를 적은 Javadoc입니다. 입력 조건을 실제로 실행하는 코드는 아닙니다.', 'comment');
    if (/^\*?\s*@throws\b/.test(text)) return result('어떤 조건에서 예외가 전달되는지 설명한 Javadoc입니다.', 'comment');
    if (/^\*?\s*@return\b/.test(text)) return result('메서드 반환값의 의미를 설명하는 Javadoc입니다.', 'comment');
    return result('원문에 작성된 설명 주석입니다. 실행 문장이 아닙니다.', 'comment');
  }
  if (/^package\s/.test(text)) return result(`이 파일을 ${syntax.packageName || text.replace(/^package\s+|;$/g, '')} 패키지에 둡니다. 폴더 이름과 Java 패키지 이름을 맞춥니다.`);
  const imported = (syntax.events || []).find(item => item.kind === 'import' && item.line === line);
  if (imported) {
    const name = imported.name.split('.').pop();
    return result(`${name} 타입${text.includes('import static') ? ' 또는 정적 메서드' : ''}을 이 파일에서 짧은 이름으로 사용합니다. ${typePurpose(name)}`);
  }
  const activeAnnotation = (syntax.events || []).find(item => item.kind === 'annotation' && item.line <= line && item.endLine >= line);
  if (activeAnnotation && /^@/.test(text)) {
    const name = activeAnnotation.name.split('.').pop(), entry = annotations[name];
    return result(entry ? entry.effect : `${name} 어노테이션에 지정한 조건을 적용합니다.`, entry ? 'specific' : 'syntax', entry ? [entry.tech] : []);
  }
  if (/^[{});,\]\s]+$/.test(text)) {
    const transactional = method && (syntax.events || []).some(item => item.kind === 'annotation' && item.name === 'Transactional' && item.line >= method.line && item.line <= method.line + 3);
    return result(text === '}' && method?.endLine === line
      ? `${method.title} 메서드의 본문을 마칩니다.${transactional ? ' 이 호출을 감싼 Spring 프록시가 트랜잭션을 확정한 뒤 호출자에게 결과를 돌려줍니다.' : ''}`
      : '앞에서 연 코드 구문을 닫습니다. 이 줄 자체가 DB 커밋 명령은 아닙니다.');
  }
  const type = (syntax.symbols || []).find(item => item.kind === 'type' && item.line <= line && item.line + 3 >= line && /\b(class|record|enum|interface)\b/.test(text));
  if (type) return result(`${type.name} ${type.typeKind.toLowerCase()}를 선언합니다. ${note.purpose}`, 'specific', ['java']);
  if (method && line <= method.line + 7 && new RegExp('\\b' + method.name + '\\s*\\(').test(text) && !/\breturn\b|->|\.\w+\(/.test(text)) {
    return result(`${method.title} 메서드${method.returns === 'constructor' ? '(생성자)' : ''}를 선언합니다. ${method.purpose}`, 'specific');
  }
  if (isSqlContext(ctx) && /^(SELECT|INSERT|UPDATE|DELETE|CREATE|ALTER|SET|WHERE|FROM|JOIN|LEFT|RIGHT|INNER|ON|VALUES|RETURNING|ORDER BY|LIMIT|AND|OR|IF|BEGIN|END|RAISE|FOR EACH|AFTER|BEFORE|REFERENCES|CHECK|UNIQUE|NOT NULL|PRIMARY KEY)\b/i.test(text)) return result(sqlLine(text), 'specific', ['jdbc']);
  if (/\.(param|query|optional|single|list|update)\s*\(/.test(text) && /^(?:\.|\"\"\"|return\s+jdbcClient)/.test(text)) {
    const calls = [...text.matchAll(/\.(param|query|optional|single|list|update)\s*\(/g)].map(match => match[1]);
    return result([...new Set(calls)].map(name => basicCalls[name]).join(' '), 'specific', ['jdbc']);
  }
  if (/^(?:case|default)\b/.test(text)) return result(`처리 결과 ${short(text.split('->')[0])}에 해당하는 분기입니다. 각 결과를 서로 다른 코드로 처리합니다.`, 'specific', ['java']);
  const started = (syntax.events || []).filter(item => item.line === line);
  const condition = started.find(item => item.kind === 'if');
  if (condition) return result(`${short(condition.name)} 조건을 검사합니다. 참인 경우에만 뒤의 분기를 실행합니다.`, 'specific');
  const thrown = started.find(item => item.kind === 'throw');
  if (thrown) return result(`${short(thrown.name)} 예외를 전달해 정상 처리를 중단합니다. 저장 도중의 실패와 재고 부족의 정상 처리 결과를 구분합니다.`, 'specific', ['error-handling']);
  if (started.some(item => item.kind === 'try')) return result('실패 처리 또는 자원 정리가 필요한 실행을 시작합니다. 괄호에 선언한 자원은 try-with-resources로 닫습니다.', 'specific');
  const caught = started.find(item => item.kind === 'catch');
  if (caught) return result(`${caught.name} 예외를 받아 지정한 실패 처리로 진행합니다. 예외를 성공으로 바꾸는지와 호출자에게 다시 전달하는지 구분합니다.`, 'specific', ['error-handling']);
  if (started.some(item => item.kind === 'loop')) return result('목록 또는 지정한 횟수의 반복을 실행합니다. 각 반복에서 바꾸는 데이터와 종료 조건은 해당 본문에 있습니다.', 'specific');
  const calls = (syntax.events || []).filter(item => item.kind === 'call' && item.line <= line && item.endLine >= line && text.includes(item.name + '('));
  if (calls.length) {
    const descriptions = [], links = [];
    for (const call of calls) {
      const defined = ctx.allMethods.get(call.name);
      // 양쪽 파일이 검토됐고 대상 파일이 하나인 메서드만 요약과 링크 재사용.
      if (reviewed && defined?.length === 1 && defined[0].reviewed) {
        descriptions.push(`${call.name} 호출: ${defined[0].purpose}`);
        links.push({ file: defined[0].file, symbol: call.name });
      } else if (basicCalls[call.name]) descriptions.push(basicCalls[call.name]);
    }
    if (descriptions.length) return result([...new Set(descriptions)].slice(0, 3).join(' '), 'specific', [], links);
  }
  const variable = started.find(item => item.kind === 'variable');
  if (variable) {
    const value = text.includes('=') ? text.slice(text.indexOf('=') + 1).trim().replace(/;$/, '') : '';
    return result(`${variable.type === 'var' ? '컴파일 시 추론한 타입' : variable.type}의 ${variable.name} 값을 ${value ? short(value) + '로 준비합니다' : '받거나 보관합니다'}. ${variableMeaning(variable.name)}`, 'specific');
  }
  const returned = started.find(item => item.kind === 'return');
  if (returned) return result(`${short(returned.name)}를 호출자에게 반환합니다. ${method ? method.purpose : ''}`, 'specific');
  if (/^this\.\w+\s*=/.test(text)) return result('생성자로 받은 객체나 설정값을 인스턴스 필드에 보관합니다. 이후 메서드가 같은 의존성을 사용합니다.', 'specific');
  if (/^else\b|^}\s*else/.test(text)) return result('앞선 조건이 거짓일 때 실행할 분기입니다.', 'specific');
  if (/^(?:ACCEPTED|CANCELLED|OUT_OF_STOCK|DEBIT|RESTORE)[,;]?$/.test(text)) return result(`${text.replace(/[,;]/g, '')}를 허용한 상태 또는 증감 이력의 종류로 선언합니다. 임의 문자열 대신 enum으로 구분합니다.`, 'specific', ['java']);
  if (!file.endsWith('.java')) return result(scriptLine(text, file), 'syntax');
  if (/^(?:public|private|protected|static|final|sealed|permits|implements|extends)\b/.test(text)) return result('타입이나 메서드 선언의 접근 범위, 상속 또는 허용된 구현을 지정합니다.', 'syntax', ['java']);
  if (/^(?:\.|\)|\]|:|\?|&&|\|\||\+|->|throws\b)/.test(text)) return result('앞선 호출의 매개변수, 연산 또는 메서드 연결을 이어갑니다. 값의 출처는 왼쪽 원문에 그대로 표시합니다.', 'syntax');
  if (text.endsWith(',') || text.endsWith(');') || text.endsWith('));')) return result('메서드 호출이나 자료형 선언에 필요한 값을 순서대로 전달합니다.', 'syntax');
  return result('이 실행 줄의 구체적인 동작 해설이 아직 작성되지 않았습니다.', 'missing');
}

function typePurpose(name) {
  const meanings = { Optional: '값이 없을 수 있는 조회 결과를 표현합니다.', List: '여러 값을 순서 있는 목록으로 다룹니다.', Map: '이름과 값의 쌍으로 결과를 보관합니다.', Principal: 'Spring Security가 확인한 로그인 계정을 받습니다.', JdbcClient: '매개변수 바인딩과 SQL 실행에 사용합니다.', Instant: '시간대와 무관한 한 시점을 표현합니다.', RowMapper: 'SQL 조회의 한 행을 Java 객체로 변환합니다.', HttpResponse: '실제 HTTP 응답의 상태, 헤더와 본문을 읽습니다.', ResponseEntity: 'HTTP 상태, 헤더와 응답 본문을 함께 지정합니다.', Semaphore: '동시에 실행할 작업 수를 제한합니다.', ObjectMapper: 'JSON과 Java 값을 변환합니다.' };
  return meanings[name] || '아래 선언과 메서드에서 해당 타입의 기능을 사용합니다.';
}

function variableMeaning(name) {
  const meanings = { buyerId: '인증된 계정으로 찾은 구매자 ID이며 요청 본문의 임의 고객 ID를 신뢰하지 않습니다.', quantity: '주문할 양의 정수 수량입니다. 실제 재고 충분 여부는 DB가 판단합니다.', productId: '주문하거나 조회할 상품 ID입니다.', orderId: '취소 또는 조회할 주문 ID입니다.', idempotencyKey: '같은 구매의 재시도에서 유지하는 키입니다.', orderRepository: '주문, 재고와 이력 SQL을 담당합니다.', productRepository: '상품 존재와 남은 수량을 조회합니다.', jdbcClient: '같은 DataSource와 트랜잭션에서 SQL을 실행합니다.', available: '실제 조회한 판매 가능 수량입니다.', server: '검사에서 직접 실행한 HTTP 서버입니다.', events: '실제 응답과 DB 관찰 값의 기록 목록입니다.' };
  return meanings[name] || '';
}

function sqlLine(text) {
  if (/ON CONFLICT/i.test(text)) return '같은 고객과 구매 키의 유일성 충돌이면 새 INSERT를 생략합니다. 앞선 요청이 커밋했는지 롤백했는지에 따라 등록 결과가 달라집니다.';
  if (/RETURNING/i.test(text)) return '이번 SQL이 실제로 저장하거나 바꾼 기록을 즉시 반환합니다. 별도 조회와 변경 건수 판정을 구분합니다.';
  if (/^WHERE|^AND|^OR/i.test(text)) return `SQL이 읽거나 바꿀 대상을 ${short(text)} 조건으로 제한합니다. 이 조건은 DB에서 평가합니다.`;
  if (/^UPDATE/i.test(text)) return '조건에 맞는 DB 기록을 갱신합니다. 다른 요청이 같은 기록을 바꾸고 있으면 잠금을 기다릴 수 있습니다.';
  if (/^SET/i.test(text)) return `지정한 열의 새 값 또는 DB 연결 설정을 ${short(text)}로 지정합니다.`;
  if (/^INSERT/i.test(text)) return '지정한 테이블에 새 기록을 등록합니다. 중복, 외래키와 수량 제약은 DB가 검사합니다.';
  if (/^SELECT/i.test(text)) return '요청 처리에 필요한 값을 조회합니다. 일반 SELECT 자체가 해당 기록의 독점 잠금은 아닙니다.';
  if (/^CREATE CONSTRAINT TRIGGER|DEFERRABLE|INITIALLY DEFERRED/i.test(text)) return '결과가 아직 없는 처리 중 기록은 허용하되, 트랜잭션 확정 때 결과 누락을 거절하는 지연 제약 트리거입니다.';
  if (/^CREATE/i.test(text)) return '테이블, 인덱스 또는 검사 함수의 구조를 정의합니다. 실제 설치는 Flyway 또는 검사 준비 단계에서 수행합니다.';
  return `SQL 구문의 ${short(text)} 부분입니다. 조회/저장 조건과 반환 대상을 지정합니다.`;
}

function scriptLine(text, file) {
  if (/^\$?\w+[.:]?\s*[:=]/.test(text)) return '이 도구나 실행 설정에서 사용할 값을 정의합니다. 환경 변수 이름과 값의 출처를 구분합니다.';
  if (/^function\b|^(?:async\s+)?function\b/.test(text)) return '아래 작업에서 재사용할 함수를 선언합니다. 실제 실행은 이 함수를 호출할 때 시작합니다.';
  if (/\bthrow\b|\bthrow new\b/.test(text)) return '검사나 실행 조건이 맞지 않으면 오류를 전달해 작업을 중단합니다.';
  if (/^(?:if|elseif|else\s+if)\b|\?/i.test(text.replace(/^}\s*/, ''))) return '실행 조건을 확인하고 해당하는 경우의 작업을 선택합니다.';
  if (/^for\b|^foreach\b|\.forEach\(/.test(text)) return '지정한 파일이나 조건 목록을 반복 처리합니다.';
  if (/^try\b|^finally\b|^catch\b/.test(text)) return '실패 처리와 직접 연 자원의 정리를 구분합니다.';
  if (/^return\b/.test(text)) return '계산하거나 조회한 결과를 호출자에게 반환합니다.';
  if (/implementation|runtimeOnly|testImplementation/.test(text)) return '빌드에 사용할 라이브러리와 적용 범위를 지정합니다. 테스트 의존성은 제품 실행 의존성과 구분합니다.';
  if (/uses:|run:|with:|env:|steps:/.test(text)) return 'CI에서 실행할 작업, 입력 또는 환경을 지정합니다. 실제 실행 결과와 설정 자체를 구분합니다.';
  return `${file.endsWith('.ps1') ? 'PowerShell' : file.endsWith('.gradle') ? 'Gradle' : '실행 도구'}의 설정 또는 명령을 연결합니다. 이 파일의 목적과 메서드 설명을 함께 확인합니다.`;
}

/** 구문 위치로 강조 태그를 붙이고 원문은 HTML 문자로 이스케이프. */
function highlight(ctx) {
  const { code, line, syntax } = ctx;
  const text = code.trim();
  if (/^(\/\/|\/\*|\*|--|#)/.test(text)) return '<span class="token-comment">' + escape(code) + '</span>';
  const intervals = (syntax.events || []).filter(item => ['literal', 'annotation'].includes(item.kind) && item.line <= line && item.endLine >= line)
    .map(item => ({ start: item.line === line ? item.column - 1 : 0, end: item.endLine === line ? item.endColumn - 1 : code.length, type: item.kind === 'annotation' ? 'annotation' : item.name.includes('STRING') || item.name.includes('CHAR') ? 'string' : 'number' }))
    .sort((a, b) => a.start - b.start || b.end - a.end);
  let cursor = 0, html = '';
  const keyword = value => escape(value).replace(/\b(public|private|protected|final|static|class|record|interface|enum|sealed|permits|return|if|else|switch|case|default|new|throw|throws|try|catch|finally|for|while|var|void|boolean|long|int|import|package|extends|implements|null|true|false)\b/g, '<span class="token-keyword">$1</span>');
  for (const item of intervals) {
    if (item.start < cursor || item.start > code.length) continue;
    html += keyword(code.slice(cursor, item.start));
    html += `<span class="token-${item.type}">${escape(code.slice(item.start, item.end))}</span>`;
    cursor = item.end;
  }
  return html + keyword(code.slice(cursor));
}

module.exports = { explain, highlight, prepareMethods, sqlLine, scriptLine };

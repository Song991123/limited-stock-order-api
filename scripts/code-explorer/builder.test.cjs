'use strict';

/** 문서 생성기 회귀 검사. 임시 소스와 해설로 변경 감지를 확인하며 주문 서버와 DB는 실행하지 않음. */

const { test, before, after } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const { build, collect, acceptReview, sourceFiles, digest, normalize, safePath, OUTPUT, LOCK, TOOL_ROOT } = require('./builder.cjs');
const { highlight, explain } = require('./explanations.cjs');
const content = require('./content.cjs');

let snapshot;
const temporary = [];
const samplePath = 'src/main/java/Fixture.java';
const sampleText = 'public class Fixture {\n    int count = 1;\n}\n';

/** 현재 저장소와 분리된 임시 소스, 해설과 검토 기준 준비. */
function fixture() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'stock-code-explorer-'));
  temporary.push(root);
  fs.mkdirSync(path.join(root, 'docs/code-explorer/notes'), { recursive: true });
  fs.mkdirSync(path.join(root, 'src/main/java'), { recursive: true });
  fs.writeFileSync(path.join(root, samplePath), sampleText);
  const notes = { files: { [samplePath]: { purpose: '생성기 검사 전용 파일', role: 'Fixture', technologies: ['java'], lines: { '1': '검사 타입 선언', '2': '검사 값 1 보관' } } } };
  fs.writeFileSync(path.join(root, 'docs/code-explorer/notes/fixture.json'), JSON.stringify(notes));
  acceptReview(root);
  return root;
}

/** 임시 해설만 수정해 검토 전후의 표시와 최신성 검사 비교. */
function amendNotes(root, change) {
  const file = path.join(root, 'docs/code-explorer/notes/fixture.json');
  const notes = JSON.parse(fs.readFileSync(file, 'utf8'));
  change(notes);
  fs.writeFileSync(file, JSON.stringify(notes));
}

/** 도우미와 호출자를 따로 만들어 다른 파일로 옛 설명이 전파되는 조건 재현. */
function methodFixture() {
  const root = fixture();
  const source = 'public class Fixture {\n    static int helper() {\n        return 1;\n    }\n}\n';
  const caller = 'public class Caller {\n    int execute() {\n        return Fixture.helper();\n    }\n}\n';
  fs.writeFileSync(path.join(root, samplePath), source);
  fs.writeFileSync(path.join(root, 'src/main/java/Caller.java'), caller);
  amendNotes(root, document => {
    Object.assign(document.files[samplePath], {
      purpose: 'OLD_FILE_PURPOSE', decisions: [{ title: 'OLD_FILE_DECISION', why: 'OLD_FILE_REASON' }],
      methods: { helper: { title: 'OLD_METHOD_TITLE', purpose: 'OLD_METHOD_PURPOSE' } },
      lines: { '1': 'OLD_LINE_NOTE' }
    });
    document.files['src/main/java/Caller.java'] = { purpose: '검토된 호출자', role: 'Fixture',
      methods: { execute: { title: '호출', purpose: '도우미 호출' } }, lines: {}, technologies: [] };
  });
  acceptReview(root);
  return { root, source, parsed: collect(root).parsed };
}

const syntaxContext = (file, code, events = []) => ({ path: file, line: 1, code,
  syntax: { symbols: [], events }, note: {}, reviewed: true, methods: [], allMethods: new Map() });

before(() => { snapshot = build(); });
after(() => {
  const parent = fs.realpathSync(os.tmpdir());
  for (const root of temporary) {
    const resolved = fs.realpathSync(root);
    assert.equal(path.dirname(resolved), parent);
    assert.ok(path.basename(resolved).startsWith('stock-code-explorer-'));
    fs.rmSync(resolved, { recursive: true });
  }
});

test('all current sources have reviewed explanations and a deterministic HTML', () => {
  assert.deepEqual(snapshot.issues, []);
  assert.equal(normalize(fs.readFileSync(snapshot.output, 'utf8')), snapshot.html);
  const repeated = build(TOOL_ROOT, { javaIndex: snapshot.parsed });
  assert.equal(snapshot.html, repeated.html);
});

test('every source line and original character is retained', () => {
  for (const file of snapshot.files) {
    const original = normalize(fs.readFileSync(path.join(TOOL_ROOT, file.path), 'utf8'));
    assert.equal(file.text, original, file.path);
    assert.equal(file.rows.map(row => row.code).join('\n'), original, file.path);
    assert.equal(file.sourceHash, digest(original));
    assert.ok(file.rows.every((row, index) => row.line === index + 1));
    assert.ok(file.rows.every(row => row.kind === 'blank' || row.description), file.path);
  }
});

test('execution guide links commands, current local reports and the executable without embedding runtime data', () => {
  const guide = snapshot.data.execution;
  assert.equal(guide.steps.length, 4);
  for (const step of guide.steps) {
    assert.ok(snapshot.files.some(file => file.path === step.source), step.source);
    assert.ok(step.command.startsWith('pwsh -NoProfile -File .\\scripts\\'));
  }
  assert.ok(guide.steps[0].command.endsWith('-IsolatedDatabase'));
  assert.deepEqual(guide.outputs.map(item => item.href), [
    '../../build/reports/demo/index.html', '../../build/reports/tests/test/index.html', '../../build/libs/limited-stock-order-api-0.1.0.jar'
  ]);
  for (const output of guide.outputs) {
    assert.ok(path.resolve(path.dirname(snapshot.output), output.href).startsWith(TOOL_ROOT + path.sep));
  }
  assert.ok(!snapshot.files.some(file => file.path.startsWith('build/') || file.path.startsWith('.runtime/')));
  assert.ok(snapshot.html.includes('id="tab-execution"'));
  assert.ok(snapshot.html.includes('id="view-execution"'));
});

test('JDK parser locates methods and annotations in all Java sources', () => {
  const javaFiles = snapshot.files.filter(file => file.path.endsWith('.java'));
  assert.equal(Object.keys(snapshot.parsed).length, javaFiles.length);
  const controller = javaFiles.find(file => file.name === 'OrderController.java');
  const service = javaFiles.find(file => file.name === 'OrderService.java');
  assert.ok(controller.methods.some(method => method.name === 'placeOrder'));
  assert.ok(service.annotations.includes('Transactional'));
  for (const file of javaFiles) for (const method of file.methods) {
    assert.ok(method.line >= 1 && method.endLine <= file.rows.length, file.path + ':' + method.name);
    assert.ok(method.endLine >= method.line);
  }
});

test('all annotations actually used have effect, reason, alternative and limits', () => {
  const names = new Set(snapshot.files.flatMap(file => file.annotations));
  for (const name of names) {
    const entry = content.annotations[name];
    assert.ok(entry, '@' + name);
    for (const key of ['effect', 'why', 'alternatives', 'limit']) assert.ok(entry[key], '@' + name + ':' + key);
    assert.ok(content.technologies[entry.tech]);
  }
});

test('flow links point to actual source methods', () => {
  for (const flow of content.flows) for (const [, target, method] of [...flow.steps, ...(flow.branches || []).flatMap(branch => branch.steps)]) {
    const file = snapshot.files.find(file => file.path === target);
    assert.ok(file, target);
    if (method) assert.ok(file.methods.some(item => item.name === method), target + ':' + method);
  }
});

test('cancellation separates first cancellation and repeated cancellation', () => {
  const flow = content.flows.find(flow => flow.id === 'cancel');
  assert.equal(flow.steps.length, 2);
  assert.deepEqual(flow.branches.map(branch => branch.id), ['first', 'again']);
  const first = flow.branches[0], again = flow.branches[1];
  assert.equal(first.title, '처음 취소');
  assert.equal(again.title, '재취소');
  assert.match(first.condition, /접수 상태에서 취소 상태/);
  assert.match(again.condition, /이미 취소/);
  assert.ok(first.steps.some(step => step[2] === 'cancelOrder' && step[3].includes('재고에 더하고')));
  assert.ok(again.steps.some(step => step[2] === 'findCancelledOrder'));
  assert.match(again.steps.at(-1)[3], /다시 실행하지 않습니다/);
  assert.match(flow.note, /다른 고객/);
  assert.match(flow.note, /오류/);
});

test('every source path is repository relative and excludes private runtime data', () => {
  assert.deepEqual(snapshot.files.map(file => file.path), sourceFiles(TOOL_ROOT));
  for (const file of snapshot.files) {
    assert.ok(!path.isAbsolute(file.path));
    assert.doesNotMatch(file.path, /(?:^|\/)(?:\.runtime|\.tools|node_modules|build|raw)(?:\/|$)/);
  }
});

test('recorded service tests and separate recovery cases stay separate', () => {
  assert.equal(snapshot.data.evidence.regular.tests, 172);
  assert.equal(snapshot.data.evidence.separateRecovery.tests, 15);
  assert.equal(snapshot.data.evidence.architecture, 9);
  assert.equal(snapshot.data.summary.stale, 0);
  assert.ok(snapshot.data.summary.manual > 1000);
});

test('all existing diagram images are embedded without network dependencies', () => {
  assert.equal(snapshot.data.figures.length, 5);
  for (const figure of snapshot.data.figures) assert.match(figure.image, /^data:image\/png;base64,/);
  assert.doesNotMatch(snapshot.html, /<(?:script|link)[^>]+(?:src|href)=["']https?:/i);
  assert.match(snapshot.html, /connect-src 'none'/);
});

test('HTML excludes private absolute workspace paths', () => {
  assert.doesNotMatch(snapshot.html, /[A-Z]:(?:\\\\|\/)+(?:Users|취준)/i);
});

test('HTML data roundtrips exactly and escapes closing script text', () => {
  const match = snapshot.html.match(/<script id="explorer-data" type="application\/json">([\s\S]*?)<\/script>/);
  assert.ok(match);
  assert.doesNotMatch(match[1], /<|&/);
  const embedded = JSON.parse(match[1]);
  assert.deepEqual(embedded.files.map(file => file.text), snapshot.files.map(file => file.text));
});

test('syntax highlighting escapes source without changing code text', () => {
  const html = highlight({ code: 'String input = "<script>&</script>";', line: 1, syntax: { events: [] } });
  assert.doesNotMatch(html, /<script>/);
  assert.match(html, /&lt;script&gt;&amp;&lt;\/script&gt;/);
});

test('unmodified fixture keeps its reviewed line notes', () => {
  const file = collect(fixture(), {}).files[0];
  assert.equal(file.reviewed, true);
  assert.equal(file.rows[1].description, '검사 값 1 보관');
});

test('source drift updates displayed source but disables obsolete line notes', () => {
  const root = fixture();
  fs.writeFileSync(path.join(root, samplePath), sampleText.replace('= 1', '= 2'));
  const result = collect(root, {}), file = result.files[0];
  assert.equal(file.reviewed, false);
  assert.match(file.text, /= 2/);
  assert.notEqual(file.rows[1].description, '검사 값 1 보관');
  assert.ok(result.issues.some(item => item.includes('review is required')));
});

test('freshness CLI fails for source changes without writing a misleading HTML', () => {
  const root = fixture();
  fs.writeFileSync(path.join(root, samplePath), sampleText.replace('= 1', '= 2'));
  const result = spawnSync(process.execPath, [path.join(TOOL_ROOT, 'scripts/build-code-explorer.cjs'), '--root', root, '--check'],
    { encoding: 'utf8', windowsHide: true });
  assert.equal(result.status, 1);
  assert.match(result.stdout, /review is required/);
  assert.equal(fs.existsSync(path.join(root, OUTPUT)), false);
});

test('source with a closing script tag stays JSON text, not executable HTML', () => {
  const root = fixture();
  const text = sampleText.replace('= 1;', '= 1; // </script><script>throw new Error("injected")</script>');
  fs.writeFileSync(path.join(root, samplePath), text);
  const result = build(root, { javaIndex: {} });
  const match = result.html.match(/<script id="explorer-data" type="application\/json">([\s\S]*?)<\/script>/);
  assert.doesNotMatch(match[1], /<|&/);
  assert.equal(JSON.parse(match[1]).files[0].text, text);
  assert.ok(result.issues.length > 0);
});

test('explanation drift also requires a new review', () => {
  const root = fixture();
  amendNotes(root, document => { document.files[samplePath].lines['2'] = '바뀐 해설'; });
  const result = collect(root, {});
  assert.equal(result.files[0].reviewed, false);
  assert.notEqual(result.files[0].rows[1].description, '바뀐 해설');
});

test('new source without explanation is reported', () => {
  const root = fixture();
  fs.writeFileSync(path.join(root, 'src/main/java/Added.java'), 'class Added {}\n');
  const result = collect(root, {});
  assert.ok(result.issues.some(item => item.includes('Added.java: file explanation is missing')));
});

test('deleted source cannot retain orphan explanation and review entries', () => {
  const root = fixture();
  fs.unlinkSync(path.join(root, samplePath));
  const result = collect(root, {});
  assert.ok(result.issues.some(item => item.includes('notes reference a removed')));
  assert.ok(result.issues.some(item => item.includes('review lock references a removed')));
});

test('out-of-range explanation and duplicate file ownership are rejected', () => {
  const root = fixture();
  amendNotes(root, document => { document.files[samplePath].lines['9999'] = '잘못된 줄'; });
  assert.ok(collect(root, {}).issues.some(item => item.includes('invalid explanation line 9999')));
  const other = path.join(root, 'docs/code-explorer/notes/duplicate.json');
  fs.copyFileSync(path.join(root, 'docs/code-explorer/notes/fixture.json'), other);
  assert.throws(() => collect(root, {}), /Duplicate notes/);
});

test('explicitly accepting a reviewed file updates only its source reference', () => {
  const root = fixture();
  fs.writeFileSync(path.join(root, samplePath), sampleText.replace('= 1', '= 2'));
  amendNotes(root, document => { document.files[samplePath].lines['2'] = '검사 값 2 보관'; });
  acceptReview(root, [samplePath]);
  assert.equal(collect(root, {}).files[0].rows[1].description, '검사 값 2 보관');
  assert.equal(JSON.parse(fs.readFileSync(path.join(root, LOCK), 'utf8')).files[samplePath].sourceHash, digest(sampleText.replace('= 1', '= 2')));
  assert.throws(() => acceptReview(root, ['../outside.java']), /Review target/);
});

test('repository traversal is rejected and cross-OS line endings have one hash', () => {
  assert.throws(() => safePath(TOOL_ROOT, '../outside.txt'), /Outside repository/);
  assert.equal(normalize('\ufeffa\r\nb\r\n'), 'a\nb\n');
  assert.equal(digest(normalize('\ufeffa\r\nb\r\n')), digest('a\nb\n'));
  assert.ok(path.isAbsolute(safePath(TOOL_ROOT, OUTPUT)));
});

test('a plain helper method end does not claim a Spring commit', () => {
  const ctx = { path: 'Fixture.java', line: 3, code: '}', syntax: { events: [] }, note: {}, reviewed: true,
    methods: [{ name: 'helper', title: '도우미', line: 1, endLine: 3 }], allMethods: new Map() };
  assert.doesNotMatch(explain(ctx).description, /커밋|트랜잭션/);
});

test('Java if is a control condition even when it contains a string literal', () => {
  const code = 'if (key.equals("SELECT")) return;';
  const result = explain(syntaxContext('Fixture.java', code, [
    { kind: 'if', name: '(key.equals("SELECT"))', line: 1, endLine: 1 },
    { kind: 'literal', name: 'STRING_LITERAL', line: 1, endLine: 1, column: 16, endColumn: 24 }
  ]));
  assert.match(result.description, /조건을 검사/);
  assert.ok(!result.tech.includes('jdbc'));
  assert.doesNotMatch(result.description, /SQL 구문/);
});

for (const [file, code] of [
  ['fixture.js', 'if (response.status === 429) return;'],
  ['fixture.ps1', 'IF ($LASTEXITCODE -ne 0) { throw "failed" }'],
  ['fixture.cjs', '} else if (failed) {']
]) test(file + ' conditions are not SQL', () => {
  const result = explain(syntaxContext(file, code));
  assert.match(result.description, /조건/);
  assert.ok(!result.tech.includes('jdbc'));
  assert.doesNotMatch(result.description, /SQL/);
});

test('real SQL and SQL inside a Java text block keep SQL explanations', () => {
  const sql = explain(syntaxContext('migration.sql', 'IF request_id IS NULL THEN'));
  const context = syntaxContext('Fixture.java', '    IF request_id IS NULL THEN', [
    { kind: 'literal', name: 'STRING_LITERAL', line: 1, endLine: 3, column: 1, endColumn: 20 }
  ]);
  context.line = 2;
  const java = explain(context);
  assert.ok(sql.tech.includes('jdbc'));
  assert.ok(java.tech.includes('jdbc'));
  assert.match(sql.description, /SQL/);
  assert.match(java.description, /SQL/);
});

test('all current code conditions avoid the former SQL misclassification', () => {
  const wrong = snapshot.files.filter(file => /\.(?:java|[cm]?js|ps1)$/.test(file.path)).flatMap(file => {
    const javaConditions = new Set((snapshot.parsed[file.path]?.events || []).filter(item => item.kind === 'if').map(item => item.line));
    return file.rows.filter(row => (!file.path.endsWith('.java') || javaConditions.has(row.line))
      && /^\s*(if|else if)\s*\(/i.test(row.code) && row.description.startsWith('SQL 구문의'))
      .map(row => file.path + ':' + row.line);
  });
  assert.deepEqual(wrong, []);
});

test('reviewed script SQL fragments keep their explicit SQL explanations', () => {
  for (const [name, line, expected] of [['query-index.cjs', 37, /SQL/], ['query-index.cjs', 96, /테이블/], ['review-load.cjs', 36, /SQL/]]) {
    const file = snapshot.files.find(file => file.name === name);
    assert.equal(file.rows[line - 1].kind, 'specific');
    assert.match(file.rows[line - 1].description, expected);
  }
});

test('stale source hides file notes and method summaries in generated HTML', () => {
  const { root, source } = methodFixture();
  fs.writeFileSync(path.join(root, samplePath), source.replace('return 1', 'return 2'));
  const result = build(root);
  const file = result.files.find(file => file.path === samplePath);
  assert.equal(file.reviewed, false);
  assert.ok(result.issues.some(issue => issue.includes('review is required')));
  assert.ok(file.methods.some(method => method.name === 'helper'));
  assert.match(file.rows[2].description, /2/);
  assert.doesNotMatch(JSON.stringify(file), /OLD_(?:FILE|METHOD|LINE)/);
  assert.doesNotMatch(result.html, /OLD_(?:FILE|METHOD|LINE)/);
});

test('reviewed callers do not reuse summaries from stale callees', () => {
  const { root, source, parsed } = methodFixture();
  const before = collect(root, parsed).files.find(file => file.name === 'Caller.java');
  assert.match(before.rows[2].description, /OLD_METHOD_PURPOSE/);
  fs.writeFileSync(path.join(root, samplePath), source.replace('return 1', 'return 2'));
  const caller = collect(root, parsed).files.find(file => file.name === 'Caller.java');
  assert.equal(caller.reviewed, true);
  assert.doesNotMatch(caller.rows[2].description, /OLD_METHOD_PURPOSE/);
  assert.ok(!caller.rows[2].links.some(link => link.file === samplePath));
});

test('stale callers do not reuse their own summaries or other project summaries', () => {
  const { root, parsed } = methodFixture();
  const file = path.join(root, 'src/main/java/Caller.java');
  fs.writeFileSync(file, fs.readFileSync(file, 'utf8').replace('return Fixture.helper();', 'return Fixture.helper() + 1;'));
  const caller = collect(root, parsed).files.find(file => file.name === 'Caller.java');
  assert.equal(caller.reviewed, false);
  assert.doesNotMatch(caller.rows[2].description, /OLD_METHOD_PURPOSE/);
});

test('changed method notes stay hidden until explicitly reviewed', () => {
  const { root, parsed } = methodFixture();
  amendNotes(root, document => { document.files[samplePath].methods.helper.purpose = 'NEW_REVIEWED_METHOD_PURPOSE'; });
  const pending = build(root, { javaIndex: parsed });
  assert.doesNotMatch(pending.html, /OLD_METHOD_PURPOSE|NEW_REVIEWED_METHOD_PURPOSE/);
  acceptReview(root, [samplePath]);
  const approved = collect(root, parsed);
  assert.ok(approved.files.every(file => file.reviewed));
  assert.match(approved.files.find(file => file.name === 'Caller.java').rows[2].description, /NEW_REVIEWED_METHOD_PURPOSE/);
});

test('freshness CLI rejects changed method explanations without overwriting HTML', () => {
  const { root, parsed } = methodFixture();
  const output = path.join(root, OUTPUT), html = build(root, { javaIndex: parsed }).html;
  fs.writeFileSync(output, html);
  amendNotes(root, document => { document.files[samplePath].methods.helper.purpose = 'UNREVIEWED_METHOD_PURPOSE'; });
  const result = spawnSync(process.execPath, [path.join(TOOL_ROOT, 'scripts/build-code-explorer.cjs'), '--root', root, '--check'],
    { encoding: 'utf8', windowsHide: true });
  assert.equal(result.status, 1);
  assert.match(result.stdout, /review is required/);
  assert.equal(JSON.parse(result.stdout).staleFiles, 1);
  assert.equal(fs.readFileSync(output, 'utf8'), html);
});

test('a stale same-named method cannot make a different method appear unique', () => {
  const { root, source, parsed } = methodFixture();
  fs.writeFileSync(path.join(root, 'src/main/java/Other.java'), 'class Other {\n    int helper() { return 9; }\n}\n');
  amendNotes(root, document => { document.files['src/main/java/Other.java'] = { purpose: '별도 도우미', role: 'Fixture', lines: {},
    methods: { helper: { title: '별도 도우미', purpose: 'UNRELATED_METHOD_PURPOSE' } } }; });
  acceptReview(root, ['src/main/java/Other.java']);
  fs.writeFileSync(path.join(root, samplePath), source.replace('return 1', 'return 2'));
  const caller = collect(root, parsed).files.find(file => file.name === 'Caller.java');
  assert.doesNotMatch(caller.rows[2].description, /OLD_METHOD_PURPOSE|UNRELATED_METHOD_PURPOSE/);
});

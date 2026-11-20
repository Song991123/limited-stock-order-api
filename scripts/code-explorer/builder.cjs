'use strict';

/** 원본과 해설을 대조해 정적 HTML 구성. */
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { spawnSync } = require('node:child_process');
const content = require('./content.cjs');
const { explain, highlight, prepareMethods } = require('./explanations.cjs');

const TOOL_ROOT = path.resolve(__dirname, '../..');
const OUTPUT = 'docs/code-explorer/index.html';
const LOCK = 'docs/code-explorer/reviewed-sources.json';
const normalize = text => text.replace(/^\uFEFF/, '').replace(/\r\n/g, '\n');
const digest = text => crypto.createHash('sha256').update(text).digest('hex');
const read = file => normalize(fs.readFileSync(file, 'utf8'));
const escape = text => String(text).replace(/[&<>"']/g, item => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[item]));

function safePath(root, relative) {
  const absolute = path.resolve(root, relative);
  if (!absolute.startsWith(path.resolve(root) + path.sep)) throw new Error(`Outside repository: ${relative}`);
  if (fs.existsSync(absolute) && fs.realpathSync(absolute) !== absolute) {
    const resolved = fs.realpathSync(absolute);
    if (!resolved.startsWith(fs.realpathSync(root) + path.sep)) throw new Error(`External symlink: ${relative}`);
  }
  return absolute;
}

function sourceFiles(root) {
  const result = [];
  function visit(relative, recursive) {
    const absolute = safePath(root, relative);
    if (!fs.existsSync(absolute)) return;
    for (const entry of fs.readdirSync(absolute, { withFileTypes: true })) {
      const name = `${relative}/${entry.name}`;
      if (entry.isSymbolicLink()) throw new Error(`Source symlink is not exported: ${name}`);
      if (entry.isDirectory()) { if (recursive) visit(name, recursive); }
      else if (/\.(java|sql|ya?ml|properties|json|xml|ps1|cjs|js)$/.test(name)
          && !/^scripts\/(?:build-code-explorer|check-code-explorer)\./.test(name)) result.push(name);
    }
  }
  visit('src', true);
  visit('scripts', false);
  visit('scripts/demo', true);
  visit('.github/workflows', false);
  for (const name of ['build.gradle', 'settings.gradle', 'gradle.properties', 'gradle/wrapper/gradle-wrapper.properties']) {
    if (fs.existsSync(safePath(root, name))) result.push(name);
  }
  return result.sort((a, b) => a.localeCompare(b, 'en'));
}

function loadNotes(root) {
  const dir = safePath(root, 'docs/code-explorer/notes');
  const notes = {}, issues = [];
  for (const name of fs.readdirSync(dir).filter(name => name.endsWith('.json')).sort()) {
    const document = JSON.parse(read(path.join(dir, name)));
    if (!document.files || typeof document.files !== 'object') throw new Error(`${name}: files object is required`);
    for (const [file, entry] of Object.entries(document.files)) {
      if (notes[file]) throw new Error(`Duplicate notes: ${file}`);
      if (!entry.purpose || !entry.role) issues.push(`${file}: purpose/role is missing`);
      for (const id of entry.technologies || []) if (!content.technologies[id]) issues.push(`${file}: unknown technology ${id}`);
      notes[file] = entry;
    }
  }
  return { notes, issues };
}

/** 애플리케이션 실행 대신 JDK 구문 분석으로 Java 선언과 호출 위치 수집. */
function javaIndex(root) {
  const result = spawnSync(process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java',
    [path.join(TOOL_ROOT, 'scripts/code-explorer/SourceIndex.java'), root],
    { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024, windowsHide: true });
  if (result.status !== 0) throw new Error(`Java source parsing failed. JDK 21 is required.\n${result.stderr || result.error}`);
  return JSON.parse(result.stdout);
}

function loadEvidence(root) {
  const record = name => {
    const file = safePath(root, 'evidence/verification/' + name);
    return fs.existsSync(file) ? JSON.parse(read(file)) : null;
  };
  const regular = record('latest.json');
  const review = record('authentication-structure-review.json');
  return {
    recordedRun: regular?.testedOn || null,
    regular: regular ? { tests: regular.tests, failures: regular.failures, errors: regular.errors, skipped: regular.skipped } : null,
    suites: regular?.suites || [], separateRecovery: review?.databaseRecovery?.summary || null,
    architecture: review?.architecture?.tests ?? null
  };
}

/** 원문과 해설의 해시를 대조하고 검토된 해설만 표시 자료에 포함. */
function collect(root, indexOverride) {
  const paths = sourceFiles(root);
  const { notes, issues } = loadNotes(root);
  const lockPath = safePath(root, LOCK);
  const lock = fs.existsSync(lockPath) ? JSON.parse(read(lockPath)) : { schemaVersion: 1, files: {} };
  const parsed = indexOverride || javaIndex(root);
  const files = [];
  const sources = paths.map(relative => {
    const text = read(safePath(root, relative));
    const note = notes[relative] || { purpose: '파일 해설 검토 필요', role: '미분류', methods: {}, lines: {}, technologies: [] };
    const sourceHash = digest(text), noteHash = digest(JSON.stringify(note));
    const reviewed = lock.files[relative]?.sourceHash === sourceHash && lock.files[relative]?.noteHash === noteHash;
    return { relative, text, note, sourceHash, noteHash, reviewed };
  });
  // 갱신 필요 파일의 메서드 이름도 남겨 동명 메서드의 잘못된 연결 방지.
  const allMethods = new Map();
  for (const { relative, note, reviewed } of sources) {
    const names = new Set([...Object.keys(note.methods || {}),
      ...(parsed[relative]?.symbols || []).filter(item => item.kind === 'method').map(item => item.name)]);
    for (const method of names) {
      if (!allMethods.has(method)) allMethods.set(method, []);
      const details = note.methods?.[method];
      allMethods.get(method).push({ file: relative, reviewed: reviewed && Boolean(details?.purpose), ...(reviewed ? details || {} : {}) });
    }
  }
  for (const { relative, text, note, sourceHash, noteHash, reviewed } of sources) {
    if (!notes[relative]) issues.push(`${relative}: file explanation is missing`);
    if (!reviewed) issues.push(`${relative}: source or explanation changed; review is required`);
    const sourceLines = text.split('\n');
    for (const key of Object.keys(note.lines || {})) {
      const line = Number(key);
      if (!Number.isInteger(line) || line < 1 || line > sourceLines.length) issues.push(`${relative}: invalid explanation line ${key}`);
      if (typeof note.lines[key] !== 'string') issues.push(`${relative}:${key}: line explanation must be text`);
    }
    const syntax = parsed[relative] || { symbols: [], events: [], packageName: '' };
    // 원문이나 해설 해시가 다르면 이전 파일 해설과 메서드 요약 제외.
    const visibleNote = reviewed ? note : { purpose: '파일 해설 검토 필요', role: '검토 필요', methods: {}, lines: {}, technologies: [], decisions: [] };
    const methods = prepareMethods(syntax, visibleNote);
    const rows = sourceLines.map((code, offset) => {
      const line = offset + 1;
      const context = { path: relative, line, code, syntax, note: visibleNote, reviewed, allMethods, methods };
      const explanation = explain(context);
      return { line, code, html: highlight(context), ...explanation,
        annotations: [...new Set(syntax.events.filter(item => item.kind === 'annotation' && item.line === line).map(item => item.name.split('.').pop()))] };
    });
    const missing = rows.filter(row => row.kind === 'missing');
    if (missing.length && relative.startsWith('src/main/')) issues.push(`${relative}: ${missing.length} lines need specific explanations (${missing.slice(0, 12).map(row => row.line).join(',')})`);
    files.push({ path: relative, name: path.basename(relative), language: language(relative), text, sourceHash, noteHash,
      reviewed, note: visibleNote, methods, rows, annotations: [...new Set(syntax.events.filter(item => item.kind === 'annotation').map(item => item.name.split('.').pop()))],
      explainedLines: rows.filter(row => row.kind === 'specific').length, manualLines: Object.keys(note.lines || {}).length, missingLines: missing.length });
  }
  for (const relative of Object.keys(notes)) if (!paths.includes(relative)) issues.push(`${relative}: notes reference a removed or excluded file`);
  for (const relative of Object.keys(lock.files)) if (!paths.includes(relative)) issues.push(`${relative}: review lock references a removed file`);
  return { files, issues, lock, notes, parsed };
}

function language(file) {
  if (file.endsWith('.java')) return 'Java';
  if (file.endsWith('.sql')) return 'SQL';
  if (/\.ya?ml$/.test(file)) return 'YAML';
  if (file.endsWith('.ps1')) return 'PowerShell';
  if (/\.[cm]?js$/.test(file)) return 'JavaScript';
  if (file.endsWith('.gradle')) return 'Gradle';
  return 'Properties';
}

function asset(root, relative) {
  const file = safePath(root, relative);
  return fs.existsSync(file) ? 'data:image/png;base64,' + fs.readFileSync(file).toString('base64') : '';
}

/** 원문, 해설과 그림을 외부 통신 없는 단일 HTML로 구성. */
function build(root = TOOL_ROOT, options = {}) {
  const snapshot = collect(root, options.javaIndex);
  const data = {
    schemaVersion: 1, project: content.project, flows: content.flows, execution: content.execution, technologies: content.technologies, annotations: content.annotations,
    files: snapshot.files, evidence: loadEvidence(root),
    figures: ['01-actors', '02-last-stock', '03-request-retry', '04-cancellation-state', '05-http-and-storage'].map((id, i) => ({
      id, title: ['고객과 요청', '마지막 재고', '같은 구매의 재전송', '취소 상태와 복구', '코드 구성과 인증'][i],
      image: asset(root, `docs/design/diagrams/${id}.png`)
    })).filter(item => item.image),
    sourceSetHash: digest(snapshot.files.map(file => file.path + ':' + file.sourceHash + ':' + file.noteHash).join('\n')),
    summary: { files: snapshot.files.length, lines: snapshot.files.reduce((sum, file) => sum + file.rows.length, 0),
      specific: snapshot.files.reduce((sum, file) => sum + file.explainedLines, 0), manual: snapshot.files.reduce((sum, file) => sum + file.manualLines, 0), stale: snapshot.files.filter(file => !file.reviewed).length },
    icons: JSON.parse(read(path.join(TOOL_ROOT, 'docs/code-explorer/vendor/lucide-icons.json'))).icons
  };
  // 원문이 script 태그를 닫거나 HTML로 해석되지 않도록 JSON 문자 이스케이프.
  const safeJson = JSON.stringify(data).replace(/</g, '\\u003c').replace(/&/g, '\\u0026').replace(/\u2028/g, '\\u2028').replace(/\u2029/g, '\\u2029');
  const replacements = {
    '/* CODE_EXPLORER_STYLE */': read(path.join(TOOL_ROOT, 'docs/code-explorer/source/explorer.css')),
    '__CODE_EXPLORER_DATA__': safeJson,
    '/* CODE_EXPLORER_APP */': read(path.join(TOOL_ROOT, 'docs/code-explorer/source/explorer.js'))
  };
  let html = read(path.join(TOOL_ROOT, 'docs/code-explorer/source/template.html'));
  for (const [marker, replacement] of Object.entries(replacements)) {
    if (html.split(marker).length !== 2) throw new Error(`Template marker is missing or repeated: ${marker}`);
    html = html.replace(marker, () => replacement);
  }
  return { ...snapshot, data, html, output: safePath(root, OUTPUT) };
}

/**
 * 원본과 해설을 사람이 대조한 뒤 완료 표시로 현재 해시 저장.
 * 문장 정확성이나 서비스 동작의 자동 검증은 아님.
 */
function acceptReview(root = TOOL_ROOT, selected = []) {
  const paths = sourceFiles(root);
  const { notes, issues } = loadNotes(root);
  if (issues.length) throw new Error(issues.join('\n'));
  const target = safePath(root, LOCK);
  const lock = fs.existsSync(target) ? JSON.parse(read(target)) : { schemaVersion: 1, files: {} };
  const choices = selected.length ? selected : paths;
  for (const name of choices) {
    if (!paths.includes(name) || !notes[name]) throw new Error(`Review target needs source and notes: ${name}`);
    lock.files[name] = { sourceHash: digest(read(safePath(root, name))), noteHash: digest(JSON.stringify(notes[name])) };
  }
  for (const name of Object.keys(lock.files)) if (!paths.includes(name)) delete lock.files[name];
  lock.files = Object.fromEntries(Object.entries(lock.files).sort(([a], [b]) => a.localeCompare(b, 'en')));
  fs.writeFileSync(target, JSON.stringify(lock, null, 2) + '\n');
  return choices.length;
}

module.exports = { build, collect, acceptReview, sourceFiles, digest, normalize, escape, safePath, OUTPUT, LOCK, TOOL_ROOT };

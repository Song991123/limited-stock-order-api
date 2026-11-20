'use strict';

/** 실제 시연 JSON을 요청, 응답과 재고 표로 표시. 주문 서버나 DB는 실행하지 않음. */
const fs = require('node:fs');
const path = require('node:path');
const escape = value => String(value ?? '').replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[char]));
const json = value => typeof value === 'string' ? (() => { try { return JSON.stringify(JSON.parse(value), null, 2); } catch { return value; } })() : JSON.stringify(value, null, 2);

/** 기록된 조건이 전부 참이고 실패가 없어야 완료로 표시. 원시 응답 자체의 정확성을 증명하지는 않음. */
function isPassed(trace) {
  return trace.passed === true && !trace.failure && trace.steps.length > 0 && trace.inventory.length > 0
    && trace.checks.length > 0 && trace.checks.every(check => check.passed === true);
}

function renderReport(trace, rootHref = '../../../') {
  if (trace.schemaVersion !== 1 || !Array.isArray(trace.steps) || !Array.isArray(trace.inventory) || !Array.isArray(trace.checks)) throw new Error('Invalid demo trace');
  if (!/^(?:\.\.\/)*$/.test(rootHref)) throw new Error('Only relative project links are allowed');
  const passed = isPassed(trace);
  const inventory = trace.inventory.map(item => `<th scope="col">${escape(item.stage)}</th>`).join('');
  const quantities = trace.inventory.map(item => `<td>${escape(item.quantity)}</td>`).join('');
  const checks = trace.checks.map(check => `<tr><th scope="row">${escape(check.name)}</th><td>${escape(check.expected)}</td><td>${escape(check.actual)}</td><td class="${check.passed ? 'pass' : 'fail'}">${check.passed ? '일치' : '불일치'}</td></tr>`).join('');
  const steps = trace.steps.map(step => `<article class="step" id="step-${Number(step.number)}">
    <header><span class="number">${escape(step.number)}</span><h2>${escape(step.title)}</h2><span class="http">${step.response.status == null ? '응답 미수신' : 'HTTP ' + escape(step.response.status)}</span></header>
    <div class="exchange"><section><h3>요청</h3><p class="request-line">${escape(step.request.method)} ${escape(step.request.path)}</p>
      ${Object.keys(step.request.headers || {}).length ? `<pre>${escape(json(step.request.headers))}</pre>` : ''}
      <pre>${escape(step.request.body ? json(step.request.body) : '본문 없음')}</pre></section>
    <section><h3>응답</h3>${Object.keys(step.response.headers || {}).length ? `<pre>${escape(json(step.response.headers))}</pre>` : ''}${step.response.tokenHidden ? '<p class="note">발급된 CSRF 토큰 값은 기록에서 제외.</p>' : ''}<pre>${escape(json(step.response.body))}</pre></section></div></article>`).join('');
  return `<!doctype html>
<html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'">
<title>주문과 취소 | 단계별 실행 결과</title><style>
:root{font-family:"Malgun Gothic","Segoe UI",sans-serif;color:#25313a;background:#fff;letter-spacing:0}*{box-sizing:border-box;letter-spacing:0}body{margin:0}a{color:#087b65;text-underline-offset:3px}a:focus-visible,summary:focus-visible{outline:2px solid #087b65;outline-offset:3px}.topbar{border-bottom:1px solid #dfe5e8;padding:16px 24px;display:flex;flex-wrap:wrap;justify-content:space-between;gap:12px;font-size:13px}.topbar nav{display:flex;gap:18px;flex-wrap:wrap}main{max-width:1180px;padding:30px 28px 70px;margin:auto}h1{font-size:24px;line-height:1.6;margin:0 0 8px}h2{font-size:17px;line-height:1.7}h3{font-size:12px;color:#61717b;margin:0 0 10px}p{font-size:13px;line-height:1.9;overflow-wrap:anywhere}.meta{color:#6d7d87;font-size:12px}.status{font-size:13px;color:#087b65;padding:9px 0}.fail{color:#a02b39}.pass{color:#087b65}.error{border-left:3px solid #b92f42;padding:9px 15px;background:#fff4f5}.table-wrap{overflow-x:auto;max-width:100%}table{width:100%;border-collapse:collapse;margin:15px 0;table-layout:fixed}td,th{padding:12px;text-align:left;border-bottom:1px solid #dfe5e8;font-size:12px;line-height:1.8;overflow-wrap:anywhere}th{background:#f5f8fa;font-weight:500}.inventory td{font-size:20px}.step{padding:24px 0;border-top:1px solid #dfe5e8}.step>header{display:flex;align-items:baseline;gap:12px}.step h2{margin:0;flex:1}.number{font-size:12px;color:#6c7b84;width:20px;flex:none}.http{font-family:Consolas,monospace;font-size:12px;color:#52636f;white-space:nowrap}.exchange{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:28px;padding:18px 0 0 32px}.exchange section{min-width:0}pre{font-family:Consolas,"Malgun Gothic",monospace;font-size:12px;line-height:1.85;background:#f6f8fa;padding:14px;margin:8px 0;white-space:pre-wrap;overflow-wrap:anywhere;border-radius:4px;tab-size:2}.request-line{font-family:Consolas,monospace;font-size:12px;overflow-wrap:anywhere}.note{font-size:11px;color:#7a5d29}.checks{margin-top:20px;border-top:1px solid #dfe5e8;padding-top:15px}summary{cursor:pointer;font-size:14px;padding:12px 0}.hash{font-family:Consolas,monospace;font-size:11px}footer{border-top:1px solid #dfe5e8;margin-top:25px;padding-top:15px;color:#6d7d87}@media(max-width:700px){main{padding:22px 16px 50px}.topbar{padding:13px 16px}h1{font-size:21px}.exchange{grid-template-columns:minmax(0,1fr);gap:15px;padding-left:0}.step>header{gap:8px}.step h2{font-size:14px}.http{font-size:10px}td,th{padding:8px 6px;font-size:11px}.inventory td{font-size:17px}.inventory th{font-size:10px}pre{font-size:11px}.number{width:15px}}@media print{.topbar{display:none}main{max-width:none;padding:0}.step{break-inside:avoid}pre{background:#fff;border:1px solid #ddd}}
</style></head><body><header class="topbar"><span>한정 재고 상품 주문 접수 서버</span><nav><a href="${rootHref}docs/code-explorer/index.html#view=execution">실행 방법과 코드 해설</a><a href="${rootHref}build/reports/tests/test/index.html">전체 검사 보고서</a></nav></header><main>
<h1>주문과 취소의 실제 실행 결과</h1><p class="meta">시작 ${escape(trace.startedAt)}<br>종료 ${escape(trace.finishedAt)}<br>서버 ${escape(trace.baseUrl)}</p>
<p class="status ${passed ? 'pass' : 'fail'}">${passed ? `시연 완료 / ${trace.checks.length}개 조건 일치` : '시연 미완료 / 아래 실패 조건과 마지막 응답 확인'}</p>
${trace.failure ? `<p class="error">${escape(trace.failure)}</p>` : ''}
<h2>단계별 재고</h2><div class="table-wrap"><table class="inventory"><thead><tr>${inventory}</tr></thead><tbody><tr>${quantities}</tr></tbody></table></div>
<p>${escape(trace.scope)}</p><p class="meta">201은 새 주문 접수, 200은 기존 결과 또는 취소 결과 반환. 인증 비밀번호, 세션 쿠키와 CSRF 토큰 값은 기록하지 않음. 다른 주문이 동시에 들어오면 재고 비교 결과가 달라질 수 있음.</p>
<div class="steps">${steps}</div><details class="checks" open><summary>기대값과 실제 값 비교</summary><div class="table-wrap"><table><thead><tr><th scope="col">확인 조건</th><th scope="col">기대값</th><th scope="col">실제 값</th><th scope="col">판정</th></tr></thead><tbody>${checks}</tbody></table></div></details>
<footer><p class="meta">이 시연의 순차 요청과 전체 JUnit 검사는 별도 실행. DB의 주문 및 이력 대조, 동시 요청과 장애 복구는 전체 검사 보고서의 해당 조건에서 확인.</p>${trace.jarSha256 ? `<p class="hash">사용한 실행 파일 SHA-256<br>${escape(trace.jarSha256)}</p>` : ''}</footer></main></body></html>`;
}

if (require.main === module) {
  const [input, ...outputs] = process.argv.slice(2);
  if (!input || !outputs.length) throw new Error('Usage: node scripts/demo/report.cjs trace.json report.html [latest.html]');
  const trace = JSON.parse(fs.readFileSync(input, 'utf8').replace(/^\uFEFF/, ''));
  const root = path.resolve(__dirname, '../..');
  for (const output of outputs) {
    const absolute = path.resolve(output);
    const relative = path.relative(root, absolute);
    if (relative.startsWith('..') || path.isAbsolute(relative)) throw new Error('Report output must stay inside the project');
    const rootHref = path.relative(path.dirname(absolute), root).replace(/\\/g, '/') + '/';
    fs.mkdirSync(path.dirname(absolute), { recursive: true });
    fs.writeFileSync(absolute, renderReport(trace, rootHref));
  }
}

module.exports = { renderReport, isPassed };

'use strict';

/** 합성 기록으로 HTML 표시와 실패 판정 검사. 실제 HTTP/DB 검사는 별도 실행. */
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { renderReport, isPassed } = require('./report.cjs');

const fixture = () => ({
  schemaVersion: 1, startedAt: '2026-11-20T12:00:00+09:00', finishedAt: '2026-11-20T12:00:01+09:00',
  baseUrl: 'http://127.0.0.1:18086', passed: true, failure: null, scope: '합성 표시 검사', jarSha256: 'a'.repeat(64),
  inventory: [{ stage: '주문 전', quantity: 10 }, { stage: '주문 후', quantity: 9 }, { stage: '재취소 후', quantity: 10 }],
  steps: [{ number: 1, title: '같은 구매 재요청', request: { method: 'POST', path: '/orders', headers: { 'Idempotency-Key': 'demo-key' }, body: '{"productId":1,"quantity":1}' }, response: { status: 200, body: '{"id":7,"status":"ACCEPTED"}' } }],
  checks: [{ name: '주문 ID 유지', expected: 7, actual: 7, passed: true }]
});

test('actual request and response fields are displayed with inventory and expectation values', () => {
  const html = renderReport(fixture());
  for (const value of ['POST /orders', 'Idempotency-Key', 'demo-key', 'productId', 'HTTP 200', 'ACCEPTED', '주문 전', '재취소 후', '주문 ID 유지', '시연 완료 / 1개 조건 일치']) assert.ok(html.includes(value), value);
});

test('failed or incomplete checks cannot be displayed as a successful demo', () => {
  for (const patch of [{ passed: false }, { failure: '실패' }, { checks: [] }, { steps: [] }, { inventory: [] }, { checks: [{ name: '실패', expected: 1, actual: 2, passed: false }] }]) {
    const trace = { ...fixture(), ...patch };
    assert.equal(isPassed(trace), false);
    assert.ok(renderReport(trace).includes('시연 미완료'));
    assert.ok(!renderReport(trace).includes('시연 완료 /'));
  }
});

test('request, response and error strings are escaped rather than interpreted as HTML', () => {
  const trace = fixture();
  trace.failure = '<img src=x onerror=alert(1)>';
  trace.steps[0].request.body = '</pre><script>alert(2)</script>';
  trace.steps[0].response.body = '<svg onload=alert(3)>';
  const html = renderReport(trace);
  assert.ok(!html.includes('<script>'));
  assert.ok(!html.includes('<img src=x'));
  assert.ok(!html.includes('<svg onload='));
  assert.ok(html.includes('&lt;script&gt;'));
});

test('masked CSRF response is identified and report itself has no executable scripts or remote assets', () => {
  const trace = fixture();
  trace.steps[0].response = { status: 200, body: '{"token":"[비공개]"}', tokenHidden: true };
  const html = renderReport(trace);
  assert.ok(html.includes('발급된 CSRF 토큰 값은 기록에서 제외'));
  assert.ok(!/<script\b|<iframe\b|<img\b|\son\w+=/.test(html));
  assert.ok(html.includes("default-src 'none'"));
});

test('project links work at the latest report and per-run report depths', () => {
  for (const root of ['../../../', '../../../../']) {
    const html = renderReport(fixture(), root);
    assert.ok(html.includes(`href="${root}docs/code-explorer/index.html#view=execution"`));
    assert.ok(html.includes(`href="${root}build/reports/tests/test/index.html"`));
  }
  assert.throws(() => renderReport(fixture(), 'javascript:alert(1)'), /relative/);
  assert.throws(() => renderReport(fixture(), 'https://example.invalid/'), /relative/);
});

test('missing trace collections fail instead of rendering placeholder success', () => {
  assert.throws(() => renderReport({ schemaVersion: 1 }), /Invalid demo trace/);
});

test('an unanswered request retains its purchase key and is not shown as HTTP success', () => {
  const trace = fixture();
  trace.passed = false;
  trace.failure = '응답 미수신';
  trace.steps[0].response = { status: null, body: '확정 여부를 아직 알 수 없습니다.' };
  const html = renderReport(trace);
  assert.ok(html.includes('demo-key'));
  assert.ok(html.includes('응답 미수신'));
  assert.ok(!html.includes('HTTP 200'));
  assert.ok(html.includes('시연 미완료'));
});

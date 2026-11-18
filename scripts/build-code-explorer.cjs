#!/usr/bin/env node
'use strict';

/** 원본과 해설의 대조 완료 표시, HTML 생성과 갱신 누락 검사 명령 처리. */
const fs = require('node:fs');
const path = require('node:path');
const { build, acceptReview, normalize, TOOL_ROOT } = require('./code-explorer/builder.cjs');

/** 명령 인자로 검토 표시, HTML 생성 또는 파일을 저장하지 않는 검사 선택. */
function run() {
  const args = process.argv.slice(2);
  const rootIndex = args.indexOf('--root');
  const root = rootIndex >= 0 ? path.resolve(args[rootIndex + 1]) : TOOL_ROOT;
  if (rootIndex >= 0) args.splice(rootIndex, 2);
  // --accept-reviewed는 사람의 대조 완료 표시이며 해설의 정확성 자동 검사는 아님.
  if (args.includes('--accept-reviewed')) {
    const selected = args.filter(arg => arg !== '--accept-reviewed' && !arg.startsWith('--'));
    console.log(`Reviewed source references updated: ${acceptReview(root, selected)}`);
  }
  const result = build(root);
  // 검사 모드에서는 생성 예정 내용만 비교하고 기존 HTML은 보존.
  if (args.includes('--check')) {
    const exists = fs.existsSync(result.output);
    if (!exists || normalize(fs.readFileSync(result.output, 'utf8')) !== normalize(result.html)) result.issues.push('Generated HTML differs from current sources or notes; run node scripts/build-code-explorer.cjs');
    console.log(JSON.stringify({ files: result.files.length, sourceLines: result.data.summary.lines,
      manualLineNotes: result.data.summary.manual, staleFiles: result.data.summary.stale,
      issues: result.issues }, null, 2));
    if (result.issues.length) process.exitCode = 1;
    return;
  }
  fs.mkdirSync(path.dirname(result.output), { recursive: true });
  fs.writeFileSync(result.output, result.html);
  console.log(JSON.stringify({ output: result.output, files: result.files.length,
    sourceLines: result.data.summary.lines, manualLineNotes: result.data.summary.manual,
    staleFiles: result.data.summary.stale, issues: result.issues }, null, 2));
  if (result.issues.length) process.exitCode = 1;
}

try { run(); } catch (error) { console.error(error.message); process.exitCode = 1; }

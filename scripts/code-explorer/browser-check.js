/** 로컬 HTML의 원문, 검색과 취소 안내 표시 검사. 주문 API와 DB 실행 결과는 검사 대상에서 제외. */
async page => {
  const passed = [];
  const failures = [];
  const requests = [];
  const errors = [];
  const check = (name, condition) => {
    if (!condition) { failures.push(name); throw new Error(name); }
    passed.push(name);
  };
  page.on('pageerror', error => errors.push(error.message));
  page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
  page.on('request', request => { if (/^https?:/.test(request.url())) requests.push(request.url()); });
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.reload();
  await page.waitForFunction(() => Boolean(window.codeExplorer));
  const info = await page.evaluate(() => ({ summary: window.codeExplorer.stats, paths: window.codeExplorer.data.files.map(file => file.path), evidence: window.codeExplorer.data.evidence }));
  check('all source explanations reviewed', info.summary.stale === 0);
  check('no startup error', await page.locator('#app-error').isHidden());

  const sourceEquality = await page.evaluate(() => {
    const mismatches = [];
    for (const file of window.codeExplorer.data.files) {
      window.codeExplorer.selectFile(file.path);
      const text = Array.from(document.querySelectorAll('[data-source-line]'), node => node.textContent).join('\n');
      if (text !== file.text) mismatches.push(file.path);
    }
    return mismatches;
  });
  check('every rendered source preserves original text', sourceEquality.length === 0);

  await page.locator('#collapse-tree').click();
  check('folder collapse', await page.locator('#tree .active').count() === 0);
  await page.locator('#expand-tree').click();
  check('actual source tree contains all files', await page.locator('#tree .file-symbol').count() === info.summary.files);
  await page.locator('#tree button[title$="/service/OrderService.java"]').click();
  check('tree selects source', (await page.locator('#file-name').innerText()) === 'OrderService.java');
  const methodLine = await page.evaluate(() => window.codeExplorer.data.files.find(file => file.name === 'OrderService.java').methods.find(method => method.name === 'placeOrder').line);
  await page.locator('#method-select').selectOption(String(methodLine));
  check('method jump selects its real line', (await page.evaluate(() => window.codeExplorer.current().line)) === methodLine);
  await page.locator('.line-links button').filter({ hasText: '@Transactional 선택 이유' }).first().click();
  check('annotation opens exact explanation', (await page.locator('#technology-title').innerText()) === '@Transactional');
  check('annotation includes effect and alternatives', (await page.locator('#technology-body').innerText()).includes('다른 방법'));
  await page.keyboard.press('Escape');
  check('annotation dialog closes', !(await page.locator('#technology-dialog').evaluate(node => node.open)));
  await page.locator('#show-comments').uncheck();
  check('comment toggle hides comment rows', await page.locator('.code-row.comment:visible').count() === 0);
  await page.locator('#show-comments').check();
  // 실제 클립보드를 바꾸지 않고 복사 버튼이 전달하는 원문만 대조.
  await page.evaluate(() => {
    window.clipboardDescriptor = Object.getOwnPropertyDescriptor(navigator, 'clipboard');
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: async text => { window.copiedSource = text; } } });
  });
  await page.locator('#copy-code').click();
  check('copy passes exact source to clipboard adapter', await page.evaluate(() => window.copiedSource === window.codeExplorer.data.files.find(file => file.name === 'OrderService.java').text));
  await page.evaluate(() => { if (window.clipboardDescriptor) Object.defineProperty(navigator, 'clipboard', window.clipboardDescriptor); else delete navigator.clipboard; });
  await page.locator('#tree .active').focus();
  await page.keyboard.press('ArrowUp');
  check('keyboard moves through source tree', await page.evaluate(() => document.activeElement?.getAttribute('role') === 'treeitem' && document.activeElement.getAttribute('aria-selected') !== 'true'));
  await page.locator('#tab-code').focus();
  await page.keyboard.press('ArrowRight');
  check('keyboard switches document tabs', await page.locator('#view-project').isVisible());
  await page.keyboard.press('ArrowLeft');

  for (const query of ['주문취소', '@Transactional', 'Idempotency-Key', 'DTO', '재고']) {
    await page.locator('#search').fill(query);
    check('instant search: ' + query, await page.locator('#search-results .search-result').count() > 0);
  }
  await page.locator('#search').fill('zzzz_no_such_source_98765');
  check('empty search state', await page.locator('.empty-results').isVisible());
  await page.locator('#clear-search').click();
  check('search reset restores tree', await page.locator('#tree').isVisible());

  const downloadEvent = page.waitForEvent('download');
  await page.locator('#download-code').click();
  const download = await downloadEvent;
  check('source download uses real filename', download.suggestedFilename() === 'OrderService.java');
  check('source download succeeds', !(await download.failure()));

  await page.locator('[data-view="project"]').click();
  check('program purpose explains customer and stock', (await page.locator('#view-project').innerText()).includes('고객'));
  await page.getByRole('button', { name: '고객과 요청 그림 크게 보기', exact: true }).click();
  check('actor diagram opens full-size view', await page.locator('#project-figure img').isVisible());
  for (const title of ['주문 접수', '같은 구매 재요청', '주문 취소', '로그인 정보 확인']) {
    await page.getByRole('button', { name: title, exact: true }).click();
    check('request flow: ' + title, await page.locator('.flow-step').count() >= 3);
    if (title === '주문 취소') {
      check('first and repeated cancellation are separate branches', await page.locator('.flow-branch').count() === 2);
      check('repeated cancellation does not restore stock again', (await page.locator('[data-branch="again"]').innerText()).includes('다시 실행하지 않습니다'));
      check('cancellation failure and ownership conditions remain', (await page.locator('.flow-note').innerText()).includes('다른 고객'));
    }
  }
  for (const title of ['마지막 재고', '같은 구매의 재전송', '취소 상태와 복구', '코드 구성과 인증']) {
    await page.locator('.diagram-list').getByRole('button', { name: title, exact: true }).click();
    await page.waitForFunction(() => Array.from(document.querySelectorAll('#view-project img')).every(img => img.complete && img.naturalWidth > 0));
    check('embedded diagram: ' + title, await page.locator('#project-figure img').isVisible());
  }
  await page.locator('.flow-step .code-link').first().click();
  check('flow links return to source code', await page.locator('#view-code').isVisible());
  await page.goBack();
  check('browser back restores previous view', await page.locator('#view-project').isVisible());

  await page.locator('[data-view="technology"]').click();
  await page.locator('.technology-menu').getByRole('button', { name: '같은 구매의 재요청과 멱등성', exact: true }).click();
  check('technology compares alternatives and limitations', (await page.locator('.technology-detail').innerText()).includes('비용과 보장 범위'));
  await page.locator('[data-view="verification"]').click();
  const verificationText = await page.locator('#view-verification').innerText();
  check('recorded regular tests and separate recovery are not combined', verificationText.includes(`${info.evidence.regular.tests}건`) && verificationText.includes('15건') && verificationText.includes('정규 검사와 별도'));
  check('displayed test groups match recorded suites', verificationText.includes(`HTTP/DB ${info.evidence.groups.httpDatabase}건`) && verificationText.includes(`단위/구조 ${info.evidence.groups.unitArchitecture}건`));
  check('database server recovery remains an archived scope', verificationText.includes('과거 DB 서버 중단'));

  await page.locator('[data-view="execution"]').click();
  check('execution guide has the four real commands', await page.locator('#view-execution .command-row').count() === 4);
  check('execution guide distinguishes HTTP demo and DB test coverage', (await page.locator('#view-execution').innerText()).includes('DB 대조는 전체 검사 보고서'));
  check('JAR link points to the generated executable', (await page.locator('#server-jar').getAttribute('href')) === '../../build/libs/limited-stock-order-api-0.1.0.jar');
  await page.evaluate(() => {
    window.commandClipboardDescriptor = Object.getOwnPropertyDescriptor(navigator, 'clipboard');
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: async text => { window.copiedCommand = text; } } });
  });
  await page.getByRole('button', { name: '명령 복사: 전체 검사와 실행 파일 생성', exact: true }).click();
  check('copy command preserves the actual PowerShell invocation', await page.evaluate(() => window.copiedCommand === window.codeExplorer.data.execution.steps[0].command));
  await page.evaluate(() => { if (window.commandClipboardDescriptor) Object.defineProperty(navigator, 'clipboard', window.commandClipboardDescriptor); else delete navigator.clipboard; });
  for (const [id, heading] of [['demo-result', '주문과 취소의 실제 실행 결과'], ['junit-result', 'Test Summary']]) {
    const opening = page.waitForEvent('popup');
    await page.locator('#' + id).click();
    const popup = await opening;
    await popup.waitForLoadState('domcontentloaded');
    check('local result link opens its actual report: ' + id, (await popup.locator('h1').first().innerText()).includes(heading));
    if (id === 'demo-result') {
      check('demo report contains the real sequential HTTP requests', await popup.locator('.step').count() === 11);
      check('demo report displays successful recorded comparisons', (await popup.locator('.status').innerText()).includes('시연 완료'));
      const amounts = await popup.locator('.inventory td').allTextContents();
      check('demo report shows debit once and restore once', amounts.length === 5 && Number(amounts[1]) === Number(amounts[0]) - 1 && amounts[1] === amounts[2] && amounts[0] === amounts[3] && amounts[3] === amounts[4]);
      await popup.setViewportSize({ width: 390, height: 844 });
      check('demo report fits a mobile viewport', await popup.evaluate(() => document.body.scrollWidth <= innerWidth));
      await popup.screenshot({ path: '.runtime/code-explorer/demo-mobile.png', fullPage: true });
    }
    await popup.close();
  }
  await page.screenshot({ path: '.runtime/code-explorer/execution-desktop.png' });

  const controller = info.paths.find(name => name.endsWith('/controller/OrderController.java'));
  for (const [width, height] of [[1440, 900], [1280, 800], [768, 1024], [390, 844], [320, 780]]) {
    await page.setViewportSize({ width, height });
    await page.evaluate(({ file, line }) => window.codeExplorer.selectFile(file, line), { file: controller, line: 54 });
    await page.waitForTimeout(80);
    const layout = await page.evaluate(() => ({
      viewport: innerWidth, body: document.body.scrollWidth,
      scroll: document.querySelector('#code-scroll').getBoundingClientRect().height,
      name: document.querySelector('#file-name').getBoundingClientRect().width,
      tabs: document.querySelector('.view-tabs').scrollWidth,
      tabBox: document.querySelector('.view-tabs').clientWidth
    }));
    check('viewport has no page overflow: ' + width, layout.body <= layout.viewport && layout.tabs <= layout.tabBox);
    check('reader has space: ' + width, layout.scroll > 150 && layout.name > 50);
    if (width <= 800) {
      await page.locator('#open-tree').click();
      check('mobile folder drawer: ' + width, await page.locator('#sidebar').isVisible());
      await page.locator('#close-tree').click();
      check('mobile drawer closes: ' + width, !(await page.locator('#sidebar').isVisible()));
    }
    await page.screenshot({ path: '.runtime/code-explorer/code-' + width + '.png' });
    if (width <= 390) {
      for (const view of ['project', 'execution', 'technology', 'verification']) {
        await page.locator('[data-view="' + view + '"]').click();
        check('document view fits: ' + view + '/' + width, await page.evaluate(() => document.body.scrollWidth <= innerWidth));
      }
      await page.locator('[data-view="code"]').click();
      await page.locator('.line-links button').filter({ hasText: '@PostMapping 선택 이유' }).first().click();
      check('annotation dialog fits: ' + width, await page.locator('#technology-dialog').evaluate(node => node.getBoundingClientRect().right <= innerWidth && node.getBoundingClientRect().left >= 0));
      await page.screenshot({ path: '.runtime/code-explorer/annotation-' + width + '.png' });
      await page.keyboard.press('Escape');
    }
  }
  for (const [width, height] of [[1280, 900], [390, 844]]) {
    await page.setViewportSize({ width, height });
    await page.locator('[data-view="project"]').click();
    await page.getByRole('button', { name: '주문 취소', exact: true }).click();
    const layout = await page.locator('.flow-branches').evaluate(node => {
      const [first, again] = node.children;
      const a = first.getBoundingClientRect(), b = again.getBoundingClientRect();
      return { first: { x: a.x, top: a.top, bottom: a.bottom }, again: { x: b.x, top: b.top }, overflow: document.body.scrollWidth > innerWidth };
    });
    check('cancellation branch layout: ' + width, width > 800 ? layout.again.x > layout.first.x : layout.again.top >= layout.first.bottom);
    check('cancellation branches fit: ' + width, !layout.overflow);
    await page.locator('.flow-branches').scrollIntoViewIfNeeded();
    await page.screenshot({ path: '.runtime/code-explorer/cancellation-' + width + '.png' });
  }
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.locator('[data-view="project"]').click();
  await page.screenshot({ path: '.runtime/code-explorer/program-desktop.png' });
  await page.locator('[data-view="code"]').click();
  await page.locator('#search').fill('멱등키');
  await page.screenshot({ path: '.runtime/code-explorer/search-desktop.png' });
  await page.locator('#clear-search').click();
  check('no external network request', requests.length === 0);
  check('no JavaScript or CSP error', errors.length === 0);
  return { browser: page.context().browser().version(), sourceFiles: info.summary.files, sourceLines: info.summary.lines, passed: passed.length, failures, errors, externalRequests: requests, checks: passed };
}

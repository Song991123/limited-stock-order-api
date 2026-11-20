/** 폴더 탐색과 원본 코드, 줄별 설명을 표시하는 화면. */
(() => {
  'use strict';
  const data = JSON.parse(document.getElementById('explorer-data').textContent);
  const files = new Map(data.files.map(file => [file.path, file]));
  const usedAnnotations = new Set(data.files.flatMap(file => file.annotations));
  const $ = id => document.getElementById(id);
  const state = { view: 'code', file: '', line: 1, technology: 'transaction', flow: 'order', showComments: true, query: '' };
  const expanded = new Set();
  let toastTimer;

  function element(tag, attributes = {}, ...children) {
    const node = document.createElement(tag);
    for (const [key, value] of Object.entries(attributes)) {
      if (key === 'class') node.className = value;
      else if (key === 'text') node.textContent = value;
      else if (key.startsWith('on')) node.addEventListener(key.slice(2), value);
      else if (value !== null && value !== undefined) node.setAttribute(key, value);
    }
    for (const child of children.flat()) if (child !== null && child !== undefined) node.append(child instanceof Node ? child : document.createTextNode(String(child)));
    return node;
  }

  function icon(name, className = '') {
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    for (const [key, value] of Object.entries({ viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', 'stroke-width': '1.7', 'stroke-linecap': 'round', 'stroke-linejoin': 'round', 'aria-hidden': 'true', class: className })) svg.setAttribute(key, value);
    for (const [tag, attributes] of data.icons[name] || data.icons.FileCode2) {
      if (!['path', 'rect', 'line', 'circle', 'ellipse', 'polyline', 'polygon'].includes(tag)) continue;
      const child = document.createElementNS('http://www.w3.org/2000/svg', tag);
      for (const [key, value] of Object.entries(attributes)) child.setAttribute(key, value);
      svg.append(child);
    }
    return svg;
  }

  function decorateIcons() {
    document.querySelectorAll('[data-icon]').forEach(node => node.prepend(icon(node.dataset.icon)));
  }

  function toast(text) {
    $('toast').textContent = text; $('toast').hidden = false;
    clearTimeout(toastTimer); toastTimer = setTimeout(() => { $('toast').hidden = true; }, 1800);
  }

  function setAddress(replace = false) {
    const parameters = new URLSearchParams({ view: state.view, file: state.file, line: state.line });
    if (state.view === 'technology') parameters.set('technology', state.technology);
    const target = '#' + parameters;
    if (location.hash !== target) history[replace ? 'replaceState' : 'pushState'](null, '', target);
  }

  function readAddress() {
    const parameters = new URLSearchParams(location.hash.slice(1));
    const fallback = 'src/main/java/dev/mentoring/orders/controller/OrderController.java';
    state.file = files.has(parameters.get('file')) ? parameters.get('file') : fallback;
    state.line = Math.max(1, Math.min(Number(parameters.get('line')) || 54, files.get(state.file)?.rows.length || 1));
    state.view = ['code', 'project', 'execution', 'technology', 'verification'].includes(parameters.get('view')) ? parameters.get('view') : 'code';
    if (data.technologies[parameters.get('technology')]) state.technology = parameters.get('technology');
    renderFile(); renderView(); revealAncestors(state.file); renderTree();
  }

  function changeView(view) {
    state.view = view; renderView(); setAddress(); closeSidebar();
    if (view === 'code') lineSelection(state.line, true);
    else $('view-' + view).scrollTop = 0;
  }

  function renderView() {
    document.querySelectorAll('[data-view]').forEach(button => {
      button.setAttribute('aria-selected', String(button.dataset.view === state.view));
      button.tabIndex = button.dataset.view === state.view ? 0 : -1;
    });
    for (const view of ['code', 'project', 'execution', 'technology', 'verification']) $('view-' + view).hidden = view !== state.view;
    if (state.view === 'project') renderProject();
    if (state.view === 'execution') renderExecution();
    if (state.view === 'technology') renderTechnology();
    if (state.view === 'verification') renderVerification();
  }

  /** 메서드 링크는 이름으로 현재 선언 줄을 찾아 이동. */
  function openFile(filePath, line = 1, symbol) {
    const file = files.get(filePath);
    if (!file) { toast('이 원문 파일을 찾을 수 없습니다.'); return; }
    if ($('technology-dialog').open) $('technology-dialog').close();
    const match = symbol ? file.methods.find(method => method.name === symbol) : null;
    state.file = filePath; state.line = Math.max(1, Math.min(match?.line || Number(line) || 1, file.rows.length)); state.view = 'code';
    revealAncestors(filePath); renderFile(); renderView(); renderTree(); setAddress(); closeSidebar();
  }

  function lineSelection(line, scroll = false) {
    const file = files.get(state.file);
    state.line = Math.max(1, Math.min(Number(line) || 1, file.rows.length));
    $('code-rows').querySelectorAll('.selected').forEach(node => node.classList.remove('selected'));
    const row = $('code-rows').querySelector(`[data-line="${state.line}"]`);
    if (row) row.classList.add('selected');
    const method = file.methods.filter(item => item.line <= state.line && item.endLine >= state.line).sort((a, b) => (a.endLine - a.line) - (b.endLine - b.line))[0];
    $('method-select').value = method ? String(method.line) : '';
    $('current-line').textContent = `L${state.line}${method ? ' / ' + method.name : ''}`;
    if (scroll && row) requestAnimationFrame(() => row.scrollIntoView({ block: 'start', inline: 'nearest' }));
  }

  function technologyLink(id, label) {
    const tech = data.technologies[id];
    if (!tech) return null;
    return element('button', { class: 'tech-link', type: 'button', onclick: () => openTechnology(id) }, label || tech.title);
  }

  /** 원문과 생성된 줄 해설을 나란히 표시하고 검토 필요 상태 안내. */
  function renderFile() {
    const file = files.get(state.file);
    if (!file) return;
    $('file-name').textContent = file.name; $('file-path').textContent = file.path;
    $('file-role').textContent = file.note.role; $('file-purpose').textContent = file.note.purpose;
    $('file-technologies').replaceChildren(...(file.note.technologies || []).slice(0, 5).map(id => technologyLink(id)).filter(Boolean));
    $('stale-banner').hidden = file.reviewed;
    $('stale-banner').textContent = '원본 코드 또는 해설이 변경됐습니다. 이전 줄 해설을 적용하지 않고 현재 원문의 기본 구문만 표시합니다. 해설 검토가 필요합니다.';
    const choices = [element('option', { value: '' }, '파일 전체')];
    for (const method of file.methods) choices.push(element('option', { value: method.line }, `${method.title}  L${method.line}`));
    $('method-select').replaceChildren(...choices);
    $('method-select').disabled = !file.methods.length;
    $('file-decisions').open = false;
    $('file-decisions').hidden = !(file.note.decisions?.length || file.annotations.length);
    const decisionNodes = [];
    for (const decision of file.note.decisions || []) {
      decisionNodes.push(element('div', { class: 'decision' }, element('h3', {}, decision.title),
        element('p', {}, decision.why), decision.alternatives ? element('p', {}, element('span', {}, '대안'), decision.alternatives) : null,
        decision.limit ? element('p', {}, element('span', {}, '범위'), decision.limit) : null));
    }
    if (file.annotations.length) decisionNodes.push(element('div', { class: 'file-technologies', style: 'padding-bottom:12px' },
      ...file.annotations.map(name => element('button', { class: 'tech-link', onclick: () => openAnnotation(name) }, '@' + name))));
    $('decision-content').replaceChildren(...decisionNodes);
    const fragment = document.createDocumentFragment();
    for (const row of file.rows) {
      const wrapper = element('div', { class: 'code-row ' + row.kind, 'data-line': row.line });
      wrapper.hidden = !state.showComments && row.kind === 'comment';
      const number = element('button', { class: 'line-number', title: row.line + '번 줄', 'aria-label': row.line + '번 줄 선택', onclick: () => { lineSelection(row.line); setAddress(true); } }, row.line);
      const source = element('pre', { class: 'source-code', 'data-source-line': row.line });
      source.innerHTML = row.html;
      const explanation = element('div', { class: 'line-explanation' }, element('p', {}, row.description));
      const links = element('div', { class: 'line-links' });
      for (const name of row.annotations || []) if (data.annotations[name]) links.append(element('button', { onclick: () => openAnnotation(name) }, '@' + name + ' 선택 이유'));
      for (const id of row.tech || []) if (data.technologies[id]) links.append(technologyLink(id));
      for (const link of (row.links || []).slice(0, 2)) links.append(element('button', { onclick: () => openFile(link.file, 1, link.symbol) }, link.symbol + ' 원문'));
      if (links.childNodes.length) explanation.append(links);
      wrapper.append(number, source, explanation); fragment.append(wrapper);
    }
    $('code-rows').replaceChildren(fragment);
    $('code-count').textContent = `${file.language} / ${file.rows.length}줄 / ${file.reviewed ? '해설 기준 일치' : '해설 검토 필요'}`;
    lineSelection(state.line, true);
  }

  function makeTree() {
    const root = { children: new Map(), path: '' };
    for (const file of data.files) {
      const segments = file.path.split('/'); let parent = root, current = '';
      segments.forEach((name, index) => {
        current = current ? current + '/' + name : name;
        if (!parent.children.has(name)) parent.children.set(name, { name, path: current, children: new Map(), file: index === segments.length - 1 ? file : null });
        parent = parent.children.get(name);
      });
    }
    return root;
  }
  const treeModel = makeTree();

  function revealAncestors(file) {
    let parent = '';
    for (const segment of file.split('/').slice(0, -1)) { parent = parent ? parent + '/' + segment : segment; expanded.add(parent); }
  }

  function renderTree() {
    const previous = $('tree').scrollTop;
    const focusedPath = $('tree').contains(document.activeElement) ? document.activeElement.title : null;
    function level(node, depth = 0) {
      const group = element('ul', { role: depth ? 'group' : 'presentation' });
      const children = [...node.children.values()].sort((a, b) => Boolean(a.file) - Boolean(b.file) || a.name.localeCompare(b.name, 'en'));
      for (let child of children) {
        let label = child.name;
        while (!child.file && child.children.size === 1 && ![...child.children.values()][0].file) {
          child = [...child.children.values()][0]; label += '/' + child.name;
        }
        const item = element('li', { class: 'tree-node', role: 'none' });
        const isOpen = expanded.has(child.path);
        const button = element('button', { class: 'tree-row' + (child.file && state.file === child.path ? ' active' : ''), title: child.path,
          role: 'treeitem', 'aria-level': depth + 1, 'aria-selected': child.file ? String(state.file === child.path) : null,
          'aria-expanded': child.file ? null : String(isOpen), style: `padding-left:${6 + depth * 12}px`,
          onclick: () => { if (child.file) openFile(child.path); else { isOpen ? expanded.delete(child.path) : expanded.add(child.path); renderTree(); } } });
        if (child.file) button.append(element('span', { class: 'spacer' }), icon('FileCode2', 'file-symbol ' + child.name.split('.').pop()));
        else button.append(icon(isOpen ? 'ChevronDown' : 'ChevronRight', 'caret'), icon(isOpen ? 'FolderOpen' : 'Folder', 'folder-symbol'));
        button.append(element('span', { class: 'label' }, label)); item.append(button);
        if (!child.file && isOpen) item.append(level(child, depth + 1));
        group.append(item);
      }
      return group;
    }
    $('tree').replaceChildren(level(treeModel)); $('tree').scrollTop = previous;
    const items = [...$('tree').querySelectorAll('[role="treeitem"]')];
    const focused = items.find(item => item.title === focusedPath);
    const current = focused || items.find(item => item.getAttribute('aria-selected') === 'true') || items[0];
    for (const item of items) item.tabIndex = item === current ? 0 : -1;
    if (focusedPath && focused) focused.focus({ preventScroll: true });
  }

  const synonyms = {
    '멱등성': ['멱등', 'idempotency', 'request_key'], '멱등키': ['멱등', 'idempotency', 'request_key'],
    '트랜잭션': ['transaction', '커밋', '롤백'], '락': ['lock', '잠금'], '비관적락': ['for update', '잠금'],
    '낙관적락': ['version', '버전 충돌'], '유일성': ['unique', 'on conflict'], '동시성': ['동시', '경쟁'],
    '취소': ['cancel', 'restore'], '어노테이션': ['annotation', '@'], '검증': ['검사', 'assert', 'test']
  };
  const normalize = value => String(value).normalize('NFKC').toLowerCase();
  const compact = value => normalize(value).replace(/[\s_.-]/g, '');
  const searchRows = data.files.flatMap(file => file.rows.filter(row => row.code.trim() && !['comment', 'blank'].includes(row.kind)).map(row => ({
    file: file.path, name: file.name, line: row.line, code: row.code, description: row.description, kind: row.kind,
    haystack: normalize(file.path + ' ' + row.code + ' ' + row.description),
    compact: compact(file.path + ' ' + row.code + ' ' + row.description)
  })));

  function search() {
    state.query = $('search').value.trim(); $('clear-search').hidden = !state.query;
    $('tree').hidden = Boolean(state.query); $('search-results').hidden = !state.query;
    $('sidebar-label').textContent = state.query ? '검색 결과' : '파일';
    if (!state.query) { $('file-count').textContent = data.files.length; $('search-results').replaceChildren(); return; }
    const raw = normalize(state.query), tokens = raw.split(/\s+/).filter(Boolean);
    const groups = tokens.map(token => [token, ...(synonyms[token] || [])].map(normalize));
    const matches = searchRows.filter(row => groups.every(group => group.some(term => row.haystack.includes(term) || row.compact.includes(compact(term))))).map(row => ({ ...row,
      score: (normalize(row.code).includes(raw) ? 10 : 0) + (normalize(row.description).includes(raw) ? 8 : 0) + (normalize(row.name).includes(raw) ? 4 : 0)
        + (row.file.startsWith('src/main/') ? 4 : 0) + (row.kind === 'specific' ? 2 : 0)
    })).sort((a, b) => b.score - a.score || a.file.localeCompare(b.file, 'en') || a.line - b.line);
    const topics = Object.entries(data.technologies).filter(([, value]) => groups.every(group => group.some(term => normalize(value.title + ' ' + value.description + ' ' + value.why + ' ' + value.alternatives).includes(term))));
    $('file-count').textContent = matches.length + topics.length;
    const fragment = document.createDocumentFragment();
    for (const [id, topic] of topics.slice(0, 4)) fragment.append(element('button', { class: 'search-result', onclick: () => openTechnology(id) },
      element('strong', {}, topic.title, element('span', { class: 'role-label' }, '기술')), element('p', {}, topic.description)));
    for (const row of matches.slice(0, 90)) fragment.append(element('button', { class: 'search-result', onclick: () => openFile(row.file, row.line) },
      element('strong', {}, row.name, element('span', {}, 'L' + row.line)), element('small', {}, row.file), element('p', {}, row.kind === 'specific' ? row.description : row.code.trim())));
    if (!fragment.childNodes.length) fragment.append(element('div', { class: 'empty-results' }, '일치하는 코드와 해설이 없습니다.'));
    if (matches.length > 90) fragment.append(element('p', { class: 'result-more' }, `코드 결과 ${matches.length}개 중 90개 표시`));
    $('search-results').replaceChildren(fragment); $('search-results').scrollTop = 0;
    if (matchMedia('(max-width:800px)').matches) openSidebar(false);
  }

  function codeLink(file, symbol, label) {
    return element('button', { class: 'code-link', onclick: () => openFile(file, 1, symbol) }, label || file.split('/').pop() + (symbol ? '.' + symbol : ''));
  }

  /** 접수 상태의 첫 취소와 재취소를 별도 문서 분기로 표시해 재고 복구 여부 구분. */
  function renderProject() {
    const flow = data.flows.find(item => item.id === state.flow) || data.flows[0];
    const steps = (entries, offset = 0) => element('ol', { class: 'flow-steps', start: offset + 1 },
      ...entries.map(([title, file, method, description], index) => element('li', { class: 'flow-step' },
        element('span', { class: 'flow-number' }, String(offset + index + 1).padStart(2, '0')),
        element('div', {}, element('h3', {}, title), element('p', {}, description), codeLink(file, method)))));
    const diagram = data.figures.find(item => item.id === '01-actors');
    const figure = diagram ? element('figure', {}, element('button', { class: 'figure-preview', title: '고객과 요청 그림 크게 보기', 'aria-label': '고객과 요청 그림 크게 보기', onclick: () => showFigure(diagram) }, element('img', { src: diagram.image, alt: diagram.title })), element('figcaption', {}, diagram.title)) : null;
    const node = element('div', { class: 'document-inner' }, element('p', { class: 'eyebrow' }, data.project.subtitle), element('h1', {}, data.project.title),
      element('div', { class: 'project-layout' }, element('div', {}, element('p', {}, data.project.purpose), element('p', {}, data.project.actor), element('p', {}, data.project.scope), element('p', { class: 'stack' }, data.project.stack)), figure),
      element('section', {}, element('h2', {}, '고객 요청의 처리 순서'),
        element('div', { class: 'flow-tabs' }, ...data.flows.map(item => element('button', { class: item.id === flow.id ? 'active' : '', onclick: () => { state.flow = item.id; renderProject(); } }, item.title))),
        steps(flow.steps), flow.branches ? element('div', { class: 'flow-branches' }, ...flow.branches.map(branch =>
          element('div', { class: 'flow-branch', 'data-branch': branch.id, role: 'group', 'aria-label': branch.title },
            element('h3', { class: 'branch-title' }, branch.title), element('p', { class: 'branch-condition' }, branch.condition), steps(branch.steps, flow.steps.length)))) : null,
        flow.note ? element('p', { class: 'flow-note' }, flow.note) : null),
      element('section', {}, element('h2', {}, '주문과 재고의 기준'), element('p', {}, data.project.invariant), element('p', {}, '같은 구매를 다시 요청해도 주문은 추가되지 않습니다. 취소는 접수 상태를 바꾼 요청만 수량을 복구합니다.')),
      element('section', {}, element('h2', {}, '구성과 저장 순서'), element('div', { class: 'diagram-list' }, ...data.figures.filter(item => item.id !== '01-actors').map(item => element('button', { onclick: () => showFigure(item) }, item.title))), element('div', { id: 'project-figure' })));
    $('view-project').replaceChildren(node);
  }

  function showFigure(figure) {
    $('project-figure').replaceChildren(element('h3', {}, figure.title), element('img', { class: 'full-figure', src: figure.image, alt: figure.title }));
    $('project-figure').scrollIntoView({ block: 'start' });
  }

  function definition(label, text) {
    return element('div', { class: 'definition-group' }, element('h3', {}, label), element('p', {}, text));
  }

  function renderTechnology() {
    const active = data.technologies[state.technology] ? state.technology : 'transaction', topic = data.technologies[active];
    const menu = element('nav', { class: 'technology-menu', 'aria-label': '기술 목록' }, ...Object.entries(data.technologies).map(([id, entry]) => element('button', { class: active === id ? 'active' : '', onclick: () => { state.technology = id; renderTechnology(); setAddress(true); } }, entry.title)));
    const detail = element('article', { class: 'technology-detail' }, element('p', { class: 'eyebrow' }, '구현과 선택 이유'), element('h1', {}, topic.title),
      definition('역할', topic.description), definition('이 서버에서 사용한 이유', topic.why), definition('다른 방법과 비교', topic.alternatives), definition('비용과 보장 범위', topic.limit),
      element('h3', {}, '관련 원문'), ...topic.files.filter(file => files.has(file)).map(file => element('p', {}, codeLink(file))),
      topic.source ? element('p', {}, element('a', { href: topic.source, target: '_blank', rel: 'noopener noreferrer' }, '공식 문서')) : null);
    const annotations = Object.entries(data.annotations).filter(([name, value]) => value.tech === active && usedAnnotations.has(name));
    if (annotations.length) detail.append(element('h2', {}, '사용한 어노테이션'), element('div', { class: 'annotation-grid' }, ...annotations.map(([name, entry]) => element('div', { class: 'annotation-entry' }, element('button', { onclick: () => openAnnotation(name) }, '@' + name), element('p', {}, entry.effect)))));
    $('view-technology').replaceChildren(element('div', { class: 'document-inner technology-layout' }, menu, detail));
  }

  function openTechnology(id) {
    const topic = data.technologies[id]; if (!topic) return;
    $('technology-title').textContent = topic.title;
    const body = [definition('역할', topic.description), definition('사용한 이유', topic.why), definition('대안', topic.alternatives), definition('범위와 주의점', topic.limit)];
    if (topic.files.length) body.push(element('div', { class: 'dialog-links' }, ...topic.files.filter(file => files.has(file)).map(file => codeLink(file))));
    if (topic.source) body.push(element('p', { class: 'dialog-links' }, element('a', { href: topic.source, target: '_blank', rel: 'noopener noreferrer' }, '공식 문서')));
    $('technology-body').replaceChildren(...body);
    if (!$('technology-dialog').open) $('technology-dialog').showModal();
  }

  function openAnnotation(name) {
    const entry = data.annotations[name];
    if (!entry) { toast('@' + name + '의 별도 해설이 아직 없습니다.'); return; }
    $('technology-title').textContent = '@' + name;
    $('technology-body').replaceChildren(definition('효과', entry.effect), definition('이 코드에서 사용한 이유', entry.why), definition('다른 방법', entry.alternatives), definition('주의할 점', entry.limit),
      element('div', { class: 'dialog-links' }, element('button', { class: 'code-link', onclick: () => openTechnology(entry.tech) }, data.technologies[entry.tech].title)));
    if (!$('technology-dialog').open) $('technology-dialog').showModal();
  }

  function table(headers, rows) {
    return element('table', { class: 'data-table' }, element('thead', {}, element('tr', {}, ...headers.map(value => element('th', { scope: 'col' }, value)))), element('tbody', {}, ...rows.map(row => element('tr', {}, ...row.map(value => element('td', {}, value))))));
  }

  function resultLink(output) {
    return element('a', { id: output.id, class: 'artifact-link', href: output.href, target: '_blank', rel: 'noopener noreferrer' }, output.title, icon('ExternalLink'));
  }

  /** 서버 실행 명령과 로컬에서 생성한 결과 연결. 이 화면 자체는 명령이나 HTTP 요청을 실행하지 않음. */
  function renderExecution() {
    const guide = data.execution;
    const commands = guide.steps.map((step, index) => {
      const copy = element('button', { class: 'icon-button', title: '명령 복사: ' + step.title, 'aria-label': '명령 복사: ' + step.title,
        onclick: async () => { try { await copyText(step.command); toast('명령을 복사했습니다.'); } catch { toast('이 브라우저에서 복사를 허용하지 않았습니다.'); } } }, icon('Copy'));
      return element('section', { class: 'execution-step' }, element('h2', {}, `${index + 1}. ${step.title}`), element('p', {}, step.text),
        element('div', { class: 'command-row' }, element('pre', {}, element('code', {}, step.command)), copy), codeLink(step.source));
    });
    $('view-execution').replaceChildren(element('div', { class: 'document-inner' }, element('p', { class: 'eyebrow' }, '실행 파일과 실제 요청 결과'),
      element('h1', {}, '서버 실행과 주문 확인'), element('p', {}, guide.requirements),
      element('p', { class: 'run-note' }, '이 HTML은 서버를 자동으로 켜지 않습니다. 아래 명령을 실행한 뒤 생성된 로컬 결과를 엽니다.'),
      element('section', { class: 'execution-outputs' }, element('h2', {}, '실행 결과 열기'), ...guide.outputs.map(output =>
        element('div', { class: 'artifact-row' }, resultLink(output), element('p', {}, output.text)))),
      ...commands,
      element('section', {}, element('h2', {}, '시연에서 확인하는 재고 변화'),
        table(['동작', '확인할 결과'], [['주문 전 재고 조회', '현재 재고 N을 직접 조회'], ['상품 1개 주문', '새 주문 접수와 재고 N-1'],
          ['같은 구매 키와 본문으로 재요청', '같은 주문 ID, 재고 N-1 유지'], ['처음 취소', 'CANCELLED 상태와 재고 N 복구'], ['같은 주문 재취소', '같은 주문 ID, 재고 N 유지']]),
        element('p', {}, '시연 기록은 HTTP 응답을 비교합니다. 동시 주문, SQL 오류와 복구 이력의 DB 대조는 전체 검사 보고서에서 별도로 확인합니다.'))));
  }

  /** 보관된 서비스 검사 결과와 해설 문서의 갱신 상태를 구분해 표시. */
  function renderVerification() {
    const evidence = data.evidence, regular = evidence.regular, recovery = evidence.separateRecovery;
    const date = evidence.recordedRun ? evidence.recordedRun.slice(0, 4) + '-' + evidence.recordedRun.slice(4, 6) + '-' + evidence.recordedRun.slice(6, 8) : '기록 없음';
    $('view-verification').replaceChildren(element('div', { class: 'document-inner' }, element('p', { class: 'eyebrow' }, '실행 기록과 확인 범위'), element('h1', {}, '검증 결과'),
      element('p', { class: 'local-result-links' }, ...data.execution.outputs.filter(output => output.id !== 'server-jar').map(output => resultLink({ ...output, id: output.id + '-verification' }))),
      element('p', {}, '위 링크는 로컬에서 마지막으로 실행해 만든 보고서입니다. 아래 표는 저장소에 보관한 기존 검사 기록입니다.'),
      element('p', { class: 'record-date' }, '보관된 서비스 검사 기록 / ' + date),
      element('p', {}, '아래 수치는 저장된 JUnit과 HTTP/DB 검사 기록입니다. 코드 해설 HTML을 생성하거나 읽는 작업은 주문 서버와 DB를 새로 실행하지 않습니다.'),
      table(['검사 구분', '보관된 결과'], [
        ['정규 JUnit', regular ? `${regular.tests}건, 실패 ${regular.failures} / 오류 ${regular.errors} / 생략 ${regular.skipped}` : '기록 없음'],
        ['정규 검사 구성', '실제 HTTP/DB 83건, 별도 JVM 종료/재시작 1건, 단위/구조 88건'],
        ['구조 검사', `${evidence.architecture ?? '기록 없음'}건, 정규 검사 수에 포함`],
        ['별도 DB 서버 중단', recovery ? `${recovery.tests}건, 5조건을 각각 3회. 정규 검사와 별도` : '기록 없음']
      ]),
      element('section', {}, element('h2', {}, '대표 조건과 결과'), table(['재현 조건', '관찰 결과'], [
        ['마지막 재고 1개, 서로 다른 고객 두 명', '주문 접수 1건, 재고 부족 1건, 남은 수량 0'],
        ['같은 구매 키로 동시 20건', '주문과 차감 이력 각각 1건'],
        ['같은 주문 취소 동시 20건', '취소 상태와 수량 복구, 복구 이력 한 번'],
        ['저장 중 SQL 오류', '미확정 변경 롤백, 기존 상태와 수량 유지'],
        ['커밋 후 응답 유실과 JVM 재시작', '같은 키 또는 주문 ID로 확인, 추가 차감과 복구 없음'],
        ['서버 JVM 유지, 전용 DB 중단/재시작', 'DB 연결 회복 후 재요청 성공, 주문과 재고 및 이력 일치']
      ])),
      element('section', {}, element('h2', {}, '아직 확인하지 않은 범위'), element('ul', {}, ...data.project.limits.map(item => element('li', {}, item))), element('p', {}, '과거 k6와 SQL 실행 계획은 해당 코드 버전의 기록이며 최신 코드의 성능 재측정으로 옮기지 않습니다.')),
      element('section', {}, element('h2', {}, '코드 해설의 갱신 상태'), table(['문서 확인', '내용'], [
        ['수록한 원문', `${data.summary.files}파일, ${data.summary.lines.toLocaleString()}줄`],
        ['파일별 직접 작성한 줄 해설', `${data.summary.manual.toLocaleString()}줄. 구문 분석에 따른 공통 해설은 별도`],
        ['해설 검토 필요', `${data.summary.stale}파일`],
        ['소스 묶음 해시', data.sourceSetHash.slice(0, 20)]
      ]), element('p', {}, '원문과 해설 기준의 해시 비교는 갱신 누락을 확인합니다. 설명 문장의 기술적 정확성이나 서비스 동작 검증을 대신하지 않습니다.'))));
  }

  function openSidebar(focus = true) { document.body.classList.add('sidebar-open'); $('sidebar-backdrop').hidden = false; $('open-tree').setAttribute('aria-expanded', 'true'); if (focus) $('close-tree').focus(); }
  function closeSidebar() { document.body.classList.remove('sidebar-open'); $('sidebar-backdrop').hidden = true; $('open-tree').setAttribute('aria-expanded', 'false'); }

  function copyText(text) {
    if (navigator.clipboard?.writeText) return navigator.clipboard.writeText(text).catch(() => fallbackCopy(text));
    return Promise.resolve(fallbackCopy(text));
  }
  function fallbackCopy(text) {
    const field = element('textarea', { style: 'position:fixed;left:-10000px;top:0' }); field.value = text; document.body.append(field); field.select();
    const success = document.execCommand('copy'); field.remove(); if (!success) throw new Error('Clipboard is not available');
  }

  decorateIcons();
  $('file-count').textContent = data.files.length; $('snapshot-hash').textContent = data.sourceSetHash.slice(0, 12);
  $('source-state').textContent = data.summary.stale ? `해설 검토 필요 ${data.summary.stale}파일` : '코드와 해설 기준 일치';
  $('status-dot').classList.toggle('stale', data.summary.stale > 0);
  document.querySelectorAll('[data-view]').forEach(button => button.addEventListener('click', () => changeView(button.dataset.view)));
  $('search').addEventListener('input', search);
  $('search').addEventListener('search', search);
  $('clear-search').addEventListener('click', () => { $('search').value = ''; search(); $('search').focus(); });
  $('method-select').addEventListener('change', () => { lineSelection(Number($('method-select').value) || 1, true); setAddress(); });
  $('show-comments').addEventListener('change', () => { state.showComments = $('show-comments').checked; $('code-rows').querySelectorAll('.comment').forEach(row => { row.hidden = !state.showComments; }); });
  $('copy-code').addEventListener('click', async () => { try { await copyText(files.get(state.file).text); toast('원본 코드를 복사했습니다.'); } catch { toast('이 브라우저에서 복사를 허용하지 않았습니다.'); } });
  $('download-code').addEventListener('click', () => {
    const file = files.get(state.file), url = URL.createObjectURL(new Blob([file.text], { type: 'text/plain;charset=utf-8' }));
    const anchor = element('a', { href: url, download: file.name }); document.body.append(anchor); anchor.click(); anchor.remove(); setTimeout(() => URL.revokeObjectURL(url), 1200);
  });
  $('open-github').addEventListener('click', () => window.open('https://github.com/Song991123/limited-stock-order-api/blob/main/' + state.file.split('/').map(encodeURIComponent).join('/'), '_blank', 'noopener,noreferrer'));
  $('expand-tree').addEventListener('click', () => { for (const file of data.files) revealAncestors(file.path); renderTree(); });
  $('collapse-tree').addEventListener('click', () => { expanded.clear(); renderTree(); });
  $('open-tree').addEventListener('click', () => openSidebar()); $('close-tree').addEventListener('click', closeSidebar); $('sidebar-backdrop').addEventListener('click', closeSidebar);
  $('close-technology').addEventListener('click', () => $('technology-dialog').close());
  document.querySelector('.view-tabs').addEventListener('keydown', event => {
    if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
    const tabs = [...document.querySelectorAll('[data-view]')];
    const index = tabs.indexOf(event.target.closest('[data-view]'));
    if (index < 0) return;
    event.preventDefault();
    const next = event.key === 'Home' ? 0 : event.key === 'End' ? tabs.length - 1 : (index + (event.key === 'ArrowRight' ? 1 : -1) + tabs.length) % tabs.length;
    changeView(tabs[next].dataset.view); tabs[next].focus();
  });
  $('tree').addEventListener('focusin', event => {
    const row = event.target.closest('[role="treeitem"]');
    if (row) $('tree').querySelectorAll('[role="treeitem"]').forEach(item => { item.tabIndex = item === row ? 0 : -1; });
  });
  $('tree').addEventListener('keydown', event => {
    const row = event.target.closest('[role="treeitem"]');
    if (!row || !['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
    event.preventDefault();
    const rows = [...$('tree').querySelectorAll('[role="treeitem"]')], index = rows.indexOf(row);
    if (event.key === 'ArrowRight' && row.getAttribute('aria-expanded') === 'false') { row.click(); return; }
    if (event.key === 'ArrowLeft') {
      if (row.getAttribute('aria-expanded') === 'true') row.click();
      else row.parentElement.parentElement.closest('li')?.querySelector(':scope > button')?.focus();
      return;
    }
    const next = event.key === 'Home' ? 0 : event.key === 'End' ? rows.length - 1 : Math.max(0, Math.min(rows.length - 1, index + (event.key === 'ArrowUp' ? -1 : 1)));
    rows[next]?.focus();
  });
  $('technology-dialog').addEventListener('click', event => { if (event.target === $('technology-dialog')) { const box = event.target.getBoundingClientRect(); if (event.clientX < box.left || event.clientX > box.right || event.clientY < box.top || event.clientY > box.bottom) event.target.close(); } });
  document.addEventListener('keydown', event => {
    if (event.key === 'Escape' && !$('technology-dialog').open) { if (document.body.classList.contains('sidebar-open')) closeSidebar(); else if ($('search').value) { $('search').value = ''; search(); } }
  });
  window.addEventListener('popstate', readAddress); window.addEventListener('hashchange', readAddress);
  readAddress(); setAddress(true);
  window.codeExplorer = Object.freeze({ stats: data.summary, current: () => ({ ...state }), selectFile: openFile, data });
})()

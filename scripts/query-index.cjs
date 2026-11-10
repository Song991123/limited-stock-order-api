// 고객별 주문 목록의 기존 인덱스 비교. 새로 만든 전용 DB에서만 인덱스 제거와 복원 수행.
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');
const { spawnSync } = require('node:child_process');
const root = path.resolve(__dirname, '..');
const directory = path.join(root, 'evidence', 'raw', 'query-' + new Date().toISOString().replace(/[:.]/g, '-'));
const instance = 'isolated-' + crypto.randomBytes(8).toString('hex');
const env = { ...process.env, STOCK_DB_TEST_INSTANCE: instance };
const results = [];
let initialized = false;
fs.mkdirSync(directory, { recursive: true });

function command(executable, args, options = {}) {
  const result = spawnSync(executable, args, { cwd: root, env, windowsHide: true, encoding: 'utf8',
    timeout: 180000, maxBuffer: 16 * 1024 * 1024, ...options });
  if (result.error || result.status !== 0) throw new Error(`${path.basename(executable)} failed: ${result.stderr || result.error}`);
  return result.stdout;
}
function database(action) { return JSON.parse(command(process.execPath, [path.join(__dirname, 'local-db.cjs'), action])); }
function save(name, value) { fs.writeFileSync(path.join(directory, name), JSON.stringify(value, null, 2) + '\n'); }
function sql(statement) {
  return command(path.join(env.STOCK_PG_TEST_BIN, 'psql.exe'), ['-X', '-w', '-A', '-t', '-q', '-h', '127.0.0.1',
    '-p', String(new URL(env.STOCK_DB_URL.slice(5)).port), '-U', env.STOCK_DB_USER, '-d', 'stock_order_test', '-v', 'ON_ERROR_STOP=1'],
  { input: statement, env: { ...env, PGPASSWORD: env.STOCK_DB_PASSWORD } }).trim();
}
function snapshot() {
  return JSON.parse(sql(`SELECT json_build_object(
    'orders',(SELECT count(*) FROM orders),'requests',(SELECT count(*) FROM order_requests),
    'movements',(SELECT count(*) FROM inventory_movements),
    'inventoryViolations',(SELECT count(*) FROM inventories i WHERE available_quantity<>initial_quantity-
      COALESCE((SELECT sum(quantity) FROM orders o WHERE o.product_id=i.product_id AND o.status='ACCEPTED'),0)
      OR available_quantity<>initial_quantity+COALESCE((SELECT sum(quantity_delta) FROM inventory_movements m WHERE m.product_id=i.product_id),0)),
    'requestViolations',(SELECT count(*) FROM order_requests r WHERE outcome<>'ACCEPTED' OR outcome IS NULL
      OR (SELECT count(*) FROM orders o WHERE o.request_id=r.id)<>1),
    'orderViolations',(SELECT count(*) FROM orders o JOIN order_requests r ON r.id=o.request_id
      WHERE o.buyer_id<>r.buyer_id OR o.product_id<>r.product_id OR o.quantity<>r.quantity
      OR (SELECT count(*) FROM inventory_movements m WHERE m.order_id=o.id AND m.kind='DEBIT'
        AND m.product_id=o.product_id AND m.quantity_delta=-o.quantity)<>1))`));
}
function nodes(plan) { return [plan, ...(plan.Plans || []).flatMap(nodes)]; }
function setIndex(present) {
  sql(present ? 'CREATE INDEX IF NOT EXISTS orders_buyer_page ON orders(buyer_id,id DESC);'
    : 'DROP INDEX IF EXISTS orders_buyer_page;');
}
function compare(buyerCount, before) {
  const statement = `SELECT id, product_id, quantity, status, created_at, cancelled_at
    FROM orders WHERE buyer_id=1 AND id<${before} ORDER BY id DESC LIMIT 20`;
  const readIds = () => JSON.parse(sql(`SELECT COALESCE(json_agg(id ORDER BY id DESC),'[]'::json) FROM (${statement}) page;`));
  const expected = readIds();
  assert.equal(expected.length, 20);
  for (let repetition = 1; repetition <= 3; repetition++) {
    for (const present of (repetition % 2 ? [true, false] : [false, true])) {
      setIndex(present);
      sql(statement); // 각 조건에서 한 번 실행한 뒤 계획 수집. 최초 읽기 시간으로 해석하지 않음.
      const plan = JSON.parse(sql(`EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) ${statement};`))[0];
      const actual = readIds();
      assert.deepEqual(actual, expected);
      assert.equal(plan.Plan['Actual Rows'], 20);
      const name = `${buyerCount}-buyers-before-${before}-${present ? 'index' : 'no-index'}-${repetition}.json`;
      save(name, plan);
      results.push({ buyerCount, orderCount: 100000, beforeId: before, indexPresent: present, repetition,
        returnedIds: actual, executionMs: plan['Execution Time'], sharedHitBlocks: plan.Plan['Shared Hit Blocks'],
        sharedReadBlocks: plan.Plan['Shared Read Blocks'], scans: nodes(plan.Plan).filter(n => n['Relation Name']).map(n => ({
          type: n['Node Type'], index: n['Index Name'] || null, rows: n['Actual Rows'], loops: n['Actual Loops'],
          removedByFilter: n['Rows Removed by Filter'] || 0, indexCondition: n['Index Cond'] || null })), rawPlan: name });
    }
  }
  setIndex(true);
}

try {
  save('source-sha256.json', JSON.parse(command('pwsh', ['-NoProfile', '-Command', '& ./scripts/source-snapshot.ps1 | ConvertTo-Json -Depth 4'])));
  const meta = database('init'); initialized = true;
  Object.assign(env, JSON.parse(command(process.execPath, [path.join(__dirname, 'local-db.cjs'), 'env', 'test'])));
  const owned = JSON.parse(fs.readFileSync(path.join(root, '.runtime', instance, 'database.json'), 'utf8'));
  save('owned-database.json', { instance, data: owned.data, port: owned.port,
    pid: Number(fs.readFileSync(path.join(owned.data, 'postmaster.pid'), 'utf8').split(/\r?\n/)[0]) });
  assert.equal(sql('SELECT current_database();'), 'stock_order_test');
  assert.equal(sql("SELECT count(*) FROM information_schema.tables WHERE table_schema='public';"), '0');
  sql(fs.readFileSync(path.join(root, 'src/main/resources/db/migration/V1__orders_and_inventory.sql'), 'utf8'));
  // SQL 의미가 달라지면 복사한 비교 SQL로 검사를 계속하지 않도록 현재 저장소 조건 확인.
  const repository = fs.readFileSync(path.join(root, 'src/main/java/dev/mentoring/orders/repository/OrderRepository.java'), 'utf8');
  for (const text of ['"id, product_id, quantity, status, created_at, cancelled_at"',
    'FROM orders WHERE buyer_id = :buyer AND id < :before', 'ORDER BY id DESC LIMIT :limit']) assert.ok(repository.includes(text));
  save('environment.json', { postgres: meta.version, node: process.version, platform: os.platform(), cpu: os.cpus()[0].model,
    settings: JSON.parse(sql("SELECT json_build_object('fsync',current_setting('fsync'),'synchronous_commit',current_setting('synchronous_commit'),'shared_buffers',current_setting('shared_buffers'));")),
    scope: 'Equivalent repository list SQL on synthetic rows, not HTTP latency or a production capacity test. No planner options forced.' });
  const datasets = [];
  for (const buyerCount of [2, 1000]) {
    sql(`BEGIN;
      TRUNCATE inventory_movements,orders,order_requests,inventories,products,buyers RESTART IDENTITY CASCADE;
      INSERT INTO buyers(username,password_hash) SELECT 'plan-buyer-'||n,'unused-synthetic-hash' FROM generate_series(1,${buyerCount}) n;
      INSERT INTO products(name) VALUES ('Plan fixture');
      INSERT INTO inventories VALUES (1,100000,0);
      INSERT INTO order_requests(buyer_id,request_key,product_id,quantity,outcome)
        SELECT ((n-1)%${buyerCount})+1,'plan-key-'||n,1,1,'ACCEPTED' FROM generate_series(1,100000) n;
      INSERT INTO orders(request_id,buyer_id,product_id,quantity,status) SELECT id,buyer_id,product_id,quantity,'ACCEPTED' FROM order_requests ORDER BY id;
      INSERT INTO inventory_movements(order_id,product_id,kind,quantity_delta) SELECT id,product_id,'DEBIT',-quantity FROM orders;
      COMMIT;
      ANALYZE buyers; ANALYZE order_requests; ANALYZE orders; ANALYZE inventories; ANALYZE inventory_movements;`);
    const before = snapshot();
    for (const key of ['orders', 'requests', 'movements']) assert.equal(before[key], 100000);
    for (const key of ['inventoryViolations', 'requestViolations', 'orderViolations']) assert.equal(before[key], 0);
    compare(buyerCount, '9223372036854775807');
    compare(buyerCount, '90000');
    assert.deepEqual(snapshot(), before);
    const sizes = JSON.parse(sql(`SELECT json_agg(json_build_object('name',relname,'bytes',pg_relation_size(oid)) ORDER BY relname)
      FROM pg_class WHERE relnamespace='public'::regnamespace AND relkind IN ('r','i');`));
    datasets.push({ buyers: buyerCount, before, after: snapshot(), relationBytes: sizes });
    console.log(JSON.stringify({ buyerCount, plans: results.length, violations: 0 }));
  }
  save('query-index-results.json', { datasets, plans: results, scope: [
    'Same 100000 orders per distribution; first page and beforeId=90000; three repetitions per condition.',
    'Index present/absent order alternates. Warmup, DDL and fixture insertion are outside plan execution time.',
    'Same returned IDs and quantity/request/ledger counts checked. Only orders_buyer_page is removed in this owned DB.',
    'No claim about write cost, cold cache, arbitrary customer skew, maximum capacity or best locking method.' ] });
} finally {
  if (initialized) {
    const cleanup = database('stop');
    save('cleanup.json', { stopped: cleanup.stopped, running: database('status').running });
  }
}
console.log(JSON.stringify({ directory, plans: results.length }));

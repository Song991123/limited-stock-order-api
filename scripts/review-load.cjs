const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const crypto = require('node:crypto');
const net = require('node:net');
const { spawn, spawnSync } = require('node:child_process');
const root = path.resolve(__dirname, '..');
const runId = new Date().toISOString().replace(/[:.]/g, '-');
const directory = path.join(root, 'evidence', 'raw', 'load-' + runId);
const instance = 'isolated-' + crypto.randomBytes(8).toString('hex');
const env = { ...process.env, STOCK_DB_TEST_INSTANCE: instance, K6_NO_USAGE_REPORT: 'true' };
const k6 = path.join(root, '.tools', 'k6', 'v2.3.0', 'k6-v2.3.0-windows-amd64', 'k6.exe');
const observations = [];
const smoke = process.argv.includes('--smoke');
let server;
fs.mkdirSync(directory, { recursive: true });

function command(executable, args, options = {}) {
  const result = spawnSync(executable, args, { cwd: root, env, encoding: 'utf8', windowsHide: true, timeout: 180000, maxBuffer: 16 * 1024 * 1024, ...options });
  if (result.error || result.status !== 0) throw new Error(`${path.basename(executable)} failed (exit ${result.status}): ${result.stderr || result.error || result.stdout || 'inspect per-run stderr log'}`);
  return result.stdout;
}
function database(action) { return JSON.parse(command(process.execPath, [path.join(__dirname, 'local-db.cjs'), action])); }
function sql(statement) {
  const result = command(path.join(env.STOCK_PG_TEST_BIN, 'psql.exe'), ['-X', '-w', '-A', '-t', '-h', '127.0.0.1',
    '-p', String(new URL(env.STOCK_DB_URL.slice(5)).port), '-U', env.STOCK_DB_USER, '-d', 'stock_order_test', '-v', 'ON_ERROR_STOP=1'],
  { input: statement, env: { ...env, PGPASSWORD: env.STOCK_DB_PASSWORD } });
  return result.trim();
}
function save(name, data) { fs.writeFileSync(path.join(directory, name), JSON.stringify(data, null, 2)); }
function snapshot() {
  return JSON.parse(sql(`SELECT json_build_object(
    'orders',(SELECT count(*) FROM orders),'requests',(SELECT count(*) FROM order_requests),
    'available',(SELECT sum(available_quantity) FROM inventories),
    'inventoryViolations',(SELECT count(*) FROM inventories i WHERE available_quantity<0 OR available_quantity>initial_quantity
      OR available_quantity<>initial_quantity-COALESCE((SELECT sum(quantity) FROM orders o WHERE o.product_id=i.product_id AND o.status='ACCEPTED'),0)
      OR available_quantity<>initial_quantity+COALESCE((SELECT sum(quantity_delta) FROM inventory_movements m WHERE m.product_id=i.product_id),0)),
    'requestViolations',(SELECT count(*) FROM order_requests r WHERE outcome IS NULL OR outcome<>'ACCEPTED'
      OR (SELECT count(*) FROM orders o WHERE o.request_id=r.id)<>1),
    'orderViolations',(SELECT count(*) FROM orders o JOIN order_requests r ON o.request_id=r.id WHERE o.product_id<>r.product_id
      OR o.buyer_id<>r.buyer_id OR o.quantity<>r.quantity OR (SELECT count(*) FROM inventory_movements m WHERE m.order_id=o.id AND m.kind='DEBIT')<>1
      OR EXISTS(SELECT 1 FROM inventory_movements m WHERE m.order_id=o.id AND (m.product_id<>o.product_id OR m.quantity_delta<>-o.quantity))))`));
}
async function startServer() {
  const port = await new Promise((resolve, reject) => {
    const socket = net.createServer(); socket.once('error', reject);
    socket.listen(0, '127.0.0.1', () => { const port = socket.address().port; socket.close(() => resolve(port)); });
  });
  const jars = fs.readdirSync(path.join(root, 'build', 'libs')).filter(name => name.endsWith('.jar') && !name.endsWith('-plain.jar'));
  if (jars.length !== 1) throw new Error('Build exactly one bootJar first');
  const log = fs.openSync(path.join(directory, 'server.log'), 'a');
  server = spawn('java', ['-jar', path.join(root, 'build', 'libs', jars[0]), '--spring.profiles.active=demo', `--server.port=${port}`,
    '--spring.main.banner-mode=off', '--stock.admission.per-customer-per-minute=100000',
    '--stock.admission.concurrent-writes=32', '--spring.datasource.hikari.maximum-pool-size=12'],
  { cwd: root, env, windowsHide: true, stdio: ['ignore', log, log] });
  fs.closeSync(log);
  env.STOCK_LOAD_URL = `http://127.0.0.1:${port}`;
  const authorization = 'Basic ' + Buffer.from('alice:' + env.STOCK_ALICE_PASSWORD).toString('base64');
  for (let attempt = 0; attempt < 150; attempt++) {
    if (server.exitCode !== null) throw new Error('Owned load server exited');
    try {
      const response = await fetch(env.STOCK_LOAD_URL + '/csrf', { headers: { Authorization: authorization }, signal: AbortSignal.timeout(2000) });
      if (response.status === 200) return;
    } catch (_) { /* 시작 확인에만 짧은 재조회 사용. 제품 코드의 대기 정책은 변경하지 않음. */ }
    await new Promise(resolve => setTimeout(resolve, 200));
  }
  throw new Error('Owned load server did not become ready');
}
async function main() {
  if (!fs.existsSync(k6)) throw new Error('Prepare the verified official k6 v2.3.0 binary in .tools/k6/v2.3.0');
  save('source-sha256.json', JSON.parse(command('pwsh', ['-NoProfile', '-Command', '& ./scripts/source-snapshot.ps1 | ConvertTo-Json -Depth 4'])));
  const initialized = database('init');
  Object.assign(env, JSON.parse(command(process.execPath, [path.join(__dirname, 'local-db.cjs'), 'env', 'test'])));
  save('environment.json', { k6: command(k6, ['version']).trim(), k6BinarySha256: crypto.createHash('sha256').update(fs.readFileSync(k6)).digest('hex'),
    officialArchiveSha256: '112276d495e5741c968e2bc09ea6196099c1275bd6db9ee0875d173c7148ce43',
    node: process.version, platform: os.platform(), cpu: os.cpus()[0].model, cpuCount: os.cpus().length, memoryBytes: os.totalmem(),
    postgres: initialized.version, java: command('java', ['--version']).trim(), instance,
    concurrentWrites: 32, poolSize: 12, requestsPerCustomerPerMinute: 100000, warmupSeconds: 10, measurementSeconds: 30,
    durationScope: 'Custom measured_* metrics exclude warmup and CSRF. Same-PC HTTP including Basic authentication, not production capacity.' });
  await startServer();
  for (const workload of (smoke ? ['same-product'] : ['same-product', 'many-products'])) {
    for (const vus of (smoke ? [1] : [1, 8, 32, 64])) {
      for (let repetition = 1; repetition <= (smoke ? 1 : 3); repetition++) {
        const label = `${workload}-${vus}-${repetition}`;
        sql(`TRUNCATE inventory_movements,orders,order_requests,inventories,products RESTART IDENTITY CASCADE;
          INSERT INTO products(name) SELECT 'Load product '||n FROM generate_series(1,${workload === 'same-product' ? 1 : 100}) n;
          INSERT INTO inventories SELECT id,${workload === 'same-product' ? 1000000 : 10000},${workload === 'same-product' ? 1000000 : 10000} FROM products;`);
        const products = sql('SELECT json_agg(id ORDER BY id) FROM products');
        const summary = path.join(directory, label + '-summary.json');
        const stdout = fs.openSync(path.join(directory, label + '.log'), 'w');
        const stderr = fs.openSync(path.join(directory, label + '-stderr.log'), 'w');
        try { command(k6, ['run', '--quiet', ...(smoke ? ['--duration', '2s'] : []), '--out', `json=${path.join(directory, label + '-metrics.json.gz')}`, path.join(__dirname, 'load-orders.k6.js')], {
          env: { ...env, STOCK_LOAD_CASE: label, STOCK_LOAD_VUS: String(vus), STOCK_LOAD_PRODUCTS: products, STOCK_LOAD_SUMMARY: summary },
          stdio: ['ignore', stdout, stderr],
        }); } finally { fs.closeSync(stdout); fs.closeSync(stderr); }
        const data = JSON.parse(fs.readFileSync(summary, 'utf8'));
        const db = snapshot();
        const createdAll = data.metrics.purchase_created_all?.values.count || 0;
        const result = { workload, vus, repetition, createdAll, database: db, metrics: Object.fromEntries(
          Object.entries(data.metrics).filter(([key]) => key.startsWith('measured_') || key === 'unexpected_responses')) };
        observations.push(result); save('load-results.json', observations);
        if (db.orders !== createdAll || db.requests !== createdAll || db.inventoryViolations || db.requestViolations || db.orderViolations) {
          throw new Error('HTTP and database observations differ: ' + label);
        }
        console.log(JSON.stringify({ completed: observations.length, label, createdAll, dbOrders: db.orders, violations: 0 }));
      }
    }
  }
  save('query-plans.json', {
    purchaseKey: JSON.parse(sql("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) SELECT * FROM order_requests WHERE buyer_id=1 AND request_key='missing-review-key'")),
    orderPage: JSON.parse(sql('EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) SELECT id,product_id,quantity,status FROM orders WHERE buyer_id=1 AND id<9223372036854775807 ORDER BY id DESC LIMIT 20')),
  });
  save('summary.json', { completed: true, runs: observations.length, junit: false, samePc: true, smoke });
}
main().catch(error => { save('failure.json', { error: error.message, completedRuns: observations.length }); console.error(error.message); process.exitCode = 1; })
  .finally(async () => {
    if (server && server.exitCode === null) {
      const exited = new Promise(resolve => server.once('exit', resolve)); server.kill(); await exited;
    }
    try { const stopped = database('stop'); save('cleanup.json', { ...stopped, instance, serverStopped: !server || server.exitCode !== null || server.signalCode !== null, databaseRunning: database('status').running }); }
    catch (error) { console.error(error.message); process.exitCode = 1; }
    console.log('Load evidence: ' + directory);
  });

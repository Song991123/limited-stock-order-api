const fs = require('node:fs');
const path = require('node:path');
const net = require('node:net');
const os = require('node:os');
const crypto = require('node:crypto');
const { spawnSync } = require('node:child_process');
const root = path.resolve(__dirname, '..');
const instance = process.env.STOCK_DB_TEST_INSTANCE || '';
if (instance && !/^isolated-[a-f0-9]{16}$/.test(instance)) throw new Error('Invalid isolated test instance');
const runtime = path.join(root, '.runtime', instance);
const baseWorkspace = path.join(os.tmpdir(), 'stock-order-mini-' + crypto.createHash('sha256').update(root).digest('hex').slice(0, 12));
const workspace = instance ? path.join(os.tmpdir(), 'stock-order-mini-' + crypto.createHash('sha256').update(root + '#' + instance).digest('hex').slice(0, 12)) : baseWorkspace;
const installation = path.join(baseWorkspace, 'pgsql');
const data = path.join(workspace, 'data');
const manifest = path.join(runtime, 'database.json');
const source = process.env.STOCK_PG_SOURCE || path.join(root, '.tools', 'pgsql');
const read = () => JSON.parse(fs.readFileSync(manifest, 'utf8'));
const write = value => fs.writeFileSync(manifest, JSON.stringify(value, null, 2));

function run(name, args, input, env = {}) {
  // The detached PostgreSQL server must not inherit captured pipes from pg_ctl.
  const controlLog = name === 'pg_ctl' ? fs.openSync(path.join(workspace, 'control.log'), 'a') : null;
  const result = spawnSync(path.join(installation, 'bin', name + '.exe'), args, {
    cwd: runtime, input, encoding: 'utf8', timeout: 90000,
    windowsHide: true, env: { ...process.env, ...env }, maxBuffer: 4 * 1024 * 1024,
    ...(controlLog === null ? {} : { stdio: ['ignore', controlLog, controlLog] }),
  });
  if (controlLog !== null) fs.closeSync(controlLog);
  if (result.error || result.status !== 0) throw new Error(name + ': ' + (result.stderr || result.error));
  return result.stdout || '';
}

function verifyOwnership(meta) {
  if (meta.project !== root || meta.workspace !== workspace || path.resolve(meta.data) !== data) throw new Error('Unexpected database workspace');
  const pidFile = path.join(data, 'postmaster.pid');
  if (fs.existsSync(pidFile)) {
    const lines = fs.readFileSync(pidFile, 'utf8').split(/\r?\n/);
    if (path.resolve(lines[1]).toLowerCase() !== data.toLowerCase() || Number(lines[3]) !== meta.port) {
      throw new Error('Database process is not owned by this workspace');
    }
  }
}

function isRunning() {
  if (!fs.existsSync(manifest)) return false;
  verifyOwnership(read());
  const result = spawnSync(path.join(installation, 'bin', 'pg_ctl.exe'), ['-D', data, 'status'], {
    encoding: 'utf8', timeout: 10000, windowsHide: true,
  });
  if (result.error) throw result.error;
  if (result.status === 0) return true;
  if (result.status === 3) return false;
  throw new Error('Could not determine the dedicated database status');
}

function start() {
  const meta = read();
  verifyOwnership(meta);
  if (isRunning()) return false;
  run('pg_ctl', ['-D', data, '-l', path.join(runtime, 'postgres.log'), '-w', '-t', '30',
    '-o', `-h 127.0.0.1 -p ${meta.port} -c max_connections=30 -c shared_buffers=32MB -c fsync=on -c synchronous_commit=on`, 'start']);
  verifyOwnership(meta);
  return true;
}

function stop() {
  if (!fs.existsSync(manifest)) return false;
  const meta = read();
  verifyOwnership(meta);
  if (!isRunning()) return false;
  run('pg_ctl', ['-D', data, '-m', 'fast', '-w', '-t', '30', 'stop']);
  return true;
}

function sql(meta, database, statement) {
  return run('psql', ['-X', '-w', '-A', '-t', '-h', '127.0.0.1', '-p', String(meta.port), '-U', 'stock_mini_admin',
    '-d', database, '-v', 'ON_ERROR_STOP=1'], statement, { PGPASSWORD: meta.adminPassword });
}

function createDatabases(meta) {
  if (sql(meta, 'postgres', "SELECT count(*) FROM pg_roles WHERE rolname='stock_order_app';").trim() === '0') {
    sql(meta, 'postgres', `CREATE ROLE stock_order_app LOGIN PASSWORD '${meta.appPassword}';`);
  }
  for (const name of ['stock_order_test', 'stock_order_demo']) {
    if (sql(meta, 'postgres', `SELECT count(*) FROM pg_database WHERE datname='${name}';`).trim() === '0') {
      sql(meta, 'postgres', `CREATE DATABASE ${name} OWNER stock_order_app;`);
    }
  }
  meta.databasesCreated = true;
  meta.version = run('postgres', ['--version']).trim();
  write(meta);
}

async function init() {
  fs.mkdirSync(runtime, { recursive: true });
  fs.mkdirSync(workspace, { recursive: true });
  if (fs.existsSync(manifest)) {
    const started = start();
    const meta = read();
    if (!meta.databasesCreated) createDatabases(meta);
    console.log(JSON.stringify({ initialized: true, started, port: read().port }));
    return;
  }
  if (fs.existsSync(data)) throw new Error('Untracked database directory exists; it was not overwritten');
  if (!fs.existsSync(path.join(installation, 'bin', 'postgres.exe'))) {
    const directories = ['bin', 'lib', 'share'];
    if (!source || !fs.existsSync(path.join(source, 'bin', 'postgres.exe')) ||
        directories.some(name => !fs.existsSync(path.join(source, name)))) {
      throw new Error('Set STOCK_PG_SOURCE to a PostgreSQL 16 distribution containing bin, lib and share; see README.md');
    }
    // Use the Windows copy provider; Node 24's native recursive copy can terminate this process.
    fs.mkdirSync(installation, { recursive: true });
    const copied = spawnSync('pwsh.exe', ['-NoProfile', '-NonInteractive', '-Command',
      '$ErrorActionPreference="Stop"; foreach ($name in @("bin","lib","share")) { Copy-Item -LiteralPath (Join-Path $env:STOCK_COPY_SOURCE $name) -Destination $env:STOCK_COPY_TARGET -Recurse -Force }'], {
      windowsHide: true, encoding: 'utf8', timeout: 180000,
      env: { ...process.env, STOCK_COPY_SOURCE: source, STOCK_COPY_TARGET: installation },
    });
    if (copied.error || copied.status !== 0) throw new Error('PostgreSQL executable copy failed: ' + (copied.stderr || copied.error));
  }
  const port = await new Promise((resolve, reject) => {
    const server = net.createServer();
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => {
      const value = server.address().port;
      server.close(error => error ? reject(error) : resolve(value));
    });
  });
  const secret = () => crypto.randomBytes(24).toString('hex');
  const meta = { project: root, workspace, data, port, adminPassword: secret(), appPassword: secret(),
    alicePassword: secret(), bobPassword: secret(), databasesCreated: false };
  const passwordFile = path.join(runtime, 'init-password.txt');
  fs.writeFileSync(passwordFile, meta.adminPassword + '\n');
  run('initdb', ['-D', data, '-U', 'stock_mini_admin', '--pwfile=' + passwordFile, '--auth=scram-sha-256',
    '--encoding=UTF8', '--no-locale', '--data-checksums']);
  write(meta);
  start();
  try {
    createDatabases(meta);
    console.log(JSON.stringify({ initialized: true, started: true, port, version: meta.version }));
  } catch (error) {
    stop();
    throw error;
  }
}

async function main() {
  const action = process.argv[2];
  if (action === 'init') return init();
  if (action === 'start') return console.log(JSON.stringify({ started: start(), port: read().port }));
  if (action === 'stop') return console.log(JSON.stringify({ stopped: stop() }));
  if (action === 'status') return console.log(JSON.stringify({ running: isRunning() }));
  if (action === 'env') {
    const mode = process.argv[3];
    if (!['test', 'demo'].includes(mode)) throw new Error('Choose test or demo');
    const meta = read();
    verifyOwnership(meta);
    if (!meta.databasesCreated) throw new Error('Database initialization did not finish');
    return console.log(JSON.stringify({ STOCK_DB_URL: `jdbc:postgresql://127.0.0.1:${meta.port}/stock_order_${mode}`,
      STOCK_DB_USER: 'stock_order_app', STOCK_DB_PASSWORD: meta.appPassword,
      STOCK_PG_TEST_BIN: path.join(installation, 'bin'),
      STOCK_ALICE_PASSWORD: meta.alicePassword, STOCK_BOB_PASSWORD: meta.bobPassword }));
  }
  throw new Error('Usage: node scripts/local-db.cjs init|start|stop|status|env test|env demo');
}
main().catch(error => { console.error(error.message); process.exitCode = 1; });

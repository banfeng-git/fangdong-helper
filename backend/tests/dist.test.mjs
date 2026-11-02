/**
 * 三平台“可部署产物”端到端自测：
 * 直接 import backend/<platform>/dist/index.js，模拟各平台运行时，
 * 走一遍 注册 → 登录 → 上传 → 拉取 的真实流程。
 *
 *   node backend/tests/dist.test.mjs
 */
import { DatabaseSync } from 'node:sqlite';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '..', '..');

let pass = 0, fail = 0;
const check = (name, cond, extra) => {
  if (cond) { pass++; console.log('PASS ' + name); }
  else { fail++; console.log('FAIL ' + name + (extra ? '  → ' + extra : '')); }
};

function fakeD1() {
  const db = new DatabaseSync(':memory:');
  db.exec(`
    CREATE TABLE users (id TEXT PRIMARY KEY, username TEXT, username_lower TEXT UNIQUE, password TEXT, created_at INTEGER);
    CREATE TABLE sessions (token TEXT PRIMARY KEY, user_id TEXT, expires_at INTEGER);
    CREATE TABLE bills (user_id TEXT, uid TEXT, payload TEXT, updated_at INTEGER, PRIMARY KEY (user_id, uid));
  `);
  return {
    prepare: (sql) => ({
      bind: (...args) => ({
        first: async () => db.prepare(sql).get(...args) || null,
        all: async () => ({ results: db.prepare(sql).all(...args) }),
        run: async () => ({ success: true, meta: db.prepare(sql).run(...args) })
      })
    })
  };
}

function fakeKv() {
  const m = new Map();
  return {
    get: async (k) => (m.has(k) ? m.get(k) : null),
    put: async (k, v) => { m.set(k, String(v)); },
    delete: async (k) => { m.delete(k); }
  };
}

async function flow(label, invoke) {
  const name = 'user_' + Math.random().toString(36).slice(2, 8);
  const register = await invoke('/api/register', { method: 'POST', body: { username: name, password: 'abc12345' } });
  check(label + ' 注册', register.code === 0 && !!register.data.token, JSON.stringify(register));

  const login = await invoke('/api/login', { method: 'POST', body: { username: name, password: 'abc12345' } });
  check(label + ' 登录', login.code === 0 && !!login.data.token);

  const token = login.data.token;
  const bill = {
    uid: 'b1', room: '526', month: '2026-11',
    waterMeters: [{ label: '水表1', start: 0, end: 65, usage: 65, fee: 195 }],
    gasMeters: [], meters: [{ label: '电', start: 800, end: 858, usage: 58, fee: 58 }],
    extras: [{ name: '卫生费', usageBased: false, usage: 0, amount: 10 }],
    waterFee: 195, gasFee: 0, elecFee: 58, updatedAt: 1700000000000
  };
  const up = await invoke('/api/bills/sync', { method: 'POST', token, body: { bills: [bill] } });
  check(label + ' 上传并返回云端全量', up.code === 0 && up.data.bills.length === 1, JSON.stringify(up));

  const list = await invoke('/api/bills', { token });
  check(label + ' 拉取', list.code === 0 && list.data.bills[0].room === '526');

  const del = await invoke('/api/bills/delete', { method: 'POST', token, body: { uid: 'b1' } });
  const after = await invoke('/api/bills', { token });
  check(label + ' 删除', del.code === 0 && after.data.bills.length === 0);

  const unauth = await invoke('/api/bills');
  check(label + ' 无 token 拒绝', unauth.code === 401);
}

function reqFactory(base) {
  return (pathname, opts = {}) => new Request(base + pathname, {
    method: opts.method || 'GET',
    headers: Object.assign(
      { 'Content-Type': 'application/json' },
      opts.token ? { Authorization: 'Bearer ' + opts.token } : {}
    ),
    body: opts.body ? JSON.stringify(opts.body) : undefined
  });
}

async function readJson(res) {
  const text = await res.text();
  try { return JSON.parse(text); } catch (e) { return { code: -1, msg: text }; }
}

/* ---------- Cloudflare Workers ---------- */
{
  const mod = await import(path.join(root, 'backend/cf-workers/dist/index.js'));
  const env = { DB: fakeD1(), CORS_ORIGIN: '*' };
  const mk = reqFactory('https://fd.example.workers.dev');
  const invoke = async (p, o) => readJson(await mod.default.fetch(mk(p, o), env));
  await flow('CF', invoke);

  const health = await invoke('/api/health');
  check('CF /health 平台标识', health.data.platform === 'cloudflare', JSON.stringify(health));
}

/* ---------- 腾讯 EdgeOne ---------- */
{
  globalThis.my_kv = fakeKv();
  const mod = await import(path.join(root, 'backend/tencent-edgeone/dist/index.js'));
  const mk = reqFactory('https://fd.example.edgeone.app');
  const invoke = async (p, o) => readJson(await mod.onRequest({ request: mk(p, o), env: {}, params: {} }));
  await flow('EdgeOne', invoke);

  const health = await invoke('/api/health');
  check('EdgeOne /health 平台标识', health.data.platform === 'edgeone', JSON.stringify(health));
}

/* ---------- 阿里云 ESA ---------- */
{
  const store = fakeKv();
  globalThis.EdgeKV = class {
    constructor(opts) { this.opts = opts; }
    async get(k, o) {
      const v = store.get ? await store.get(k) : null;
      if (v === null) return undefined;
      return (o && o.type === 'json') ? JSON.parse(v) : v;
    }
    async put(k, v) { await store.put(k, v); }
    async delete(k) { await store.delete(k); return true; }
  };
  const mod = await import(path.join(root, 'backend/aliyun-esa-er/dist/index.js'));
  const mk = reqFactory('https://fd.example.esa');
  const invoke = async (p, o) => readJson(await mod.default.fetch(mk(p, o)));
  await flow('ESA', invoke);

  const health = await invoke('/api/health');
  check('ESA /health 平台标识', health.data.platform === 'aliyun-esa', JSON.stringify(health));
}

console.log('\n' + pass + ' passed, ' + fail + ' failed');
if (fail > 0) process.exit(1);
console.log('ALL PASS（三平台部署产物均可直接跑通注册/登录/同步）');

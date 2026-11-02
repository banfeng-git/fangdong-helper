/**
 * 房东小助手 · 云同步后端（阿里云 ESA 边缘函数（Edge Routine）+ EdgeKV）
 * 本文件由 backend/scripts/build.mjs 自动生成，请勿手改；
 * 修改逻辑请改 backend/shared/core.js 后重新执行 npm run build。
 */
/**
 * 房东小助手 · 云同步后端核心（平台无关）
 *
 * 同一份实现同时用于：
 *   1) Cloudflare Workers + D1（SQLite）
 *   2) 腾讯 EdgeOne Makers 边缘函数 + EdgeOne KV
 *   3) 阿里云 ESA 边缘函数（Edge Routine）+ EdgeKV
 *
 * 三平台对外 API 完全一致，安卓 / PC 客户端只需改服务端地址即可切换。
 * 不依赖 Node.js、不依赖 PHP、不依赖 MySQL。
 *
 * 密码哈希：纯 JS 实现的 PBKDF2-HMAC-SHA256，三平台行为完全一致，
 *          不依赖各平台对 crypto.subtle 的支持差异。
 */

/* ============================ SHA-256 / HMAC / PBKDF2 ============================ */

const SHA256_K = new Uint32Array([
  0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
  0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
  0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
  0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
  0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
  0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
  0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
  0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
]);

function rotr(x, n) {
  return ((x >>> n) | (x << (32 - n))) >>> 0;
}

function sha256Bytes(data) {
  const H = new Uint32Array([
    0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
    0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19
  ]);
  const bitLen = data.length * 8;
  const totalLen = (((data.length + 9) + 63) >> 6) << 6;
  const msg = new Uint8Array(totalLen);
  msg.set(data);
  msg[data.length] = 0x80;
  const dv = new DataView(msg.buffer);
  dv.setUint32(totalLen - 8, Math.floor(bitLen / 4294967296));
  dv.setUint32(totalLen - 4, bitLen >>> 0);

  const w = new Uint32Array(64);
  for (let off = 0; off < totalLen; off += 64) {
    for (let i = 0; i < 16; i++) w[i] = dv.getUint32(off + i * 4);
    for (let i = 16; i < 64; i++) {
      const s0 = rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^ (w[i - 15] >>> 3);
      const s1 = rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^ (w[i - 2] >>> 10);
      w[i] = (w[i - 16] + s0 + w[i - 7] + s1) >>> 0;
    }
    let a = H[0], b = H[1], c = H[2], d = H[3], e = H[4], f = H[5], g = H[6], h = H[7];
    for (let i = 0; i < 64; i++) {
      const S1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25);
      const ch = (e & f) ^ (~e & g);
      const t1 = (h + S1 + ch + SHA256_K[i] + w[i]) >>> 0;
      const S0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22);
      const maj = (a & b) ^ (a & c) ^ (b & c);
      const t2 = (S0 + maj) >>> 0;
      h = g; g = f; f = e; e = (d + t1) >>> 0;
      d = c; c = b; b = a; a = (t1 + t2) >>> 0;
    }
    H[0] = (H[0] + a) >>> 0; H[1] = (H[1] + b) >>> 0;
    H[2] = (H[2] + c) >>> 0; H[3] = (H[3] + d) >>> 0;
    H[4] = (H[4] + e) >>> 0; H[5] = (H[5] + f) >>> 0;
    H[6] = (H[6] + g) >>> 0; H[7] = (H[7] + h) >>> 0;
  }
  const out = new Uint8Array(32);
  const ov = new DataView(out.buffer);
  for (let i = 0; i < 8; i++) ov.setUint32(i * 4, H[i]);
  return out;
}

function hmacSha256(keyBytes, messageBytes) {
  const block = new Uint8Array(64);
  block.set(keyBytes.length > 64 ? sha256Bytes(keyBytes) : keyBytes);
  const ipad = new Uint8Array(64), opad = new Uint8Array(64);
  for (let i = 0; i < 64; i++) {
    ipad[i] = block[i] ^ 0x36;
    opad[i] = block[i] ^ 0x5c;
  }
  const inner = new Uint8Array(64 + messageBytes.length);
  inner.set(ipad); inner.set(messageBytes, 64);
  const h1 = sha256Bytes(inner);
  const outer = new Uint8Array(96);
  outer.set(opad); outer.set(h1, 64);
  return sha256Bytes(outer);
}

function pbkdf2Sha256(passwordBytes, saltBytes, iterations, dkLen) {
  const dk = new Uint8Array(dkLen || 32);
  let offset = 0, blockIndex = 1;
  while (offset < dk.length) {
    const saltBlock = new Uint8Array(saltBytes.length + 4);
    saltBlock.set(saltBytes);
    new DataView(saltBlock.buffer).setUint32(saltBytes.length, blockIndex);
    let U = hmacSha256(passwordBytes, saltBlock);
    const T = new Uint8Array(U);
    for (let i = 1; i < iterations; i++) {
      U = hmacSha256(passwordBytes, U);
      for (let j = 0; j < 32; j++) T[j] ^= U[j];
    }
    const need = Math.min(32, dk.length - offset);
    dk.set(T.subarray(0, need), offset);
    offset += need;
    blockIndex++;
  }
  return dk;
}

/* ============================ 编码工具 ============================ */

function utf8Bytes(str) {
  return new TextEncoder().encode(str);
}

function bytesToHex(bytes) {
  let s = '';
  for (let i = 0; i < bytes.length; i++) s += (bytes[i] < 16 ? '0' : '') + bytes[i].toString(16);
  return s;
}

function bytesToB64(bytes) {
  let bin = '';
  for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
  if (typeof btoa === 'function') return btoa(bin);
  let out = '';
  for (let i = 0; i < bin.length; i++) {
    const c = bin.charCodeAt(i);
    out += 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/'.charAt(c >> 2 & 63)
      + 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/'.charAt(c >> 4 & 63)
      + 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/'.charAt(c >> 6 & 63)
      + 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/'.charAt(c & 63);
  }
  return out;
}

function b64ToBytes(b64) {
  if (typeof atob === 'function') {
    const bin = atob(b64);
    const out = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
  }
  const table = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  const clean = b64.replace(/=+/g, '');
  const out = new Uint8Array(Math.floor(clean.length * 3 / 4));
  let o = 0, buf = 0, bits = 0;
  for (let i = 0; i < clean.length; i++) {
    buf = (buf << 6) | table.indexOf(clean[i]);
    bits += 6;
    if (bits >= 8) {
      bits -= 8;
      out[o++] = (buf >> bits) & 0xFF;
    }
  }
  return out.subarray(0, o);
}

function randomBytes(n) {
  const b = new Uint8Array(n);
  if (typeof crypto !== 'undefined' && crypto.getRandomValues) {
    crypto.getRandomValues(b);
    return b;
  }
  for (let i = 0; i < n; i++) b[i] = Math.floor(Math.random() * 256);
  return b;
}

/* ============================ 密码 ============================ */

const PBKDF2_ITERATIONS = 2048;

function hashPassword(password) {
  const salt = randomBytes(16);
  const dk = pbkdf2Sha256(utf8Bytes(password), salt, PBKDF2_ITERATIONS, 32);
  return 'pbkdf2$' + PBKDF2_ITERATIONS + '$' + bytesToB64(salt) + '$' + bytesToB64(dk);
}

function verifyPassword(password, stored) {
  try {
    const parts = String(stored || '').split('$');
    if (parts.length !== 4 || parts[0] !== 'pbkdf2') return false;
    const iterations = parseInt(parts[1], 10) || PBKDF2_ITERATIONS;
    const salt = b64ToBytes(parts[2]);
    const expect = b64ToBytes(parts[3]);
    const actual = pbkdf2Sha256(utf8Bytes(password), salt, iterations, expect.length);
    if (actual.length !== expect.length) return false;
    let diff = 0;
    for (let i = 0; i < actual.length; i++) diff |= actual[i] ^ expect[i];
    return diff === 0;
  } catch (e) {
    return false;
  }
}

/* ============================ KV 存储适配 ============================ */

/**
 * kv 适配器接口（三平台各自实现，见 dist 里的 glue）：
 *   getText(key)  -> string | null
 *   putText(key, value) -> Promise
 *   deleteKey(key) -> Promise
 */
function createKvStore(kv) {
  const K_USER = (name) => 'u_' + name;
  const K_SESSION = (token) => 's_' + token;
  const K_BILL = (userId, uid) => 'b_' + userId + '_' + uid;
  const K_INDEX = (userId) => 'idx_' + userId;

  async function readJson(key, fallback) {
    const raw = await kv.getText(key);
    if (!raw) return fallback;
    try {
      return JSON.parse(raw);
    } catch (e) {
      return fallback;
    }
  }

  return {
    async createUser(username, usernameLower, passwordHash) {
      const exists = await readJson(K_USER(usernameLower), null);
      if (exists) return null;
      const user = {
        id: bytesToHex(randomBytes(8)),
        username,
        password: passwordHash,
        created_at: Date.now()
      };
      await kv.putText(K_USER(usernameLower), JSON.stringify(user));
      // 顺手写 id -> username 的反查，方便日志排查
      await kv.putText('uid_' + user.id, usernameLower);
      return user;
    },

    async getUser(usernameLower) {
      return await readJson(K_USER(usernameLower), null);
    },

    async saveSession(token, userId, expiresAt) {
      await kv.putText(K_SESSION(token), JSON.stringify({ user_id: userId, exp: expiresAt }));
    },

    async getSession(token) {
      const s = await readJson(K_SESSION(token), null);
      if (!s) return null;
      if (!s.exp || s.exp < Date.now()) {
        await kv.deleteKey(K_SESSION(token));
        return null;
      }
      return s;
    },

    async deleteSession(token) {
      await kv.deleteKey(K_SESSION(token));
    },

    async putBill(userId, bill) {
      const uid = bill.uid;
      const existing = await readJson(K_BILL(userId, uid), null);
      if (existing && (existing.updatedAt || 0) > (bill.updatedAt || 0)) return;
      await kv.putText(K_BILL(userId, uid), JSON.stringify(bill));
      const index = await readJson(K_INDEX(userId), []);
      if (index.indexOf(uid) < 0) {
        index.push(uid);
        await kv.putText(K_INDEX(userId), JSON.stringify(index));
      }
    },

    async getBills(userId) {
      const index = await readJson(K_INDEX(userId), []);
      const bills = [];
      for (let i = 0; i < index.length; i++) {
        const b = await readJson(K_BILL(userId, index[i]), null);
        if (b) bills.push(b);
      }
      return bills;
    },

    async deleteBill(userId, uid) {
      await kv.deleteKey(K_BILL(userId, uid));
      const index = await readJson(K_INDEX(userId), []);
      const next = index.filter((x) => x !== uid);
      await kv.putText(K_INDEX(userId), JSON.stringify(next));
    }
  };
}

/* ============================ D1（SQL）存储适配 ============================ */

function createD1Store(db) {
  return {
    async createUser(username, usernameLower, passwordHash) {
      const existing = await this.getUser(usernameLower);
      if (existing) return null;
      const user = {
        id: bytesToHex(randomBytes(8)),
        username,
        password: passwordHash,
        created_at: Date.now()
      };
      await db.prepare(
        'INSERT INTO users (id, username, username_lower, password, created_at) VALUES (?, ?, ?, ?, ?)'
      ).bind(user.id, username, usernameLower, passwordHash, user.created_at).run();
      return user;
    },

    async getUser(usernameLower) {
      const row = await db.prepare(
        'SELECT id, username, password, created_at FROM users WHERE username_lower = ?'
      ).bind(usernameLower).first();
      if (!row) return null;
      return { id: row.id, username: row.username, password: row.password, created_at: row.created_at };
    },

    async saveSession(token, userId, expiresAt) {
      await db.prepare(
        'INSERT INTO sessions (token, user_id, expires_at) VALUES (?, ?, ?)'
      ).bind(token, userId, expiresAt).run();
    },

    async getSession(token) {
      const row = await db.prepare(
        'SELECT user_id, expires_at FROM sessions WHERE token = ?'
      ).bind(token).first();
      if (!row) return null;
      if (!row.expires_at || row.expires_at < Date.now()) {
        await db.prepare('DELETE FROM sessions WHERE token = ?').bind(token).run();
        return null;
      }
      return { user_id: row.user_id, exp: row.expires_at };
    },

    async deleteSession(token) {
      await db.prepare('DELETE FROM sessions WHERE token = ?').bind(token).run();
    },

    async putBill(userId, bill) {
      await db.prepare(
        'INSERT INTO bills (user_id, uid, payload, updated_at) VALUES (?, ?, ?, ?) ' +
        'ON CONFLICT(user_id, uid) DO UPDATE SET payload = excluded.payload, updated_at = excluded.updated_at ' +
        'WHERE excluded.updated_at >= bills.updated_at'
      ).bind(userId, bill.uid, JSON.stringify(bill), bill.updatedAt || 0).run();
    },

    async getBills(userId) {
      const res = await db.prepare(
        'SELECT payload FROM bills WHERE user_id = ? ORDER BY updated_at DESC'
      ).bind(userId).all();
      const rows = (res && res.results) || [];
      const bills = [];
      for (let i = 0; i < rows.length; i++) {
        try {
          bills.push(JSON.parse(rows[i].payload));
        } catch (e) { /* 跳过损坏记录 */ }
      }
      return bills;
    },

    async deleteBill(userId, uid) {
      await db.prepare('DELETE FROM bills WHERE user_id = ? AND uid = ?').bind(userId, uid).run();
    }
  };
}

/* ============================ 账单规范化 ============================ */

const MAX_BILLS_PER_REQUEST = 500;

function num(v, def) {
  const n = typeof v === 'number' ? v : parseFloat(v);
  return isFinite(n) ? Math.round(n * 100) / 100 : (def || 0);
}

function str(v, maxLen) {
  if (v === null || v === undefined) return '';
  return String(v).slice(0, maxLen || 120);
}

function normalizeMeter(m) {
  return {
    label: str(m && m.label, 40),
    start: num(m && m.start, 0),
    end: num(m && m.end, 0),
    usage: num(m && m.usage, 0),
    fee: num(m && m.fee, 0)
  };
}

function normalizeBill(raw, userUid) {
  const src = raw && typeof raw === 'object' ? raw : {};
  const uid = src.uid && String(src.uid).length <= 64 ? String(src.uid) : (userUid || newUid());
  const meters = (arr, max) => {
    const list = Array.isArray(arr) ? arr.slice(0, max) : [];
    return list.map(normalizeMeter);
  };
  const extras = (Array.isArray(src.extras) ? src.extras.slice(0, 40) : [])
    .filter((e) => e && typeof e === 'object')
    .map((e) => ({
    name: str(e && e.name, 40),
    usageBased: !!(e && e.usageBased),
    usage: num(e && e.usage, 0),
    amount: num(e && e.amount, 0)
  }));
  return {
    uid: uid,
    room: str(src.room, 60),
    tenant: str(src.tenant, 60),
    month: str(src.month, 20),
    note: str(src.note, 200),
    waterMeters: meters(src.waterMeters, 8),
    gasMeters: meters(src.gasMeters, 8),
    meters: meters(src.meters, 8),
    waterFee: num(src.waterFee, 0),
    gasFee: num(src.gasFee, 0),
    elecFee: num(src.elecFee, 0),
    extras: extras,
    updatedAt: Math.round(num(src.updatedAt, Date.now())) || Date.now()
  };
}

function newUid() {
  return bytesToHex(randomBytes(12));
}

/* ============================ HTTP 路由 ============================ */

function jsonResponse(body, status, corsOrigin) {
  return new Response(JSON.stringify(body), {
    status: status || 200,
    headers: {
      'Content-Type': 'application/json; charset=utf-8',
      'Access-Control-Allow-Origin': corsOrigin || '*',
      'Access-Control-Allow-Headers': 'Content-Type, Authorization',
      'Access-Control-Allow-Methods': 'GET, POST, DELETE, OPTIONS',
      'Cache-Control': 'no-store'
    }
  });
}

function ok(data, corsOrigin) {
  return jsonResponse({ code: 0, msg: 'ok', data: data || {} }, 200, corsOrigin);
}

function fail(code, msg, corsOrigin, status) {
  return jsonResponse({ code: code, msg: msg }, status || 200, corsOrigin);
}

async function readBodyText(request) {
  const text = await request.text();
  if (text && text.length > 2 * 1024 * 1024) throw new Error('body_too_large');
  return text;
}

function parseJson(text) {
  if (!text) return {};
  try {
    const v = JSON.parse(text);
    return v && typeof v === 'object' ? v : {};
  } catch (e) {
    return {};
  }
}

function bearerToken(request) {
  const h = request.headers.get('authorization') || request.headers.get('Authorization') || '';
  const m = /^Bearer\s+(.+)$/i.exec(h.trim());
  return m ? m[1].trim() : '';
}

function nowMs() {
  return Date.now();
}

const TOKEN_TTL_MS = 30 * 24 * 3600 * 1000;

/**
 * 统一入口
 * @param {{store:object, platform:string, corsOrigin?:string}} options
 * @returns {(request: Request) => Promise<Response>}
 */
function createHandler(options) {
  const store = options.store;
  const platform = options.platform || 'unknown';
  const corsOrigin = options.corsOrigin || '*';

  return async function handle(request) {
    const method = (request.method || 'GET').toUpperCase();
    if (method === 'OPTIONS') {
      return jsonResponse({ code: 0 }, 200, corsOrigin);
    }

    let path = '';
    try {
      path = new URL(request.url).pathname || '/';
    } catch (e) {
      path = '/';
    }
    path = path.replace(/\/+$/, '');

    const last = path.split('/').pop();
    const prev = path.split('/').slice(-2, -1)[0];

    try {
      /* ---------- 健康检查 ---------- */
      if (last === 'health' || last === '') {
        return ok({ platform: platform, time: nowMs(), version: '2.10' }, corsOrigin);
      }

      /* ---------- 注册 ---------- */
      if (last === 'register' && method === 'POST') {
        const body = parseJson(await readBodyText(request));
        const username = str(body.username, 32).trim();
        const password = str(body.password, 128);
        if (username.length < 2) return fail(400, '账号至少 2 个字符', corsOrigin);
        if (password.length < 6) return fail(400, '密码至少 6 位', corsOrigin);
        const lower = username.toLowerCase();
        const user = await store.createUser(username, lower, hashPassword(password));
        if (!user) return fail(409, '该账号已被注册', corsOrigin, 409);
        const token = bytesToHex(randomBytes(16));
        await store.saveSession(token, user.id, nowMs() + TOKEN_TTL_MS);
        return ok({ user_id: user.id, username: user.username, token: token }, corsOrigin);
      }

      /* ---------- 登录 ---------- */
      if (last === 'login' && method === 'POST') {
        const body = parseJson(await readBodyText(request));
        const username = str(body.username, 32).trim();
        const password = str(body.password, 128);
        if (!username || !password) return fail(400, '请输入账号和密码', corsOrigin);
        const user = await store.getUser(username.toLowerCase());
        if (!user || !verifyPassword(password, user.password)) {
          return fail(401, '账号或密码错误', corsOrigin);
        }
        const token = bytesToHex(randomBytes(16));
        await store.saveSession(token, user.id, nowMs() + TOKEN_TTL_MS);
        return ok({ user_id: user.id, username: user.username, token: token }, corsOrigin);
      }

      /* ---------- 以下接口需要登录 ---------- */
      const token = bearerToken(request);
      if (!token) return fail(401, '未登录或登录已过期', corsOrigin);
      const session = await store.getSession(token);
      if (!session) return fail(401, '未登录或登录已过期', corsOrigin);
      const userId = session.user_id;

      if (last === 'logout' && method === 'POST') {
        await store.deleteSession(token);
        return ok({}, corsOrigin);
      }

      if (last === 'bills' && method === 'GET') {
        const bills = await store.getBills(userId);
        return ok({ bills: bills, count: bills.length }, corsOrigin);
      }

      /* 上传（单条或多条批量），返回合并后的云端全量账单，客户端一次往返即可完成同步 */
      if (last === 'sync' && method === 'POST') {
        const body = parseJson(await readBodyText(request));
        const incoming = Array.isArray(body.bills) ? body.bills.slice(0, MAX_BILLS_PER_REQUEST)
          : (body.bill ? [body.bill] : []);
        let accepted = 0;
        for (let i = 0; i < incoming.length; i++) {
          const bill = normalizeBill(incoming[i]);
          if (!bill.uid) bill.uid = newUid();
          if (!bill.room && !bill.month) continue;
          await store.putBill(userId, bill);
          accepted++;
        }
        const all = await store.getBills(userId);
        return ok({ uploaded: accepted, bills: all, count: all.length }, corsOrigin);
      }

      if (last === 'delete' && (method === 'POST' || method === 'DELETE')) {
        const body = parseJson(await readBodyText(request));
        const uid = str(body.uid, 64);
        if (!uid) return fail(400, '缺少账单 uid', corsOrigin);
        await store.deleteBill(userId, uid);
        return ok({ uid: uid }, corsOrigin);
      }

      return fail(404, '接口不存在：' + path, corsOrigin, 404);
    } catch (e) {
      return fail(500, '服务端错误：' + (e && e.message ? e.message : 'unknown'), corsOrigin);
    }
  };
}


/* ---------- 阿里云 ESA 入口：EdgeKV ----------
 * KV_NAMESPACE 必须与 ESA 控制台里创建的“存储空间名称”一致。
 */
const KV_NAMESPACE = 'fangdong';

export default {
  async fetch(request) {
    const edgeKv = new EdgeKV({ namespace: KV_NAMESPACE });
    const kv = {
      async getText(key) {
        const v = await edgeKv.get(key, { type: 'text' });
        return (v === undefined || v === null) ? null : String(v);
      },
      async putText(key, value) { await edgeKv.put(key, String(value)); },
      async deleteKey(key) { await edgeKv.delete(key); }
    };
    const store = createKvStore(kv);
    return createHandler({ store: store, platform: 'aliyun-esa' })(request);
  }
};

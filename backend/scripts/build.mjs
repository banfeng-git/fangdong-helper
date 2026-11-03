/**
 * 把 backend/shared/core.js 打包成三家平台各自“单文件可直接部署”的函数。
 *
 * 为什么不用 webpack/rollup：边缘运行时（EdgeOne Makers / ESA 边缘函数）
 * 对多文件 import 的支持不完全一致，单文件最稳，也不需要任何第三方依赖。
 *
 * 用法：node backend/scripts/build.mjs
 * 产物：backend/<platform>/dist/index.js
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const backendDir = path.resolve(here, '..');

const corePath = path.join(backendDir, 'shared', 'core.js');
let core = fs.readFileSync(corePath, 'utf8');

// 去掉文件末尾的 `export { ... };`（打包后不再需要模块导出）
core = core.replace(/\nexport\s*\{[^}]*\};\s*$/m, '\n');
if (/export\s*\{/.test(core)) {
  throw new Error('core.js 里的 export 语句没有清理干净');
}

const banner = (platform) => `/**
 * 房东小助手 · 云同步后端（${platform}）
 * 本文件由 backend/scripts/build.mjs 自动生成，请勿手改；
 * 修改逻辑请改 backend/shared/core.js 后重新执行 npm run build。
 */
`;

const platforms = {
  'cf-workers': {
    title: 'Cloudflare Workers + D1',
    code: `
/* ---------- Cloudflare Workers 入口：D1 关系库 ---------- */
export default {
  async fetch(request, env) {
    if (!env || !env.DB) {
      return new Response(JSON.stringify({ code: 500, msg: '未绑定 D1 数据库，请在 wrangler.toml 中配置 d1_databases 的 binding = DB' }), {
        status: 500,
        headers: { 'Content-Type': 'application/json; charset=utf-8' }
      });
    }
    const store = createD1Store(env.DB);
    const cors = (env.CORS_ORIGIN || '*').trim();
    return createHandler({ store: store, platform: 'cloudflare', corsOrigin: cors })(request);
  }
};
`
  },
  'tencent-edgeone': {
    title: '腾讯 EdgeOne Makers 边缘函数 + EdgeOne KV',
    code: `
/* ---------- 腾讯 EdgeOne 入口：KV 键值存储 ----------
 * 控制台把 KV 命名空间绑定到函数时填写的变量名默认为 my_kv，
 * 若你用了别的变量名，既可以直接改下面的 KV_BIND，也可以在环境变量里配 KV。
 */
const KV_BIND = (typeof my_kv !== 'undefined' && my_kv) ? my_kv : null;

export async function onRequest({ request, env }) {
  const binding = KV_BIND || (env && (env.KV || env.my_kv));
  if (!binding) {
    return new Response(JSON.stringify({ code: 500, msg: '未绑定 EdgeOne KV 命名空间' }), {
      status: 500, headers: { 'Content-Type': 'application/json; charset=utf-8' }
    });
  }
  const kv = {
    async getText(key) {
      const v = await binding.get(key);
      return (v === null || v === undefined) ? null : String(v);
    },
    async putText(key, value) { await binding.put(key, String(value)); },
    async deleteKey(key) { await binding.delete(key); }
  };
  const store = createKvStore(kv);
  return createHandler({ store: store, platform: 'edgeone' })(request);
}
`
  },
  'aliyun-esa-er': {
    title: '阿里云 ESA 边缘函数（Edge Routine）+ EdgeKV',
    code: `
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
`
  }
};

for (const [dir, spec] of Object.entries(platforms)) {
  const outDir = path.join(backendDir, dir, 'dist');
  fs.mkdirSync(outDir, { recursive: true });
  const out = banner(spec.title) + core + spec.code;
  const outFile = path.join(outDir, 'index.js');
  fs.writeFileSync(outFile, out, 'utf8');
  console.log('生成 ' + path.relative(process.cwd(), outFile) + '  (' + out.length + ' 字节)');
}


/* ---------- EdgeOne 国际版 Makers 入口（edge-functions 目录） ----------
 * 有 KV 绑定就走 KV；没有就自动落到 Blob 存储（getStore 即用，不需要提前开通）。
 * Blob 是最终一致存储，这里统一用 consistency: 'strong' 读，避免刚写完读不到。
 */
const pagesGlue = `
/* ---------- EdgeOne 国际版 Makers 入口：KV 优先，Blob 兜底 ---------- */
import { getStore } from '@edgeone/pages-blob';

const KV_BIND = (typeof my_kv !== 'undefined' && my_kv) ? my_kv : null;
const BLOB_NAMESPACE = 'fangdong';

export async function onRequest({ request, env }) {
  const binding = KV_BIND || (env && (env.KV || env.my_kv));
  let store;
  if (binding) {
    store = createKvStore({
      async getText(key) {
        const v = await binding.get(key);
        return (v === null || v === undefined) ? null : String(v);
      },
      async putText(key, value) { await binding.put(key, String(value)); },
      async deleteKey(key) { await binding.delete(key); }
    });
  } else {
    const blob = getStore(BLOB_NAMESPACE);
    store = createKvStore({
      async getText(key) {
        const v = await blob.get(key, { consistency: 'strong' });
        return (v === null || v === undefined) ? null : String(v);
      },
      async putText(key, value) { await blob.set(key, String(value)); },
      async deleteKey(key) { await blob.delete(key); }
    });
  }
  return createHandler({ store: store, platform: 'edgeone' })(request);
}
`;

const pagesOut = path.join(backendDir, 'tencent-edgeone-pages', 'edge-functions', 'api', '[[default]].js');
fs.mkdirSync(path.dirname(pagesOut), { recursive: true });
fs.writeFileSync(pagesOut, banner('腾讯 EdgeOne Makers 边缘函数（国际版 edge-functions 目录）') + core + pagesGlue, 'utf8');
console.log('生成 ' + path.relative(process.cwd(), pagesOut));

console.log('\n三平台构建完成。');

# 部署说明（四选一，接口完全一致）

部署完任意一家，都能被 App 与电脑版直接使用，客户端只需填自己的域名。

---

## 一、Cloudflare Workers + D1（可全自动，推荐）

### 方式 A：GitHub Actions 一键部署（推荐）

1. 把本仓库推到 GitHub。
2. 在 Cloudflare 建一个 D1 数据库，记下 `database_id`（控制台：Storage & Databases → D1）。
3. 把仓库里的 `backend/cf-workers/wrangler.toml` 中
   `database_id = "在这里替换成你的 D1 database_id"` 换成真实 ID。
4. 仓库 Settings → Secrets and variables → Actions 添加：
   - `CLOUDFLARE_API_TOKEN`（Cloudflare 后台创建的 API Token，权限含 Workers Scripts + D1）
   - `CLOUDFLARE_ACCOUNT_ID`
5. 推送代码（或手动运行 `Deploy Cloudflare Workers` 工作流），Actions 会自动：
   建表 → 部署 Worker。

### 方式 B：本地命令行

```bash
cd backend/cf-workers
npx wrangler d1 create fangdong_db          # 把返回的 database_id 填进 wrangler.toml
npx wrangler d1 execute fangdong_db --remote --file=schema.sql
npx wrangler deploy
```

> `backend/cf-workers/dist/index.js` 是构建产物，改逻辑请改 `backend/shared/core.js`
> 后执行 `npm run build`。

部署完成后访问 `https://<你的域名>/api/health`，返回
`"platform":"cloudflare"` 即成功。

---

## 二、腾讯 EdgeOne Makers（边缘函数 + KV）

1. 控制台 → Makers → **KV 存储** → 新建命名空间（例如 `fangdong`）。
2. **边缘函数** → 新建函数 → 把 `backend/tencent-edgeone/dist/index.js` 全文粘贴进去 →
   触发方式选“处理所有请求”。
3. 函数详情 → **服务绑定 / 拓展服务** → 绑定上一步的 KV 命名空间，
   **变量名填 `my_kv`**（代码默认读这个变量；换名字就改 `dist/index.js` 顶部的 `KV_BIND`）。
4. 绑定域名后访问 `https://<你的域名>/api/health`，返回 `"platform":"edgeone"` 即成功。

说明：EdgeOne 的函数部署目前以控制台粘贴/仓库关联为主，本项目提供的是
**已经构建好、可直接粘贴的单文件**，不需要在现场装依赖或打包。

---

## 三、阿里云 ESA（边缘函数 + EdgeKV）

1. ESA 控制台 → 边缘计算 → **边缘存储** → 创建存储空间，名称建议 `fangdong`
   （要与 `backend/aliyun-esa-er/dist/index.js` 里 `const KV_NAMESPACE = 'fangdong';` 一致）。
2. **边缘函数** → 新建函数 → 粘贴 `dist/index.js` 全文 → 触发方式“所有请求”。
3. 给函数绑域名/路由，访问 `https://<你的域名>/api/health`，返回
   `"platform":"aliyun-esa"` 即成功。

注意：EdgeKV 单条 value 上限约 **1.8 MB**，账单是几 KB 级别，完全够用。

---

## 四、PHP + MySQL / SQLite（虚拟主机、宝塔、自己的服务器）

1. 把 `backend/php/` 整个目录上传到网站根目录（也可放子目录，例如 `/fd/`）。
2. MySQL：面板里建库 → 导入 `schema.mysql.sql` → 改 `config.php` 里的
   `DB_DSN / DB_USER / DB_PASS` 三行。
   SQLite：什么都不用改，`data/` 目录给写权限即可（首次访问自动建表）。
3. Apache 已带 `.htaccess`，直接可用；Nginx 见 `backend/php/README.md` 的两行配置。
4. 访问 `https://你的域名/api/health`，返回 `"platform":"php"` 即成功。

要求 PHP 7.4+，开启 `pdo_mysql` 或 `pdo_sqlite`。银行/公司内网服务器同样适用，
不依赖任何云厂商。

---

## 五、客户端如何对接

1. App：设置 → 云端同步（可选） → 填服务端地址（只填域名即可，自动补 `/api`）→ 注册 / 登录
2. 电脑版：工具栏“云端同步” → 同上
3. 登录后点“上传本地账单”或“从云端下载”，两边按 `uid + updatedAt` 合并，
   不会互相覆盖。

---

## 五、自检

```bash
npm run build          # 重新生成三家 Serverless 平台的单文件
npm test               # 核心逻辑：KV 与 D1 两条路径全都跑一遍
node backend/tests/dist.test.mjs   # 直接 import 三家产物，模拟运行时跑通注册/登录/同步
backend/php/test.sh    # PHP 版：起本地服务，跑 18 项完整流程
```

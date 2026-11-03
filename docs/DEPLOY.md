# 部署总教程（从零到能用，四种后端任选其一）

> 整套东西分三块：**后端**（存账号和账单）、**App**（安卓）、**电脑版**。
> 后端四选一：Cloudflare / 腾讯 EdgeOne / 阿里云 ESA / 自己的 PHP 服务器。
> 四家接口完全一致，客户端只认域名，换后端不用改客户端。

---

## 0. 准备工作（5 分钟）

### 0.1 拿到代码

两种方式，任选：

- **下载 zip**：打开发布页
  <https://github.com/banfeng-git/fangdong-helper/releases/latest>
  ，按需下载（彼此独立，用哪个下哪个）：

  | 文件 | 用途 |
  | --- | --- |
  | `fangdong-helper-v2.10-android.apk` | 安卓安装包，直接装手机 |
  | `fangdong-helper-v2.10-pc.zip` | 电脑版成品（jar + 双击启动脚本） |
  | `fangdong-helper-v2.10-backend-01-cloudflare.zip` | Cloudflare 后端 |
  | `fangdong-helper-v2.10-backend-02-edgeone.zip` | 腾讯 EdgeOne 后端 |
  | `fangdong-helper-v2.10-backend-03-esa.zip` | 阿里云 ESA 后端 |
  | `fangdong-helper-v2.10-backend-04-php.zip` | PHP + MySQL/SQLite 后端 |
  | `fangdong-helper-v2.10-android-source.zip` | 安卓源码（想自己改 App） |
  | `房东小助手-电脑版源码-v2.10.zip` | 电脑版源码 |
  | `fangdong-helper-v2.10-source-all.zip` | 上面全部 + 文档 + Actions |

- **git clone**：`git clone https://github.com/banfeng-git/fangdong-helper.git`

### 0.2 装客户端

- 安卓：装 `fangdong-helper-v2.10-android.apk`（支持安卓 9–17）
- 电脑：解压电脑版 zip，Windows 双击 `启动-Windows.bat`（需 Java 11+，推荐 17）

> 客户端**不需要**登录也能用：记账、算费、出账单图片全在本地。
> 只有想多设备同步时，才需要下面的后端。

---

## 一、Cloudflare Workers + D1（可全自动部署）

### 1.1 注册账号

注册 Cloudflare → 进入控制台 Dashboard。

### 1.2 建 D1 数据库

1. 左边菜单 **Storage & Databases → D1 SQL Database**（新版也叫 **Workers & Pages → D1**）
2. 点 **Create database**，名字填 `fangdong_db`，点创建
3. 创建完进入详情页，复制 **Database ID**（一串 UUID）

### 1.3 建 API Token

1. 右上角头像 → **My Profile → API Tokens → Create Token**
2. 选模板 **Edit Cloudflare Workers**（没有模板就自定义权限：
   `Workers Scripts:Edit`、`D1:Edit`、`Account:Read`）
3. 创建后立刻复制 token（只显示一次）

### 1.4 在 GitHub 配 Secrets

打开你的仓库：**Settings → Secrets and variables → Actions → New repository secret**

| Secret 名 | 值 |
| --- | --- |
| `CLOUDFLARE_API_TOKEN` | 1.3 复制的 token |
| `CLOUDFLARE_ACCOUNT_ID` | 控制台首页右侧 **Account ID** |

### 1.5 改 database_id

编辑 `backend/cf-workers/wrangler.toml`：

```toml
database_id = "把这里换成 1.2 复制的 Database ID"
```

提交到 `main` 分支。

### 1.6 一键部署

仓库 **Actions** 页面 → 左侧 **Deploy Cloudflare Workers** → 右侧 **Run workflow** →
`remote` 选 `true`（首次部署要对远程库建表）→ 点绿色 **Run workflow**。

等 1–2 分钟，两步都变绿即成功。地址形如：

```
https://fangdong-sync.你的账号.workers.dev
```

### 1.7 验证

浏览器打开 `https://fangdong-sync.你的账号.workers.dev/api/health`：

```json
{"code":0,"msg":"ok","data":{"platform":"cloudflare","time":...,"version":"2.10"}}
```

### 1.8 本地命令行部署（不想用 Actions）

```bash
cd backend/cf-workers
npm i -g wrangler
wrangler login
wrangler d1 execute fangdong_db --remote --file=schema.sql
wrangler deploy
```

---

## 二、腾讯 EdgeOne（边缘函数 + EdgeOne KV）

> 源码包已带**构建好的单文件**，在控制台粘贴即可，不用装环境。

### 2.1 开通

登录腾讯云 → 控制台搜索 **EdgeOne** → 开通 **EdgeOne Makers**（有免费额度）。

### 2.2 建 KV 命名空间

1. EdgeOne 控制台 → **Makers → KV 存储**
2. **创建命名空间**，名称填 `fangdong`
3. 记下这个命名空间

### 2.3 建边缘函数

1. **Makers → 边缘函数 → 新建函数**，名称 `fangdong-sync`
2. 代码编辑区：把 `backend/tencent-edgeone/dist/index.js` **全文**粘贴进去
3. 触发方式：**处理所有请求**（All requests）
4. 保存并发布

### 2.4 绑定 KV（关键一步）

函数详情页 → **服务绑定 / 扩展服务 → 绑定 KV 存储** → 选 2.2 建的 `fangdong` →
**变量名必须填 `my_kv`**（代码默认读 `env.my_kv`）。

### 2.5 绑定域名并验证

函数 → **域名 / 访问地址** → 绑定域名（或使用平台测试域名）。

访问 `https://你的域名/api/health`，返回 `"platform":"edgeone"` 即成功。

### 2.6 想改逻辑怎么办

不要直接改 `dist/index.js`（构建产物）。改 `backend/shared/core.js` 后：

```bash
node backend/scripts/build.mjs     # 重新生成三家平台的 dist
```

再把新的 `backend/tencent-edgeone/dist/index.js` 粘回控制台。

---

## 二·补充、EdgeOne **国际版（Makers）** 部署（已实测跑通）

> 国际站和国内站不是一个控制台。国际版走 **EdgeOne Makers**（pages.edgeone.ai / console.tencentcloud.com/edgeone/makers），
> 目录结构是 `edge-functions/`，不是「粘贴单文件」那套。仓库里已经放好了适配目录：
> `backend/tencent-edgeone-pages/`（`edge-functions/api/[[default]].js` 兜住所有 `/api/*`）。

### B1. 准备上传包

```bash
cd backend/tencent-edgeone-pages
zip -r /tmp/fangdong-edgeone-pages.zip edge-functions index.html package.json
```

也可以直接用发布页里 `fangdong-helper-v2.10-backend-02-edgeone.zip` 内的 `tencent-edgeone-pages/` 目录。

### B2. 上传建项目

1. 打开 <https://console.tencentcloud.com/edgeone/makers> → **Create project** → **Upload directly**
2. Project name 填 `fangdong-sync`，加速区域保持 `Global (MLC excluded)`
3. 选那个 zip → **Start deployment**，约 20 秒出 `Success`
4. 域名默认是 `https://<项目名>.edgeone.dev`

### B3. 开 KV 并绑定（必须）

控制台左上 **Makers → Storage → KV Storage**：

1. 首次使用要先 **Apply now** 申请开通 KV（填一句用途，等审核通过；控制台显示 `Pending Approval`）
2. 通过后回到项目 → **Storage → KV Storage → Bind namespace**
3. **Variable name 填 `KV`**（代码里 `env.KV || env.my_kv` 两个名字都认），Namespace 选刚建的那个
4. 重新部署一次（或等下一次部署）让绑定生效

验证：

```bash
curl https://<项目名>.edgeone.dev/api/health
# 未绑 KV 时会返回：{"code":500,"msg":"未绑定 EdgeOne KV 命名空间"}
# 绑定后返回：{"code":0,"msg":"ok","data":{"platform":"edgeone",...}}
```

### B3'. KV 还没审核通过？用 Blob 兜底（已实测）

国际版 Makers 的 **Blob** 存储不需要开通、不需要绑定：函数里 `getStore('fangdong')` 一调用，平台自动建命名空间。

`backend/tencent-edgeone-pages/` 里的函数入口已经做成 **KV 优先、Blob 兜底**：

- `env.KV` / `env.my_kv` 有绑定时走 KV（最快）
- 没绑定时自动 `getStore('fangdong')` 读写 Blob，**不用等审核就能用**
- Blob 是最终一致存储，代码里读操作统一加了 `{ consistency: 'strong' }`，写完立刻读得到

`package.json` 里已声明 `"@edgeone/pages-blob": "^0.0.18"`，Makers 构建时会自动 `npm install`（构建日志的 Install 阶段能看到），无需本地打包依赖。

> 注意：Blob 免费版单账户 1GB，存账单文本绰绰有余；KV 审核通过后建议还是绑上 KV 走主路径。

### B4. 关于 401 和访问地址（重要）

国际版如果加速区域是 **Global (MLC excluded)**：

- 从**中国大陆网络**访问项目域名会返回 `401 UNAUTHORIZED`（平台合规限制，不是代码问题）
- 控制台点项目卡片上的 **Preview** 会生成带 `?eo_token=...&eo_time=...` 的临时链接，**有效期 3 小时**，大陆网络可访问
- 想长期稳定：**Domains → Add custom domain** 绑自己的域名（全球区不含大陆的加速区域**不需要备案**），见下面的实测步骤
- 客户端填地址时：临时调试可以带 token 的完整链接；长期用自己绑的域名

### B5. 绑自定义域名（实测通过，以 `fd.466949.xyz` 为例）

1. 项目 → **Domains → Add custom domain**，填子域名（如 `fd.example.com`），环境选 `Production`
2. 平台要求先做**归属验证**，给一条 TXT：

   | 主机记录 | 类型 | 值 |
   | --- | --- | --- |
   | `edgeonereclaim.fd` | TXT | `reclaim-xxxxxxxx`（控制台生成的一串） |

   注意主机记录是「平台前缀 + 你的子域前缀」，例如域名是 `fd.example.com` 就填 `edgeonereclaim.fd`
3. 加完等 5–10 分钟，回控制台点 **Verify**，显示 `Verify successfully` 后点 **Complete**
4. 状态会先变成 `Deploying`，同时 CNAME 列的**目标地址**才出现（例如 `fd.example.com.pages.dnsoe7.com`）。
   这个回源记录是平台异步生成的，**刚加域名时查它是 NXDOMAIN，属于正常**
5. 回 DNS 后台加 CNAME：主机记录填子域前缀（`fd`），值填第 4 步的目标
   - Cloudflare 用户：先关代理（灰色云朵 / DNS only），否则会套一层 CDN，和 EdgeOne 打架
6. 等状态从 `Deploying` 变成 `Effective`（实测约 2 分钟）
7. HTTPS 列会显示 `Not configured` → 点 **Configuration** → **Apply for a free certificate**（推荐）→ Save，
   平台自动签发 DV 证书（实测 TrustAsia，几分钟签好），之后 `https://你的域名/api/health` 直接通

验收命令：

```bash
curl https://fd.example.com/api/health
# {"code":0,"msg":"ok","data":{"platform":"edgeone","version":"2.10"}}
```

绑好域名后，**大陆网络也能直连**，不再需要 3 小时一换的预览链接。

## 三、阿里云 ESA（边缘函数 + EdgeKV）

### 3.1 开通

登录阿里云 → 控制台搜索 **ESA（边缘安全加速）** → 开通，添加站点/域名。

### 3.2 建 EdgeKV 存储空间

1. ESA 控制台 → **边缘计算 → 边缘存储（EdgeKV）**
2. **创建命名空间**，名称填 `fangdong`（必须与代码一致）
3. 记下命名空间名

### 3.3 建边缘函数

1. ESA 控制台 → **边缘计算 → 边缘函数 → 新建函数**，名称 `fangdong-sync`
2. 代码：粘贴 `backend/aliyun-esa-er/dist/index.js` 全文
3. 触发规则：**所有请求**（`/*`）
4. 发布

### 3.4 绑定 KV

函数配置 → **边缘存储绑定** → 选 `fangdong` 命名空间。
命名空间不叫 `fangdong` 的话，改 `dist/index.js` 顶部：

```js
const KV_NAMESPACE = 'fangdong';   // 改成你的命名空间
```

### 3.5 验证

访问 `https://你的域名/api/health`，返回 `"platform":"aliyun-esa"` 即成功。

> EdgeKV 单条 value 上限 1.8 MB，账单是 KB 级别，够用。

---

## 四、PHP + MySQL / SQLite（虚拟主机、宝塔、自己的服务器）

> 适合已有主机/服务器的人，不依赖任何云厂商。

### 4.1 上传文件

把 `backend/php/` 里的东西传到网站根目录（或子目录，如 `/fd/`）：

```
index.php           接口主文件
config.php          数据库配置
.htaccess           Apache 伪静态
schema.mysql.sql    MySQL 建表
data/               SQLite 数据库目录（要可写）
```

### 4.2A 用 MySQL（推荐）

1. 主机面板建数据库：库名 `fangdong`，记下用户名密码
2. 导入表结构：宝塔/phpMyAdmin 的 **导入** → 选 `schema.mysql.sql`；
   或命令行 `mysql -u fangdong -p fangdong < schema.mysql.sql`
3. 改 `config.php`：

```php
define('DB_DSN',  'mysql:host=127.0.0.1;port=3306;dbname=fangdong;charset=utf8mb4');
define('DB_USER', 'fangdong');
define('DB_PASS', '你的密码');
```

### 4.2B 用 SQLite（零配置）

什么都不用改，只要给 `data/` 写权限：

```bash
chmod 755 data      # 有些主机要 777
```

首次访问自动建表，数据库文件 `data/fangdong.db`，备份复制这一个文件即可。

### 4.3 Nginx 用户加伪静态

```nginx
location / {
    try_files $uri $uri/ /index.php?$query_string;
}
fastcgi_param HTTP_AUTHORIZATION $http_authorization;   # 不加这句拿不到 token
```

Apache 主机不用管，`.htaccess` 已写好。

### 4.4 验证

访问 `https://你的域名/api/health`，返回 `"platform":"php"` 即成功。

### 4.5 自测（可选）

```bash
cd backend/php && ./test.sh      # 需要本机有 PHP，18 项全流程
```

---

## 五、客户端连上后端

### 5.1 安卓 App

1. 打开 App → **设置** → **云端同步（可选）**
2. **服务端地址**：填你的域名，例如 `https://fangdong-sync.xxx.workers.dev`
   （只填域名即可，App 自动补 `/api`；填了 `/api` 也不会重复）
3. 填账号密码 → **注册**（第一次）或 **登录**
4. **上传本地账单**：手机账单传到云端
5. **从云端下载**：云端账单拉到这台手机

同步规则：按账单唯一编号合并，**修改时间新的覆盖旧的**，不会互相冲掉。
不登录也能正常记账，只是不同步。

### 5.2 电脑版

工具栏 **云端同步** → 同样的地址、同一套账号 → 上传 / 下载。
手机和电脑用同一账号，账目互通。

### 5.3 填地址的两个坑

- 必须 `https://`（或 `http://`）开头，结尾不要带 `/api/` 这种多余斜杠
- EdgeOne/ESA 填的是**函数域名**，不是站点主域名

---

## 六、常见问题

| 现象 | 原因 / 解决 |
| --- | --- |
| `/api/health` 404 | 函数触发规则没选“所有请求”；PHP 是伪静态没生效 |
| 登录返回 401 | PHP 主机没传 `Authorization` 头，加 `fastcgi_param HTTP_AUTHORIZATION` |
| 上传后云端 0 条 | EdgeOne 的 KV 变量名不是 `my_kv`，或 ESA 命名空间不匹配 |
| Actions 部署报 D1 错 | `wrangler.toml` 里的 `database_id` 没换成自己的 |
| 换后端后账号没了 | 正常，各后端数据互相独立，要在新后端重新注册 |

---

## 七、自检命令（开发者）

```bash
npm run build                       # 生成三家 Serverless 平台的单文件
npm test                            # 核心逻辑：KV 与 D1 两条路径
node backend/tests/dist.test.mjs    # 三家产物端到端（模拟运行时）
cd backend/php && ./test.sh         # PHP 版 18 项全流程
```

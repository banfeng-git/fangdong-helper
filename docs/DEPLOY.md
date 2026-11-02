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

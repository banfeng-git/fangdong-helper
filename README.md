# 房东小助手（开源版）

房东收房租、抄水表电表燃气表的记账工具。**安卓 App + 电脑桌面版 + 四套可自建云端同步后端**
（Cloudflare Workers、腾讯 EdgeOne、阿里云 ESA、PHP + MySQL/SQLite），一套代码，随处部署。

> 不登录、不填服务器，App 和电脑版都能完整使用（记账、算费、生成账单表格图片）。
> 云端同步是**可选功能**：想多设备共用一份账本时，自己在 Cloudflare / 腾讯云 / 阿里云
> 部署一套后端，然后在 App 或电脑版里填上自己的地址即可。

---

## 下载

>> **最新：v2.10.1 紧急修复版**（修复 v2.10 升级后账单列表变空的 bug，数据本身没丢）→ [fangdong-helper-v2.10.1-android.apk](https://github.com/banfeng-git/fangdong-helper/releases/download/v2.10.1/fangdong-helper-v2.10.1-android.apk) · 直接覆盖安装，**不要卸载**

（不用 clone 也能用）

GitHub 发布页：**<https://github.com/banfeng-git/fangdong-helper/releases/latest>**
每个包都是 zip（APK 除外），按需下载，彼此独立：

| 下载文件 | 里面是什么 | 给谁用 |
| --- | --- | --- |
| `fangdong-helper-v2.10-android.apk` | 安卓安装包 | 直接装手机 |
| `fangdong-helper-v2.10-pc.zip` | jar + 双击启动脚本 + 源码 | 电脑上直接用 |
| `fangdong-helper-v2.10-backend-01-cloudflare.zip` | Workers 单文件函数 + wrangler 配置 + 建表 SQL | 部署到 Cloudflare |
| `fangdong-helper-v2.10-backend-02-edgeone.zip` | 边缘函数单文件 + 绑定说明 | 部署到腾讯 EdgeOne（含国际版 Makers 目录） |
| `fangdong-helper-v2.10-backend-03-esa.zip` | 边缘函数单文件 + 绑定说明 | 部署到阿里云 ESA |
| `fangdong-helper-v2.10-backend-04-php.zip` | index.php + 配置 + 建表 SQL + 自测脚本 | 传到虚拟主机/服务器 |
| `fangdong-helper-v2.10-android-source.zip` | 完整安卓工程（含 gradlew） | 自己改 App |
| `fangdong-helper-v2.10-source-all.zip` | 全部源码 + 文档 + GitHub Actions | 想一次拿全 |

完整部署教程（四套后端的每一步点击路径 + 客户端连接 + 常见问题）：
[`docs/DEPLOY.md`](docs/DEPLOY.md)

---

## 目录结构

```
fangdong-helper-open/
├── android-app/             安卓 App 完整工程（Java，minSdk 26，可直接用 Android Studio 打开）
├── pc-app/                  电脑桌面版（Java Swing 独立程序，不是网页）
│   ├── src/                 源码
│   ├── dist/                已编译好的 jar
│   ├── 启动-Windows.bat     双击启动
│   └── build-exe.bat        在 Windows 上一键打成 .exe 安装包
├── backend/
│   ├── shared/core.js       后端核心（三平台共用一份，纯 JS，无第三方依赖）
│   ├── scripts/build.mjs    把 core.js 打包成三家各自的“单文件函数”
│   ├── cf-workers/          Cloudflare Workers + D1（含 wrangler.toml、schema.sql）
│   ├── tencent-edgeone/     腾讯 EdgeOne Makers 边缘函数 + EdgeOne KV
│   ├── aliyun-esa-er/       阿里云 ESA 边缘函数 + EdgeKV
│   ├── php/                 PHP + MySQL / SQLite（虚拟主机、宝塔、普通服务器）
│   └── tests/               后端自测（Node 直接跑，不需要数据库）
├── .github/workflows/       GitHub Actions：CI + Cloudflare 一键部署
└── docs/                    API 文档、部署说明
```

## 四套后端，同一套 API

| 平台 | 存储 | 说明 |
| --- | --- | --- |
| Cloudflare Workers | D1（SQLite） | `backend/cf-workers`，GitHub Actions 一键部署 |
| 腾讯 EdgeOne Makers | EdgeOne KV | `backend/tencent-edgeone`，控制台绑定 KV 后粘贴函数 |
| 阿里云 ESA | EdgeKV | `backend/aliyun-esa-er`，控制台建存储空间后粘贴函数 |
| **PHP + MySQL / SQLite** | 自己的数据库 | `backend/php`，上传目录即可，虚拟主机/宝塔/服务器通用 |

四家的接口**完全一致**（注册 / 登录 / 上传 / 列表 / 删除，Bearer token），
所以 App 和电脑版里填任意一家的域名都能连，换平台不用改客户端。

接口细节见 [`docs/API.md`](docs/API.md)，部署步骤见 [`docs/DEPLOY.md`](docs/DEPLOY.md)。

## 业务规则（与手机 App v2.9/v2.10 一致）

- 项目名称固定为 **水 / 电 / 燃气**（不再出现“煤”这个简称，只用于识别用户自建的“煤气表”等项目名）
- 列顺序固定：水 → 电 → 燃气
- 明细表标题按“实际存在的项目类别”动态拼接：
  - 只有一类 → `电费收费明细表`
  - 两类 → `水电费用收费明细表`
  - 三类 → `水电燃气费用收费明细表`
- `XX合计` 行（如“水电燃气合计”）**同时满足两条才显示**：
  1. 有“其他收费”（固定金额项目，如卫生费）
  2. 表格列数 ≥ 2
- 单列不显示合计；没有其他收费也不显示合计（否则合计等于总费用，重复）
- 起止码自动算用量：止码 − 起码（负数按 0），费用 = 用量 × 单价
- 多块表时列名为 `水表1 / 电表1 / 燃气表1`，单块时列名为 `水 / 电 / 燃气`

## 快速开始

### 电脑版

```bash
cd pc-app
./build.sh          # 需要 JDK 11+，会生成 dist/房东小助手-电脑版.jar
# Windows 直接双击 启动-Windows.bat
```

### 安卓版

用 Android Studio 打开 `android-app/`，或命令行：

```bash
cd android-app
./gradlew assembleRelease
```

### 后端

```bash
npm run build          # 生成三家 Serverless 平台的单文件函数
npm test               # 后端自测（KV / D1 两条路径都会测）
backend/php/test.sh    # PHP 版自测（本地起服务跑完整流程，需装 PHP）
```

## 数据与隐私

- 本地数据：安卓在 App 私有数据库；电脑版在 `用户目录/.fangdong-helper/data.json`
- 云端只存你自己的账单，密码用 PBKDF2-HMAC-SHA256 加盐哈希后存储，token 30 天过期
- 服务端地址由用户自己填，项目本身不内置任何公共服务器

## 许可

MIT

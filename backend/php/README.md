# 房东小助手 · 云端同步（PHP + 数据库版）

和 Cloudflare / 腾讯 EdgeOne / 阿里云 ESA 三套后端**接口完全一致**，
App 与电脑版填这个域名就能用。适合已有虚拟主机、宝塔、阿里云/腾讯云服务器的用户。

## 上传哪些文件

把本目录整个传到网站根目录（或子目录，例如 `/fd/`）：

```
index.php          接口主文件（路由 + 校验 + 数据库读写，全在这一个文件里）
config.php         数据库配置
.htaccess          Apache 伪静态 + 保留 Authorization 头
schema.mysql.sql   MySQL 建表语句
schema.sqlite.sql  SQLite 建表语句（可选，程序会自动建）
data/              SQLite 数据库目录（需可写）
```

要求：PHP 7.4 以上（推荐 8.1+），开启 `pdo_mysql` 或 `pdo_sqlite`。

## 方式一：MySQL（推荐，虚拟主机都能用）

1. 在主机面板建一个数据库（例如 `fangdong`）。
2. 导入建表语句：
   ```bash
   mysql -u fangdong -p fangdong < schema.mysql.sql
   ```
   或在 phpMyAdmin / 宝塔“导入”里上传 `schema.mysql.sql`。
3. 改 `config.php`：
   ```php
   define('DB_DSN',  'mysql:host=127.0.0.1;port=3306;dbname=fangdong;charset=utf8mb4');
   define('DB_USER', 'fangdong');
   define('DB_PASS', '你的密码');
   ```
4. 访问 `https://你的域名/api/health`，看到 `"platform":"php"` 就成功了。

## 方式二：SQLite（零配置）

不用建库、不用改配置，直接传上去就能用（首次访问自动建表，
数据库文件在 `data/fangdong.db`）。记得给目录写权限：

```bash
chmod 755 data        # 或者 777，取决于主机
```

小规模（几十个房东、几千条账单）完全够用，且备份就是一个文件。

## Nginx 配置

Apache 的 `.htaccess` 在 Nginx 上无效，加了这段：

```nginx
location / {
    try_files $uri $uri/ /index.php?$query_string;
}

# 让 PHP 拿到 Authorization 头
fastcgi_param HTTP_AUTHORIZATION $http_authorization;
```

## 自测

```bash
./test.sh          # 起一个本地 PHP 服务器，跑完整接口流程（SQLite）
```

实测输出：`18 passed, 0 failed / ALL PASS`，覆盖注册、重复注册、错密码、无 token、
上传、旧数据不覆盖新数据、新数据覆盖、追加、账号隔离、删除、登出失效、未知接口。

## 接口

与另外三家完全一样，见 [`../../docs/API.md`](../../docs/API.md)。
健康检查的 `platform` 字段会返回 `php`。

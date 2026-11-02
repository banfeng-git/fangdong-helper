<?php
/**
 * 房东小助手 · 云端同步（PHP 版）配置
 *
 * 两种用法：
 * 1) MySQL / MariaDB（虚拟主机、宝塔、LNMP 都能用）——把下面的 DSN 换成自己的库，
 *    然后导入 schema.mysql.sql。
 * 2) SQLite（零配置，单文件数据库，适合小规模或本地测试）——保持默认即可，
 *    数据库文件会自动建在 data/fangdong.db。
 *
 * 注意：data 目录必须可写（chmod 755/777，取决于主机）。
 */

// ---------- 数据库 ----------
// MySQL 示例：
//   define('DB_DSN',  'mysql:host=127.0.0.1;port=3306;dbname=fangdong;charset=utf8mb4');
//   define('DB_USER', 'fangdong');
//   define('DB_PASS', '你的密码');
//
// SQLite（默认）：
define('DB_DSN', 'sqlite:' . (getenv('DB_FILE') ?: __DIR__ . '/data/fangdong.db'));
define('DB_USER', '');
define('DB_PASS', '');

// ---------- 跨域 ----------
// 客户端是本机 App / 桌面程序，默认放开；想收紧就改成自己的域名。
define('CORS_ORIGIN', '*');

// ---------- 其它 ----------
define('MAX_BILLS_PER_REQUEST', 500);
define('TOKEN_TTL_MS', 30 * 24 * 3600 * 1000);

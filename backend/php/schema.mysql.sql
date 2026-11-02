-- 房东小助手 · MySQL / MariaDB 建表
-- 用法：mysql -u 用户名 -p 库名 < schema.mysql.sql
--      或在 phpMyAdmin / 宝塔面板里导入

CREATE TABLE IF NOT EXISTS users (
  id            VARCHAR(32)  NOT NULL PRIMARY KEY,
  username      VARCHAR(64)  NOT NULL,
  username_lower VARCHAR(64) NOT NULL UNIQUE,
  password      VARCHAR(255) NOT NULL,
  created_at    BIGINT       NOT NULL,
  KEY idx_username_lower (username_lower)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS sessions (
  token      VARCHAR(64) NOT NULL PRIMARY KEY,
  user_id    VARCHAR(32) NOT NULL,
  expires_at BIGINT      NOT NULL,
  KEY idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS bills (
  user_id    VARCHAR(32)  NOT NULL,
  uid        VARCHAR(64)  NOT NULL,
  payload    LONGTEXT     NOT NULL,
  updated_at BIGINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (user_id, uid),
  KEY idx_user_updated (user_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

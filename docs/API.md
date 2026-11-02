# 云端同步 API（三平台通用）

- 统一前缀：`/api`
- 编码：请求与响应均为 JSON（UTF-8）
- 鉴权：除注册/登录/健康检查外，均需请求头 `Authorization: Bearer <token>`
- 响应格式统一：

```json
{ "code": 0, "msg": "ok", "data": { } }
```

`code = 0` 表示成功；非 0 时看 `msg`。

| code | 含义 |
| --- | --- |
| 0 | 成功 |
| 400 | 参数不合法 |
| 401 | 未登录 / 登录过期 / 密码错误 |
| 409 | 账号已存在 |
| 404 | 接口不存在 |
| 500 | 服务端错误 |

---

## 1. 健康检查

`GET /api/health`

```json
{ "code": 0, "msg": "ok", "data": { "platform": "edgeone", "time": 1730000000000, "version": "2.10" } }
```

`platform` 取值：`cloudflare` / `edgeone` / `aliyun-esa` / `php`，可以用来确认自己连的是哪家。

## 2. 注册

`POST /api/register`

```json
{ "username": "zhangsan", "password": "secret123" }
```

- 账号 2–32 字符，密码 6–128 字符
- 注册成功直接返回 token（等于自动登录）

```json
{
  "code": 0,
  "data": { "user_id": "a1b2c3d4e5f60718", "username": "zhangsan", "token": "……" }
}
```

失败：`{"code":409,"msg":"该账号已被注册"}`

## 3. 登录

`POST /api/login`

```json
{ "username": "zhangsan", "password": "secret123" }
```

返回同上（`token` 有效期 30 天）。

失败：`{"code":401,"msg":"账号或密码错误"}`

## 4. 登出

`POST /api/logout` （需 token）

```json
{ "code": 0, "msg": "ok", "data": {} }
```

## 5. 拉取账单

`GET /api/bills` （需 token）

```json
{
  "code": 0,
  "data": {
    "count": 2,
    "bills": [
      {
        "uid": "5f3a…",
        "room": "526",
        "tenant": "张三",
        "month": "2026-11",
        "note": "月底结清",
        "waterMeters": [{ "label": "水表1", "start": 0, "end": 65, "usage": 65, "fee": 195 }],
        "gasMeters":   [{ "label": "燃气表1", "start": 10, "end": 28, "usage": 18, "fee": 45 }],
        "meters":      [{ "label": "电表1", "start": 800, "end": 858, "usage": 58, "fee": 58 }],
        "waterFee": 195, "gasFee": 45, "elecFee": 58,
        "extras": [
          { "name": "卫生费", "usageBased": false, "usage": 0, "amount": 10 },
          { "name": "网费",   "usageBased": false, "usage": 0, "amount": 50 }
        ],
        "updatedAt": 1730000000000
      }
    ]
  }
}
```

## 6. 上传 / 双向同步

`POST /api/bills/sync` （需 token）

```json
{ "bills": [ { …同上单条账单… } ] }
```

- 一次最多 500 条
- 服务端按 `uid` 合并，`updatedAt` 大的覆盖小的（旧数据不会覆盖新数据）
- **返回值直接带上合并后的云端全量账单**，客户端一次往返就能完成双向同步

```json
{
  "code": 0,
  "data": { "uploaded": 3, "count": 5, "bills": [ …云端全量… ] }
}
```

## 7. 删除账单

`POST /api/bills/delete` （需 token）

```json
{ "uid": "5f3a…" }
```

```json
{ "code": 0, "data": { "uid": "5f3a…" } }
```

---

## 客户端怎么填地址

App / 电脑版的“云端同步”页面只需要填**域名**：

- 填 `https://fd.xxx.workers.dev` → 客户端自动请求 `https://fd.xxx.workers.dev/api/...`
- 填 `https://fd.xxx.workers.dev/api` → 同样可用（不重复补 `/api`）

三套后端任选其一，同一份账号只在同一台服务器内有效（各平台数据互不相通）。

## 存储实现说明（给二次开发者）

- 阿里云 ESA 的 EdgeKV 只有 `get / put / delete`，**没有前缀列举**，所以账单不是“一条一个 key”，
  而是“每个用户一份索引 + 每张账单一个 key”（`idx_<userId>` 存 uid 数组）
- 腾讯 EdgeOne KV 的 key 只允许**数字、字母、下划线**，所以 key 统一用 `u_ / s_ / b_ / idx_` 前缀
- Cloudflare 用 D1 关系表（`users / sessions / bills`），表结构见 `backend/cf-workers/schema.sql`
- 三平台共用 `backend/shared/core.js` 里的同一套逻辑，行为完全一致

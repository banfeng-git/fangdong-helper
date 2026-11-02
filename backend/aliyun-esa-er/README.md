# 阿里云 ESA 部署（边缘函数 Edge Routine + EdgeKV）

用的是 ESA 的**边缘存储 EdgeKV**，同样不需要 MySQL。

## 控制台步骤

1. ESA 控制台 → **边缘计算 → 边缘存储**，创建存储空间（命名空间），名称建议 `fangdong`
   （必须与 `dist/index.js` 里的 `const KV_NAMESPACE = 'fangdong';` 一致，改了就同步改）。
2. **边缘函数** → 新建函数 → 把 `dist/index.js` 整体粘贴 → 触发方式“所有请求”。
3. 给函数绑定域名或路由，例如 `sync.example.com/*`。
4. 访问 `https://你的域名/api/health`，返回 `"platform":"aliyun-esa"` 即成功。

## 客户端怎么填

服务端地址填：`https://你的域名/api`

## 注意

- EdgeKV 单条 value 上限约 1.8 MB（账单 JSON 通常几 KB，完全够用）；
- 边缘函数里已内置 `EdgeKV` 全局对象，无需 npm 依赖；
- 若你的套餐入口叫“边缘程序 / ER”，逻辑完全一致，都是 `export default { async fetch(request) {...} }`。

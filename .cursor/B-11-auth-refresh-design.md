# B-11 设计备忘：短 access + 长 refresh（含 M-8 / C-5）

> 状态：**仅设计，不实现**  
> 更新日期：2026-09-04  
> 来源：管理后台审查 B-11；小程序 M-8 / C-5 余量  
> 触发落地：接真实支付 / 对外正式发版 / 合规要求前专项开干

---

## 1. 现状（已具备 / 缺口）

| 能力 | 现状 |
|------|------|
| 单 JWT 会话 | `JwtService.createToken`；`LoginResponse.token` |
| `/api/v2/auth/refresh` | 用**当前同一枚 JWT** 校验 → `revokeTokenQuietly` → 再发新 JWT（滑动续期） |
| jti + 吊销名单 | 已有；登出 / refresh 轮换会 revoke |
| 运营后台 | HttpOnly Cookie 为主（`AUTH_COOKIE_ENABLED`），勿再落 localStorage JWT |
| 消费者 / 商户小程序 | Bearer token **明文** `uni.Storage`；无独立 refresh 凭证 |
| 客户端过期 | 部分路径仍 `expiresInSeconds ?? 1800` 静默默认（C-5 余量） |

审查要点：无 access/refresh **类型区分** → 长寿命 access 等价于可无限滑动续期；被盗 access 在过期前可持续调 refresh。客户端「加密再存 Storage」**不在方案内**（可逆混淆 ≠ 安全）。

---

## 2. 目标模型

```
登录成功
  ├─ accessToken   短寿命，仅调业务 API（含 Authorization）
  └─ refreshToken  长寿命，仅调 POST /auth/refresh（及 logout）
刷新成功
  ├─ 吊销旧 refresh（或整族 session）
  └─ 下发新 access + 新 refresh（旋转）
登出
  └─ 吊销 access jti + refresh jti（同 sessionFamily）
```

### 建议寿命（可配置，示意）

| 端 | access | refresh |
|----|--------|---------|
| 运营后台（Cookie） | 15–30 min | 7–14 d（或与 Cookie Max-Age 对齐） |
| 消费者 / 商户小程序 | 15–30 min | 14–30 d（「记住登录」才发长 refresh；否则随进程） |

- access / refresh 均带 **`typ` claim**（`access` | `refresh`）与 **`jti`**；refresh 另带 **`sid`（session family）**。
- 业务拦截器**只接受** `typ=access`；`/refresh`、`/logout` **只接受** `typ=refresh`（或 Cookie 内 refresh）。
- `expiresInSeconds` **必须**随 access 下发；客户端缺失则视为异常（登出 / 强制重登），禁止静默 1800。

---

## 3. 存储（按端）

| 端 | access | refresh | 禁止 |
|----|--------|---------|------|
| admin-vue | HttpOnly Secure Cookie（可拆两 Cookie 或一 Cookie 会话句柄） | 同左 / 同族 | localStorage 存 JWT |
| consumer-mp / merchant-mp | 内存优先；冷启动可回落 Storage | Storage 可接受，但须与 access 分 key；能用平台安全能力则优先 | 密码落盘；客户端自造「加密 token」 |
| H5 | 对齐小程序；优先 Cookie（SameSite）若同站网关 | 同左 | URL query 拼 token（证据下载已收敛 Header） |

M-8：商户端「记住账号」**只存手机号**；会话只靠 refresh，不存密码。

---

## 4. 协议与客户端行为（草图）

**登录 / 刷新响应（方向）**

```ts
{
  accessToken: string;
  refreshToken: string;      // Cookie 模式可省略 body，只 Set-Cookie
  expiresInSeconds: number;  // access 剩余秒数，必填
  userId: number;
  // 兼容期可暂留 token === accessToken
}
```

**小程序 `mpRequest`（已有单飞 refresh）**

1. 业务 401 → 用 refresh 调 `/auth/refresh`（单飞）。
2. 成功 → 写入新 access/refresh → 重试原请求一次。
3. refresh 失败 → 清会话 → 登录页（商户端已有 401 防抖）。

**兼容期（建议 1 个小版本）**

- 旧单 token：refresh 端点仍可接受无 `typ` 的旧 JWT，但新签发一律双 token。
- `LoginResponse.token` 别名 access，避免一次改爆所有调用方。

---

## 5. 服务端改动面（落地时）

1. `JwtService`：`createAccess` / `createRefresh`；校验按 `typ` 分流。  
2. `AuthController.refresh`：只验 refresh；旋转并吊销旧 refresh（及可选旧 access）。  
3. `LoginResponse` / `shared-types`：字段扩展；admin Cookie 写入策略同步。  
4. `AuthInterceptor`：拒绝 refresh 调业务 API。  
5. 单测：旋转、重放旧 refresh、登出后 refresh、跨端 Cookie realm。  
6. 文档：`docs/API_DOCUMENTATION.md` 鉴权节更新。

---

## 6. 非目标（本专项不做）

- 客户端可逆「加密」Storage 冒充安全存储  
- 改 EMQX / MinIO 口令（其它报告里的同名编号勿混）  
- 一次清完全部 P3 / N-13/N-15  
- 上线前临时加长单一 JWT 冒充 refresh

---

## 7. 验收清单（落地时）

- [ ] access 过期后，仅持 access 无法 refresh；持 refresh 可旋转  
- [ ] 旧 refresh 重放失败  
- [ ] logout 后 access + refresh 均失效  
- [ ] 运营后台 Cookie 路径无 localStorage JWT  
- [ ] 双小程序无密码落盘；`expiresInSeconds` 缺失会登出  
- [ ] 并发 401 只触发一次 refresh（沿用现有单飞）

---

## 8. 决策记录

| 日期 | 决策 |
|------|------|
| 2026-09-04 | **停在本备忘，不写实现代码**；真实支付 / 正式发版前再开专项 |

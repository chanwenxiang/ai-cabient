# 柜机一码两用 Implementation Plan

> **For agentic workers:** Execute task-by-task. Steps use checkbox syntax.

**Goal:** 柜门 HTTPS 一码；微信进小程序、支付宝进 H5；支付宝 OAuth 绑定；后台可下载柜机码；结算跟 `entry_channel`。

**Architecture:** trade-service 生成 `/o/{deviceId}` 链接与 PNG；公开中间页按 UA 分流；`alipay_user_id` + `/auth/alipay/login`；admin 设备详情出码；consumer H5 授权开门。

**Tech Stack:** Spring Boot 3、ZXing、Flyway、Vue3 admin、uni-app H5、现有 Alipay OpenAPI 签名。

## Global Constraints

- 不做支付宝小程序；不做微信 getwxacode 作柜门主码；批量 ZIP 二期。
- 密钥仅环境变量；mock 下可无真实支付宝联调登录。
- 复用 `shopping_session.entry_channel` 与 PayScore 渠道优先逻辑。

---

### Task 1: QR 配置 + 出码 API + 后台 UI（阶段 A） — DONE

- [x] ZXing + `aicabinet.qr.*`
- [x] `GET .../qr-link` / `qr.png`
- [x] 设备详情 UI
- [x] `DeviceQrServiceTest`

### Task 2: 中间页 UA 分流（阶段 B） — DONE

- [x] `GET /o/{deviceId}`
- [x] nginx `/o/`（conf / compose / full）
- [x] Alipay → H5；微信提示；其他引导

### Task 3: 支付宝 OAuth 登录（阶段 C 后端） — DONE

- [x] `V148__user_alipay_user_id.sql`
- [x] `POST /api/v2/auth/alipay/login`
- [x] mock / live oauth.token

### Task 4: Consumer H5（阶段 C 前端） — DONE

- [x] `consumerAlipayLogin` + H5 `ensureConsumerAuth`

### Task 5: 验收 — DONE（2026-08-05 Docker full）

- [x] `qr-link` → `http://localhost/o/CAB-001`
- [x] `qr.png` 200 / ~705 bytes
- [x] gateway `/o/CAB-001` 分流页
- [x] Alipay UA → 302 → `http://127.0.0.1:3002/#/pages/index/index?...&channel=ALIPAY`
- [x] mock alipay login 返回 token

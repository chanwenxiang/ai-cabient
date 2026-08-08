# 柜机一码两用 + 支付宝 H5 授权开门

**日期**：2026-08-05  
**范围**：中间落地页、消费者 H5（支付宝内）、支付宝 OAuth 绑定、运营后台柜机码、结算入口渠道  
**依据**：竞品（友朋/中吉/魁鲸类）柜门固定 HTTPS 码 + UA 分流；用户确认：不做支付宝小程序；支付宝授权拿用户标识再绑定；支付跟当前打开的 App 走

---

## 1. 目标

1. 柜门只贴 **一个 HTTPS 二维码**，微信 / 支付宝扫一扫均可进入开门流程。  
2. **微信** → 现有微信小程序；**支付宝** → 支付宝内置浏览器打开 **H5**（不做支付宝小程序）。  
3. 支付宝侧用 **授权拿到 `user_id`（支付宝用户标识）再绑定/建档**。  
4. 开门会话记录 `entry_channel`，关门结算优先用 **当前入口渠道**（微信→微信，支付宝→支付宝）。  
5. 运营后台可按柜机 **预览 / 下载 PNG / 复制链接**（批量下载二期）。

### 1.1 非目标（本轮不做）

- 支付宝小程序端  
- 微信 `getwxacode` 作为柜门主码（仅微信可用，破坏一码两用）  
- 柜门分贴微信码 + 支付宝码  
- 批量打包下载、标签打印模板（二期）  
- 刷脸开门  

---

## 2. 总体流程

```
柜门码：https://{PUBLIC_HOST}/o/{deviceId}
              │
              ▼
         中间页 /o/{deviceId}
         （按 User-Agent）
         ├─ 微信   → 跳转微信小程序 pages/index/index?deviceId=&channel=WECHAT&autoOpen=1
         ├─ 支付宝 → 302/引导 至 H5：/h5/#/pages/index/index?deviceId=&channel=ALIPAY&autoOpen=1
         └─ 其他   → 提示「请使用微信或支付宝扫码」
              │
              ▼
    鉴权就绪 → createSession(deviceId, entryChannel) → OPEN_DOOR
              │
              ▼
    关门识别 → 结算：PayScoreService 优先 session.entry_channel
```

已有能力复用：

- `shopping_session.entry_channel`（V101）  
- `parseCabinetScan` / `parseLaunchOptions`（`packages/shared-uni`）  
- `PayScoreService.charge(..., preferredChannel)`、`OrderPaymentService` 读会话渠道  
- 消费者 H5 构建路径（`consumer-mp` uni-app H5）  

---

## 3. 柜机码规范

| 项 | 约定 |
|----|------|
| 内容 | `https://{PUBLIC_HOST}/o/{deviceId}`，`deviceId` 大写，与库中一致（如 `CAB-001`） |
| 类型 | 普通 QR（编码上述 URL），**不是**微信/支付宝官方小程序码 |
| 稳定性 | 码只绑定 `deviceId`；改域名/小程序路径只改中间页与配置，柜门贴纸尽量不换 |
| 兼容解析 | 现有解析已支持 URL path / query；中间页上线后柜门统一用 `/o/{id}` |

配置：

- `aicabinet.qr.public-host`（或环境变量 `QR_PUBLIC_HOST`）：对外域名，须 HTTPS  
- 本地/dev 可用 `http://localhost` 或局域网 IP，仅联调  

---

## 4. 中间落地页

### 4.1 承载

- 由 **trade-service**（或 gateway 静态）提供轻量页/接口：  
  - `GET /o/{deviceId}` → HTML（内嵌 UA 判断脚本）或服务端 302  
- 校验：`deviceId` 合法且设备存在；不存在则展示友好错误（不暴露内部异常）

### 4.2 分流规则

| UA | 行为 |
|----|------|
| 含 `MicroMessenger` | 打开微信小程序（优先 **URL Link**；未配置时展示「请在微信中打开」+ 复制路径兜底） |
| 含 `AlipayClient` | 跳转消费者 H5 开门页，带 `deviceId`、`channel=ALIPAY`、`autoOpen=1` |
| 其他 | 静态提示文案 |

微信跳转依赖配置（可后置接真）：

- `WECHAT_MP_APPID`、URL Link 生成所需密钥/接口  
- mock/dev：可直接展示「模拟已进入小程序」+ 深链参数日志，不阻塞后台出码  

支付宝 H5 基址：

- `CONSUMER_H5_BASE`（如 `https://{PUBLIC_HOST}/h5/`）  

---

## 5. 支付宝 H5：授权绑定

### 5.1 数据模型

`user_info` 新增（Flyway）：

| 列 | 说明 |
|----|------|
| `alipay_user_id` | 支付宝用户 `user_id`，唯一索引（可空） |

与现有 `wx_open_id`、`alipay_agreement_id` 并存：前者是身份，后者是免密协议。

### 5.2 登录 API

- `POST /api/v2/auth/alipay/login`  
  - 入参：`authCode`（支付宝网页授权 code）  
  - 服务端：code → `access_token` → `user_id`  
  - 若已有 `alipay_user_id` → 发登录 JWT  
  - 若无 → **自动建档**（对齐微信 `wx.login` 免注册体验），再发 JWT  
  - 可选后续：引导绑定手机号（本轮不强制，与微信免注册一致）

- mock：`AICABINET_MOCK_ENABLED=true` 时可用固定/传入的 mock `alipay_user_id` 登录，便于无沙箱联调  

### 5.3 H5 前端

在 `consumer-mp` H5（支付宝容器内）：

1. 识别 `channel=ALIPAY`（launch / query）  
2. 未登录 → 调起支付宝网页授权 → 回调带 `authCode` → 调上述 login  
3. 已登录且 `autoOpen=1` → 沿用现有开门前校验（免密/余额）→ `createSession`  

支付准备：

- 入口为 `ALIPAY` 时，开门前引导 **支付宝免密签约**（现有 verify / prep drawer 能力），不引导微信支付分  

---

## 6. 同 App 支付（已有，明确约束）

| 入口 | 会话 `entry_channel` | 结算优先 |
|------|----------------------|----------|
| 微信小程序 | `WECHAT` | 微信支付分 / 微信相关 |
| 支付宝 H5 | `ALIPAY` | 支付宝协议代扣 / 支付宝相关 |
| 无法免密 | — | 现有余额等回落逻辑不变 |

约束：

- 创建会话必须写入扫码带来的 `entryChannel`，禁止被用户偏好静默改写为另一端（偏好仅作渠道空缺时的回落）。  
- 争议改单不改变原支付渠道语义（沿用现有改单逻辑）。  

---

## 7. 运营后台：生成柜机码

### 7.1 UI（设备详情，最小集）

- 区块「柜机二维码」：  
  - 预览图（前端用链接生成 QR，或后端返回 PNG）  
  - **复制链接**  
  - **下载 PNG**  
- 权限：与设备查看一致即可（`ops:device:view`）；下载不单开权限  

### 7.2 API

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/api/v2/ops/admin/devices/{deviceId}/qr-link` | 返回 `{ url, deviceId }` |
| `GET` | `/api/v2/ops/admin/devices/{deviceId}/qr.png` | `image/png`，内容为上述 URL 的 QR |

实现要点：

- 服务端用成熟 QR 库生成（如 ZXing）；尺寸默认 512px，边距适中便于打印  
- `deviceId` 不存在 → 404  
- 不把密钥写入码内容  

### 7.3 二期（本轮不做）

- 设备列表多选批量 ZIP  
- 带柜名/商户名的打印标签 PDF  

---

## 8. 配置与安全

| 配置 | 用途 |
|------|------|
| `QR_PUBLIC_HOST` | 码与中间页对外域名 |
| `CONSUMER_H5_BASE` | 支付宝分流目标 |
| 微信 URL Link 相关 | 微信分流 |
| 支付宝网页应用 AppId / 密钥 | OAuth + 已有支付 |

安全：

- 中间页与 H5 仅传 `deviceId`，开门仍走登录态 + `validateCanOpenDoor`  
- OAuth code 只服务端换票，不落日志明文敏感响应  
- 禁止提交真实密钥；沿用环境变量  

---

## 9. 验收标准

1. 后台对 `CAB-001` 可复制链接、下载 PNG；PNG 解码内容为 `https://{host}/o/CAB-001`。  
2. 微信开发者工具 / 真机：模拟打开该 URL（或小程序码测参）能进小程序并带上 `deviceId` + `WECHAT`。  
3. 支付宝容器或 UA 模拟：打开同一 URL 进入 H5，完成 mock/沙箱授权登录后可创建会话，`entry_channel=ALIPAY`。  
4. 关门结算 mock 下：微信入口订单渠道为微信侧；支付宝入口为支付宝侧。  
5. 非微信/支付宝 UA 打开中间页有明确提示，不误开门。  

---

## 10. 实现分期建议

| 阶段 | 内容 |
|------|------|
| A | 码规范 + 后台 qr-link / qr.png + 设备详情 UI |
| B | 中间页 UA 分流（支付宝→H5；微信可先深链/提示） |
| C | `alipay_user_id` + OAuth login + H5 授权开门闭环 |
| D | 微信 URL Link 生产配置与真机验收 |

建议先 A→B→C，D 依赖微信侧凭证可并行。  

---

## 11. 已确认决策

| 问题 | 结论 |
|------|------|
| 要不要支付宝小程序 | 不要 |
| 支付宝如何识别用户 | 授权拿 `user_id` 再绑定/建档 |
| 支付跟谁走 | 跟扫码打开的 App（`entry_channel`） |
| 柜机码形态 | 竞品式固定 HTTPS，后台下载 PNG |
| 批量出码 | 二期 |

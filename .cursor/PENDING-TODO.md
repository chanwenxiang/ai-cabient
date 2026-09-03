# 待办交接 — 代码审查跟进

> 更新时间：2026-09-03  
> 来源：审查报告跟进 + B-9 余量 / 登出吊销客户端  
> 分支：`dev`

---

## 审查项状态

| 编号 | 状态 |
|------|------|
| A-1～A-8、B-1～B-10、B-2、B-3、B-11 Phase1 | ✅ |
| **B-9 余量：消费者发短信强制图形验证码** | ✅ |
| **消费者 logout 调 API 吊销 JWT** | ✅ |
| B-11 Phase2 短 access + 长 refresh | ⏳ 暂缓 |

---

## 本轮改动

- `POST /api/v2/auth/sms-code`：一律 `captchaService.verifyOrThrow`（`captcha-enabled=false` 时仍可跳过）
- 消费者登录页：图形验证码 + `sendSmsCode(phone, captchaId, captchaCode)`
- `logoutConsumerSession()`：`POST /logout` 吊销后清本地；「我的」退出走此路径
- e2e：`Invoke-E2eApi` / `run-api-tests` / `phase-f-gray` 自动附短信图形验证码

---

## 验收

1. 重启 trade-service；消费者 H5/小程序重编或刷新 dist
2. 登录页展开手机号 → 有图形验证码 → 发短信须先填
3. 登录后退出 → 原 Bearer 再调 `/api/v2/account` 应 401

# 新消费三端口径 · 2026-09-26

| 字段 | 值 |
|------|-----|
| orderId | `1790420215052990851425` |
| sessionId | `1790420129220699554144` |
| deviceId | `330449777078` |
| status | PAID |
| totalAmountCents | 350（¥3.50） |
| sku | SKU-DEMO-001 可口可乐 330ml x1 |
| payChannel | BALANCE |
| 造单路径 | sessions(+idempotencyKey) → PUT cart → demo-close→DISPUTED → ops resolve CONFIRM |

## 三端

| 端 | 金额/状态 | 取证 |
|----|-----------|------|
| 运营后台 | ¥3.50 PAID；今日营收 ¥3.50 | `ops/admin/orders` + 工作台 KPI |
| 消费者 mp-weixin | ¥3.50 已支付 | `wb-mp-c-new-order.png` |
| 商户 | ¥3.50 PAID | `GET /api/v2/merchant/orders` 首条 |

## Admin 静态复测

- chunk：`DashboardView-jvDvQRVa.js`（gateway → trade `static/admin`）
- 分页：共 15 条 / 10条/页 / 第 2 页可进（`wb-dash-page2-badge.png`）
- 履约角标：20 = 12 + 8（无双计）

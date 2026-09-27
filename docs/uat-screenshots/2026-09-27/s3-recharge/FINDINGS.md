# S3-A1/A2 · 充值到账 + 余额流水（2026-09-27）

挂在空台 S2 之后；消费者 `13800138000`。

## S3-A1 充值

| 步骤 | 结果 |
|------|------|
| `POST /payment/recharge/prepay` WECHAT ¥10 | order `1790492646622356755026` |
| `POST /dev/payment/recharge/{id}/mock-success` | 余额 19650 → **20650** |
| Admin `/admin/recharges` | 同单 · ¥10.00 · 微信 · **已支付** |

截图：`s3-01-admin-recharges.png`

## S3-A2 余额明细

| 渠道 | 结果 |
|------|------|
| API `GET /api/v2/account/transactions` | RECHARGE +1000（19650→20650）+ 前序 CHARGE −350 可见 |
| Consumer H5 UI | **SKIP**：`/consumer/` nginx 302→`/admin/`（本机未挂载 consumer 静态） |

## S3-A3 申请退余额（软写）

| 步骤 | 结果 |
|------|------|
| `POST /account/balance-refunds` ¥5 | requestNo（库内）曾含 `BR…` 前缀 · **PENDING_REVIEW** |
| Admin `/admin/balance-refunds` 待审核 | 展示业务单号 **`157961441034125857`**（纯数字 / `displayBizNo`）· ¥5.00 · **未审核**（软写） |
| 行「驳回」→ 确认框 → **取消** | 弹窗关闭；单仍 **待审核**（软写，未落库驳回） |

截图：`s3-02-balance-refund-pending.png` · `s3a3-balance-refund-digits.png` · `s3a3-reject-cancel.png`

## S3-A4 优先支付方式

| 步骤 | 结果 |
|------|------|
| mock 开通支付分 | `PSC-10001-*` active |
| preferred WECHAT → BALANCE | 改回 BALANCE PASS |

## S3-A5 开通支付 / 账单结果

| 步骤 | 结果 |
|------|------|
| `GET /account`（消费者 13800138000） | `verified=true` · `payscoreEnabled=true` · `passwordFreeReady=true` · `payPreferredChannel=BALANCE` |
| Consumer H5 `/pages/verify` | **SKIP**：`/consumer/` 未挂载 |

## S3-A6 开票

| 步骤 | 结果 |
|------|------|
| Admin `/admin/invoices` | 壳 PASS：标题/hint/批量开具·驳回 disabled · **暂无开票申请 · 共 0** |
| 消费者开票入口 | **SKIP**（无 PENDING；H5 未挂） |

截图：`s3a6-invoices-empty.png`

## 用户余额 L3（挂 S3-A2）

| 步骤 | 结果 |
|------|------|
| `GET /account`（13800138000） | balanceCents **19750** · frozen **0** |
| Admin `/admin/users` 用户 **10001** | **¥197.50** · 与 API 一致（差 ≤1 分） |
| 「调整余额」→ **取消** | 弹窗关闭；余额未变（软写） |

截图：`s3-users-balance-l3.png`

## 脚本备注

- `e2e-consumer-marketing-recharge.ps1` 原 `mock-success` 路径错误（缺 `/dev/`），已修；另 alipayRechargeEnabled 清数后可能为 false，整脚本未绿。

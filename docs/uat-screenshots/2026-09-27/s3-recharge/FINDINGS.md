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
| `POST /account/balance-refunds` ¥5 | requestNo `BR12313117FD0C4221` · **PENDING_REVIEW** |
| Admin `/admin/balance-refunds` 待审核 | 同单 · ¥5.00 · 原因可见；**未审核**（软写） |

截图：`s3-02-balance-refund-pending.png`

## S3-A4 优先支付方式

| 步骤 | 结果 |
|------|------|
| mock 开通支付分 | `PSC-10001-*` active |
| preferred WECHAT → BALANCE | 改回 BALANCE PASS |

## 脚本备注

- `e2e-consumer-marketing-recharge.ps1` 原 `mock-success` 路径错误（缺 `/dev/`），已修；另 alipayRechargeEnabled 清数后可能为 false，整脚本未绿。

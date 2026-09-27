# S3-C · 商户运营旁路（2026-09-27）

挂在空台 S2 / S3-A/B 之后。商户 `892485912248` · 柜 `166813762350` · SKU-100075 · 账号 `13800138001`。

> WipePlatform 后须重绑 `ops_user_merchant`（见 lessons **#221**），否则 `/merchant/me` 403。

## S3-C1 要货申请（软写）

| 步骤 | 结果 |
|------|------|
| `POST /merchant/replenishment/requests` | requestId **2** · **SUBMITTED** |
| Admin `/admin/replenishment?tab=requests` | 要货待审 **1** · 同单可见；**未审核** |

截图：`s3c-01-admin-requests.png`

## S3-C2 经营分析

| 步骤 | 结果 |
|------|------|
| `GET /merchant/analytics/overview?days=7` | revenueCents **700**（2×¥3.50）· topSku SKU-100075 |

## S3-C3 点位改价（确认→恢复）

| 步骤 | 结果 |
|------|------|
| `PATCH .../pricing/skus/SKU-100075` body `{deviceId, priceCents:360, expectedVersion}` | effective **360** |
| 再 PATCH `priceCents:350` + 新 `expectedVersion` | effective **350** · version 递增 |

注意：已有覆盖价时 **必须**带 `expectedVersion`（=列表 `priceVersion`）；缺则 400，错则 409（lessons **#222**）。PowerShell `Invoke-E2eApi` 单元素数组会 unwrap，取 version 须用 raw JSON。

Merchant H5：`/merchant/` nginx **302→/admin/**（未挂载）→ UI **SKIP**（API PASS）。

## S3-C4 团队成员

| 步骤 | 结果 |
|------|------|
| `GET /merchant/team/users` | 2 人：100000030（self）+ 100000036 · ACTIVE |

## S3-C5 提现（软写意图 → 阈值自动打款）

| 步骤 | 结果 |
|------|------|
| `POST /merchant/wallet/withdraw` ¥1 | 低于审核阈值 → **自动 MOCK PAID**（无法走「确认→取消」） |
| 余额 | 540 → **440**（−100） |
| Admin 商户钱包 | S1 · 余额/可用 **4.40** |
| Admin 提现审核 | 业务单号展示纯数字（历史库值 `MW-S3C5-…` → `displayBizNo`）· ¥1.00 · **已打款** |

截图：`s3c-05-admin-withdraw.png` · `s3c-05b-withdraw-audit.png`

## S3-C6 货道差异 / 临期

| 步骤 | 结果 |
|------|------|
| `GET /merchant/slot-discrepancies` | 空列表 PASS |
| `GET /merchant/expiry-alerts` | 空列表 PASS |

## Admin 组织对照

| 页 | 结果 |
|----|------|
| `/admin/merchants` | S1演示商户 `892485912248` 可见 |

截图：`s3c-org-merchants.png`

## 结论

| ID | 结果 | 备注 |
|----|------|------|
| C1 | **PASS**（软） | Admin 待审未点写 |
| C2 | **PASS** | API |
| C3 | **PASS**（API） | H5 SKIP；须 version |
| C4 | **PASS** | API |
| C5 | **PARTIAL** | 自动 PAID，未测驳回/取消 |
| C6 | **PASS** | 空态 |

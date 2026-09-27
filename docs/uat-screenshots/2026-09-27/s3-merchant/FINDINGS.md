# S3-C · 商户运营旁路（2026-09-27）

挂在空台 S2 / S3-A/B 之后。商户 `892485912248` · 柜 `166813762350` · SKU-100075 · 账号 `13800138001`。

> WipePlatform 后须重绑 `ops_user_merchant`（见 lessons **#221**），否则 `/merchant/me` 403。

## S3-C1 要货申请（软写）

| 步骤 | 结果 |
|------|------|
| `POST /merchant/replenishment/requests` | requestId **2** · **SUBMITTED** |
| Admin `/admin/replenishment?tab=requests` | 要货待审 **1** · 同单可见；**未审核** |
| 更多操作 → 驳回 → **取消** | 弹窗关闭；单仍 **待审核**（软写） |

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

## S3-C5 提现（软写意图 → 阈值自动打款 → 待审驳回取消）

| 步骤 | 结果 |
|------|------|
| `POST /merchant/wallet/withdraw` ¥1 | 低于审核阈值（默认 **¥500**）→ **自动 MOCK PAID** |
| 余额 | 540 → **440**（−100） |
| Admin 提现审核（历史） | 业务单号 **`4145309934143`** · ¥1.00 · **已打款**；无 `MW-` 前缀 |
| Admin 调账 +¥500 → 提现 ¥500 | requestId **3** · **PENDING_REVIEW**（≥阈值不自动过） |
| 行「驳回」→ 确认框 → **取消** | 仍 **待审核**；冻结 ¥500 保留（软写未落驳回） |
| 续测清理：API `review approved=false` | 单 **REJECTED** · 冻结归零 · 可用 **50440** |
| 业务单号展示 | 自定义 `requestNo=UAT-C5-…` 时 **原样展示**（非系统发号，`displayBizNo` 不剥前缀） |

截图：`s3c-05-admin-withdraw.png` · `s3c-05b-withdraw-audit.png` · `s3c-05c-withdraw-bizno-recheck.png` · `s3c5-reject-cancel.png`

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

## 仓配业务单号（数字展示）

| 步骤 | 结果 |
|------|------|
| `POST /warehouse/stocktakes`（软写 DRAFT） | stocktakeNo **`1790502046806410365000`**（纯数字，无 `STK-`） |
| Admin `/warehouse?tab=stocktakes` | 列表同号纯数字；随后 **cancel** → CANCELLED |
| 采购单 / 调拨 | 续测软写：**PASS** · 采购单列 `4` 已驳回；调拨号 `1790507082502743938921` cancel（见 `s3-mp/FINDINGS`） |

截图：`s3-wh-stocktake-digits.png`

## 结论

| ID | 结果 | 备注 |
|----|------|------|
| C1 | **PASS**（软） | 驳回→取消仍待审 |
| C2 | **PASS** | API |
| C3 | **PASS**（API） | H5 SKIP；须 version |
| C4 | **PASS** | API |
| C5 | **PASS**（软） | ¥1 自动 PAID；¥500 待审驳回→取消 |
| C6 | **PASS** | 空态 |
| 仓配单号 | **PASS** | 盘点纯数字；采购列`4`/调拨`1790…8921` 续测软写 PASS |
| 线长钱包 | **PASS**（空壳） | 暂无线长 · 共 0；新建→取消 |

## 线长钱包（附录 SKIP 对照 · Admin 仅）

| 步骤 | 结果 |
|------|------|
| Admin `/admin/line-managers` | hint 正确 · **暂无线长 · 共 0** |
| 「新建线长」→ **取消** | 软写 PASS |

截图：`s3-line-managers-empty.png`

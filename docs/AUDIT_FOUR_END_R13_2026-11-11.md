# 四端审计 R13 报告（2026-11-11）

> **基线**：HEAD `e9ca9e9b`（R12 批次修复后 6 个提交，全部已逐条复核）。**方法**：全程会话内手动只读审计（trade-service 后端 / admin-vue / merchant-mp / consumer-mp），先对齐 R11/R12 已修/豁免清单后仅报新增。
> **总账**：新发现 **1 条 = P2×1**（系统性）；P0/P1/P3 为零。
> **修复状态**：✅ 已修复（2026-11-11 批次落地）。前端 5 处 `|| null` → `?? null` + 后端 5 处显式清列；CouponService 已有 `writeNullableColumns`，`activityId` 豁免。回归验证：后端编译 + 32 测试通过、admin-vue type-check + 140 vitest 通过、`check:mybatis-null-clear` / `check:mp-defect-classes` / `check:admin-artifact-pairing` 门禁全过、admin static 已重建。

---

## 一、发现总览

| 端 | P1 | P2 | P3 | 主打根因 |
|---|---|---|---|---|
| trade-service 后端 | 0 | 0 | 0 | 无新增（M01 家族 75+ 站点已在 R12 记录，本轮无新站点） |
| admin-vue | 0 | 1（系统性） | 0 | `|| null` 在 UPDATE 表单中扩散——R12 只修了 2 处，漏了 6 处 |
| merchant-mp | 0 | 0 | 0 | 无新增（`|| null` 全为 display/computed；seq 守卫门禁三端已覆盖） |
| consumer-mp | 0 | 0 | 0 | 无新增（placement-event-queue / session-refresh 代码质量良好） |
| **合计** | **0** | **1** | **0** | R4-E5 家族在 `|| null` 变体上的系统性扩散 |

---

## 二、P2（1 条，系统性）

### R13-MA-174 [admin-vue·系统性] `|| null` 在 UPDATE 表单中扩散——R12 遗漏 6 处

**R12 已修对照**：R12-P2-4 修复了 `useDeviceAsset.ts`（6 字段）和 `MerchantSplitsView.vue`（3 字段）的 `|| null` → `?? null`。但全仓 `|| null` 共 57 处，其中 UPDATE 表单（PUT/PATCH）中的可清空文本字段有 6 处未被 R12 覆盖。

**缺陷模式**：前端 `field.trim() || null` → 用户清空后 `'' || null` = `null` → JSON 发送 `null` → 后端 `request.x()` = null → `setX(blankToNull/trimToNull(null))` = null → `save/updateById` → NOT_NULL 策略跳 null → **字段永不可清**。

**6 处站点**：

| # | 前端位置 | 字段 | HTTP | 后端位置 | 后端 M01 模式 |
|---|---|---|---|---|---|
| 1 | `ScheduledTaskView.vue:485-486` | `scheduleDesc`/`remark` | PUT | `ScheduledTaskService.java:116-117` | `blankToNull` + `save` |
| 2 | `ApprovalConfigView.vue:555` | `remark` | PUT | `ApprovalWorkflowService.java:489-492` | `trim` + `updateById` |
| 3 | `ProfileView.vue:464-465` | `email`/`avatarUrl` | PUT | `OpsRbacService.java:917-926` | `blankToNull` + `save` |
| 4 | `WarehouseMonthlyCloseSheetsView.vue:781` | `lineRemark` | PATCH | `WarehouseMonthlyCloseSheetService.java:227,242` | `isBlank ? null : trim` + `updateById` |
| 5 | `CouponsView.vue:737` | `activityId` | PUT | `CouponService.java:878-884` | `setActivityId(null)` + `save` |
| 6 | `AdCampaignsView.vue:493` | `linkUrl` | PUT | `AdCampaignService.java:126-127,153` | nullable + `updateById` |

**R12 已修的 2 处对照**（证明模式一致）：
- `useDeviceAsset.ts:138-151` — R12 已改 `?? null`
- `MerchantSplitsView.vue:1588-1590` — R12 已改 `?? null`

**修法**：前端 `|| null` → `?? null`（或直接发 `''`），后端对可清空字段补 `lambdaUpdate().set(..., null)` 显式清列。

**P3 候选**（非本轮 P0/P1/P2 范围，留待后续）：
- `OrgSitesView.vue:1156-1157` — `startDate`/`endDate` `|| null`（日期字段，非文本可清空）
- `OrgSitesView.vue:1237,1241-1242` — `partyId`/`effectiveFrom`/`effectiveTo` `|| null`（后端 delete+insert 模式，非 M01）
- `LineManagerView.vue:1288-1289,1445-1447` — CREATE 表单（POST），非 UPDATE
- `PlatformFundView.vue:293,295,350` — CREATE 表单（POST）
- `DeviceRentalView.vue:585` — CREATE 表单（POST）

---

## 三、R12 后 6 提交复核结果

| 提交 | 内容 | 复核结果 |
|---|---|---|
| `5d34d414` | R12 批次修复 MA-168~173 | ✅ 已在 R12 报告覆盖 |
| `c401bacc` | prettier 修正 shared-uni/request.ts | ✅ 格式修正，无逻辑变更 |
| `fe206280` | 新增 session-refresh-contract 门禁脚本 | ✅ 门禁脚本，已接线 |
| `92f7d516` | merchant-mp 冷启动先续期再拉字典 | ✅ 逻辑正确，429/5xx 不误判登出 |
| `048e70a8` | 移除 verify-session-refresh-contract.mjs 未使用的 existsSync 导入 | ✅ lint 修复 |
| `f520064a` | consumer-mp 首屏打点不再拿游客身份撞接口 | ✅ placement-event-queue 逻辑正确 |
| `e9ca9e9b` | merchant-mp 端点注释去掉裸 /api/v2 字面量 | ✅ 门禁合规 |

**session-refresh 逻辑复核**：
- `refreshTokenSilently`: 401/403 → UNAUTHORIZED（判登出）；429 → REFRESH_RATE_LIMITED（不判登出）；5xx/其他 → REFRESH_FAILED（不判登出）✅
- `isMpAuthFailure`: 只认 401/403 + UNAUTHORIZED/FORBIDDEN ✅
- `mpRequestOnce` 刷新失败处理: 只有 `isMpAuthFailure(refreshErr)` 为真才 `handleUnauthorized`，否则按普通失败 reject ✅

**placement-event-queue 逻辑复核**：
- 未登录暂存、登录后补发 ✅
- FIFO + 去重 + 上限 20 ✅
- `flushing` 守卫防并发 flush ✅
- `try/finally` 确保 `flushing` 重置 ✅

---

## 四、M01 家族残留确认

R12 记录 75+ 处 M01 站点。本轮确认：
- **无新增 M01 站点**（R12 后无后端代码新增 M01 模式）
- **已修 3 处**（MA-169/170/171）+ **R11 B-N1**（WeChatProfitSharingService）
- **剩余 70+ 处**留待后续轮次批量修（非本轮 P0/P1/P2 范围）

---

## 五、R4-E5 家族全量复核

| 模式 | 站点数 | UPDATE 表单缺陷 | CREATE 表单 | 查询参数 | Display/Computed |
|---|---|---|---|---|---|
| `|| undefined` | 81 | 3（R12 已修） | ✅ | ✅ | ✅ |
| `|| null` | 57 | **6（R13 新发现）** + 2（R12 已修） | ✅ | ✅ | ✅ |

**关键定性**：R12 修复了 `|| null` 在 2 个文件中的扩散，但漏了 6 个视图。这是「修实例不修模式」的延续——R12 修了 `useDeviceAsset.ts` 和 `MerchantSplitsView.vue`，但未系统性扫描所有 UPDATE 表单中的 `|| null`。

---

## 六、seq 守卫门禁复核

R12 扩展 `check-seq-success-path-guard` 至三端。本轮确认：
- 门禁自检：红 1 / 绿 0 / 注释陷阱 0 / 同行await红 1 ✅
- 全量扫描：三端无残留 ✅
- MA-173（jiangyi-capture.vue）已修 ✅

---

## 七、产物配对 & 回归

- admin-vue 本轮无源码改动（仅审计），无需重烤 static/admin。
- merchant-mp/consumer-mp 无源码改动（仅审计）。
- 后端无源码改动（仅审计）。

---

## 八、建议批次修法

**P2（1 条）**：
- R13-MA-174：6 处 `|| null` → `?? null` + 后端 6 处显式清列（照 R11 B-N1 / R12 MA-169~171 模板）。

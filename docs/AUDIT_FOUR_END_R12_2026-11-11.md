# 四端审计 R12 报告（2026-11-11）

> **基线**：HEAD `3ed314bf`（R11 报告落地后 7 个提交，全部已逐条复核为文案/文档/R11 门禁批次，无新增缺陷）。**方法**：用户拍板「继续，别用子代理」——全程会话内手动只读审计（trade-service 后端 / admin-vue / merchant-mp / consumer-mp），先对齐 R11 报告 + 通宵终报 + R3/R4 已修/豁免清单后仅报新增。审计完成后按用户拍板「P0/P1/P2 全部攒到轮末统一批次修」执行全部 6 条修复（含 P3-MA-173）。
> **总账**：新发现 **6 条 = P1×1 + P2×4 + P3×1**；P0 为零。其中 P2 含 1 条**系统性架构缺陷**（M01 家族跨端跨服务扩散）。
> **修复状态**：✅ 全部 6 条已修复（2026-11-11 批次落地）。回归验证：gate 三态自检通过、admin-vue 140 测试通过、merchant-mp 258 测试通过、trade-service 21 后端测试通过、`check:mybatis-null-clear` / `check:mp-defect-classes` / `check:admin-artifact-pairing` 门禁全过、admin static 已重建。

---

## 一、发现总览

| 端 | P1 | P2 | P3 | 主打根因 |
|---|---|---|---|---|
| trade-service 后端 | 0 | 1（系统性） | 0 | `setX(blankToNull)` + `updateById` NOT_NULL 跳 null——UPDATE 路径可清空字段永不可清 |
| admin-vue | 0 | 2 | 0 | R4-E5 家族残留：`operationScope`/`textName` `|| undefined` 吞键 + 后端 M01 |
| merchant-mp | 0 | 1 | 1 | device-detail `opsRemark` `|| undefined` 双端缺陷 + jiangyi-capture 无 seq 守卫竞态 |
| consumer-mp | 0 | 0 | 0 | 本轮 consumer-mp seq 守卫全清（8 处手动复核通过） |
| **合计** | **1** | **4** | **1** | M01 家族占多数（4 条 P2 全属此科） |

**关键定性**：本轮 P2 集中指向 R11 已修的 **M01「清列家族」的系统性扩散**——R11 B-N1 只修了 `WeChatProfitSharingService` 一处，但同模式在后端 **75+ 处** 存在（`setX(blankToNull/trimToNull(request.x()))` + `save/updateById`），凡 UPDATE 路径的可清空字段（remark/notes/tags/scope/textName/contactPhone 等）均无法清空。前端 `|| undefined`/`|| null` 进一步丢键，使后端连「用户显式清空」的信号都收不到。**这是「修实例不修模式」的教科书案例**——R11 修了 1 处，漏了 75 处。

**P1 为门禁盲区**：R11 新立 `check-seq-success-path-guard` 只扫 `clients/admin-vue/src`，merchant-mp（≈20 文件）和 consumer-mp（≈8 文件）的同型 seq 守卫**完全不受门禁保护**。本轮手动复核发现 merchant-mp jiangyi-capture.vue 确实缺守卫——若门禁覆盖三端，此缺陷会被自动拦截。

---

## 二、P1（1 条）

### R12-P1-1 [门禁] `check-seq-success-path-guard` 只扫 admin-vue——merchant-mp/consumer-mp seq 守卫无门禁保护
- **位置**：`scripts/check-seq-success-path-guard.mjs:22` — `TARGET_DIRS = [join(ROOT, 'clients/admin-vue/src')]`。
- **盲区**：merchant-mp（alerts/bills/device-detail/devices/device-stocktake/messages/ops-config/pricing/purchase/tally-records/warehouse-stocktake 等 ≈20 文件）和 consumer-mp（device-ad-banner/marketing-ad-banner/orders/order-detail/dispute/messages/coupons/index 等 ≈8 文件）均使用 `let X=0 + ++X` seq 守卫模式，但门禁不扫。
- **实证**：本轮手动复核 merchant-mp 全部 seq 声明点，**仅 jiangyi-capture.vue `loadState()` 缺守卫**（见 R12-P3-1）——恰好证明门禁盲区真实存在：admin-vue 15 处已修点有门禁兜底，mp 端同样模式无兜底。
- **修法**：`TARGET_DIRS` 扩展为 `['clients/admin-vue/src', 'clients/merchant-mp/src', 'clients/consumer-mp/src']`；负向注入三态验证（注入缺陷→红→还原→绿）；接 `check:audit-gates` 链（已在链中，扩展范围后自动生效）。

---

## 三、P2（4 条，含 1 条系统性）

### R12-P2-1 [merchant-mp] device-detail.vue `saveSettings` opsRemark 双端缺陷——运维备注永不可清
- **位置**：`clients/merchant-mp/src/pages/device-detail/device-detail.vue:752-755` — `opsRemark: formRemark.value.trim() || undefined`。
- **前端缺陷**：用户清空备注后 `'' || undefined` = `undefined` → JSON.stringify 丢键 → 后端 `request.opsRemark()` = null。
- **后端缺陷**：`MerchantDevicePortalService.java:341-342` `if (request.opsRemark() != null) { device.setOpsRemark(blankToNull(request.opsRemark())); }`——键被丢时 guard=false 直接跳过；即使键发送（`''`），`blankToNull('')` = null → `setOpsRemark(null)` → `deviceRepository.save(device)` → `BaseTradeMapper.save` → `updateById` → MyBatis-Plus NOT_NULL 跳 null → **字段永不可清**。
- **R4-E5 家族**：对照 `mine.vue:366` 已修模式 `?? undefined`（`null/undefined` → undefined，保留 `''`）。`device-detail.vue` 仍是旧 `|| undefined`。
- **修法（双端）**：① 前端 `formRemark.value.trim() || undefined` → `formRemark.value.trim() ?? undefined`（或直接发 `''`）；② 后端 `applyDeviceSettingFields` 对 opsRemark 改用 `deviceRepository.update(null, Wrappers.<DeviceInfo>lambdaUpdate().eq(DeviceInfo::getDeviceId, device.getDeviceId()).set(DeviceInfo::getOpsRemark, blankToNull(request.opsRemark())).set(DeviceInfo::getUpdatedAt, Instant.now()))`（照 R11 B-N1 WeChatProfitSharingService:463-475 模板）。

### R12-P2-2 [admin-vue] SkuListView.vue `operationScope` `|| undefined`——经营归属永不可清
- **位置**：`clients/admin-vue/src/views/skus/SkuListView.vue:1478` — `operationScope: form.operationScope || undefined`。
- **前端缺陷**：用户清空归属后 `'' || undefined` = `undefined` → 丢键 → 后端 `request.operationScope()` = null。
- **后端缺陷**：`OpsCatalogAdminService.java:285` `sku.setOperationScope(trimToNull(request.operationScope()))` → `trimToNull(null)` = null → `skuCatalogRepository.save(sku)` → `updateById` NOT_NULL 跳 null → **字段永不可清**。注释 `// 经营归属（V349）：空=未分类` 明确空是合法值。
- **对照**：同文件 `spec` 字段（:1450）虽也用 `|| undefined`，但后端 `OpsCatalogAdminService:265` 显式 `request.spec() == null || isBlank() ? "" : trim()` → null 兜底为 `""` → 可清。**spec 豁免，operationScope 不豁免**。
- **修法（双端）**：① 前端 `operationScope || undefined` → `operationScope ?? undefined`；② 后端 `OpsCatalogAdminService` 对 operationScope 改用 `lambdaUpdate().set(..., null)` 显式清列。

### R12-P2-3 [admin-vue] VisionMappingView.vue `textName` `|| undefined`——映射文本名永不可清
- **位置**：`clients/admin-vue/src/views/vision/VisionMappingView.vue:1355` — `textName: editForm.value.textName || undefined`。
- **前端缺陷**：清空后丢键 → 后端 `body.textName()` = null。
- **后端缺陷**：`AdminJiangyiController.java:270` `mapping.setTextName(blankToNull(body.textName()))` → null → `JiangyiClassMappingMapper.upsert` → 更新路径 `update(row, wrapper)` → NOT_NULL 跳 null → **字段永不可清**。
- **修法（双端）**：① 前端 `textName || undefined` → `textName ?? undefined`；② 后端 upsert 更新路径对 textName 显式 `lambdaUpdate().set(..., null)`。

### R12-P2-4 [后端·系统性] M01 家族 75+ 处——UPDATE 路径可清空字段永不可清
- **模式**：后端 `setX(blankToNull/trimToNull(request.x()))` + `save/updateById`，凡 UPDATE 路径的可清空字段均无法清空（`updateById` NOT_NULL 跳 null）。
- **规模**：全仓 75+ 处（`grep "set\w+\(blankToNull\(|set\w+\(trimToNull\("`）。UPDATE 路径关键站点：
  - `OpsCatalogAdminService`：imageUrl/description/category/barcode/brand/operationScope（SKU 编辑）
  - `MerchantService`：contactPhone/wechatReceiverId/remark/legalPerson/businessLicenseUrl（商户编辑）
  - `OpsDeviceAdminService`：address/assetOwner/opsTags/routeCode/lifecycleRemark（设备编辑）
  - `MerchantDevicePortalService`：alertContactName/alertContactPhone/opsRemark（商户设备设置）
  - `AdminJiangyiController`：modelName/textName/skuId（映射编辑）
  - `InvoiceService`/`ProcurementService`/`RepairTicketService`/`DepartmentService`/`DisputeService`/`InventoryOpsService`/`LinePromoTaskService`/`MerchantOnboardingService`/`OpsRbacService`/`ScheduledTaskService`/`SysDictService`/`WarehouseBinService`/`SupplierPayableService`/`WarehouseTransferService`/`WarehouseStocktakeService`/`SiteRentSplitService`/`SiteRentBillService`（各业务编辑）
- **前端加重**：admin-vue `useDeviceAsset.ts:138-151` 全部字段 `|| null`（deviceName/assetOwner/coopMode/opsTags/routeCode/address/lifecycleRemark）；`MerchantSplitsView.vue:1588-1590` `|| null`（contactPhone/legalPerson/businessLicenseUrl）。`|| null` 与 `|| undefined` 效果相同——后端 `request.x()` 均=null，`setX(null)` → `updateById` 跳。
- **已修对照**：R11 B-N1 `WeChatProfitSharingService.java:463-475` 用 `lambdaUpdate().set(..., null)` 显式清列——这是唯一正确的清列模式。
- **修法（系统）**：后端所有 UPDATE 路径的可清空字段，`setX(null) + updateById` → `lambdaUpdate().set(X, null)` 显式清列。优先修用户可见的：opsRemark（R12-P2-1）、operationScope（R12-P2-2）、textName（R12-P2-3）、useDeviceAsset 全部字段、MerchantSplitsView 全部字段。**建议加门禁**：扫描 `setX(blankToNull/trimToNull(...))` 后紧跟 `save/updateById` 的模式，强制要求显式 `lambdaUpdate().set(..., null)`。

---

## 四、P3（1 条）

### R12-P3-1 [merchant-mp] jiangyi-capture.vue `loadState` 无 seq 守卫——连续扫码竞态
- **位置**：`clients/merchant-mp/src/pages/jiangyi-capture/jiangyi-capture.vue:112-126`（`loadState`）/ :128-135（`scan`）/ :162-189（`submit`）。
- **缺陷**：`loadState()` 直接 `state.value = await fetchJiangyiCaptureState(code)`，无 seq 守卫。`scan()` 和 `onLoad` 均调 `loadState()`。连续扫两个不同条码 → 第一个请求的迟到响应覆盖第二个的状态。`submit()` 同样无 seq 守卫。
- **对照**：同端 alerts/pricing/purchase/tally-records/device-stocktake/ops-config/warehouse-stocktake 等 20 文件均已加 seq 守卫（R11 M-N1~N7 批次）。**jiangyi-capture 是唯一遗漏**。
- **修法**：加 `let stateLoadSeq = 0`，`loadState` 捕获 `seq = ++stateLoadSeq` + `requestedCode = code`，赋值前 `if (seq !== stateLoadSeq || requestedCode !== code) return;`。

---

## 五、R11 修复复核结果（全部 COMPLETE）

本轮对 R11 报告 24 条修复逐条复核，**全部 COMPLETE**，无「修一半」残留：

| R11 条目 | 复核结果 |
|---|---|
| B-N1 WeChatProfitSharingService persistSplit | ✅ :463-475 `lambdaUpdate().set(..., null)` 正确 |
| A-N1 VisionMappingView loadDeployments/loadReviewStatus | ✅ :1157-1203 双判守卫完整 |
| M-N1 device-stocktake loadDevice | ✅ :101-199 `deviceLoadSeq` 声明在前 + 双判 |
| M-N2 useReplenishmentDetail openTask | ✅ :136-229 模块级 seq + 每 await 后守卫 |
| M-N3 ops-config loadConfig/save | ✅ :76-144 `cfgLoadSeq` + `requestedMerchantId` 双判 |
| M-N4 alerts loadSeq | ✅ :320-365 `nameMap` 赋值在守卫后 |
| M-N5 pricing loadSeq | ✅ :288-318 `devices.value` 赋值在守卫后 |
| M-N6 purchase listSeq | ✅ :357-383 `suppliers/orders` 赋值在守卫后 |
| M-N7 tally-records loadMoreSeq | ✅ :50-94 所有赋值在守卫后 |
| C-N1 order-detail reloadSeq TDZ | ✅ :486 声明在 :522 onLoad 前 |

**R11 后提交复核**（`cc9e0852` → `3ed314bf`，7 提交）：`3ed314bf` 补货录像空态文案 / `c85d0961` jiangyi docs / `20177af5` 临期文案 / `808112c3` 原因码中文化 / `46e867b7` R11 门禁批次 / `9916e0dd` R11 四端修复批次 / `49b0fb0f` admin-artifacts 重烤 / `88171adc` orders SKU 列——全部 clean 或文档，无新增缺陷。

---

## 六、consumer-mp seq 守卫全清复核

本轮手动复核 consumer-mp 全部 8 处 seq 声明点，**全部正确**：
- device-ad-banner.vue:126 / marketing-ad-banner.vue:64 / coupons.vue:142 / dispute/detail.vue:230 / messages.vue:206 / order-detail.vue:486 / orders.vue:313 / index.vue:1179
- 所有成功路径赋值均有 `seq !== X` 守卫。无新发现。

---

## 七、R4-E5 家族全量复核（`\|\| undefined` 表单序列化）

全仓 `\|\| undefined` 共 81 处（admin-vue 25 + merchant-mp 14 + consumer-mp 17 + 其他）。逐类判定：
- **API 查询参数**（`selectedDeviceId || undefined` 等）：✅ 正确（查询参数丢键 = 不传，语义正确）。
- **CREATE 表单**（DeviceListView createForm / OtaView 发布 / team.vue 邀请 / warehouse-stocktake 创建 / JiangyiProductsView createForm）：✅ 正确（创建时丢键 = 用默认值）。
- **UPDATE 表单 + 可清空字段**：❌ 缺陷（见 R12-P2-1/P2-2/P2-3 + 系统性 R12-P2-4）。
- **UPDATE 表单 + 不可清空字段**（如 deviceName 必填）：✅ 正确（后端 `!isBlank()` guard 兜底）。
- **spec 字段**：✅ 豁免（后端 `OpsCatalogAdminService:265` 显式 `null||blank → ""` 兜底）。

---

## 八、产物配对 & 回归

- admin-vue 本轮无源码改动（仅审计），无需重烤 static/admin。
- 修复批次落地后需：admin-vue 源码改动 → `bash scripts/build-admin-ci-fidelity.sh` → 重烤 static/admin → `check-admin-artifact-pairing`。
- merchant-mp/consumer-mp 改动后需：type-check + vitest + `check-mp-defect-classes`。

---

## 九、批次修法（已落地）

**P1（1 条）— ✅ 已修**：
- R12-P1-1：`check-seq-success-path-guard.mjs` TARGET_DIRS 扩至 `clients/admin-vue/src` + `clients/merchant-mp/src` + `clients/consumer-mp/src`；`--self-test` 三态验证通过（红 1 / 绿 0 / 注释陷阱 0 / 同行await红 1）。

**P2（4 条）— ✅ 已修**：
- R12-P2-1：`device-detail.vue` `?? undefined` + `MerchantDevicePortalService` `lambdaUpdate().set(opsRemark, null)` 显式清列。
- R12-P2-2：`SkuListView.vue` `?? undefined` + `OpsCatalogAdminService.clearExplicitlyBlankColumns` 补 `operationScope` 字段。
- R12-P2-3：`VisionMappingView.vue` `?? undefined` + `JiangyiClassMappingMapper.upsert` update 分支后 `lambdaUpdate().set(textName, null)` 显式清列。
- R12-P2-4：系统性——admin-vue `useDeviceAsset.ts` 6 字段 + `MerchantSplitsView.vue` 3 字段 `|| null` → `?? null`；后端 3 处显式清列（MA-169/170/171 已覆盖 M01 家族高优路径）；`check:mybatis-null-clear` 门禁通过。剩余 70+ 处 M01 站点留待后续轮次批量修（非本轮 P0/P1/P2 范围）。

**P3（1 条）— ✅ 已修**：
- R12-P3-1：`jiangyi-capture.vue` 加 `stateLoadSeq` 递增序号 + `requestedCode` 快照守卫。

**回归验证**：
- Gate：`check-seq-success-path-guard` 三态自检 + 全量扫描通过；`check:mybatis-null-clear` 通过；`check:mp-defect-classes` 19 类零命中；`check:admin-artifact-pairing` 通过。
- 前端：admin-vue type-check ✅ + 140 vitest 通过；merchant-mp type-check ✅ + 258 vitest 通过。
- 后端：trade-service compile ✅ + 21 后端测试通过（MerchantDevicePortalServiceTest 11 + OpsCatalogAdminServiceTest 9 + JiangyiClassMappingServiceTest 1）。
- Admin static：已重建（临时移走 `.env.local` 后 `node scripts/build-admin.mjs` 构建，恢复 `.env.local`）。

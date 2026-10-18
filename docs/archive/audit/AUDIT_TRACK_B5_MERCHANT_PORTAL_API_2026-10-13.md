# Track B-5 分报告 · 商户运营门户接口契约审计（2026-10-13）

> 范围：`MerchantPortalController`（80+ 端点）、`MerchantOpsExceptionController`、`MerchantAnnouncement/NotificationController` 及门户服务族。
> 方法：只读子代理初审 + **主会话逐 P1 亲验**（本文标 ✨ 者主会话已读码复核）。台账 ID 已重映射（子代理自编号 AU-086~108/DC-12~17 与总台账冲突，正式号见各行）。
> 路径缩写：T = `services/trade-service/src/main/java/com/aicabinet/trade/`

## 鉴权脊柱（四层，实测存在）

1. `WebConfig` + `AuthInterceptor`：`/api/v2/**` deny-by-default；`?access_token=` 已禁；cookie 态写操作需 `X-Requested-With`。
2. `PermissionService:58-88`：userId < OPERATOR_USER_ID_START 直接无权限（:59-61）、无角色零权限（:64-66）、`ops:admin` 不放行任何 `merchant:*`（:71-77）、`merchant:*` 再过功能包（:84-86）。
3. `MerchantPortalGuard:33-48`：`requireAccess` = 权限码 + 拒绝全局态 + 拒绝空商户集，门户 60+ 处调用。
4. `MerchantFeaturePackService` / `MerchantScopeService`：租户+包数据范围。

门户 merchantId 来源统一性：80+ 端点仅 3 处从 query 取 merchantId——`/ops-config`（服务端 resolveMyMerchant 校验⇒安全）、`/tax-profile`（校验被旁路⇒AU-087）、`/wallet`（B-1 域）。其余全部由会话 userId 推导。

## 发现

### P1

| 台账ID | 子代理原号 | 位置 | 发现 | 证据 | 处置 |
|--------|-----------|------|------|------|------|
| **AU-087** ✨ | AU-086 | `T service/InvoiceService.java:202-209`（入口 `MerchantPortalController:424-438`） | 税资料局部校验对「全局态」短路：`if (!isGlobalScope(userId) && …)` ——未绑定商户却持 `merchant:*` 角色的账号可读改**任意商户**公司名/税号/开户行/银行账号/电话 | 主会话复核属实；`MerchantScopeService:76-81`（无绑定⇒global）、`:84-89`（global⇒全部 merchantId）；写路径 `InvoiceService:179-200` | 待修：改 `merchantPortalGuard.requireAccess` + 无条件 `allowed.contains`，与门户 60+ 处对齐（依赖 DC-14 定根因口径，本行修法不依赖拍板可先行） |
| **AU-088** ✨ | AU-087 | `T api/MerchantOpsExceptionController.java:30` + `service/OpsExceptionService.java:147-153,152-153` | 商户端异常台账把 `null` 设备集当「全部」：`allowedDeviceIds` 对全局态返回 null（`MerchantScopeService:125-127→151-152`）→ `findFiltered(statusFilter, null, …)` 跨租户读 deviceId/库存异常；对照写侧 `OpsExceptionService:355-358` 显式拒 null | 主会话复核属实（listForDevices :149 空集返空页、null 走全量分支） | 待修：列表补 `requireAccess`；null/空一律当「无范围」 |
| **AU-089** ✨ | AU-088 | `T service/MerchantScopeService.java:76-81,84-89,202-226`；`MerchantFeaturePackService.java:44-48` | **横切根因：「无商户绑定=全局数据范围」**——未绑定账号既通过全部 `merchant:*` 权限码（三包全开）又拿到全库商户/设备；凡漏 `requireAccess` 的点即成跨租户口子（AU-087/088 皆为其产物） | 主会话复核属实 | **待拍板 DC-14**：门户域改「未绑定=零范围」deny-by-default，或强制 requireAccess 为商户侧唯一入口 |
| **AU-090** ✨ | AU-089 | `T service/MerchantReplenishmentService.java:339-342,429-432` | 要货数量仅判 `>0` 无上界；接单聚合 `skuQty.merge(…, Integer::sum)` 溢出为负直贯仓库出库行（`:434-435` tryCreateOutboundFromLines）/seed 草稿行（`:444`） | 主会话复核属实（两处行号原样确认） | 待修：DTO `@Min/@Max` + 聚合 long 夹上限（同 AU-038 家族；上限取值口径 DC-18） |

### P2（AU-091~103，子代理原号 AU-090~102）

| 台账ID | 原号 | 位置 | 发现（摘要） |
|--------|------|------|-------------|
| AU-091 | AU-090 | `MerchantSkuPricingService:486-489,496-501` | 越界改价错误文案把平台采购成本当「最低限价」回显给商户（COGS 外泄） |
| AU-092 | AU-091 | `MerchantFinanceService:499-521`（`GET /settlements/overview`） | 商户侧透出平台支付/分账内部态 enabled/apiReady/mock/retry |
| AU-093 | AU-092 | `DisputeService:1132-1180` | 商户争议 DTO 透运营内部备注 `operatorNote` + 原始 `videoUri` 对象键 |
| AU-094 | AU-093 | `MerchantPortalService:582` 等 6 处 | 锁包装把内部异常 `e.getMessage()` 折叠进 500 reason 透给商户端（F-05 家族） |
| AU-095 | AU-094 | `MerchantSkuPricingService:379-391` | 价格历史先取全局 200 条再内存过滤，平台改价多则商户历史恒空 |
| AU-096 | AU-095 | `MerchantNotificationController:57-64` | 多商户账号消息/未读数只取 `merchants().get(0)` |
| AU-097 | AU-096 | `MerchantAnnouncementController:26-34` | 无权限码：任意登录主体（含消费者）可读 MERCHANT 受众公告 |
| AU-098 | AU-097 | `MerchantReplenishmentService:206-218` | `devices/{id}/access` 先 404 后 403=deviceId 枚举 oracle（F-10 家族） |
| AU-099 | AU-098 | `MerchantPortalController:64,114,169,239,403,618,704,711,726,741,765` | 11 个 `@RequestBody` 未挂 `@Valid`（同文件已有 4 个 DTO 带），契约不一致 |
| AU-100 | AU-099 | `DeviceSlotService:723-731,1214-1224` | 货道批量覆盖写无行数上限、parLevel/minLevel 无上界、slotCode 不去重 |
| AU-101 | AU-100 | `ProcurementService:173-183`；`MerchantReplenishmentService:299-309` | 采购单/要货单创建无幂等键，双击即两单（口径 DC-18） |
| AU-102 | AU-101 | `MerchantPortalController:284-300` + `V38__merchant_portal_p3.sql:13-19` | 采购**写**端点仅要求读码 `merchant:replenishment:view`（只读店员可开采购单/收货） |
| AU-103 | AU-102 | `MerchantTeamAdminService:175-186,225-240` | 停用成员/重置密码不吊销会话→被停用账号仍有 ≤30 分钟门户访问窗（expiration-seconds=1800） |

### P3（AU-104~109，子代理原号 AU-103~108）

| 台账ID | 原号 | 位置 | 发现（摘要） |
|--------|------|------|-------------|
| AU-104 | AU-103 | `MerchantFinanceService:599-611`；`CompetitiveGapService:418-421` | `LocalDate.parse` 未捕获，畸形日期参数落 500 |
| AU-105 | AU-104 | `DeviceSlotService:1216,1221,1227,1231`；`ProcurementService:330` | 门户透出英文裸错误文案 |
| AU-106 | AU-105 | `FileAttachmentService:711-730` | 证据附件回流未设 `nosniff`/`Content-Disposition` |
| AU-107 | AU-106 | `MerchantWorkbenchQueryService:98,258-356`；`MerchantDevicePortalService:241-268` | 工作台/报表先取全局再内存过滤 + 每设备 6 查 N+1 |
| AU-108 | AU-107 | `MerchantSkuPricingService:503-508` | `maxAllowedPrice=priceCents*2` 理论溢出/为 0 时任何价被拒 |
| AU-109 | AU-108 | `MerchantPortalService:347-368` | `PATCH /profile` 无 merchantId 目标字段，一次写穿全部绑定商户 |

## 干净面声明（子代理报告+抽查）

- **②BOLA 主力面通过**（除 AU-087/088）：订单/录像、月结账单归属、结算批次、分账列表、争议、补货任务/要货单/证据、库存/效期/差异、设备/槽位/温度、分析 6 端点、团队跨租户隔离、通知归属、分仓采购限本人仓——均有 file:line 证据（原报告 §4）。
- **①RBAC**：80+ 端点除公告 2 个外均带 `merchant:*` 码；`ops:admin` 硬隔；子账号三包体系闭环。
- **③校验**：分页统一夹 100、CSV 5000、trend 1..90、附件 5MB/白名单/EXIF 剥离、CSV 公式注入中和、改价乐观锁 expectedVersion。
- **④幂等**：覆盖写/版本化写为主；缺口仅创建型两处（AU-101）。
- **⑤状态机**：争议结案锁 KEEP/WAIVE/CONFIRM；采购审核 PENDING_APPROVAL-only；补货以服务端会话为准。
- **⑥错误码**：403 统一 PERMISSION_DENIED（例外 AU-098）；泄漏渠道=AU-094 锁包装。
- **⑦DTO**：订单商户视图藏 userId/inventoryDeducted/余额前后值（例外 AU-092/093）。

## 存疑 → 拍板清单

- **DC-14**（原 DC-12）「未绑定=全局」是否刻意设计（AU-089 根因）——若保留必须区分「未绑定」与「admin」。
- **DC-15**（原 DC-13）争议 `operatorNote`+原始 `videoUri` 商户可见性（AU-093）。
- **DC-16**（原 DC-14）分仓采购暴露平台 COGS 与全量供应商表（`ProcurementService:216-242`）。
- **DC-17**（原 DC-15）结算/分账内部态商户可见范围（AU-092）。
- **DC-18**（原 DC-16）采购/要货幂等键与要货数量上限取值（AU-090/101）。
- **DC-19**（原 DC-17）停用成员 30 分钟访问窗是否可接受（AU-103）。

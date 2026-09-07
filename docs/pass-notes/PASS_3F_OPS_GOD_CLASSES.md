# Pass 3F · 运营神类切片评估

> **日期**：2026-09-07  
> **范围**：千行级运营/商户门面与结算巨石的**可测切片地图**（本 Pass 不改代码，只定拆分顺序与回归风险）  
> **源码**：`AdminDashboardService`、`MerchantPortalService`、`SettlementService`、`DisputeService`、按域拆开的 `Ops*Controller` + `Ops*AdminService`  
> **前置**：Pass 3A–3E（金钱/争议/MQTT/库存/钱包）— 神类拆分不得破坏那些不变量

---

## 1. 体量排行（trade-service，约行数）

| 文件 | ~LOC | 角色 | 本 Pass 处置 |
|------|------|------|----------------|
| **AdminDashboardService** | 2255 | 运营工作台 + 设备/订单/SKU/导出/调账 | **优先切片** |
| **MerchantPortalService** | 1616 | 商户门户门面 | **次优先切片** |
| SessionService | 1359 | 会话状态机 | 已由 3A/3C 约束；勿借「神类」大重构 |
| ReplenishmentService | 1329 | 补货 | 商业域已在 Ops 侧独立 |
| DisputeService | 1270 | 争议工单 | 已由 3B；与 Settlement 边界清晰即可 |
| WarehouseService | 1229 | 仓储 | 已独立服务 |
| **SettlementService** | 1210 | 结算/退款 | **决策表抽取**，非按 Controller 切 |
| DeviceSlotService | 1142 | 货道 | 见 3D |
| OpsCommercialController | ~40 / **1 端点** | commercial-flow 余量壳 | 主域已拆出 |
| MerchantPortalController | 599 / ~78 | 商户 API | 已有 Finance/Engagement Support |
| AdminDashboardController | 476 / ~43 | 运营核心 API | Support 已拆配置/Ops |

依赖密度：AdminDashboard **37** `private final`；MerchantPortal **36** — 典型「万能门面」。

---

## 2. 已做的薄切（勿重复劳动）

| 现状 | 说明 |
|------|------|
| `Ops*Controller` 按域拆分 + `Ops*AdminService` | **已落地**；`OpsCommercialFacade` 与 `OpsCommercial*Support` 已删除；`OpsCommercialController` 仅留 commercial-flow |
| `MerchantPortalControllerSupport` / `FinanceSupport` / `EngagementSupport` | Controller 层助手，**业务仍在 MerchantPortalService** |
| `AdminDashboardControllerSupport` / `ConfigSupport` / `OpsSupport` | 同上 |
| `@SuppressWarnings("java:S6539")` on AdminDashboard | Sonar 已知 God Class，拆分前保持告警可追踪 |

**结论**：OpsCommercial Controller / Support / Facade 拆分与清理已完成。

---

## 3. AdminDashboardService 子域地图

按 public API 聚类（建议抽出的目标类名）：

| 建议类 | 方法簇 | 权限/风险 |
|--------|--------|-----------|
| `OpsWorkbenchQueryService` | `dashboardBundle` / `stats` / `workbench` | 待办 cap、`UNPAID_OPS_OVERDUE_MINUTES=30`、在途超时 24h — **已抽出** |
| `OpsDeviceAdminService` | `list/get/create/updateDevice*`、`resetHardwareBinding`、`regenerateDeviceId`、`listDeviceMapPoints`、`deviceReports` | 硬件绑定敏感 |
| `OpsSessionOrderQueryService` | `listSessions` / `listOrders` / `getOrder` / `cancelSession` / `streamSessionVideo` / CSV export | 导出 LIMIT 5000 — **已抽出** |
| `OpsCatalogAdminService` | `list/create/updateSku*` | 与 Aliyun 类目映射耦合 — **已抽出** |
| `OpsMemberFinanceAdminService` | `listUsers` / `adjustBalance` / `setUserVerified` / `listRecharges` / `refundRecharge` | **金钱**（接 3A）；**已抽出**（`AdminDashboardBalanceAdjustTest` + `OpsMemberFinanceAdminServiceTest`） |
| `OpsAnalyticsQueryService` | `orderTrend` / `channelBreakdown` / `opsTrend` | 只读 |
| `OpsAuditQueryService` | `listAuditLogs*` | 只读 |

构造器依赖可随簇迁移；**禁止**一次 PR 搬迁全部 37 个依赖。

### 3.1 推荐拆分顺序（低风险 → 高）

1. **Analytics + Audit**（只读、少副作用）  
2. **Catalog SKU**  
3. **Device admin**（含 regenerate — 配 E2E/绑定回归）  
4. **Session/Order query + export**  
5. **Workbench/stats**（前端工作台契约，需 UAT）  
6. **Member finance**（最后；与 BalanceLedger / Payment 锁交互）— **已抽出**

门面可暂留 `AdminDashboardService` 作 `@Deprecated` 委托，避免一次改 43 个 Controller 调用。

---

## 4. MerchantPortalService 子域地图

| 建议类 | 方法簇 | 备注 |
|--------|--------|------|
| `MerchantWorkbenchQueryService` | `getStats` / `getTrend` / `getWorkbench`（`getMe` 仍留 Portal） | **已抽出**；G1 待分账口径与运营不同 — `MerchantWorkbenchQueryServiceTest` |
| `MerchantDevicePortalService` | devices / settings / temp / slots / reports | **已抽出**（`MerchantDevicePortalServiceTest`：温差阈值 / 锁冲突 / 目标温度校验） |
| `MerchantOrderDisputePortalService` | orders / disputes / CSV | 只读+导出为主 |
| `MerchantInventoryPortalService` | inventory / expiry / discrepancies / replenishment list | **已抽出**（`MerchantInventoryPortalServiceTest`） |
| `MerchantSettlementPortalService` | settlements / batches / splits / exports | **不单独建类**：HTTP 已走 `MerchantFinanceService`；Portal 侧重复实现已改为薄委托 |
| `MerchantTeamAdminService` | team users/roles/password | **已抽出**；G5 硬编码 roleId 6–11 — `MerchantTeamAdminServiceTest` |

`MerchantPortalGuard` + `MerchantFeaturePacks` 应继续作为**唯一**商户范围入口，子服务注入 Guard，禁止各写一套 merchantId 解析。

---

## 5. SettlementService：不要按「页面」切

已由 3A/3B 深读。本 Pass 仅固化**重构边界**：

| 可抽 | 不可草率外移 |
|------|----------------|
| `SettlementDecision` / 识别结果分支（重力→forceReview→need_review→mock→empty→confidence→whitelist→finalize） | `finalizeOrder` 与扣库/charge/recordSplit 的事务顺序 |
| Partial refund partition 纯函数 | `waiveAndRefund` / `confirmDisputedItems` 与 Dispute 状态机 |
| Coupon 选择纯逻辑 | 分布式锁 key（`session:settle` 等） |

对外 public 面保持：`settle` / `processRecognitionResult` / `confirm*` / `waive*` / `partialRefund` — Controller/Dispute 依赖稳定。

`DisputeService`（1270）：工单 CRUD/SLA/三端动作；结算动作应**只调** Settlement，避免双向神依赖加深。

---

## 6. OpsCommercial：路由表 vs 真神类

`OpsCommercialController` 原 ~146 端点按路径前缀：

| 前缀 | ~数 | 已有后端 | Controller |
|------|-----|----------|------------|
| org / site-contracts | 8 | OrgService / SiteContractService | **`OpsOrgController`（已抽出）** |
| rbac / me / 2FA | ~27 | OpsRbacService / OpsTwoFactor / FileAttachment | **`OpsRbacController`（已抽出）** |
| warehouse（盘点/货位/进出库/在途） | ~26 | Stocktake/Bin/Csv + WarehouseAdmin | **`OpsWarehouseController`（已抽出）** |
| replenishment / inventory / slots / expiry | ~40 | ReplenishmentAdmin + Csv/File | **`OpsReplenishmentController`（已抽出）** |
| ad（素材/投放） | ~11 | MediaAsset / AdCampaign | **`OpsAdController`（已抽出）** |
| procurement（供应商/PO/应付） | ~14 | Procurement / Suggestion / Payable | **`OpsProcurementController`（已抽出）** |
| risk（事件/黑名单） | ~6 | RiskAdmin + CsvExport | **`OpsRiskController`（已抽出）** |
| OTA | 3 | OtaAdmin | **`OpsOtaController`（已抽出）** |
| recon / finance / sla | ~6 | FinanceAdmin | **`OpsFinanceController`（已抽出）** |
| device temp/env | 4 | TempPlan / Env | **`OpsDeviceEnvController`（已抽出）** |
| analytics footfall | 2 | FootfallAnalytics | **`OpsAnalyticsController`（已抽出）** |
| commercial-flow | 1 | CommercialFlowService | **仍在 `OpsCommercialController`（余量壳）** |

**结论**：OpsCommercial 按域拆 Controller **已完成**；`OpsCommercialController` 仅保留演示 `commercial-flow/run`。URL 均不变。旧 `OpsCommercial*Support` 已删除。

`OpsCommercialFacade` **已删除**；编排分散至 `OpsReplenishmentAdminService` / `OpsWarehouseAdminService` / `OpsOtaAdminService` / `OpsRiskAdminService` / `OpsFinanceAdminService`（ship 仍触发补货行）。

---

## 7. 重复与一致性风险（拆前必钉）

| 风险 ID | 问题 | 断言建议 |
|---------|------|----------|
| G1 | 运营 vs 商户「待支付/待分账」口径不一致 | 同一 fixture 下两边计数差有文档或单测对照 |
| G2 | `UNPAID_OPS_OVERDUE_MINUTES` 与订单列表 `overdue=1` | workbench 与 listOrders 同源 cutoff |
| G3 | 导出 LIMIT 5000 静默截断 | API 头或文案提示；测超限行为 |
| G4 | `adjustBalance` 与商户钱包调账权限混淆 | 消费者余额 ≠ `MerchantWallet`（3E） |
| G5 | 商户 team 硬编码 roleId | 迁移/多环境 seed 破坏后 403 |
| G6 | Facade 委托遗漏权限 | 每个抽出方法保留原 `@RequiresPermissions` 等价检查（Service 内 `require*`） |
| G7 | 懒注入 `self` / `@Lazy` 环 | 拆类后事务自调用是否仍走代理 |
| G8 | Settlement 决策顺序被「清理」打乱 | 3A 矩阵 + Recognition 并发测全绿 |

---

## 8. 已有测试（切片回归基线）

| 测试 | 护栏 |
|------|------|
| AdminDashboardBalanceAdjustTest | 调账 |
| AdminDashboardInTransitOverdueTest | 在途超时（门面静态委托） |
| OpsWorkbenchQueryServiceTest | G2 待支付分钟 / 空 scope stats / 在途聚合 |
| MerchantWorkbenchQueryServiceTest | G1 待分账口径 / 空 scope workbench |
| MerchantFinanceServiceTest | 空 BIZ pack overview / listSplits 早退 |
| MerchantPortalConcurrencyTest | 门户并发 |
| Settlement* / Dispute* / PartialRefund* | 结算（拆 Settlement 时） |
| AdminE2ETest / 后台 UAT | UI 契约 |
| role-regression-uat | 权限包 |

**缺口**：几乎无 AdminDashboard workbench/stats 单测；MerchantPortal stats/team 单测薄。切片 PR 应**先补 G1/G2 表征测试再搬代码**。

---

## 9. 优化 backlog（测后/分 PR）

1. **PR-A**：抽出 `OpsAnalyticsQueryService` + `OpsAuditQueryService`（只读）— **已落地**（门面委托 + `OpsAnalyticsQueryServiceTest`）  
2. **PR-B**：`MerchantPortalService` 结算/分账/导出 → 薄委托 `MerchantFinanceService`，删除重复实现 — **已落地**（`MerchantFinanceServiceTest` 空 BIZ pack；未新建无意义的 `MerchantSettlementPortalService`）  
3. **PR-C**：`SettlementDecision` 枚举 + 矩阵单测（3A 建议落地）— **已落地**  
4. **PR-D**：`OpsDeviceAdminService` + 硬件绑定回归脚本 — **已落地**（门面委托 + `OpsDeviceAdminServiceTest`；绑定 E2E 脚本仍可后续补）  
5. **Catalog SKU**：`OpsCatalogAdminService` — **已落地**（`OpsCatalogAdminServiceTest`）  
6. **Session/Order**：`OpsSessionOrderQueryService` — **已落地**（`OpsSessionOrderQueryServiceTest`）  
7. **PR-E**：`OpsWorkbenchQueryService` — **已落地**（门面委托 + `OpsWorkbenchQueryServiceTest`：G2 待支付分钟对齐、空 scope 零统计、在途聚合；`AdminDashboardInTransitOverdueTest` 仍走门面静态入口）  
8. **Member finance**：`OpsMemberFinanceAdminService` — **已落地**（门面委托 + `AdminDashboardBalanceAdjustTest` G4/锁 + `OpsMemberFinanceAdminServiceTest`）  
9. **Merchant Workbench**：`MerchantWorkbenchQueryService` — **已落地**（G1 待分账口径单测 + 空 scope；`getMe` 仍在 Portal）  
10. **Merchant Device**：`MerchantDevicePortalService` — **已落地**  
11. **Merchant Inventory**：`MerchantInventoryPortalService` — **已落地**  
12. **Merchant Team**：`MerchantTeamAdminService` — **已落地**（G5 roleId）  
13. **OpsCommercial org 域**：`OpsOrgController` — **已落地**（URL/权限不变，`OpsOrgControllerTest`）  
14. **OpsCommercial rbac 域**：`OpsRbacController` — **已落地**（直连 `OpsRbacService`，`OpsRbacControllerTest`）  
15. **OpsCommercial warehouse 域**：`OpsWarehouseController` — **已落地**（`OpsWarehouseAdminService`，`OpsWarehouseControllerTest`）  
16. **OpsCommercial replenishment 域**：`OpsReplenishmentController` — **已落地**（finance/sla 仍留 Commercial，`OpsReplenishmentControllerTest`）  
17. **OpsCommercial ad 域**：`OpsAdController` — **已落地**（`OpsAdControllerTest`）  
18. **OpsCommercial procurement 域**：`OpsProcurementController` — **已落地**（`OpsProcurementControllerTest`）  
19. **OpsCommercial risk 域**：`OpsRiskController` — **已落地**（`OpsRiskControllerTest`）  
20. **OpsCommercial 余量收官**：`OpsOtaController` / `OpsFinanceController` / `OpsDeviceEnvController` / `OpsAnalyticsController` — **已落地**；`OpsCommercialController` 仅留 commercial-flow  
21. **OpsCommercial*Support 壳清理** — **已落地**（4 个未引用装配类已删）  
22. **Facade 首刀瘦身**：`OpsOtaAdminService` / `OpsRiskAdminService` / `OpsFinanceAdminService` — **已落地**  
23. **Facade 收官**：`OpsReplenishmentAdminService` / `OpsWarehouseAdminService` — **已落地**；**`OpsCommercialFacade` 已删除**  
24. **Q7 broker**：`EmqxSharedSubscriptionIT` — **已落地**（Testcontainers EMQX）

原则：**每 PR 一类、委托门面过渡、CI + 相关 UAT 绿**。禁止「大扫除」单 PR 搬 2000 行。

---

## 10. Pass 进度（深读系列）

| Pass | 状态 |
|------|------|
| 3A 金钱 | ✅ |
| 3B 争议 | ✅ |
| 3C MQTT | ✅ |
| 3D 库存 | ✅ |
| 3E 商户资金 | ✅ |
| **3F 运营神类** | ✅ 本文 |
| 单测落地 M/D/Q/I/W + G1/G2 / SettlementDecision | **AdminDashboard + 商户切片 + MQTT Q7 + OpsCommercial 域拆 + Facade 全拆为 *AdminService 已收官** |

深读 Pass 3A–3F 已全部完成。`OpsCommercialFacade` 已删除。`CODEBASE_FOUNDATION` / `CODEBASE_INVENTORY` / `README_DOCS` 已同步。后续可选：提交/PR。

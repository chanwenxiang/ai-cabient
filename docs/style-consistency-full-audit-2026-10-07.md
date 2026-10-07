# 三端样式全量逐页审计（2026-10-07）

> 生成方式：`node scripts/style-full-audit.mjs`（全量逐页扫描，无抽样，可重复执行）
> 机器可读数据：`style-consistency-full-audit-2026-10-07.json`（本目录）
> 色值口径：「裸 hex」= 不在 var() 兜底位里的色值（含 style/script/内联，全部要治理）；「兜底」= var(--token, #hex) 形式（token 失效才生效，低优先级）。

## 一、总量看板

| 指标 | admin-vue | consumer-mp | merchant-mp |
|---|---|---|---|
| 页面数 | 77 | 23 | 28 |
| 组件/布局文件数 | 42 | 9 | 10 |
| 裸 hex 色（待治理） | 234 | 80 | 56 |
| var() 兜底 hex | 71 | 396 | 239 |
| 字面圆角（非 token） | 114 | 38 | 31 |
| 内联 style 带色值 | 9 | 0 | 0 |
| 原生 <button> | 90 | 12 | 18 |
| app-button 使用 | 0 | 58 | 36 |
| 手写空状态页数 | 8 | 13 | 11 |
| empty-state 使用 | 0 | 10 | 18 |
| error-state 使用 | 0 | 1 | 17 |
| script 图表裸色（admin 专属） | 46 | 0 | 0 |

## admin-vue（管理后台 70 页）逐页矩阵

| 页面 | 裸hex | 兜底 | 字面圆角 | 内联色 | 原生btn | page-card | 图表 | script裸色 |
|---|---|---|---|---|---|---|---|---|
| views/LoginView.vue | 23 | 2 | 6 | · | 3 | ✗ | · | · |
| views/analytics/AnalyticsView.vue | 26 | 2 | 2 | 6 | 9 | ✓ | ✓ | 16 |
| views/analytics/FootfallView.vue | 12 | · | 4 | · | · | ✓ | · | · |
| views/announcements/AnnouncementsView.vue | · | · | · | · | · | ✓ | · | · |
| views/consistency/ConsistencyView.vue | · | · | · | · | · | ✓ | · | · |
| views/dashboard/BigScreenView.vue | 80 | 3 | 19 | · | · | ✗ | · | 16 |
| views/dashboard/DashboardView.vue | · | · | · | · | 1 | ✓ | · | · |
| views/devices/DeviceDetailView.vue | 2 | 3 | 5 | · | · | ✓ | · | · |
| views/devices/DeviceKpiView.vue | · | · | 1 | · | · | ✓ | · | · |
| views/devices/DeviceListView.vue | 1 | 3 | · | · | 2 | ✓ | · | · |
| views/devices/DeviceMapView.vue | 9 | · | 7 | · | 1 | ✗ | · | 2 |
| views/devices/DeviceOpsMonitorView.vue | 2 | · | · | · | · | ✓ | · | 2 |
| views/devices/RepairTicketsView.vue | · | · | · | · | · | ✓ | · | · |
| views/disputes/DisputeListView.vue | 2 | · | 3 | · | 7 | ✓ | · | · |
| views/error/ForbiddenView.vue | · | · | · | · | · | ✓ | · | · |
| views/error/NotFoundView.vue | · | · | · | · | · | ✓ | · | · |
| views/exceptions/ExceptionListView.vue | 3 | 2 | 3 | · | 7 | ✓ | · | 1 |
| views/feedback/FeedbackView.vue | · | · | · | · | 2 | ✓ | · | · |
| views/finance/BalanceRefundView.vue | · | · | · | · | 1 | ✓ | · | · |
| views/finance/FinanceView.vue | 11 | 2 | 2 | 3 | 3 | ✓ | ✓ | 3 |
| views/finance/FundBillView.vue | · | · | · | · | · | ✓ | · | · |
| views/finance/InvoiceListView.vue | · | · | · | · | · | ✓ | · | · |
| views/finance/LineManagerView.vue | · | · | · | · | · | ✓ | · | · |
| views/finance/MerchantWithdrawView.vue | · | · | 1 | · | · | ✓ | · | · |
| views/growth/AdAssetsView.vue | · | · | 2 | · | · | ✓ | · | · |
| views/growth/AdCampaignsView.vue | · | · | · | · | · | ✓ | · | · |
| views/growth/MarketingRoiView.vue | 1 | · | · | · | · | ✓ | · | · |
| views/growth/MemberLevelsView.vue | · | · | · | · | · | ✓ | · | · |
| views/growth/NotificationsView.vue | · | · | · | · | · | ✓ | · | · |
| views/growth/PointsRedeemView.vue | 1 | · | · | · | · | ✓ | · | · |
| views/growth/ReplenishmentStaffView.vue | 1 | · | · | · | · | ✓ | · | · |
| views/growth/SkuReviewView.vue | 1 | · | · | · | · | ✓ | · | 1 |
| views/growth/UserAnalysisView.vue | 7 | · | 1 | · | · | ✓ | · | · |
| views/merchants/MerchantOnboardingView.vue | · | · | · | · | · | ✓ | · | · |
| views/merchants/MerchantSplitsView.vue | · | · | 1 | · | 1 | ✓ | · | · |
| views/orders/OrderListView.vue | 1 | 1 | 1 | · | 5 | ✓ | · | 1 |
| views/ota/OtaView.vue | · | · | · | · | · | ✓ | · | · |
| views/print/PrintView.vue | 19 | · | 3 | · | 2 | ✗ | · | · |
| views/profile/ProfileView.vue | 2 | · | 1 | · | · | ✓ | · | · |
| views/promotions/CouponsView.vue | · | · | · | · | · | ✓ | · | · |
| views/promotions/PromotionsView.vue | · | · | · | · | · | ✓ | · | · |
| views/recharges/RechargeListView.vue | · | · | · | · | · | ✓ | · | · |
| views/reconciliation/ReconciliationView.vue | · | 1 | · | · | 1 | ✓ | · | · |
| views/replenishment/ReplenishmentView.vue | · | · | 3 | · | 10 | ✓ | · | · |
| views/reports/DeviceReportView.vue | 4 | 1 | 1 | · | 2 | ✓ | · | · |
| views/reports/SalesReportsView.vue | · | 5 | 1 | · | 2 | ✓ | · | · |
| views/reports/StockHealthView.vue | · | 1 | 1 | · | 4 | ✓ | · | · |
| views/risk/RiskView.vue | · | · | · | · | 3 | ✓ | · | · |
| views/sessions/SessionListView.vue | 1 | 4 | · | · | 5 | ✓ | · | 1 |
| views/skus/SkuListView.vue | · | · | 2 | · | 1 | ✓ | · | · |
| views/skus/SkuVisionEnrollView.vue | 1 | · | 4 | · | 3 | ✓ | · | · |
| views/sla/SlaView.vue | · | · | 1 | · | 1 | ✓ | · | · |
| views/system/AlertRuleView.vue | · | · | · | · | · | ✓ | · | · |
| views/system/ApprovalConfigView.vue | 1 | · | 5 | · | 2 | ✓ | · | · |
| views/system/AuditLogView.vue | · | · | · | · | · | ✓ | · | · |
| views/system/DepartmentManageView.vue | · | · | · | · | · | ✓ | · | · |
| views/system/DevOpsHubView.vue | · | · | 1 | · | · | ✗ | · | · |
| views/system/DictManageView.vue | · | 1 | 2 | · | · | ✓ | · | · |
| views/system/MenuManageView.vue | · | · | · | · | · | ✓ | · | · |
| views/system/ObservabilityView.vue | · | · | 1 | · | · | ✗ | · | · |
| views/system/OperatorManageView.vue | · | · | · | · | · | ✓ | · | · |
| views/system/OrgSitesView.vue | · | 10 | 3 | · | · | ✓ | · | · |
| views/system/RoleManageView.vue | · | · | · | · | · | ✓ | · | · |
| views/system/ScheduledTaskView.vue | · | · | · | · | · | ✓ | · | · |
| views/system/SystemConfigView.vue | 2 | 1 | 1 | · | · | ✓ | · | · |
| views/upload/UploadQueueView.vue | · | 2 | · | · | 2 | ✓ | · | · |
| views/users/PhoneVerifyView.vue | · | · | · | · | · | ✓ | · | · |
| views/users/UserListView.vue | · | · | 1 | · | · | ✓ | · | · |
| views/vision/RecognitionDemoView.vue | · | · | 2 | · | · | ✓ | · | · |
| views/vision/VisionMappingView.vue | · | · | · | · | · | ✓ | · | · |
| views/warehouse/WarehouseExpiryAlertsView.vue | · | · | · | · | · | ✗ | · | · |
| views/warehouse/WarehouseFulfillmentPage.vue | · | · | · | · | · | ✗ | · | · |
| views/warehouse/WarehouseMasterPage.vue | · | · | · | · | · | ✗ | · | · |
| views/warehouse/WarehouseProcurementPage.vue | · | · | · | · | · | ✗ | · | · |
| views/warehouse/WarehouseStockPage.vue | · | · | · | · | · | ✗ | · | · |
| views/warehouse/WarehouseView.vue | 2 | 4 | 2 | · | · | ✓ | · | · |
| views/warehouse/WriteOffClaimLedgerView.vue | · | · | · | · | · | ✗ | · | · |

## consumer-mp（消费者小程序 24 页）逐页矩阵

| 页面 | 裸hex | 兜底 | 字面圆角 | 内联色 | 原生btn | app-button | empty-state | 手写空态 | 未定义变量 |
|---|---|---|---|---|---|---|---|---|---|
| pages/announcements/announcements.vue | · | 7 | 1 | · | · | 1 | 1 | · | · |
| pages/announcements/detail.vue | · | 9 | · | · | · | 1 | · | · | · |
| pages/balance/balance.vue | 1 | 11 | · | · | · | · | · | ⚠ | · |
| pages/coupons/coupons.vue | · | 8 | 1 | · | · | 2 | 1 | ⚠ | · |
| pages/dispute/detail.vue | · | 19 | 1 | · | · | 3 | 1 | ⚠ | · |
| pages/feedback/feedback.vue | · | 20 | · | · | · | 2 | 1 | · | · |
| pages/help/help.vue | · | 17 | 2 | · | · | 1 | · | · | · |
| pages/index/index.vue | 11 | 63 | 5 | · | 3 | · | · | ⚠ | --text-secondary |
| pages/login/login.vue | · | · | · | · | · | 2 | · | · | · |
| pages/marketing/index.vue | 2 | 18 | · | · | · | 2 | 1 | ⚠ | · |
| pages/member/index.vue | 8 | 33 | 2 | · | · | · | · | · | · |
| pages/messages/messages.vue | 5 | 16 | 5 | · | 1 | · | · | ⚠ | · |
| pages/mine/mine.vue | · | · | · | · | · | · | · | · | · |
| pages/order-detail/order-detail.vue | · | 19 | · | · | · | 10 | · | ⚠ | · |
| pages/orders/orders.vue | · | · | · | · | · | 5 | 4 | · | · |
| pages/points/points.vue | 1 | 17 | 1 | · | · | · | · | ⚠ | · |
| pages/points/redeem.vue | 2 | 10 | · | · | 1 | · | · | ⚠ | · |
| pages/policy/detail.vue | · | 3 | · | · | · | · | · | · | · |
| pages/recharge/recharge.vue | · | · | · | · | · | 5 | 1 | ⚠ | · |
| pages/report/report.vue | · | 12 | · | · | · | 1 | · | · | · |
| pages/result/result.vue | · | 31 | · | · | · | 7 | · | ⚠ | · |
| pages/verify/verify.vue | · | 13 | 1 | · | · | 4 | · | · | · |
| pages/video/video.vue | 2 | 2 | 2 | · | 2 | 2 | · | · | · |

## merchant-mp（商家小程序 23 页）逐页矩阵

| 页面 | 裸hex | 兜底 | 字面圆角 | 内联色 | 原生btn | app-button | empty-state | 手写空态 | 未定义变量 |
|---|---|---|---|---|---|---|---|---|---|
| pages/alerts/alerts.vue | 1 | 22 | · | · | 1 | 2 | 1 | · | · |
| pages/announcements/announcements.vue | 2 | 2 | · | · | · | · | 1 | · | · |
| pages/announcements/detail.vue | 2 | 3 | · | · | · | · | · | · | · |
| pages/business/business.vue | · | · | · | · | · | · | · | ⚠ | · |
| pages/cabinet-reports/cabinet-reports.vue | · | · | · | · | · | 1 | · | ⚠ | · |
| pages/device-detail/device-detail.vue | 2 | 13 | 3 | · | · | 5 | · | · | · |
| pages/devices/devices.vue | 1 | 27 | 4 | · | · | · | 1 | · | · |
| pages/disputes/disputes.vue | · | · | · | · | · | 9 | 1 | · | · |
| pages/home/home.vue | 5 | 33 | 2 | · | 1 | · | 1 | ⚠ | · |
| pages/line-wallet/line-wallet.vue | · | · | · | · | · | · | · | · | · |
| pages/login/login.vue | 3 | 9 | 2 | · | · | 1 | · | · | · |
| pages/messages/messages.vue | 1 | 3 | · | · | · | · | 1 | ⚠ | · |
| pages/mine/mine.vue | 7 | 13 | 3 | · | 2 | · | · | · | · |
| pages/notify/notify.vue | · | · | · | · | · | 2 | · | · | · |
| pages/ops-config/ops-config.vue | · | · | · | · | · | 1 | · | ⚠ | · |
| pages/order-detail/order-detail.vue | 4 | 10 | · | · | · | 3 | · | · | · |
| pages/orders/orders.vue | 4 | 16 | 3 | · | · | 1 | 1 | · | · |
| pages/policy/privacy.vue | · | 1 | · | · | · | · | · | · | · |
| pages/pricing/pricing.vue | 1 | 6 | 1 | · | · | · | 1 | · | · |
| pages/purchase/purchase.vue | · | · | · | · | · | 2 | 1 | ⚠ | · |
| pages/replenishment/replenishment.vue | · | · | · | · | 4 | 2 | 1 | ⚠ | · |
| pages/request/request.vue | · | · | · | · | · | 1 | · | ⚠ | · |
| pages/settlements/settlements.vue | 1 | 6 | · | · | · | 1 | 2 | · | · |
| pages/splits/splits.vue | · | 4 | · | · | · | · | 1 | · | · |
| pages/tax/tax.vue | · | · | · | · | · | 1 | · | ⚠ | · |
| pages/team/team.vue | · | · | · | · | 7 | · | 1 | · | · |
| pages/video/video.vue | 2 | · | · | · | · | · | · | · | · |
| pages/wallet/wallet.vue | · | · | · | · | · | 1 | 1 | · | · |

## 五、组件与布局文件（仅列有问题的）

| 端 | 文件 | 裸hex | 字面圆角 | 原生btn | 未定义变量 |
|---|---|---|---|---|---|
| admin-vue | components/EChart.vue | · | 1 | · | · |
| admin-vue | components/GlobalSearch.vue | · | 1 | 3 | · |
| admin-vue | components/OpsApprovalInbox.vue | 3 | 2 | 3 | · |
| admin-vue | components/ResizableDrawer.vue | · | 2 | · | · |
| admin-vue | components/SlotGrid.vue | 8 | 1 | · | · |
| admin-vue | components/TableActions.vue | · | 1 | 2 | · |
| admin-vue | components/device/DeviceTempEnvTab.vue | · | 1 | · | · |
| admin-vue | components/replenishment/ReplenishmentPlanRouteDialog.vue | · | 2 | · | · |
| admin-vue | components/warehouse/WarehouseBinDialogs.vue | · | 1 | · | · |
| admin-vue | components/warehouse/WarehouseEntityDialogs.vue | · | 2 | · | · |
| admin-vue | components/warehouse/WarehousePurchaseDialogs.vue | · | 2 | · | --admin-font-size-caption |
| admin-vue | layouts/AdminLayout.vue | 8 | 5 | 2 | · |
| consumer-mp | App.vue | 39 | 5 | · | · |
| consumer-mp | components/HomeCartBar.vue | · | · | 4 | · |
| consumer-mp | components/HomeLanding.vue | 7 | 7 | 1 | · |
| consumer-mp | components/device-ad-banner.vue | 2 | 1 | · | · |
| consumer-mp | components/open-prep-drawer.vue | · | 3 | · | · |
| consumer-mp | components/order-appeal-sheet.vue | · | 1 | · | · |
| merchant-mp | App.vue | 16 | 6 | · | · |
| merchant-mp | components/AppConfirmDialog.vue | · | · | 2 | · |
| merchant-mp | components/AppSheet.vue | · | 1 | · | · |
| merchant-mp | components/ReplenishActionDock.vue | · | 1 | · | · |
| merchant-mp | components/ReplenishCabinetCard.vue | · | 1 | · | · |
| merchant-mp | components/ReplenishLinesSection.vue | 2 | 3 | 1 | · |
| merchant-mp | components/ReplenishStepBar.vue | 1 | 1 | · | · |
| merchant-mp | components/WalletPage.vue | 1 | · | · | · |

## 六、页面注册完整性（防漏页）

- **admin-vue**：实际页面文件 77 个；注册/路由引用 76 个；缺失文件 0；未注册孤儿 1：clients/admin-vue/src/views/warehouse/WarehouseView.vue
- **consumer-mp**：实际页面文件 23 个；注册/路由引用 23 个；缺失文件 0；未注册孤儿 0
- **merchant-mp**：实际页面文件 28 个；注册/路由引用 28 个；缺失文件 0；未注册孤儿 0

**consumer-mp globalStyle**：{"navigationBarTextStyle":"white","navigationBarBackgroundColor":"#0f766e","backgroundColor":"#ededed","backgroundColorTop":"#0f766e","backgroundColorBottom":"#ededed","backgroundTextStyle":"dark"}；tabBar：{"color":"#86a89a","selectedColor":"#0f766e","backgroundColor":"#ffffff"}

**merchant-mp globalStyle**：{"navigationBarTextStyle":"white","navigationBarBackgroundColor":"#134e4a","backgroundColor":"#ffffff","backgroundColorTop":"#134e4a"}；tabBar：{"color":"#64748b","selectedColor":"#0f766e","backgroundColor":"#f4faf7"}

## 七、共享组件本地拷贝一致性（对比 packages/shared-uni/src/components）

| 组件 | consumer-mp | merchant-mp |
|---|---|---|

## 八、使用了但全 app 未定义的 CSS 变量（bug 级，恒走 fallback）

- admin-vue · components/warehouse/WarehousePurchaseDialogs.vue：--admin-font-size-caption
- consumer-mp · pages/index/index.vue：--text-secondary

## 九、模板引用但找不到定义的类（需人工确认是否死类/动态拼接）

- admin-vue · views/analytics/AnalyticsView.vue：analytics-page
- admin-vue · views/devices/DeviceDetailView.vue：hero-info
- admin-vue · views/disputes/DisputeListView.vue：dispute-workbench、suggest-table
- admin-vue · views/exceptions/ExceptionListView.vue：exception-workbench、exception-drawer-body
- admin-vue · views/finance/FinanceView.vue：finance-page
- admin-vue · views/finance/LineManagerView.vue：org-toolbar、kpi-box
- admin-vue · views/merchants/MerchantSplitsView.vue：mb-12
- admin-vue · views/orders/OrderListView.vue：refund-amt
- admin-vue · views/profile/ProfileView.vue：profile-card
- admin-vue · views/replenishment/ReplenishmentView.vue：lines-status-tag、lines-table、request-evidence
- admin-vue · views/system/ApprovalConfigView.vue：flow-chip__name、flow-dialog
- admin-vue · views/system/DevOpsHubView.vue：prom-helper、grafana-panel
- admin-vue · views/system/OrgSitesView.vue：rent-split-dialog
- admin-vue · views/system/ScheduledTaskView.vue：cell-hint
- admin-vue · views/vision/RecognitionDemoView.vue：demo-card、upload-panel、item-main
- admin-vue · views/warehouse/WarehouseExpiryAlertsView.vue：page
- admin-vue · views/warehouse/WriteOffClaimLedgerView.vue：claim-ledger
- admin-vue · components/CrudTable.vue：crud-cols、crud-cols__item--fixed
- admin-vue · components/EChart.vue：echart-skeleton、echart-empty
- admin-vue · components/SlotGrid.vue：slot-qty
- admin-vue · components/warehouse/WarehouseBinsTab.vue：section-title、compact
- admin-vue · components/warehouse/WarehouseMovementsTab.vue：tip
- admin-vue · components/warehouse/WarehouseOutboundsTab.vue：expand-panel、line-table、outbound-id-cell、print-btn
- admin-vue · components/warehouse/WarehousePayablesTab.vue：expand-panel、line-table
- admin-vue · components/warehouse/WarehousePurchaseOrdersTab.vue：expand-panel、line-table、print-btn
- admin-vue · components/warehouse/WarehousePurchaseReturnsTab.vue：expand-panel、line-table
- admin-vue · components/warehouse/WarehouseTransitTab.vue：sla-meta、danger
- consumer-mp · pages/balance/balance.vue：log-list
- consumer-mp · pages/index/index.vue：landing-mask、landing-sheet-title、detail-rows、landing-sheet-actions、landing-sheet-btn
- consumer-mp · pages/login/login.vue：login-wrap、login-bg-scene、login-illustration、login-illustration-anim、anim-orb、anim-orb-a、anim-orb-b、anim-shimmer、login-overlay、login-content、hero、brand、tagline、badge、badge-icon、badge-text、form-card、title、subtitle、btn-wx、divider、divider-line、divider-toggle、divider-text、tabs、field、field-label、field-auth、row、flex、btn-captcha、captcha-img、captcha-placeholder、btn-code、login-submit、dev-hint、login-back、err、legal-row、legal-link、legal-dot
- consumer-mp · pages/member/index.vue：benefit-list、benefit-copy
- consumer-mp · pages/messages/messages.vue：prefs-card
- consumer-mp · pages/mine/mine.vue：profile-cell、avatar、avatar-text、profile-mid、hello、hello-sub、tags、tag、profile-side、profile-bal、profile-bal-hint、menu-arrow、menu-list、wallet-row、menu-text、menu-title、menu-desc、wallet-amt、wallet-go、setup-banner、setup-text、setup-title、setup-desc、setup-arrow、pay-pref-block、pay-pref-inner、pay-pref-title、pay-pref-hint、pay-pref-chips、pay-pref-chip、pay-pref-unsign-row、pay-pref-unsign、pay-pref-unsign-hint、quick-grid、quick-item、quick-icon、quick-label、menu-icon、dev-section、dev-label、highlight、menu-badge、logout-wrap、danger-cell、danger
- consumer-mp · pages/order-detail/order-detail.vue：status-copy、field-label
- consumer-mp · pages/orders/orders.vue：state-wrap、btn-slot、orders-main、review-section、section-label、review-card、review-icon、review-body、review-top、review-title、chip、pending、review-detail、review-foot、review-time、review-link、review-more、filter-block、search-row、search-input、search-clear、filter-aux、aux-link、inline、list-inner、order-card、order-top、order-meta、order-device-name、order-id、order-mid、order-thumb、order-copy、order-copy-main、order-summary、order-tags、order-tag、slot、soft、mono、order-amt-block、amt-origin、amt、discount、order-bottom、order-bottom-left、order-time、order-refund-amt、order-hint、refund、load-more、hint、list-foot、foot-actions、foot-btn、primary
- consumer-mp · pages/points/points.vue：log-list、log-main
- consumer-mp · pages/points/redeem.vue：item-list、item-copy
- consumer-mp · pages/recharge/recharge.vue：balance-card、bal-label、bal-amount、refund-entry、refund-title、refund-hint、refund-row、refund-input、custom-error、refund-list、refund-item、refund-amt、refund-meta、refund-remark、refund-right、refund-time、done、amount-grid、amount-card、amount-value、custom-row、custom-label、custom-input、btn-slot、channel-hint、btn-slot--back、recharge-list、section-head、section-title、cleanup、empty、record-row、record-amount、record-meta、record-channel、record-id、record-time、record-right、record-status、cancel-link、note
- merchant-mp · pages/announcements/detail.vue：article
- merchant-mp · pages/business/business.vue：period-card、cabinet-pick、cabinet-pick-label、cabinet-pick-name、state、summary-card、hero、hero-kicker、hero-range、hero-amount、hero-eq、hero-eq-op、hero-meta、kpi-grid、kpi-cell、kpi-label-row、kpi-label、help-q、kpi-value、warn、warn-strip、section-head、section-title、section-sub、sku-row、sku-main、sku-name、sku-rec、sku-data、sku-money、empty、title-with-help、insight-sku、expiry-grid、expiry-cell、expiry-n、expiry-l、risk-card、risk-title、risk-desc
- merchant-mp · pages/cabinet-reports/cabinet-reports.vue：intro、intro-desc、period-card、cabinet-pick、cabinet-pick-label、cabinet-pick-name、period-hint、empty、section-head、section-title、report-dims、aux-link、chart-block、chart-title、chart-metrics、sku-row、sku-main、sku-name、sku-rec、sku-data、sku-money
- merchant-mp · pages/device-detail/device-detail.vue：meta-status-row
- merchant-mp · pages/disputes/disputes.vue：loading、card-head、card-title、sla-chip、card-meta、card-device、card-time、video-pill、card-amount-line、card-msg、card-foot、card-id、reply-btn、reply-btn--ghost、load-more、trunc-hint、detail-title、detail-reason、detail-scroll、detail-rows、detail-row、detail-lbl、detail-val、amount-diff-row、amount-diff、suggest-block、suggest-row、suggest-top、suggest-name、suggest-amt、suggest-unit、video-block、dispute-video、video-unavailable
- merchant-mp · pages/home/home.vue：section-card、empty-scan-tip
- merchant-mp · pages/notify/notify.vue：intro、intro-title、intro-desc、status-row、field-label、status-val、bind-actions、hint、notify-types、notify-type
- merchant-mp · pages/ops-config/ops-config.vue：intro、intro-title、intro-desc、empty、status-row、field-label、status-val、row、row-copy、row-title、row-desc、pct-wrap、pct-input、pct-sign
- merchant-mp · pages/purchase/purchase.vue：panel、label、warehouse、hint、picker、empty-inline、line-row、check、line-copy、sku-name、sku-meta、qty-box、qty-btn、qty-val、req-card、req-head、req-id、status、req-meta、line-list、recv-line、recv-line-head、line-item
- merchant-mp · pages/replenishment/replenishment.vue：hero、hero-head、eyebrow、title、subtitle、stats、stat、stat-value、stat-label、hero-actions、scan-primary、hero-secondary、clear-pill、filter-tip、muted、idle-tip、idle-title、idle-desc、patrol-card、patrol-head、patrol-title、patrol-sub、patrol-count、patrol-row、patrol-name、device-name、device-code、patrol-meta、patrol-badge、patrol-shortage、empty、empty-scan、ghost、task-card、task-accent、task-kicker、task-kicker-dot、task-head、task-head-main、task-addr、status、task-chips、task-chip、task-note、task-foot、task-lines、task-lines-spacer、detail-link、sheet-head、recap、sheet-head-main、sheet-recap-stack、sheet-title、close、skip-loc-row、skip-loc-copy、skip-loc-top、skip-loc-label、skip-loc-switch、skip-loc-hint、door-tip
- merchant-mp · pages/request/request.vue：panel、label、picker、hint、row-between、empty-inline、line-row、check、line-copy、sku-name、sku-meta、sku-reason、qty-box、qty-btn、qty-val、block-hint、evidence-row、evidence-thumb-wrap、evidence-thumb、evidence-add、evidence-add-plus、evidence-add-label、err、req-card、req-head、req-id、req-head-actions、status、go-replenish、req-cabinet、req-meta、line-list、line-item、req-hint、muted、reject、notes、trunc-hint
- merchant-mp · pages/tax/tax.vue：intro、intro-title、intro-desc、merchant-pick、field-label、merchant-name、static、empty、tax-form、field、field-box、tax-input、hint
- merchant-mp · pages/team/team.vue：invite-card、invite-plus、invite-copy、invite-title、invite-desc、invite-go、state、list、list-count、row、avatar、name-row、name、role-tag、sub、status-row、status-tag、inactive、self-tag、more、dialog-title、role-row、wrap、role-chip、dialog-actions、btn、ghost、hint、section、section-title、danger

## 十、改造切片工单（每片命中页面全清单，改一页销一页）

**P0 · bug 级（恒错/依赖断裂）**

- 未定义 CSS 变量、幽灵类：见 §八/§九 全量清单（含 merchant orders 裸导出按钮 → 见 §九 幽灵类 `export-btn`）。
- merchant-mp package.json 补声明 `@aicabinet/shared-uni`。
- 共享组件拷贝漂移回齐：见 §七（app-button/app-nav-bar/error-state 两端均落后共享包）。

**P1 · 两个小程序互相拉齐（不向后台看齐，遵循小程序规范）**

1. 按钮统一到 app-button（原生 <button> 清除）：
   - consumer-mp：pages/index/index.vue(3)、pages/messages/messages.vue(1)、pages/points/redeem.vue(1)、pages/video/video.vue(2)
   - merchant-mp：pages/alerts/alerts.vue(1)、pages/home/home.vue(1)、pages/mine/mine.vue(2)、pages/replenishment/replenishment.vue(4)、pages/team/team.vue(7)
2. 空状态统一到 empty-state（手写空状态清除）：
   - consumer-mp：pages/balance/balance.vue、pages/coupons/coupons.vue、pages/dispute/detail.vue、pages/index/index.vue、pages/marketing/index.vue、pages/messages/messages.vue、pages/order-detail/order-detail.vue、pages/points/points.vue、pages/points/redeem.vue、pages/recharge/recharge.vue、pages/result/result.vue
   - merchant-mp：pages/business/business.vue、pages/cabinet-reports/cabinet-reports.vue、pages/home/home.vue、pages/messages/messages.vue、pages/ops-config/ops-config.vue、pages/purchase/purchase.vue、pages/replenishment/replenishment.vue、pages/request/request.vue、pages/tax/tax.vue
3. consumer-mp error-state 组件 0 使用 → 接入或删除（merchant 已有 14 处使用可参照）。
4. 页面底色/导航口径统一：按 §六 pageOverrides 表逐页核对（nearby #f5f7f6 等私有底色收敛为 token 或有意豁免注释）。
5. 裸 hex 按密度治理（≥3 处的页面优先）：
   - consumer-mp：pages/index/index.vue(11)、pages/member/index.vue(8)、pages/messages/messages.vue(5)
   - merchant-mp：pages/mine/mine.vue(7)、pages/home/home.vue(5)、pages/order-detail/order-detail.vue(4)、pages/orders/orders.vue(4)、pages/login/login.vue(3)
6. 字面圆角接 token（≥3 处的页面优先）：
   - consumer-mp：pages/index/index.vue(5)、pages/messages/messages.vue(5)
   - merchant-mp：pages/devices/devices.vue(4)、pages/device-detail/device-detail.vue(3)、pages/mine/mine.vue(3)、pages/orders/orders.vue(3)

**P2 · 管理后台收尾**

1. ECharts 迁移（替代手写 SVG）：views/analytics/AnalyticsView.vue(script裸色16)、views/dashboard/BigScreenView.vue(script裸色16)、views/devices/DeviceMapView.vue(script裸色2)、views/devices/DeviceOpsMonitorView.vue(script裸色2)、views/exceptions/ExceptionListView.vue(script裸色1)、views/finance/FinanceView.vue(script裸色3)、views/growth/SkuReviewView.vue(script裸色1)、views/orders/OrderListView.vue(script裸色1)、views/sessions/SessionListView.vue(script裸色1)
   - ChartBox 保留加载/错误/空态壳，内部换 ECharts；主题色值从 CSS 变量读取，跟随暗色/主题切换；迁完删 utils/charts.ts SVG 体系。
2. 原生 <button> 归一到 el-button：views/replenishment/ReplenishmentView.vue(10)、views/analytics/AnalyticsView.vue(9)、views/disputes/DisputeListView.vue(7)、views/exceptions/ExceptionListView.vue(7)、views/orders/OrderListView.vue(5)、views/sessions/SessionListView.vue(5)、views/reports/StockHealthView.vue(4)、views/LoginView.vue(3)、views/finance/FinanceView.vue(3)、views/risk/RiskView.vue(3)、views/skus/SkuVisionEnrollView.vue(3)、views/devices/DeviceListView.vue(2)、views/feedback/FeedbackView.vue(2)、views/print/PrintView.vue(2)、views/reports/DeviceReportView.vue(2)、views/reports/SalesReportsView.vue(2)、views/system/ApprovalConfigView.vue(2)、views/upload/UploadQueueView.vue(2)、views/dashboard/DashboardView.vue(1)、views/devices/DeviceMapView.vue(1)、views/finance/BalanceRefundView.vue(1)、views/merchants/MerchantSplitsView.vue(1)、views/reconciliation/ReconciliationView.vue(1)、views/skus/SkuListView.vue(1)、views/sla/SlaView.vue(1)
3. 字面圆角接 --radius-*：views/dashboard/BigScreenView.vue(19)、views/devices/DeviceMapView.vue(7)、views/LoginView.vue(6)、views/devices/DeviceDetailView.vue(5)、views/system/ApprovalConfigView.vue(5)、views/analytics/FootfallView.vue(4)、views/skus/SkuVisionEnrollView.vue(4)、views/disputes/DisputeListView.vue(3)、views/exceptions/ExceptionListView.vue(3)、views/print/PrintView.vue(3)、views/replenishment/ReplenishmentView.vue(3)、views/system/OrgSitesView.vue(3)（其余 1-2 处的随页面顺手改）
4. 裸 hex 收敛（≥5 处优先）：views/dashboard/BigScreenView.vue(80)、views/analytics/AnalyticsView.vue(26)、views/LoginView.vue(23)、views/print/PrintView.vue(19)、views/analytics/FootfallView.vue(12)、views/finance/FinanceView.vue(11)、views/devices/DeviceMapView.vue(9)、views/growth/UserAnalysisView.vue(7)
5. 结构性偏离：DevOpsHubView 对齐 page-card 骨架（§矩阵中 page-card=✗ 的页面逐一核对是否合理特例）。

**机制 · 防再漂移**

- 本审计脚本纳入 CI 门禁：各端 totals 不高于基线（基线=本次 JSON），新增裸 hex/裸圆角/原生按钮即失败。
- 拷贝组件一致性校验（§七）纳入 CI：identical 必须为 true。
- stylelint 禁止新增裸 hex 与字面 border-radius（白名单 theme.css/main.css/App.vue）。

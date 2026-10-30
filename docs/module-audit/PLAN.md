# 11 轮审计计划（三端分模块）

> **快照锚点**：HEAD `7143b3d7`（2026-10-29）；清单来源（全部是代码，非文档）：
> `clients/admin-vue/src/config/menu.ts`、`clients/admin-vue/src/router/index.ts`、`clients/consumer-mp/src/pages.json`、`clients/merchant-mp/src/pages.json`。
> ⚠️ **本表数字只是起点，不是真值**：每轮开轮 Step 0 必须重拉清单（菜单/路由/pages.json）并记录当日 HEAD；页面有增删以重拉结果为准并更新本表。
> ⚠️ 快照日工作区有 309 个未提交文件（并发会话在飞）；R1 开工前建议先协调在飞工作落库，审计基线尽量落在干净 HEAD 上。

## 轮次总览

| 轮 | 主题 | 后台页面（快照数） | 状态 | 轮报告 |
|----|------|--------------------|------|--------|
| R0 | 环境基线（一次性前置，建议新增） | — | 未开始 | rounds/R00-baseline.md |
| R1 | 概览 | 9 | 未开始 | rounds/R01-overview.md |
| R2 | 交易履约 | 4 | 未开始 | rounds/R02-trade.md |
| R3 | 设备商品 | 10 + 设备详情动态页 | 未开始 | rounds/R03-devices.md |
| R4 | 仓储 | 7 + `/print` 隐藏页 + 1 条待复核路由（见线索 #1） | 未开始 | rounds/R04-warehouse.md |
| R5 | 履约仓储 | 4 | 未开始 | rounds/R05-fulfillment.md |
| R6 | 财务商户 | 16（建议分 6a 资金账单 / 6b 商户提现 两批推进） | 未开始 | rounds/R06-finance.md |
| R7 | 增长风控 | 11 | 未开始 | rounds/R07-growth.md |
| R8 | 系统 | 15 + 登录/错误页 + 权限矩阵专项 | 未开始 | rounds/R08-system.md |
| R9 | 双小程序为主 → 后台联动点验 | consumer-mp 23 页 / merchant-mp 34 页 | 未开始 | rounds/R09-miniprograms.md |
| R10 | 三端横切审计 | 同一数据三端口径/权限/一致性 | 未开始 | rounds/R10-crosscut.md |
| R11 | 业务流转测试（造数全链路） | 三端全链路剧本 | 未开始 | rounds/R11-e2e-flows.md |

## R0 环境基线（一次性前置）

产出：栈起来（docker compose 各服务健康）、三端可登录（超管/商户/补货员三账号实测）、admin 产物与源码一致（重建一次）、开发者工具两个小程序可编译预览、种子数据在位、核准/订正 README「环境速查」每一条。**没有 R0，后续每轮都要重付环境成本，且多 AI 无法互相复现问题。**

## R1–R8 各模块页面清单（menu.ts 快照）

**R1 概览（9）**：`/dashboard` 运营工作台 · `/big-screen` 运营大屏（全屏路由）· `/analytics` 数据分析 · `/footfall` 客流坪效 · `/reports` 设备报表 · `/finance` 财务毛利 · `/sales-reports` 销售报表 · `/stock-health` 库存健康 · `/user-analysis` 用户分析
联动线索：销售/用户分析类数据与 consumer-mp 订单、营销活动核销口径对照。

**R2 交易履约（4）**：`/orders` 订单管理 · `/sessions` 开门记录 · `/disputes` 争议审核 · `/exceptions` 异常中心
联动：consumer-mp `orders / order-detail / dispute`；merchant-mp `alerts`。

**R3 设备商品（10 + 详情页）**：`/device-ops` 设备运维 · `/devices` 设备管理 · `/devices/:id` 设备详情 · `/device-map` 投放地图 · `/device-kpi` 设备可用性 · `/repair-tickets` 维修工单 · `/skus` 商品管理 · `/sku-review` 选品诊断 · `/sku-vision` 识别入驻 · `/learning-library` 商品学习库 · `/vision-mappings` 识别映射
联动：merchant-mp `devices / device-detail / pricing`；学习库/识别映射涉及将邑链路。

**R4 仓储（7 + 2）**：`/warehouse/master` 仓库与库位 · `/warehouse/procurement` 采购与供应商 · `/warehouse/claims` 待索赔台账 · `/warehouse/expiry-alerts` 近效期预警 · `/warehouse/monthly-close-sheets` 月结单台账 · `/warehouse/stock` 库存与盘点 · `/warehouse/fulfillment` 领货与调拨；隐藏：`/print` 打印单据；⚠️ `/warehouse/reconciliation` 供应商对账单——**路由存在但 menu.ts 未登记（线索 #1，见 ISSUES.md）**；`/warehouse` 旧 tab 入口为兼容重定向。
联动：merchant-mp `purchase / self-replenish / warehouse-stocktake / device-stocktake / tally-records / prep-generate`。
🔴 业务判据提醒：分仓是补货员自己的仓（本部仓只对采购）；补货=自提自送、无「分仓→柜」在途段（依据 2026-10-29 用户拍板口径）。

**R5 履约仓储（4）**：`/replenishment` 补货调度 · `/ota` 固件版本 · `/sla` 服务时限监控 · `/replenishment-staff` 补货员效率
联动：merchant-mp `replenishment / self-replenish`。

**R6 财务商户（16，建议 6a/6b 两批）**：`/fund-bills` 资金账单 · `/platform-fund` 平台资金池 · `/device-rental` 设备租赁 · `/merchants` 商户与分账 · `/merchant-onboarding` 进件工作台 · `/line-managers` 线长钱包 · `/line-routes` 线路与提成 · `/machine-payee-bills` 机器账单（场地方）· `/merchant-fee-ledgers` 商户费用台账 · `/merchant-withdraw` 商户提现 · `/reconciliation` 对账 · `/consistency` 数据一致性 · `/recharges` 充值管理 · `/balance-refunds` 余额退款 · `/invoices` 开票申请 · `/users` 用户余额
联动：merchant-mp `settlements / bills / wallet / line-wallet / splits / business / tax`；consumer-mp `recharge / balance`。资金敏感：只读优先、写操作只碰 `AUDIT-` 造数、支付走 dev mock。

**R7 增长风控（11）**：`/phone-verify` 手机验证 · `/risk` 风控 · `/promotions` 营销活动 · `/coupons` 优惠券 · `/ad-assets` 素材库 · `/ad-campaigns` 投放计划 · `/points-redeem` 积分兑换管理 · `/member-levels` 会员等级规则 · `/marketing-roi` 活动效果分析 · `/notifications` 消息记录 · `/feedback` 用户反馈
联动：consumer-mp `marketing / member / points / coupons / feedback / messages`；双端 `announcements`。

**R8 系统（15 + 专项）**：`/operators` 运营账号 · `/roles` 角色管理 · `/departments` 部门管理 · `/approvals` 审批流配置 · `/menus` 菜单管理 · `/dicts` 字典管理 · `/system-configs` 参数配置 · `/alert-rules` 告警规则 · `/scheduled-tasks` 定时任务 · `/org-sites` 组织与点位 · `/announcements` 通知公告 · `/audit` 审计日志 · `/devops` DevOps 中心 · `/observability` 日志中心 · `/profile` 个人中心；外加 `/login`、`/forbidden`、`/not-found`。
专项：**角色×权限码×页面矩阵**（超管全量 + 每模块抽 1 个受限角色实测可见性/403）在此轮收口；此前各轮先用超管测全功能、受限角色仅快测该模块。

## R9 双小程序为主（快照：consumer 23 页 / merchant 34 页）

consumer-mp：主页 `index/orders/mine/login/verify/realname/result` + 分包 `order-detail, dispute, recharge, balance, marketing, member, points(2), coupons, messages, announcements(2), help, policy, report, feedback`。
merchant-mp：主页 `login/home/devices/alerts/mine` + 分包 `device-detail, pricing, replenishment, ops-config, self-replenish, warehouse-stocktake, device-stocktake, tally-records, password-change, purchase, business, tax, cabinet-reports, notify, settlements, bills, disputes, orders, order-detail, video, messages, announcements(2), team, splits, wallet, line-wallet, policy, prep-generate`。
方法：以小程序页面为审计主体（按钮/交互/样式/文案），每个写面动作到后台点验数据落点正确；R1–R8 已覆盖的后台读面只抽查不重测。

## R10 三端横切

同一份数据在三端的口径一致性（金额分/元、状态名、时间时区）、权限闭环（后台权限码 ↔ mp 接口鉴权）、消息触达（后台通知/公告 ↔ 双端消息中心）、以及 R1–R9 遗留 `confirmed` 问题的回归。

## R11 业务流转（造数全链路）

用 `seeds/` 的 `AUDIT-` 前缀造数脚本跑端到端剧本：采购入库 → 分仓 → 备货/补货任务 → 出库领货 → 上柜/领而未上 → 消费下单支付（dev mock）→ 争议/退款 → 对账/月结 → 提现。每段剧本先写进轮报告再执行，金额逐段对账（台账↔批次↔流水三层一起对）。

## 每轮工作法（固定 7 步）

1. **开轮**：规则 2 三件事（看 git 变化 / 回读本目录 / 重拉该模块页面清单）→ 建 `rounds/Rxx-*.md`，记录 HEAD 与环境。
2. **页面×选项卡清单**：从视图源码列出该页全部选项卡/子面板（不只是 menu 项），逐项挂 checklist。
3. **逐页审计**：按 [templates/page-checklist.md](templates/page-checklist.md) 九个维度过；每页测完立即写进轮报告。
4. **问题入账**：当场在 ISSUES.md 领 MA 编号、写全字段；证据（截图/请求）落 `evidence/`。
5. **联动点验**：该页涉及小程序的，到对应 mp 页面验证数据/状态一致（开发者工具即可，真机项进「需用户配合」清单）。
6. **轮末收口**：汇总发现、P0–P3 计数、需拍板/需真机清单、修复批次建议；更新 PLAN 状态列。
7. **修复**（可与下一轮并行但基线要记清）：批量修复 + 门禁 + preflight；`fixed` 必须附实跑证据。

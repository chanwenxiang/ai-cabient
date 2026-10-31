# R2 审计：交易履约（4 页）

- 执行 AI / 日期：Qoder（本会话）/ 2026-10-31
- 代码基线：HEAD `8e12c11f`（`git status --porcelain` 干净；远端 `refs/heads/dev` 同为 `8e12c11f`，`git ls-remote` 实证。本地 `origin/dev` 引用陈旧是 fetch 未带 refspec，非分叉）
- 环境：本地 docker（`infra/docker-compose.full.yml` + `win-ports`，trade `127.0.0.1:18080`，Postgres 库 `aicabinet` @15433）；admin 产物 = `c3e0e44d`（CI-fidelity 重建，其后两笔提交为 docs-only，未动 admin 源码 ⇒ 产物与 HEAD 同刻）
- 账号：超管 `13900000001`（受限角色快测本轮抽 1 个，见各页「1 入口与权限」）

## Step 0 清单重拉（以代码为准）

`node docs/module-audit/step0-inventory.mjs` @ `8e12c11f`：

```
admin 侧栏登记页合计：77（去重后）
  概览：9 / 交易履约：4 / 设备商品：10 / 履约仓储：4 / 仓储：8 / 财务商户：16 / 增长风控：11 / 系统：15
无 perm 码（登录即可见）：/profile
菜单有、路由无（死入口）：无
兼容重定向（非页面）：/index.html /oper-logs /rbac
路由有、菜单未登记（人工判）：/print
consumer-mp：main=7 sub=16 total=23
merchant-mp：main=5 sub=30 total=35
```

**R2 本轮范围 = 交易履约 4 页**，与 PLAN 快照一致（无漂移）。页×选项卡（从视图源码取，非菜单项）：

| 页面 | 路由 | 视图 | 选项卡（源码行） |
|------|------|------|------------------|
| 订单管理 | `/orders` | `views/orders/OrderListView.vue`（1791 行） | 全部 / `order_status` 字典：PENDING、PAID、DISPUTED、REFUNDED、CANCELLED（:28-33） |
| 开门记录 | `/sessions` | `views/sessions/SessionListView.vue`（1094 行） | 全部 + `stateOptions` 动态枚举（:23-24） |
| 争议审核 | `/disputes` | `views/disputes/DisputeListView.vue`（1750 行） | 全部类型 / 识别争议 RECOGNITION / …（:21-22，余下见正文） |
| 异常中心 | `/exceptions` | `views/exceptions/ExceptionListView.vue`（1651 行） | 动态（:63）/ 已归档 ARCHIVED / 全部（:69-70） |

联动端（R2 要点的 mp 面）：consumer-mp `orders`、`order-detail`、`dispute`、`result`；merchant-mp `alerts`、`orders`、`order-detail`、`disputes`、`video`。

## 逐页结果

### 页面：订单管理（`/orders`）｜选项卡：全部 / 待支付 / 已支付 / 争议中 / 已退款 / 已取消

- HEAD / 环境 / 账号角色：`8e12c11f`（修复后产物 `4715e6d9` 复验）· 本地 docker · 超管 `13900000001`
- 视图源码：`clients/admin-vue/src/views/orders/OrderListView.vue`（1791 行，+ `useCrudTable`、`shared-uni/format`）
- 联动端点：consumer-mp `orders`/`order-detail`、merchant-mp `orders`/`order-detail`/`video`（本轮未点验，见维度 7）

| 维度 | 结论 | 备注/问题 |
|------|------|-----------|
| 1 入口与权限 | ✅（部分） | 菜单可见、直开 URL 正常；🔴 受限角色快测**未做**——本轮没有第二个可用口令（密码不落文档），留 R8 权限矩阵专项 |
| 2 列表与数据 | ✅ | 真分页（10/20/50 档；10 条/页时 `共 14 条`+2 页，第 2 页 4 行且为更早单）；筛选 `q`/渠道/时间/状态 tab 均服务端参数；空态「暂无订单」；🔴 **数值对 SQL 真值全等**：`cabinet_order` 14 行（11 笔 0 元）↔ 接口 `total=14` ↔ UI「共 14 条」；勾上隐藏零元单 → `total=3` ↔ UI 3 行 |
| 3 按钮与操作 | ✅ | 详情抽屉字段齐全（含原价/优惠/分账/退款策略/扣库存/时间线）；「退款」→ `.el-message-box`「全额退款确认：原路退回订单 … 的全部金额？」，**取消后新增网络请求 = 0**（performance entries 实证）；行操作枚举＝详情/退款/更多操作（复制单号·会话·柜机视频）；`PENDING` 专属的「催付/仅超时未付」无数据未演练 |
| 4 表单与校验 | N/A（读页） | 本页无写入表单；筛选表单无必填。时间范围边界（起>止、跨年）未测 |
| 5 样式与布局 | ✅（部分） | 列设置勾选生效（20→19→20 还原）；长单号列 `min-width=140` + 省略号，横向滚动条正常；🔴 本节观测是在内置浏览器 702px 下做的，**1600px 档未复测**（第 2–4 页已切 Playwright，本页样式维度待补一次） |
| 6 文案与口径 | ✅ | 状态全中文（字典 `order_status` 翻译，无裸码）；金额 `¥3.50`、优惠行显「`¥1.50 原 ¥3.50`」+「`-¥2.00`」；「隐藏零元单」带 tooltip 说明含「已退款 Tab 仍显示有退款额的单」 |
| 7 联动一致性 | ⚠️ 部分 | 后台内自洽 + 与 DB 真值对账通过；🔴 mp 侧（consumer `orders`、merchant `orders`/`video`）**未点验**——需商户/消费者口令，交 R9 双小程序轮 |
| 8 控制台与网络 | ✅ | 全程 console error/warn = 0 条 |
| 9 打印/导出/外链 | ✅（1 条待拍板） | 服务端导出与筛选同源：`mode=orders` 14 行、`mode=orders&excludeZero=1` 3 行、`mode=lines` 14 行；🔴 见「需用户拍板」第 1 条（两套表头） |

- 发现：**MA-006**（P2，`displayBizNo` 把 `E2E-TAIL-0001` 剥成假短号「20001」⇒ 按显示值搜 0 结果、与同行「复制单号」给的真值不一致）——轮内已修：源码 `56100ddc`、产物 `4715e6d9`，mp 单测 6/6（含 3 条新钉测）+ 活栈硬刷复验（第 9 行显 `E2E-TAIL-0001`，按该值搜 → 共 1 条）。证据 `evidence/R02-orders-list.png`（修复前）、`R02-orders-displayid-search-miss.png`、`R02-orders-displayid-fixed-searchable.png`。
- 观察项（**判为非缺陷**，附判据）：「已隐藏 N 笔 0 元单」提示只在**当前筛选结果为空**时才探测并显示（`OrderListView.vue:864` 的 `(data.total||0)===0 && shouldApplyExcludeZero()` 条件）——设计如此，避免每次加载多打一次 `size=1` 探针；列表非空时不显数字，由 tooltip 承担说明。
- 数据条件缺口（与 R1 NEAR_EXPIRY 同类）：dev 库无 `PENDING`/`DISPUTED`/`REFUNDED` 订单 ⇒ 催付、超时未付勾选、已退款 tab「仍显示有退款额的单」这条既定行为**未演练**（留 R11）。

## 逐页结果（第 2–4 页）

**工具口径变更（用户 2026-10-31 指示）**：Qoder 内置浏览器视口只有 **702×634**，样式维度不能用它下结论 ⇒ 第 2–4 页改用 **Playwright 驱动本机 Chrome（`channel:'chrome'`，1600×1000，headed）**，脚本 `.tmp/r2-generic-pass.mjs`、`.tmp/r2-filters-server-side.mjs`；内置浏览器只用于快速点按。

### 页面：开门记录（`/sessions`）｜选项卡：全部 + 字典 `session_state` 10 态（共 11 个）

- HEAD / 环境 / 账号角色：`fc5a6bde`（产物 `4715e6d9`）· 本地 docker · 超管 · Playwright 1600×1000
- 视图源码：`clients/admin-vue/src/views/sessions/SessionListView.vue`（1094 行）
- 联动端点：merchant-mp `replenishment`/`device-detail`（补货/运维会话同源表）、consumer-mp 无

| 维度 | 结论 | 备注/问题 |
|------|------|-----------|
| 1 入口与权限 | ✅（部分） | 菜单可见、直开正常；受限角色快测留 R8 |
| 2 列表与数据 | ✅（MA-007 已修） | 11 个 tab 逐个扫过，**计数与 SQL 全等**：全部 72（=21 CANCELLED+24 COMPLETED+22 DISPUTED+5 FAILED）、已完成 24、待审核 22、失败 5、已取消 21、其余 6 态 0 且显空态「暂无开门记录」；真分页（72→4 页）；🔴 **MA-007 已修**（`124e03ff`+产物 `9637a884`）：原「类型」页内过滤改为服务端 `kind` 参数；修后 UI **已完成+消费 = 共 17 条 / 17 行**、请求带 `kind=CONSUMER`，与 SQL 真值一致 |
| 3 按钮与操作 | ✅ | 行操作＝时间线 / 看设备 / 更多操作；时间线抽屉字段齐全（开门/关门/创建三个时间点 + 状态 + 柜机录像入口）；本页无写操作按钮 |
| 4 表单与校验 | N/A | 只读页；筛选表单无必填 |
| 5 样式与布局 | ✅ | 1600 宽无异常；窄到 702 时页面自带横幅「当前窗口较窄，列表操作列可能拥挤。建议浏览器宽度 ≥1280px 做运营验收。」——**内置守卫，判为合理**（不是缺陷） |
| 6 文案与口径 | ✅ | 状态全中文（已创建/开门中/购物中/识别商品中/录像上传中/结算中/已完成/待审核/失败/已取消，与字典 `session_state` 一致）；副标题说明「活跃态超过 30 分钟高亮滞留」与 `stuckMinutes=30` 默认值一致 |
| 7 联动一致性 | ⚠️ 部分 | 与 DB/接口对账通过；mp 侧（补货/盘点会话在 merchant-mp 的产生面）留 R9 |
| 8 控制台与网络 | ✅ | 0 error（Playwright 全程收集） |
| 9 打印/导出/外链 | ✅（随 MA-007 收口） | 「导出」走 `useListCsv` 的 `pickSelected(crud.displayItems)` ⇒ **类型筛选下只导出当前页过滤结果**，与列表同源缺陷 |

- 发现：**MA-007**（P1）——轮末批次已修并四腿验证（真库 IT 2/2、既有 Java 判定单测 3/3、API 真值 17、活栈 UI 17 条）。同源做法与前例 `PullOffTaskDeviceFilterSqlIT`（同一类「前端页内过滤 + total 谎报」）一致。
- 服务端化对照（证明缺陷是孤例而非全局）：`仅滞留` → `?stuckOnly=true&stuckMinutes=30` ✅ 服务端；`关键词` → `?q=…` ✅ 服务端；只有 `类型` 留在客户端。
- 数据缺口：6 个生命周期态（已创建/开门中/购物中/识别商品中/录像上传中/结算中）库内 0 条 ⇒ 这些态的行内表现（滞留高亮、录像按钮可用性、时限列）无法演练，**留给 R11 三端全流程轮**。

### 页面：争议审核（`/disputes`）｜选项卡：全部类型 / 识别争议

- 视图源码：`clients/admin-vue/src/views/disputes/DisputeListView.vue`（1750 行）；接口是 `/api/v2/ops/disputes`（**不在 admin 子路径下**，端点表 `endpoints.ts:1005`）

| 维度 | 结论 | 备注/问题 |
|------|------|-----------|
| 1 入口与权限 | ✅（部分） | 按钮带 `v-hasPermi="['ops:dispute:resolve']"`；受限角色快测留 R8 |
| 2 列表与数据 | ✅ | 两个 tab 各 22 条 = SQL `dispute_ticket` 中 `status=OPEN` 22（表内共 26 行，`category` **只有 RECOGNITION 一种** ⇒ 与 tab 数量一致）；状态筛选**服务端**：待审核 22 / 已结案 4 / 已关闭 0 = SQL OPEN/RESOLVED/CLOSED 全等 |
| 3 按钮与操作 | ⚠️ 1 条待拍板 | 审单工作台内：删除商品行 ✅有确认、关闭争议 ✅、重开 ✅、结案 ✅；🔴 **「认领工单」无二次确认**（`claimSelected` 直接 POST，仅 `claiming` 防连点），且 UI **没有「取消认领/转派」入口** ⇒ 误点后该单处理人改错且界面上不可逆（见「需用户拍板」第 3 条）。本轮**未点击**（避免写坏共享 dev 数据） |
| 4 表单与校验 | ✅（部分） | 结案前有 `validateResolveVideoReview()` / `validateResolveDraftItems()` 两道闸（无录像须勾两条才能结案）——设计合理；实际提交未演练（同上，会写库） |
| 5 样式与布局 | ✅ | 1600 宽正常；工作台为抽屉，录像区左右两路占位文案清晰 |
| 6 文案与口径 | ✅ | 「无录像时的结案步骤」把顺序写在页面上（先重载/新窗口→勾无录像→勾已核对→再结案）；状态中文 |
| 7 联动一致性 | ⚠️ 部分 | 详情内可跳「异常中心」「关联订单」，三页互链 ✅；consumer-mp `dispute`（消费者发起争议）留 R9 |
| 8 控制台与网络 | ✅ | 0 error |
| 9 打印/导出/外链 | ✅ | 导出 CSV 含处理人列；无打印面 |

- 发现：无新 MA 号（认领无确认属**需拍板**，不擅自定级）。
- 数据缺口：`已关闭` 0 条 ⇒ 「关闭后仍可重开」链路未演练（R11 或造数）。

### 页面：异常中心（`/exceptions`）｜选项卡：待处理 / 处理中 / 已解决 / 已关闭 / 已归档 / 全部

- 视图源码：`clients/admin-vue/src/views/exceptions/ExceptionListView.vue`（1651 行）

| 维度 | 结论 | 备注/问题 |
|------|------|-----------|
| 1 入口与权限 | ✅（部分） | 同上，R8 收口 |
| 2 列表与数据 | ✅ | **tab 标题自带徽标计数**（待处理 31 / 处理中 0 / 已解决 120 / 已关闭 0 / 已归档 0 / 全部 151）与 SQL 全等（`ops_exception` OPEN 31 + RESOLVED 120 = 151）；徽标是 6 个并发 `size=1` 计数请求，实现正确 |
| 3 按钮与操作 | ✅ | 行操作＝详情 / 设备 / 更多操作（添加备注、转派）；详情=异常审单工作台，含「会话录像（无关联会话）」占位与关联设备/订单跳转 |
| 4 表单与校验 | N/A（本轮） | 添加备注/转派为写操作，未提交（避免污染共享库），留轮末批次或 R11 |
| 5 样式与布局 | ✅ | 1600 宽正常 |
| 6 文案与口径 | ✅ | 严重级别中文（紧急/高/中/低）；异常内容+详细信息分行，含成因（「任务 19 已签到超时自动取消」） |
| 7 联动一致性 | ✅（后台内） | 与 `/disputes`、`/sessions` 互链；级别真值对账：`级别=紧急` 在待处理 tab 下 6 条 = SQL `status=OPEN AND severity='CRITICAL'` 6 ✅；待处理 31 = CRITICAL 6 + HIGH 23 + MEDIUM 2 ✅ |
| 8 控制台与网络 | ✅ | 0 error |
| 9 打印/导出/外链 | N/A | 本页无导出/打印 |

- 筛选服务端化验证：`级别` → `severity=CRITICAL` ✅；`仅超时` → `overdue=1` ✅（与 MA-007 相反，这两条都进 query）。
- 数据缺口：`处理中`/`已关闭`/`已归档` 三态 0 条 ⇒ 归档 tab 的专属空态文案「暂无归档异常」已见，但归档动作链路未演练（R11）。

## 发现汇总

| 级别 | 数量 | 编号 |
|------|------|------|
| P0 | 0 | — |
| P1 | 1 | MA-007（轮末批次已修，`124e03ff`+产物 `9637a884`） |
| P2 | 1 | MA-006（轮内已修，`56100ddc`+产物 `4715e6d9`） |
| P3 | 0 | — |

## 需用户拍板 → 2026-10-31 已拍板「按推荐做」，三条全部落地

| # | 议题 | 决定与落点 |
|---|------|------------|
| 1 | 服务端导出英文字段表头 vs 前端选中导出中文表头（全仓 7 处服务端导出都是英文 ⇒ 既定约定） | **不改表头**（避免动 7 处服务端 + 影响既有对账脚本/`check:csv-escape`），改为**在页面上说清两种口径**：`/orders` 与 `/sessions` 头部 hint 各加一句「未勾选=服务端按当前筛选导出（表头为字段名），勾选行=前端导出（中文表头）」 |
| 2 | `E2E-TAIL-0001` 这类 E2E 遗留单要不要清 | **清掉**（先留档再删，见下节）。删后基线：订单 14→**13**、会话 72→**71**、累计营收 850→**500 分（¥5.00）**、零元单仍 11 笔。R01 报告已加基线订正注记 |
| 3 | 争议「认领工单」点击即 POST、UI 无「取消认领」 | **加二次确认 + 按钮上显当前处理人**：`claimSelected` 前置 `ElMessageBox.confirm`（文案含「认领后本页没有取消认领入口」），按钮标签变 `认领工单（当前：X）` / `认领工单（当前未认领）` |

### 已删除数据留档（拍板 2，可原样重插）

删除前逐表查过引用：`cabinet_order_line` / `dispute_ticket` / `invoice_request` / `ops_exception` / `order_revenue_split` / `payment_operation` / `payscore_order` / `recognition_result` / `revenue_share_detail` / `user_coupon` / `user_feedback` / `wallet_ledger` **全部 0 行引用** ⇒ 无级联面。删除语句（同一事务，先子后父）：

```sql
BEGIN;
DELETE FROM cabinet_order WHERE order_id='E2E-TAIL-0001';
DELETE FROM shopping_session WHERE session_id='E2ETAILS0000000000001';
COMMIT;
```

原始行（`row_to_json` 导出，恢复时按此重插）：

```json
{"order_id":"E2E-TAIL-0001","session_id":"E2ETAILS0000000000001","user_id":10001,"device_id":"CAB-OTHER","total_amount_cents":350,"status":"PAID","created_at":"2026-10-16T08:39:42.409+00:00","pay_channel":"BALANCE","pay_trade_no":null,"inventory_deducted":false,"payment_operation_id":null,"coupon_discount_cents":0,"member_discount_cents":0,"pay_score_used":false,"refunded_cents":0,"paid_amount_cents":0}
{"session_id":"E2ETAILS0000000000001","user_id":10001,"device_id":"CAB-OTHER","state":"COMPLETED","order_id":null,"upload_status":"NONE","camera_fusion_mode":"SINGLE","dispute_flag":false,"coupon_discount_cents":0,"preauth_cents":0,"preauth_status":"NONE","device_name":"门店二号柜"}
```

判它是夹具的依据：`total_amount_cents=350` 但 `paid_amount_cents=0`、`pay_trade_no`/`payment_operation_id` 均空、`inventory_deducted=false`、设备 `CAB-OTHER`（非真柜）、无商品行。

## 需用户配合（真机）

- 本轮 4 页**没有必须真机才能验的项**（写操作确认闸、分页、筛选、导出均可在浏览器完成）。
- 但有三条**只有真机/流程能补的覆盖面**，随 R11 一起约：① 会话 6 个生命周期态（开门中/购物中/识别商品中/录像上传中/结算中等）的行内表现；② 争议结案时的「必须先观看录像」闸（`validateResolveVideoReview`）在**真有录像**时的行为；③ 异常「转派 / 添加备注」提交链路（本轮只开弹窗不提交）。

## 修复批次建议

轮末统一批次（P0/P1）：

1. ✅ **MA-007（P1）已修**（`124e03ff` + 产物 `9637a884`）：`kind` 参数进 `/sessions` 与 `/sessions/export`（未知值 400）、mapper 条件与 Java 判定同源（新增 `support/SessionKind` 做唯一字面量源）、`openapi.ts` 同批 regen、导出留痕带 kind。真库 IT `SessionKindFilterSqlIT` 已入仓（`mvn verify` 跑）。
2. `/orders` 的样式维度在 1600px 档补一次复测（本轮是在内置浏览器 702px 下看的，不算验过）。

轮内已修（P2/P3）：MA-006（`56100ddc` + 产物 `4715e6d9`，CI `41185685649` 5/5 绿）。

**门禁化候选（工作法第 8 步，本轮新增 1 条）**：

| 候选 | 判据 | 依据 |
|------|------|------|
| `check:list-filter-must-be-server-side` | admin 视图的 `fetchPage` 内出现 `rows.filter(` / `displayItems` 参与分页 ⇒ 红（显式 allowlist 例外，须写理由） | MA-007：页内过滤吃掉跨页数据 + `total` 谎报；本轮另有 `/sessions` 导出同源受害 |

## 给下一轮的交接注记

- 线索 #2（`/reports` 离线 5 vs 工作台 3）**不属本轮范围**，但它读的是设备 lifecycle 口径——R3 设备商品轮开轮时先把它结掉（判缺陷还是口径差），别在 R2 半途领号。
- **R2 的「数据缺口」全部转成 R11 的前置造数清单**：订单 `PENDING`/`DISPUTED`/`REFUNDED` 各 ≥1、会话 6 个中间态各 ≥1、争议 `CLOSED` ≥1（验「关闭后仍可重开」）、异常 `PROCESSING`/`CLOSED`/归档 各 ≥1。用户已定：**不在 R2 中途造数，留给 R11 三端全流程轮**。
- 内置浏览器（browser-use）视口 702×634，**样式/布局维度一律不许用它下结论**；Playwright 用 `channel:'chrome'` 起本机真 Chrome（headed，1600×1000），脚本模板 `.tmp/r2-generic-pass.mjs`。登录选择器三处易错：验证码输入框 placeholder 是 `图形验证码…`（前缀匹配）、提交按钮是 `button.submit-btn`、登录成功判据必须是「URL 离开 `/login`」（`/admin/` 前缀判据会把 `/admin/login` 误判为已登录）。
- `/disputes` 接口挂在 `/api/v2/ops/disputes`（**不在 `ops/admin` 子路径下**），写请求监听脚本时按这个路径过滤，否则会误判成「筛选没发请求」。

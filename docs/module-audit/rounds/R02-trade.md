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

- HEAD / 环境 / 账号角色：`8e12c11f`（产物 `4715e6d9` 复验）· 本地 docker · 超管 `13900000001`
- 视图源码：`clients/admin-vue/src/views/orders/OrderListView.vue`（+ `useCrudTable`、`shared-uni/format`）
- 联动端点：consumer-mp `orders`/`order-detail`、merchant-mp `orders`/`order-detail`/`video`（本轮未点验，见维度 7）

| 维度 | 结论 | 备注/问题 |
|------|------|-----------|
| 1 入口与权限 | ✅（部分） | 菜单可见、直开 URL 正常；🔴 受限角色快测**未做**——本轮没有第二个可用口令（密码不落文档），留 R8 权限矩阵专项 |
| 2 列表与数据 | ✅ | 真分页（10/20/50 档；10 条/页时 `共 14 条`+2 页，第 2 页 4 行且为更早单）；筛选 `q`/渠道/时间/状态 tab 均服务端参数；空态「暂无订单」；🔴 **数值对 SQL 真值全等**：`cabinet_order` 14 行（11 笔 0 元）↔ 接口 `total=14` ↔ UI「共 14 条」；勾上隐藏零元单 → `total=3` ↔ UI 3 行 |
| 3 按钮与操作 | ✅ | 详情抽屉字段齐全（含原价/优惠/分账/退款策略/扣库存/时间线）；「退款」→ `.el-message-box`「全额退款确认：原路退回订单 … 的全部金额？」，**取消后新增网络请求 = 0**（performance entries 实证）；行操作枚举＝详情/退款/更多操作（复制单号·会话·柜机视频）；`PENDING` 专属的「催付/仅超时未付」无数据未演练 |
| 4 表单与校验 | N/A（读页） | 本页无写入表单；筛选表单无必填。时间范围边界（起>止、跨年）未测 |
| 5 样式与布局 | ✅（部分） | 列设置勾选生效（20→19→20 还原）；长单号列 `min-width=140` + 省略号，横向滚动条正常；窄视口未测（同页 R1 大屏类问题面） |
| 6 文案与口径 | ✅ | 状态全中文（字典 `order_status` 翻译，无裸码）；金额 `¥3.50`、优惠行显「`¥1.50 原 ¥3.50`」+「`-¥2.00`」；「隐藏零元单」带 tooltip 说明含「已退款 Tab 仍显示有退款额的单」 |
| 7 联动一致性 | ⚠️ 部分 | 后台内自洽 + 与 DB 真值对账通过；🔴 mp 侧（consumer `orders`、merchant `orders`/`video`）**未点验**——需商户/消费者口令，交 R9 双小程序轮 |
| 8 控制台与网络 | ✅ | 全程 `list_console_messages(error,warn)` = 0 条 |
| 9 打印/导出/外链 | ✅（1 条待拍板） | 服务端导出与筛选同源：`mode=orders` 14 行、`mode=orders&excludeZero=1` 3 行、`mode=lines` 14 行；🔴 见「需用户拍板」第 1 条（两套表头） |

- 发现：**MA-006**（P2，`displayBizNo` 把 `E2E-TAIL-0001` 剥成假短号「20001」⇒ 按显示值搜 0 结果、与同行「复制单号」给的真值不一致）——轮内已修：源码 `56100ddc`、产物 `4715e6d9`，mp 单测 6/6（含 3 条新钉测）+ 活栈硬刷复验（第 9 行显 `E2E-TAIL-0001`，按该值搜 → 共 1 条）。证据 `evidence/R02-orders-list.png`（修复前）、`R02-orders-displayid-search-miss.png`、`R02-orders-displayid-fixed-searchable.png`。
- 观察项（**判为非缺陷**，附判据）：「已隐藏 N 笔 0 元单」提示只在**当前筛选结果为空**时才探测并显示（`OrderListView.vue:864` 的 `(data.total||0)===0 && shouldApplyExcludeZero()` 条件）——设计如此，避免每次加载多打一次 `size=1` 探针；列表非空时不显数字，由 tooltip 承担说明。
- 数据条件缺口（与 R1 NEAR_EXPIRY 同类）：dev 库无 `PENDING`/`DISPUTED`/`REFUNDED` 订单 ⇒ 催付、超时未付勾选、已退款 tab「仍显示有退款额的单」这条既定行为**未演练**。
- 需用户配合（真机）：无（本页无真机项；柜机视频按钮指向的录像面在 R1/CB-078 已单独审过）。

## 发现汇总

| 级别 | 数量 | 编号 |
|------|------|------|
| P0 | 0 | — |
| P1 | 0 | — |
| P2 | 1 | MA-006（轮内已修） |
| P3 | 0 | — |

## 需用户拍板

1. **导出口径两套表头（系统性，非本页缺陷）**：同一页「导出」下拉里，勾了行 → 前端 `useListCsv` 出**中文表头**（`订单号,会话,…`）；未勾选/全量 → 走服务端 `orders/export`，表头是**英文字段名**（`orderId,sessionId,…`，见 `OpsSessionOrderQueryService.java:288`）。全仓 7 处服务端导出都是英文字段名 ⇒ 这是既定约定，我没有顺手改。**要不要统一成中文表头？**（改=服务端 7 处 + 可能影响既有对账脚本/`check:csv-escape`；不改=建议至少在 UI 上说明两种导出的差异）
2. **`E2E-TAIL-0001` 这类 E2E 遗留单**（设备 `CAB-OTHER`、0 商品行、金额 350 分）要不要清掉？它不是 UI 缺陷的成因（MA-006 已按函数层修），但会长期污染 R2/R6 的数值对账基线。

## 需用户配合（真机）

- （待填：开门/扫码/支付类）

## 修复批次建议

- （待填；轮末还要过一遍 PLAN 的门禁化候选台账）

## 给下一轮的交接注记

- 线索 #2（`/reports` 离线 5 vs 工作台 3）**不属本轮范围**，但它读的是设备 lifecycle 口径——R3 设备商品轮开轮时先把它结掉（判缺陷还是口径差），别在 R2 半途领号。

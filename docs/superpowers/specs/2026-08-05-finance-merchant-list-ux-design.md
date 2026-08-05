# 财务商户列表 UX 对齐（方案 B）

**日期**：2026-08-05  
**范围**：`clients/admin-vue` 财务商户分组页面；必要时轻量补齐 `trade-service` 列表 `keyword`/分页参数；导出文件名工具统一  
**依据**：用户确认方案 B——以**设备管理 / 商品管理**已落地的清单约定为标杆，财务商户整组对齐，不新造 `ListPage` 大壳。

---

## 1. 目标与非目标

### 1.1 目标

1. 财务商户 8 个入口的筛选、表区滚动、分页、弹窗、导出命名与**设备 / 商品**一致。  
2. 优先关键词搜索，减少零散专用筛选项。  
3. 导出文件名中文业务前缀 + 时间戳（与 `csvFileName` / 订单服务端导出一致）。  
4. 资金账单明确支持**跨月**日期区间导出（UI 提示 + 合理区间上限）。

### 1.2 非目标（本期不做）

- 不新建通用 `ListPage` / `FilterBar` 大组件（沿用现有 CSS class + composables）。  
- 不重做财务业务规则（分账、对账算法、提现审核逻辑）。  
- 不批量改「设备商品」以外其它分组（订单等已合格页仅作参照，除非发现同一导出命名 bug 顺手修）。  
- 不做无 UI 的纯后端重构。

---

## 2. 标杆约定（设备 / 商品）

参考：`DeviceListView.vue`、`SkuListView.vue`。

| 能力 | 约定 |
|------|------|
| 页头 | `page-card` + `page-card-head`（标题 / hint / 操作按钮） |
| 筛选 | `filter-bar filter-bar--compact`：`关键词` 优先 → 必要下拉 → 查询 / 重置 |
| 表区 | `table-scroll` > `table-scroll-inner` > `el-table`（`stripe` `border` `report-table`） |
| 分页 | 卡片底部 `page-pager`：`total, sizes, prev, pager, next` + `background`，`page-sizes=[10,20,50,100]` |
| 导出 | `useListCsv({ filePrefix: '中文名' })`；服务端下载用 `downloadAuthFile(url, csvFileName('中文名'))` |
| 弹窗 | `el-dialog` + `destroy-on-close`；表单用 Element Plus 控件（不用原生 `date`）；有简要说明文案 |
| 布局 | 筛选 / alert / KPI 不挤死表高；分页贴底固定，纵滚在 `.table-scroll` |

共享能力已有：`useListCsv`、`useTableSelection`、`csvFileName`、`main.css` 中 `.table-scroll` / `.page-pager` 布局。

本期可补的**薄工具**（可选，非大组件）：

- 分页默认常量（如 `DEFAULT_PAGE_SIZES`）——若各页硬编码不一致则抽一处；否则直接抄设备/商品即可。  
- 凡 `downloadAuthFile` 仍写死英文文件名的，一律改为 `csvFileName(...)`。

---

## 3. 页面落地清单

| 路由 | 页面 | 对齐项 |
|------|------|--------|
| `/fund-bills` | 资金账单 | 日账单缺分页 → 补 `page-pager`；主筛选加关键词（商户编号/名称）；账期区间保留并提示跨月；账务明细筛选并入/对齐主条；服务端导出改 `csvFileName('资金日账单')`（可含账期后缀） |
| `/reconciliation` | 对账 | 加关键词（对账 ID / 日期）；加账期或保留渠道+状态；底部分页；「执行对账」弹窗改 `el-date-picker` + 说明文案 |
| `/consistency` | 数据一致性 | 加关键词（类型/键/表）；类型筛选可选；底部分页 |
| `/merchants` | 商户与分账 | 商户列表加关键词；无分页 tab 补齐；弹窗补说明（对齐设备新建弹窗密度） |
| `/line-managers` | 线长钱包 | 已有关键词+分页 → 布局 class / 分页 layout / 导出命名扫齐 |
| `/merchant-withdraw` | 商户提现 | 同上 |
| `/recharges` | 充值管理 | 已基本合格 → 关键词 placeholder / 导出扫齐 |
| `/users` | 用户余额 | 同上 |

### 3.1 分页策略

- API 已支持 `page`/`size`：走服务端分页（与商品列表一致）。  
- API 仍返回全量（如对账约 30 天、日账单当前全量）：**本期前端切片分页**（与部分设备看板同源体验），筛选用客户端关键词过滤后再分页；数据量失控时再开后端分页（记为 follow-up，不阻塞本期）。

### 3.2 关键词策略

| 页面 | 关键词匹配字段（示意） |
|------|------------------------|
| 资金日账单 | `merchantId`、`merchantName` |
| 账务明细 | `orderId`、`deviceId`、`merchantName`、`entryId` |
| 对账 | `reconId`、`reconDate`、渠道中文/枚举 |
| 一致性 | `checkKey`、`checkType`、`tableName`、`errorMessage` |
| 商户列表 | `merchantId`、`merchantName` |
| 线长 / 提现 / 充值 / 用户 | 保持现有；placeholder 文案统一风格 |

### 3.3 弹窗

- 对账执行：去掉原生 `<input type="date">`，用 `el-date-picker`；补充 T+1 / 渠道说明。  
- 商户新建/编辑、挂载货柜、提交分账：补 `dialog-hint` 与必填说明，宽度/表单项对齐设备「新建设备」、商品「新建商品」密度。  
- 不引入新视觉主题；沿用 Element Plus + 现有 `dialog-hint` 样式。

### 3.4 导出与跨月

- 客户端 CSV：`filePrefix` 中文（如 `资金日账单`、`对账`、`商户`）。  
- 服务端 CSV：`fund-daily-bills.csv` → `csvFileName('资金日账单')`；若有账期，可用 `资金日账单_20260101-20260301` 类前缀再加时间戳（实现时二选一，优先可读）。  
- **跨月**：`fromDate`/`toDate` 本就支持跨月；UI 提示「支持跨月；建议单次不超过 90 天」；前端校验超限时 `ElMessage.warning`，不静默截断。  
- 选中行导出优先（`useTableSelection`）；无选中则导出当前筛选结果（前端页）或当前查询条件的服务端导出（日账单全量导出路径）。

---

## 4. 后端（仅必要时）

| 接口 | 变更 |
|------|------|
| `GET .../fund/daily-bills` | 可选：加 `keyword`；或保持全量由前端滤+分页 |
| `GET .../fund/ledger` | 已有分页；可选加 `keyword` |
| `GET .../reconciliation` | 可选 keyword；本期可前端滤 |
| `GET .../consistency`（若有） | 同上 |
| `GET .../fund/daily-bills/export` | 不改业务；仅前端下载文件名 |

默认：**优先前端对齐**，避免本期内大改 Flyway/权限。若某列表数据量已明显卡顿，再对该接口加 `keyword` + `page/size`。

---

## 5. 验收标准

1. 财务商户各列表页具备与设备/商品同结构的筛选条 + 表区 + 底部分页（数据为空时仍显示分页 total=0）。  
2. 关键词可缩小结果；重置清空条件并重新加载。  
3. 表区滚动正常，分页始终可见，不出现「只能在表内滑且内容贴底难操作」的回归。  
4. 导出文件名为中文前缀 + 时间戳 `.csv`；资金账单可选跨月区间且有上限提示。  
5. 对账执行等弹窗无原生 date 控件，说明文案可读。  
6. Cursor 内置浏览器：登录运营后台 → 抽测资金账单、对账、商户与分账、一致性四页关键路径。

---

## 6. 实现顺序（建议）

1. 统一导出文件名（`FundBillView` 服务端下载等明显 bug）。  
2. 资金账单（分页 + 关键词 + 跨月提示）。  
3. 对账、数据一致性。  
4. 商户与分账弹窗与商户列表关键词。  
5. 线长 / 提现 / 充值 / 用户扫齐。  
6. 浏览器冒烟验收。

---

## 7. 开放点（已定默认）

| 点 | 默认 |
|----|------|
| 跨月上限 | 90 天 |
| 全量 API 分页 | 前端切片，不阻塞后端改造 |
| 共享组件 | 不新建 ListPage |
| 标杆页 | 设备管理、商品管理 |

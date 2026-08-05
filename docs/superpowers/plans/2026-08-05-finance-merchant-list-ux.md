# 财务商户列表 UX 对齐 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将财务商户分组 8 个列表页对齐设备管理 / 商品管理的筛选、表区、分页、弹窗与导出命名约定。

**Architecture:** 不新建 ListPage 壳。复用 `filter-bar` / `table-scroll` / `page-pager`、`useListCsv`、`useTableSelection`、`csvFileName`。全量 API 列表按 `SkuListView` 模式做客户端 `filtered` + `slice` 分页；服务端已分页的保持服务端分页。

**Tech Stack:** Vue 3 + Element Plus（admin-vue）、现有 composables、必要时轻量改 trade-service 查询参数

**Spec:** `docs/superpowers/specs/2026-08-05-finance-merchant-list-ux-design.md`

## Global Constraints

- 标杆：`DeviceListView.vue`、`SkuListView.vue`
- 分页 UI：`page-sizes=[10,20,50,100]`，`layout="total, sizes, prev, pager, next"`，`background`
- 导出：中文 `filePrefix` + `csvFileName`；禁止写死 `fund-daily-bills.csv` 等英文名
- 跨月：资金账单日期区间支持跨月；上限 **90 天**；超限 `ElMessage.warning`，不静默截断
- 不新建通用 ListPage 组件；不改分账/对账业务算法

## File map

| File | Role |
|------|------|
| `clients/admin-vue/src/views/finance/FundBillView.vue` | 关键词、日账单前端分页、跨月校验、导出文件名 |
| `clients/admin-vue/src/views/reconciliation/ReconciliationView.vue` | 关键词、分页、执行弹窗 EP 化 |
| `clients/admin-vue/src/views/consistency/ConsistencyView.vue` | 关键词、类型筛选、分页 |
| `clients/admin-vue/src/views/merchants/MerchantSplitsView.vue` | 商户列表关键词+分页、弹窗说明 |
| `clients/admin-vue/src/views/finance/LineManagerView.vue` | 分页 layout / class 扫齐 |
| `clients/admin-vue/src/views/finance/MerchantWithdrawView.vue` | 同上 |
| `clients/admin-vue/src/views/recharges/RechargeListView.vue` | placeholder / 导出扫齐 |
| `clients/admin-vue/src/views/users/UserListView.vue` | 同上 |
| `clients/admin-vue/src/utils/csv.ts` | 已有 `csvFileName`，一般不改 |

---

### Task 1: 资金账单 — 导出文件名 + 跨月校验 + 关键词 + 日账单分页

**Files:**
- Modify: `clients/admin-vue/src/views/finance/FundBillView.vue`
- Reference: `clients/admin-vue/src/views/skus/SkuListView.vue`（`filtered` / `paged`）
- Reference: `clients/admin-vue/src/views/orders/OrderListView.vue`（`downloadAuthFile` + `csvFileName`）

**Interfaces:**
- Consumes: `csvFileName(prefix: string): string` from `@/utils/csv`
- Produces: 主筛选条含 `keyword`、`range`；日账单表绑 `pagedBills`；`page-pager` total=`filteredBills.length`

- [ ] **Step 1: 导出文件名**

将

```ts
await downloadAuthFile(`/api/v2/ops/admin/fund/daily-bills/export?${q}`, 'fund-daily-bills.csv');
```

改为：

```ts
import { csvFileName } from '@/utils/csv';
// ...
const from = range.value?.[0];
const to = range.value?.[1];
const prefix =
  from && to ? `资金日账单_${from.replaceAll('-', '')}-${to.replaceAll('-', '')}` : '资金日账单';
await downloadAuthFile(`/api/v2/ops/admin/fund/daily-bills/export?${q}`, csvFileName(prefix));
```

- [ ] **Step 2: 跨月区间校验（90 天）**

在 `load` / `exportCsv` 前：

```ts
const MAX_RANGE_DAYS = 90;
function assertRangeOk(): boolean {
  if (!range.value?.[0] || !range.value?.[1]) return true;
  const from = new Date(range.value[0] + 'T00:00:00');
  const to = new Date(range.value[1] + 'T00:00:00');
  const days = Math.floor((to.getTime() - from.getTime()) / 86400000) + 1;
  if (days > MAX_RANGE_DAYS) {
    ElMessage.warning(`账期跨度不能超过 ${MAX_RANGE_DAYS} 天（支持跨月）`);
    return false;
  }
  return true;
}
```

筛选条账期旁加短 hint：「支持跨月，单次不超过 90 天」。

- [ ] **Step 3: 主筛选加关键词；日账单客户端过滤+分页**

模板主 `el-form` 增加：

```vue
<el-form-item label="关键词">
  <el-input
    v-model="keyword"
    clearable
    placeholder="商户编号 / 名称"
    style="width: 200px"
    @keyup.enter="onSearch"
    @clear="onSearch"
  />
</el-form-item>
```

脚本（对齐 SkuList）：

```ts
const keyword = ref('');
const billPage = ref(1);
const billSize = ref(20);

const filteredBills = computed(() => {
  const q = keyword.value.trim().toLowerCase();
  let rows = displayBills.value;
  if (q) {
    rows = rows.filter(
      (r) =>
        String(r.merchantId || '').toLowerCase().includes(q) ||
        String(r.merchantName || '').toLowerCase().includes(q)
    );
  }
  return rows;
});

const pagedBills = computed(() => {
  const start = (billPage.value - 1) * billSize.value;
  return filteredBills.value.slice(start, start + billSize.value);
});

function onSearch() {
  billPage.value = 1;
  if (!assertRangeOk()) return;
  load();
}
```

- 日账单 `el-table :data="pagedBills"`
- 日账单 tab 下补：

```vue
<div class="page-pager">
  <el-pagination
    v-model:current-page="billPage"
    v-model:page-size="billSize"
    :total="filteredBills.length"
    :page-sizes="[10, 20, 50, 100]"
    layout="total, sizes, prev, pager, next"
    background
  />
</div>
```

- 账务明细：把「财务类型 / 收支」并入主筛选条（或保留 tab 内但加关键词匹配 orderId/deviceId/merchantName/entryId）；明细分页改用 `page-pager` class + sizes（现有仅 `total, prev, pager, next`）。
- CSV `toRows` 对日账单用 `pickBills(filteredBills.value)`（有选中仍走 selection）。

- [ ] **Step 4: 自测**

浏览器打开 `http://localhost/admin/index.html` → 资金账单：改账期跨月、超 90 天应警告；关键词过滤；底部分页切换；导出文件名含中文与时间戳。

---

### Task 2: 对账 — 关键词、分页、执行弹窗

**Files:**
- Modify: `clients/admin-vue/src/views/reconciliation/ReconciliationView.vue`

- [ ] **Step 1: 筛选条加关键词**

```vue
<el-form-item label="关键词">
  <el-input
    v-model="keyword"
    clearable
    placeholder="对账ID / 日期"
    style="width: 200px"
    @keyup.enter="search"
    @clear="search"
  />
</el-form-item>
```

在 `load` 得到 `rows` 后客户端过滤：

```ts
const keyword = ref('');
const page = ref(1);
const size = ref(20);
const allItems = ref<Row[]>([]); // 过滤前全量
// load 后写入 allItems，再用 computed filtered / paged 绑表
```

```ts
const filtered = computed(() => {
  const q = keyword.value.trim().toLowerCase();
  let rows = allItems.value;
  if (statusFilter.value) rows = rows.filter((r) => r.status === statusFilter.value);
  if (q) {
    rows = rows.filter(
      (r) =>
        String(r.reconId || '').toLowerCase().includes(q) ||
        String(r.reconDate || '').toLowerCase().includes(q) ||
        dictLabel('pay_channel', r.channel).toLowerCase().includes(q)
    );
  }
  return rows;
});
const paged = computed(() => {
  const start = (page.value - 1) * size.value;
  return filtered.value.slice(start, start + size.value);
});
```

- KPI 用 `filtered.length`；表绑 `paged`；导出 `pickSelected(filtered.value)`。

- [ ] **Step 2: 底部分页**

在 `table-scroll` 后加标准 `page-pager`（同 Task 1）。`search`/`reset` 时 `page.value = 1`。

- [ ] **Step 3: 执行对账弹窗**

替换原生 date：

```vue
<el-dialog v-model="runDialog" title="执行对账" width="480px" destroy-on-close>
  <p class="dialog-hint">按 T+1 节奏核对渠道流水与平台订单；请选择账期日期与渠道后执行。</p>
  <el-form label-position="top">
    <el-form-item label="日期" required>
      <el-date-picker
        v-model="runForm.date"
        type="date"
        value-format="YYYY-MM-DD"
        placeholder="选择日期"
        style="width: 100%"
      />
    </el-form-item>
    <el-form-item label="渠道">
      <el-select v-model="runForm.channel" style="width: 100%">
        <el-option
          v-for="item in dictOptions('pay_channel')"
          :key="item.value"
          :label="item.label"
          :value="item.value"
        />
      </el-select>
    </el-form-item>
  </el-form>
  <template #footer>
    <el-button @click="runDialog = false">取消</el-button>
    <el-button type="primary" :loading="saving" @click="runRecon">执行</el-button>
  </template>
</el-dialog>
```

删除 `.native-date` 样式。确认页内已有 `dialog-hint` 全局/局部样式；若无则抄 `MerchantSplitsView` 的 `.dialog-hint`。

- [ ] **Step 4: 自测**

对账页：关键词、分页、打开执行弹窗为 EP 日期控件。

---

### Task 3: 数据一致性 — 关键词 + 类型 + 分页

**Files:**
- Modify: `clients/admin-vue/src/views/consistency/ConsistencyView.vue`

- [ ] **Step 1: 筛选条**

在 alert / kpi 与 table 之间加：

```vue
<el-form inline class="filter-bar filter-bar--compact" @submit.prevent="onSearch">
  <el-form-item label="关键词">
    <el-input
      v-model="keyword"
      clearable
      placeholder="键 / 表 / 说明"
      style="width: 220px"
      @keyup.enter="onSearch"
      @clear="onSearch"
    />
  </el-form-item>
  <el-form-item label="类型">
    <el-select v-model="typeFilter" clearable placeholder="全部" style="width: 140px" @change="onSearch">
      <el-option label="订单金额" value="ORDER_AMOUNT" />
      <el-option label="支付净额" value="PAYMENT_AMOUNT" />
      <el-option label="库存汇总" value="INVENTORY_MISMATCH" />
    </el-select>
  </el-form-item>
  <el-form-item>
    <el-button type="primary" @click="onSearch">查询</el-button>
    <el-button @click="resetFilters">重置</el-button>
  </el-form-item>
</el-form>
```

- [ ] **Step 2: filtered + paged + page-pager**

同 SkuList 模式；`failCount` 仍用未过滤 FAIL 全量或过滤后数量（推荐 KPI 显示过滤后条数，标签文案「FAIL {{ filtered.length }}」）。

- [ ] **Step 3: 自测**

一致性页筛选与分页可用。

---

### Task 4: 商户与分账 — 商户列表关键词/分页 + 弹窗说明

**Files:**
- Modify: `clients/admin-vue/src/views/merchants/MerchantSplitsView.vue`

- [ ] **Step 1: 商户列表 tab**

在商户列表 alert 下加关键词筛选；对 `merchants` 做 `filteredMerchants` / `pagedMerchants` + `page-pager`（分账 tab 已有服务端分页，勿重复破坏）。

关键词匹配 `merchantId`、`merchantName`。

- [ ] **Step 2: 弹窗加厚**

- `submitDialog`：保留 splitId/orderId；补充「余额支付须填微信交易号；微信支付可留空由系统关联」说明。  
- `orgDialog`：`label-position="top"` 或与设备一致的 `label-width`；抽成字段说明（bps：1000=10%）。  
- `assignDialog`：已有 hint，补空设备时的 empty 提示。

- [ ] **Step 3: 导出**

确认 `filePrefix` 为中文（`商户` / `分账`）；与 `useListCsv` 一致。

- [ ] **Step 4: 自测**

商户列表关键词+分页；打开新建/挂载/提交分账弹窗可读。

---

### Task 5: 线长 / 提现 / 充值 / 用户 — 扫齐

**Files:**
- Modify: `clients/admin-vue/src/views/finance/LineManagerView.vue`
- Modify: `clients/admin-vue/src/views/finance/MerchantWithdrawView.vue`
- Modify: `clients/admin-vue/src/views/recharges/RechargeListView.vue`
- Modify: `clients/admin-vue/src/views/users/UserListView.vue`

- [ ] **Step 1: 分页 UI**

凡 `el-pagination` 缺 `sizes` / `background` / `page-pager` class 的，改成与商品一致；删除 scoped 里重复的 `.page-pager` 若与全局冲突。

- [ ] **Step 2: 关键词 placeholder**

统一风格（例：充值「用户编号 / 充值单」若 API 仅支持 userId 则保持准确文案，勿假装支持未实现字段）。

- [ ] **Step 3: 导出 filePrefix**

确认均为中文前缀；无 `downloadAuthFile` 英文死名。

- [ ] **Step 4: 快速点验四页加载与分页**

---

### Task 6: 浏览器冒烟验收

**入口:** `http://localhost/admin/index.html`（演示账号 `13900000001` / `123456`）  
**工具:** 仅 Cursor 内置 Browser MCP

- [ ] 确认 gateway / trade-service 已起；未起则启动或告知用户
- [ ] 资金账单：关键词、分页、跨月提示、导出文件名
- [ ] 对账：关键词、分页、执行弹窗
- [ ] 一致性：关键词、分页
- [ ] 商户与分账：商户列表关键词+分页、弹窗
- [ ] 记录实际结果；失败写明预期 vs 实际

---

## Spec coverage checklist

| Spec 项 | Task |
|---------|------|
| 筛选条关键词优先 | 1–5 |
| table-scroll + page-pager | 1–5 |
| 导出 csvFileName | 1, 4, 5 |
| 跨月 90 天 | 1 |
| 对账弹窗 EP 化 | 2 |
| 一致性筛选分页 | 3 |
| 商户弹窗加厚 | 4 |
| 线长/提现/充值/用户扫齐 | 5 |
| 浏览器验收 | 6 |
| 不新建 ListPage | 全局遵守 |

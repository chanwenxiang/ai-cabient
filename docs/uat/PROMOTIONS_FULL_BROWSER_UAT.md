# 营销活动 · 全量浏览器 UAT（单页深测册）

> **地位**：侧栏「增长风控 → 营销活动」**单页执行真源**。比 [`GROWTH_RISK_FULL_BROWSER_UAT.md`](./GROWTH_RISK_FULL_BROWSER_UAT.md) 该项更细。  
> **工具铁律**：Playwright 真实打开/点击；禁止仅 curl。新建/编辑/启停/批量停用一律打开→取消；**导入**只验可见、不进 OS 文件框。  
> **源码**：`clients/admin-vue/src/views/promotions/PromotionsView.vue`  
> **API**：`GET/POST /api/v2/ops/promotions` · `PUT …/{id}` · `POST …/launch|stop`  
> **视口**：主测 **1366×768**（DPR=1）。  
> **版本**：1.0 · 2026-09-27

---

## 0. 元信息

| 字段 | 值 |
|------|-----|
| 执行人 | Cursor Agent + Playwright MCP |
| 日期 | 2026-09-27 |
| 视口 | 1366×768；抽检 900 / 1920 |
| 账号 | 运营超管 `13900000001` |
| 截图 | `docs/uat-screenshots/2026-09-26/promotions/` |
| 明细 | [`BUTTONS.md`](../uat-screenshots/2026-09-26/promotions/BUTTONS.md) · [`FINDINGS.md`](../uat-screenshots/2026-09-26/promotions/FINDINGS.md) |
| 统计 | **PASS** · FAIL 0 · FINDING 0 · BLOCK 0 |

---

## 1. 结构

| 区 | 内容 |
|----|------|
| 头 | 「营销活动」· hint（预算已满仍可启用、发券拦截）· 新建活动 · 批量停用（有勾选启用时） |
| 筛 | 关键词 · 状态（启用/停用）· 查询 · 重置；URL `?keyword=` / `?status=` |
| 表 | 编号/名称/类型中文/时间/预算¥/已用/剩余(+预算已满)/限次/适用柜/状态 |
| 行操作 | 编辑（非启用）· 启用/停用（确认框） |
| 弹层 | 新建/编辑：名称/类型/起止/预算/限次/适用柜/描述 |
| 工具 | 导出 · **下载模板** · **导入** · 刷新 · 列设置 |

---

## 2. 用例（PR-*）

| ID | 步骤 | 期望 |
|----|------|------|
| PR-01 | 打开 `/promotions` | 标题·空态或共 N↔`/ops/promotions` |
| PR-02 | 状态下拉 | 启用 / 停用（无 ACTIVE 裸码） |
| PR-03 | 关键词无匹配 | 「暂无活动」 |
| PR-04 | 状态筛 | 共变化或空态诚实 |
| PR-05 | 新建活动 → 取消 | 「新建活动」取消 |
| PR-06 | 编辑 → 取消 | 有停用行则测；本机 **SKIP** |
| PR-07 | 启停 → 取消 | 有行则确认取消；本机 **SKIP** |
| PR-08 | 批量停用 → 取消 | 有启用勾选则测；本机 **SKIP** |
| PR-09 | 深链 `?keyword=` | 输入框同步 |
| PR-10 | 导出 / 下载模板 | 可下；导入只验可见 |
| PR-U-* | 视口 | 900 无横滚 · 结束 1366 |

---

## 3. 结案清单

- [x] 空态共 0↔API；状态中文；新建取消；深链
- [x] 编辑/启停/批停 SKIP（无数据）；导入未点文件框
- [x] 导出/模板；UX
- [x] BUTTONS / FINDINGS / Changelog；GROWTH_RISK GR-PROMO-02

---

## 4. 执行摘要（2026-09-27）

| 维度 | 结果 |
|------|------|
| 列表 | 共 **0** · 「暂无活动」· API `/ops/promotions` total=0 |
| 筛 | 启用/停用；关键词空；`?keyword=test` 深链 OK |
| 写路径 | 新建取消 · 编辑/启停/批停 SKIP |
| 工具 | 导出 · 下载模板（`营销活动导入模板_*.csv`）· 导入可见未点 |
| 视口 | 窄 900 无横滚 · 结束 1366 |
|

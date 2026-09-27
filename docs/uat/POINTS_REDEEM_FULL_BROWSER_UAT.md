# 积分兑换管理 · 全量浏览器 UAT（单页深测册）

> **地位**：侧栏「增长风控 → 积分兑换管理」**单页执行真源**。比 [`GROWTH_RISK_FULL_BROWSER_UAT.md`](./GROWTH_RISK_FULL_BROWSER_UAT.md) 该项更细。  
> **工具铁律**：Playwright 真实打开/点击；禁止仅 curl。新建/编辑/批上下架→取消；**行启停无确认**（见 FINDING，有数据时禁止盲点）。  
> **源码**：`clients/admin-vue/src/views/growth/PointsRedeemView.vue`  
> **API**：`GET/PUT /api/v2/ops/admin/growth/points-redeem` · `POST …/{id}/status`  
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
| 截图 | `docs/uat-screenshots/2026-09-26/points-redeem/` |
| 明细 | [`BUTTONS.md`](../uat-screenshots/2026-09-26/points-redeem/BUTTONS.md) · [`FINDINGS.md`](../uat-screenshots/2026-09-26/points-redeem/FINDINGS.md) |
| 统计 | **PASS** · FAIL 0 · FINDING **1** · BLOCK 0 |

---

## 1. 结构

| 区 | 内容 |
|----|------|
| 头 | 「积分兑换管理」· hint（兑券扣积分）· 新建 · 批量上架/下架（有勾选） |
| 筛 | 关键词（前端过滤）· 查询 · 重置 |
| 表 | ID/兑换项/积分/券名/券定义/库存已兑/可兑/排序/状态/创建时间 · 空态「暂无兑换项」 |
| 行操作 | 编辑 · 启用/停用 |
| 弹层 | 新建/编辑：标题/副标题/表情/积分/优惠券/库存/排序/状态 |

---

## 2. 用例（PRD-*）

| ID | 步骤 | 期望 |
|----|------|------|
| PRD-01 | 打开 `/points-redeem` | 标题·空态或共 N↔API |
| PRD-02 | 关键词无匹配 | 「暂无兑换项」 |
| PRD-03 | 新建兑换项 → 取消 | 弹层取消 |
| PRD-04 | 编辑 → 取消 | 有行则测；本机 **SKIP** |
| PRD-05 | 行启停 | 有行则见 FINDING-1；本机 **SKIP** |
| PRD-06 | 批量上架/下架 → 取消 | 有勾选则测；本机 **SKIP** |
| PRD-07 | 导出 / 刷新 | 可点 |
| PRD-U-* | 视口 | 900 无横滚 · 结束 1366 |

---

## 3. 结案清单

- [x] 空态共 0；新建取消；行写 SKIP
- [x] FINDING-1：行启停无二次确认（源码）
- [x] BUTTONS / FINDINGS / Changelog；GROWTH_RISK GR-PTS-02

---

## 4. 执行摘要（2026-09-27）

| 维度 | 结果 |
|------|------|
| 列表 | 共 **0** ·「暂无兑换项」· API 数组 length=0 |
| 写路径 | 新建取消 · 其余 SKIP |
| 视口 | 窄 900 无横滚 · 结束 1366 |
|

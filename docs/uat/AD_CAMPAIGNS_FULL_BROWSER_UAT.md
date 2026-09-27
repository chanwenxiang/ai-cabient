# 投放计划 · 全量浏览器 UAT（单页深测册）

> **地位**：侧栏「增长风控 → 投放计划」**单页执行真源**。比 [`GROWTH_RISK_FULL_BROWSER_UAT.md`](./GROWTH_RISK_FULL_BROWSER_UAT.md) 该项更细。  
> **工具铁律**：Playwright 真实打开/点击；禁止仅 curl。新建/编辑/删除→取消；**行上线/停止无确认**（见 FINDING，有数据时禁止盲点）。  
> **源码**：`clients/admin-vue/src/views/growth/AdCampaignsView.vue`  
> **API**：`GET/POST /api/v2/ops/admin/ad/campaigns` · `…/launch|stop` · `PUT/DELETE`  
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
| 截图 | `docs/uat-screenshots/2026-09-26/ad-campaigns/` |
| 明细 | [`BUTTONS.md`](../uat-screenshots/2026-09-26/ad-campaigns/BUTTONS.md) · [`FINDINGS.md`](../uat-screenshots/2026-09-26/ad-campaigns/FINDINGS.md) |
| 统计 | **PASS** · FAIL 0 · FINDING **1** · BLOCK 0 |

---

## 1. 结构

| 区 | 内容 |
|----|------|
| 头 | 「投放计划」· hint（小程序开门页曝光/完播/点击）· 新建投放 · 批量停止（有勾选） |
| 筛 | 关键词（前端过滤）· 查询 · 重置 |
| 表 | ID/名称/状态/范围/素材数/曝光/完播/完播率/柜机数/时间窗 · 空态「暂无投放计划」 |
| 行操作 | 编辑 · 上线（草稿/已停）· 停止（运行中）· 删除（非运行，更多） |
| 弹层 | 新建/编辑：名称/范围/时间窗/轮播素材 |

---

## 2. 用例（AC-*）

| ID | 步骤 | 期望 |
|----|------|------|
| AC-01 | 打开 `/ad-campaigns` | 标题·空态或共 N↔API |
| AC-02 | 关键词无匹配 | 「暂无投放计划」 |
| AC-03 | 新建投放 → 取消 | 弹层取消 |
| AC-04 | 编辑 → 取消 | 有行则测；本机 **SKIP** |
| AC-05 | 上线/停止 | 有行则测；见 FINDING-1（无确认） |
| AC-06 | 删除 → 取消 | 有行则测；本机 **SKIP** |
| AC-07 | 批量停止 → 取消 | 有 RUNNING 勾选则测；本机 **SKIP** |
| AC-08 | 导出 / 刷新 | 可点 |
| AC-U-* | 视口 | 900 无横滚 · 结束 1366 |

---

## 3. 结案清单

- [x] 空态共 0；新建取消；行写 SKIP
- [x] FINDING-1：行上线/停止无二次确认（源码）
- [x] BUTTONS / FINDINGS / Changelog；GROWTH_RISK GR-CAMP-02

---

## 4. 执行摘要（2026-09-27）

| 维度 | 结果 |
|------|------|
| 列表 | 共 **0** ·「暂无投放计划」· API total=0 |
| 写路径 | 新建取消 · 其余 SKIP |
| 视口 | 窄 900 无横滚 · 结束 1366 |
|

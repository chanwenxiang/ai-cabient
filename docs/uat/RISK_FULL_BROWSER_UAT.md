# 风控 · 全量浏览器 UAT（单页深测册）

> **地位**：侧栏「增长风控 → 风控」**单页执行真源**。比 [`GROWTH_RISK_FULL_BROWSER_UAT.md`](./GROWTH_RISK_FULL_BROWSER_UAT.md) 该项更细。  
> **工具铁律**：Playwright 真实打开/点击；禁止仅 curl。加入/移出黑名单一律打开→取消（禁止真写）。  
> **源码**：`clients/admin-vue/src/views/risk/RiskView.vue`  
> **API**：`GET …/risk/events` · `GET/POST …/risk/blacklist` · `DELETE …/blacklist/{userId}` · 双导出  
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
| 截图 | `docs/uat-screenshots/2026-09-26/risk/` |
| 明细 | [`BUTTONS.md`](../uat-screenshots/2026-09-26/risk/BUTTONS.md) · [`FINDINGS.md`](../uat-screenshots/2026-09-26/risk/FINDINGS.md) |
| 统计 | **PASS** · FAIL 0 · FINDING 0 · BLOCK 0 |

---

## 1. 结构

| 区 | 内容 |
|----|------|
| 头 | 「风控」· hint · 黑名单 Tab 显「加入黑名单」· 导出（随 Tab） |
| Tab | **风险事件** / **黑名单**；URL `?tab=blacklist` |
| 事件表 | 类型中文+ID / 用户链 / 设备链 / 详情 / 级别 / 处置中文 / 备注 / 时间 |
| 黑名单表 | 用户链 / 原因 / 来源 / 到期 / 加入时间 · 移出 · 空态「暂无黑名单」 |
| 弹层 | 「加入黑名单」：用户 ID（**不预填**）+ 原因 · 取消/确认 |

---

## 2. 用例（RK-*）

| ID | 步骤 | 期望 |
|----|------|------|
| RK-01 | 打开 `/risk` | 双 Tab；共 N↔API；处置中文 |
| RK-02 | 点用户链 | → `/users?keyword=` |
| RK-03 | Tab 黑名单 | URL `tab=blacklist`；空态或列表 |
| RK-04 | 加入黑名单 → 取消 | 弹层；userId 空；取消 |
| RK-05 | 移出 → 取消 | 有行则确认取消；本机 **SKIP** |
| RK-06 | 深链 `?tab=blacklist` | 激活黑名单 |
| RK-07/08 | 导出事件 / 黑名单 | CSV 可下 |
| RK-U-* | 视口 | 900 无横滚 · 结束 1366 |

---

## 3. 结案清单

- [x] 事件共 24；用户链；黑名单空态诚实
- [x] 加黑取消（userId 未预填）；移出 SKIP
- [x] 深链 tab；双导出；UX
- [x] BUTTONS / FINDINGS / Changelog；GROWTH_RISK GR-RISK-02

---

## 4. 执行摘要（2026-09-27）

| 维度 | 结果 |
|------|------|
| 风险事件 | 共 **24** · 处置「待处置」 |
| 用户链 | → `/users?keyword=10001` |
| 黑名单 | 共 **0** · 「暂无黑名单」 |
| 写路径 | 加入取消 · 移出 SKIP |
| 导出 | `risk-events.csv` · `risk-blacklist.csv` |
| 视口 | 窄 900 无横滚 · 结束 1366 |
|

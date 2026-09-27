# 用户余额 · 全量浏览器 UAT（单页深测册）

> **地位**：侧栏「财务商户 → 用户余额」**单页执行真源**。比 [`FINANCE_MERCHANT_FULL_BROWSER_UAT.md`](./FINANCE_MERCHANT_FULL_BROWSER_UAT.md) 该项更细。  
> **工具铁律**：Playwright 真实打开/点击；禁止仅 curl。调整余额 / 核验实名仅打开→取消（禁止真调账、真核验）。  
> **源码**：`clients/admin-vue/src/views/users/UserListView.vue`  
> **API**：`GET …/ops/admin/users` · `POST …/users/{id}/balance` · `POST …/users/{id}/verify`  
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
| 截图 | `docs/uat-screenshots/2026-09-26/users/` |
| 明细 | [`BUTTONS.md`](../uat-screenshots/2026-09-26/users/BUTTONS.md) · [`FINDINGS.md`](../uat-screenshots/2026-09-26/users/FINDINGS.md) |
| 统计 | **PASS** · FAIL 0 · FINDING 0 · BLOCK 0 |

---

## 1. 结构

| 区 | 内容 |
|----|------|
| 头 | 「用户余额」· hint（手机号/姓名/ID；有权限可调余额） |
| 筛 | 关键词 · 查询 · 重置；URL `?keyword=` 深链 |
| 表 | 用户编号/姓名/手机号/角色/余额¥/实名/会员等级/积分/黑名单/注册时间/操作 |
| 行操作 | `调整余额`（弹层）· `核验实名`（未实名行，MessageBox） |
| 工具 | 升序/降序 · 导出 CSV · 刷新 · 列设置 |

---

## 2. 用例（USR-*）

| ID | 步骤 | 期望 |
|----|------|------|
| USR-01 | 打开 `/users` | 标题「用户余额」；共 N↔API；余额 `¥x.xx`；实名/角色中文 |
| USR-02 | 关键词无匹配 | 「暂无用户」· 共 0 |
| USR-03 | 关键词命中 userId | 共 ≥1 · ¥ 可见 |
| USR-04 | 调整余额 → 取消 | 弹层「调整用户余额」（金额/原因）→ 取消；未进二次确认 |
| USR-05 | 核验实名 → 取消 | 未实名行 MessageBox → 取消 |
| USR-06 | 深链 `?keyword=` | 输入框与列表同步；共 ≥1 |
| USR-07 | 导出 CSV | 可下载 |
| USR-08 | 刷新 / 重置 | 可点 |
| USR-U-* | 视口 | 900 无横滚 · 结束 1366 DPR=1 |

---

## 3. 结案清单

- [x] 列表共 15↔API；¥/实名中文；关键词空/命中；深链
- [x] 调账弹层取消；核验 MessageBox 取消（无硬写）
- [x] 导出 CSV；UX 900/1366/1920
- [x] BUTTONS / FINDINGS / Changelog；FINANCE_MERCHANT FIN-USR-02

---

## 4. 执行摘要（2026-09-27）

| 维度 | 结果 |
|------|------|
| 列表 | 共 **15** · ¥ 展示 · 已实名/未实名 |
| 样例 | userId `100000039` 命中共 1；深链同 |
| 写路径 | 调账取消 · 核验「用户 0」取消 |
| 导出 | `用户余额_*.csv` |
| 视口 | 窄 900 无横滚 · 结束 1366 |
|

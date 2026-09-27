# 告警规则 · 全量浏览器 UAT（单页深测册）

> **地位**：侧栏「系统 → 告警规则」**单页执行真源**。比 [`SYSTEM_FULL_BROWSER_UAT.md`](./SYSTEM_FULL_BROWSER_UAT.md) 该项更细。  
> **工具铁律**：Playwright 真实打开/点击；禁止仅 curl。编辑/删除→取消；**不点**「测试发送」（Webhook 副作用）；新增无空位时诚实 toast。  
> **源码**：`clients/admin-vue/src/views/system/AlertRuleView.vue`  
> **API**：同源 `…/system-configs`（白名单过滤）· `systemConfigAlertTest`  
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
| 截图 | `docs/uat-screenshots/2026-09-26/alert-rules/` |
| 明细 | [`BUTTONS.md`](../uat-screenshots/2026-09-26/alert-rules/BUTTONS.md) · [`FINDINGS.md`](../uat-screenshots/2026-09-26/alert-rules/FINDINGS.md) |
| 统计 | **PASS** · FAIL 0 · FINDING 0 · BLOCK 0 · SKIP 1（测试发送） |

---

## 1. 结构

| 区 | 内容 |
|----|------|
| 头 | 「告警规则」· hint（与参数配置同源/白名单）· 批量删除（有选中）· **测试发送** · **新增** |
| 筛 | 关键词 · 查询/重置 |
| 表 | 分组/规则说明/配置键/单位提示/当前值/更新时间 · 空态「暂无告警规则」 |
| 行操作 | 编辑 · 删除（仅非白名单自定义键） |
| 弹层 | 新增/编辑告警规则 |
| 工具 | 导出 · 刷新（无导入/模板） |

---

## 2. 用例（AR-*）

| ID | 步骤 | 期望 |
|----|------|------|
| AR-01 | 打开 `/alert-rules` | 共 N；开关显示「开/关」 |
| AR-02 | 关键词无命中 → 重置 | 暂无告警规则 → 恢复 |
| AR-03 | 新增 | 无空位→toast「白名单键均已存在…」；有空位→弹层取消 |
| AR-04 | 编辑 → 取消 | 配置键 disabled → 取消 |
| AR-05 | 删除（自定义键）→ 取消 | MessageBox → 取消 |
| AR-06 | 勾选内置 → 批量删除 | toast「请先勾选自定义…」 |
| AR-07 | 测试发送 | SKIP（不点；会打 Webhook） |
| AR-08 | 导出 | CSV |
| AR-09 | 刷新 | 可点 |
| AR-U-* | 视口 | 900 无横滚 · 结束 1366 |

---

## 3. 结案清单

- [x] 共 25；筛；新增诚实 toast；编辑/删除取消；批删内置诚实；导出；测试发送未点
- [x] UX 900/1366/1920
- [x] BUTTONS / FINDINGS / Changelog；SYSTEM SYS-ALERT-02

---

## 4. 执行摘要（2026-09-27）

| 维度 | 结果 |
|------|------|
| 列表 | 共 **25** · 设备离线与解锁 / 温控 / 争议 SLA… |
| 筛 | 无命中空态 · 重置 25 |
| 新增 | toast「白名单键均已存在，请直接编辑列表项」（无弹窗） |
| 编辑 | `device.offline.auto_sales_lock_minutes` 键 disabled → 取消 |
| 删除 | `order.unpaid.auto_blacklist` 确认 → 取消 |
| 批删 | 勾选内置 → toast「请先勾选自定义告警规则（内置键不可批量删除）」 |
| 测试发送 | 可见 · **未点** |
| 工具 | 导出 `告警规则_20260927_111507.csv` · 无导入/模板 · 刷新 ✓ |
| 视口 | 窄 900 无横滚 · 结束 1366 |
|

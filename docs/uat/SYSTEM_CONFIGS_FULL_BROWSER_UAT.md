# 参数配置 · 全量浏览器 UAT（单页深测册）

> **地位**：侧栏「系统 → 参数配置」**单页执行真源**。比 [`SYSTEM_FULL_BROWSER_UAT.md`](./SYSTEM_FULL_BROWSER_UAT.md) 该项更细。  
> **工具铁律**：Playwright 真实打开/点击；禁止仅 curl。新增/编辑/删除→取消；**不点**「保存品牌」；上传标志/导入 OS 文件框 SKIP。  
> **源码**：`clients/admin-vue/src/views/system/SystemConfigView.vue`  
> **API**：`GET/POST/PUT/DELETE …/system-configs` · `…/history` · `…/rollback` · brand-logo  
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
| 截图 | `docs/uat-screenshots/2026-09-26/system-configs/` |
| 明细 | [`BUTTONS.md`](../uat-screenshots/2026-09-26/system-configs/BUTTONS.md) · [`FINDINGS.md`](../uat-screenshots/2026-09-26/system-configs/FINDINGS.md) |
| 统计 | **PASS** · FAIL 0 · FINDING 0 · BLOCK 0 · SKIP 2（上传标志/导入） |

---

## 1. 结构

| 区 | 内容 |
|----|------|
| 头 | 「参数配置」· hint · **新增** |
| 品牌 | 品牌外观卡：标志/主标题/副标题/侧栏标题 · 上传标志 · **保存品牌** |
| 筛 | 关键词 · 功能分组 · 查询/重置 |
| 表 | 配置键/功能分组/说明/配置值/更新时间 · 空态「暂无参数」 |
| 行操作 | 编辑 · 删除 · 历史（overflow） |
| 抽屉 | 变更历史 · {key}（回滚需确认） |
| 弹层 | 新增/编辑参数 |
| 工具 | 导出 · 下载模板 · 导入 · 刷新 |

---

## 2. 用例（CFG-*）

| ID | 步骤 | 期望 |
|----|------|------|
| CFG-01 | 打开 `/system-configs` | 共 N；品牌区可见 |
| CFG-02 | 关键词无命中 → 重置 | 暂无参数 → 恢复 |
| CFG-03 | 功能分组下拉 | 多分组可读 |
| CFG-04 | 新增 → 取消 | 「新增参数」→ 取消 |
| CFG-05 | 编辑 → 取消 | 配置键 disabled → 取消 |
| CFG-06 | 更多→历史 → 关闭 | 抽屉标题含 key；空态诚实 |
| CFG-07 | 删除 → 取消 | MessageBox → 取消 |
| CFG-08 | 导出 / 下载模板 | CSV |
| CFG-09 | 导入 / 上传标志 | SKIP（OS 文件框） |
| CFG-10 | 保存品牌 | **不点**（硬写）；按钮可见即可 |
| CFG-11 | 刷新 | 可点 |
| CFG-U-* | 视口 | 900 无横滚 · 结束 1366 |

---

## 3. 结案清单

- [x] 共 76；筛/分组；新增编辑删除取消；历史抽屉空态；导出+模板；品牌/导入 SKIP 硬写
- [x] UX 900/1366/1920
- [x] BUTTONS / FINDINGS / Changelog；SYSTEM SYS-CFG-02

---

## 4. 执行摘要（2026-09-27）

| 维度 | 结果 |
|------|------|
| 列表 | 共 **76**（页 20）· 如 `balance.refund.max_cents` / `brand.title`=前海易购 |
| 品牌 | 上传标志 / 保存品牌 **可见**；未点保存、未上传 |
| 筛 | 无命中空态 · 重置 76 · 分组含结算与识别/交易与资金/退款… |
| 写路径 | 新增取消 · 编辑键 disabled · 删「balance.refund.max_cents」取消 |
| 历史 | 「变更历史 · balance.refund.max_cents」· 暂无变更记录（审计未开或无改动） |
| 工具 | 导出 `参数配置_20260927_110824.csv` · 模板 ✓ · 导入 SKIP · 刷新 ✓ |
| 视口 | 窄 900 无横滚 · 结束 1366 |
|

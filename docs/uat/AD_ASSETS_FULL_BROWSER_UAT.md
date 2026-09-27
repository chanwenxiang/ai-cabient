# 素材库 · 全量浏览器 UAT（单页深测册）

> **地位**：侧栏「增长风控 → 素材库」**单页执行真源**。比 [`GROWTH_RISK_FULL_BROWSER_UAT.md`](./GROWTH_RISK_FULL_BROWSER_UAT.md) 该项更细。  
> **工具铁律**：Playwright 真实打开/点击；禁止仅 curl。上传面板可测但**禁止真选文件**（stub `input.click` 或 SKIP）；编辑/删除→取消。  
> **源码**：`clients/admin-vue/src/views/growth/AdAssetsView.vue`  
> **API**：`GET/POST /api/v2/ops/admin/ad/assets` · `PUT/DELETE …/assets/{id}`  
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
| 截图 | `docs/uat-screenshots/2026-09-26/ad-assets/` |
| 明细 | [`BUTTONS.md`](../uat-screenshots/2026-09-26/ad-assets/BUTTONS.md) · [`FINDINGS.md`](../uat-screenshots/2026-09-26/ad-assets/FINDINGS.md) |
| 统计 | **PASS** · FAIL 0 · FINDING **1** · BLOCK 0 |

---

## 1. 结构

| 区 | 内容 |
|----|------|
| 头 | 「素材库」· hint（柜机播放未接，仅后台预览）· 上传素材 · 批量停用/删除（有勾选） |
| 上传条 | 标题 · 类型（图片/视频/H5）· 时长 · 上传/取消 · ≤50MB |
| 筛 | 关键词（**前端过滤**）· 查询 · 重置 |
| 表 | ID/标题/类型中文/预览/时长/状态（在用）/上传时间 |
| 行操作 | 编辑 · 删除（确认框） |
| 弹层 | 「编辑素材」：标题/时长/在用开关 |

---

## 2. 用例（AA-*）

| ID | 步骤 | 期望 |
|----|------|------|
| AA-01 | 打开 `/ad-assets` | 标题·共 N↔API；hint |
| AA-02 | 关键词无匹配 | 行空 |
| AA-03 | 上传素材 → 取消 | stub 文件框；面板类型三档 → 取消 |
| AA-04 | 编辑 → 取消 | 有行则测；本机 **SKIP** |
| AA-05 | 删除 → 取消 | 有行则测；本机 **SKIP** |
| AA-06 | 批量停用 | 有行则测；见 FINDING-1（无确认） |
| AA-07 | 批量删除 → 取消 | 有行则测；本机 **SKIP** |
| AA-08 | 导出 / 刷新 | 可点 |
| AA-U-* | 视口 | 900 无横滚 · 结束 1366 |

---

## 3. 结案清单

- [x] 空态共 0↔API；上传面板取消（未真传）；行写 SKIP
- [x] FINDING-1：批量停用无二次确认（源码）
- [x] BUTTONS / FINDINGS / Changelog；GROWTH_RISK GR-AD-02

---

## 4. 执行摘要（2026-09-27）

| 维度 | 结果 |
|------|------|
| 列表 | 共 **0** · API `/ops/admin/ad/assets` total=0 |
| 上传 | 面板开（图片/视频/H5）→取消；未进 OS 文件框 |
| 写路径 | 编辑/删除/批删 SKIP |
| 视口 | 窄 900 无横滚 · 结束 1366 |
|

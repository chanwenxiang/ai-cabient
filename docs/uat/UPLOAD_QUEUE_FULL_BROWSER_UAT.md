# 录像上传 · 全量浏览器 UAT（单页深测册）

> **地位**：侧栏「设备商品 → 录像上传」**单页执行真源**。比 [`DEVICE_SKU_FULL_BROWSER_UAT.md`](./DEVICE_SKU_FULL_BROWSER_UAT.md) 该项更细。  
> **工具铁律**：Playwright 真实打开/点击；禁止仅 curl。本页**无硬写**（非人工上传入口）；筛选/深链/说明/导出只读。  
> **源码**：`clients/admin-vue/src/views/upload/UploadQueueView.vue`  
> **API**：`GET /api/v2/ops/admin/sessions?state=WAITING_UPLOAD`（`q` / `uploadStatus` / `stuckOnly`+`stuckMinutes=30`）  
> **视口**：主测 **1366×768**（DPR=1）。  
> **版本**：1.0 · 2026-09-26

---

## 0. 元信息

| 字段 | 值 |
|------|-----|
| 执行人 | Cursor Agent + Playwright MCP |
| 日期 | 2026-09-26 |
| 视口 | 1366×768；抽检 900 / 1920 |
| 账号 | 运营超管 `13900000001` |
| 截图 | `docs/uat-screenshots/2026-09-26/upload-queue/` |
| 明细 | [`BUTTONS.md`](../uat-screenshots/2026-09-26/upload-queue/BUTTONS.md) · [`FINDINGS.md`](../uat-screenshots/2026-09-26/upload-queue/FINDINGS.md) |
| 统计 | **PASS** · FAIL 0 · BLOCK 0 |

---

## 1. 结构

| 区 | 内容 |
|----|------|
| 头 | 标题「录像上传队列」· SLA 30 分钟提示 |
| 说明 | 上传说明（非人工上传入口） |
| 筛选 | 关键词 · 上传状态 · 仅滞留 · 查询/重置 |
| 表 | CrudTable：会话/设备深链 · 状态中文 · 滞留/时限 · 播放 · 导出/刷新 |

---

## 2. 用例（UQ-*）

| ID | 步骤 | 期望 |
|----|------|------|
| UQ-01 | 打开 `/upload-queue` | 共 N↔API；空态中文诚实 |
| UQ-02 | 上传说明 | 非人工入口说明可见 |
| UQ-03 | 仅滞留 | URL `stuck=1`；共↔API stuck |
| UQ-05 | 上传状态失败 | URL `uploadStatus=FAILED` |
| UQ-07 | 无匹配关键词 | 共 0 |
| UQ-08 | 重置 | 恢复默认 |
| UQ-09 | `?stuck=1` | 勾选回显 |
| UQ-10 | `?deviceId=` | 关键词回显 |
| UQ-15/16 | `/videos` `/uploads` | 重定向到 `/upload-queue` |
| UQ-17 | `?sessionId=` | 输入/URL 聚焦 |
| UQ-18 | 状态下拉 | 中文选项无英文码泄漏 |
| UQ-20 | 仅滞留空态 | 「无超过 30 分钟…」文案 |
| UQ-U-01 | 900 | 无整页横滚 |

---

## 3. 结案清单

- [x] 本机 WAITING_UPLOAD 共 **0**（诚实空态）；全量会话另有数据，队列过滤正确
- [x] 仅滞留 / 状态下拉中文 / 关键词空 / 深链 device·session·stuck
- [x] `/videos` `/uploads` 重定向；工具栏导出/刷新
- [x] BUTTONS / FINDINGS / Changelog；DEVICE_SKU 深链

---

## 4. 执行摘要（2026-09-26）

| 维度 | 结果 |
|------|------|
| 队列 | 共 **0** · 空态「暂无待上传录像」 |
| 滞留 | `stuck=1` 空态「当前无超过 30 分钟的滞留上传…」 |
| 深链 | CAB-001 / sessionId 回显；别名路由 OK |
| 视口 | 窄 900 无横滚 · 结束 1366 |
|

# 识别映射 · 全量浏览器 UAT（单页深测册）

> **地位**：侧栏「设备商品 → 识别映射」**单页执行真源**。比 [`DEVICE_SKU_FULL_BROWSER_UAT.md`](./DEVICE_SKU_FULL_BROWSER_UAT.md) 识别映射项更细。  
> **工具铁律**：Playwright 真实打开/点击；禁止仅 curl。写路径仅确认→取消（新增/编辑/删除 YOLO 与阿里云）。  
> **源码**：`clients/admin-vue/src/views/vision/VisionMappingView.vue`  
> **API**：`GET /api/v2/ops/admin/vision-mappings/yolo`（`q`/`page`/`size`）；`GET …/vision-mappings`（含 `aliyun`）；写：`POST/DELETE …/yolo|aliyun`  
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
| 截图 | `docs/uat-screenshots/2026-09-26/vision-mappings/` |
| 明细 | [`BUTTONS.md`](../uat-screenshots/2026-09-26/vision-mappings/BUTTONS.md) · [`FINDINGS.md`](../uat-screenshots/2026-09-26/vision-mappings/FINDINGS.md) |
| 统计 | **PASS** · FAIL 0 · BLOCK 0 |

---

## 1. 结构

| 区 | 内容 |
|----|------|
| 头 | 商品管理 · 识别入驻 · 新增映射 |
| 筛选 | 关键词（类别/SKU/商品名）· 查询/重置 |
| YOLO 表 | CrudTable：类名/商品/入驻状态/映射·模型/置信度；编辑/删除 |
| 阿里云卡 | 类目映射表；新增；行编辑/删除（本机空态） |

---

## 2. 用例（MAP-*）

| ID | 步骤 | 期望 |
|----|------|------|
| MAP-01 | 打开 `/vision-mappings` | 共 N↔API；入驻中文；阿里云区可见 |
| MAP-02 | 关键词「可乐」 | 共 2↔API |
| MAP-03 | 无匹配 | 共 0；空态中文 |
| MAP-04 | 重置 | 恢复全量 |
| MAP-05 | `?keyword=可乐` | 输入回显；共 2 |
| MAP-06 | 新增映射 → 取消 | 不落库 |
| MAP-07 | 行编辑 → 取消 | 不落库 |
| MAP-08 | 行删除 → 取消 | 确认框后取消 |
| MAP-09 | 阿里云新增 → 取消 | 不落库 |
| MAP-10/11 | 阿里云编辑/删除 | 有行则取消；无行 SKIP |
| MAP-12/13 | 识别入驻 / 商品管理 | 深链正确 |
| MAP-U-01 | 900 | 无整页横滚 |

---

## 3. 结案清单

- [x] 共 4；bottle→可乐；草稿/未进白名单中文；置信度 50%
- [x] 关键词 `q` 诚实；深链 `keyword`；空态共 0
- [x] YOLO 新增/编辑/删除取消；阿里云新增取消；阿里云行 SKIP（空）
- [x] BUTTONS / FINDINGS / Changelog；DEVICE_SKU 深链

---

## 4. 执行摘要（2026-09-26）

| 维度 | 结果 |
|------|------|
| YOLO | 共 **4** · 可乐关键词共 **2**（bottle/cup） |
| 阿里云 | 空态「暂无阿里云类目映射」· 新增可开取消 |
| 写 | 新增/编辑/删除均取消 |
| 视口 | 窄 900 无横滚 · 结束 1366 |
|

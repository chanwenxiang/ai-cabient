# 识别入驻 · 全量浏览器 UAT（单页深测册）

> **地位**：侧栏「设备商品 → 识别入驻」**单页执行真源**。比 [`DEVICE_SKU_FULL_BROWSER_UAT.md`](./DEVICE_SKU_FULL_BROWSER_UAT.md) 识别入驻项更细。  
> **工具铁律**：Playwright 真实打开/点击；禁止仅 curl。写路径仅确认→取消（入驻配置/编辑/批量下架）；推进状态/转生产/预览识别不点确认落库。  
> **源码**：`clients/admin-vue/src/views/skus/SkuVisionEnrollView.vue`  
> **API**：`GET /api/v2/ops/admin/sku-vision/rows`（`q` / `status` / `enrollment`）；入驻保存/推进另接口（本轮取消不写）  
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
| 截图 | `docs/uat-screenshots/2026-09-26/sku-vision/` |
| 明细 | [`BUTTONS.md`](../uat-screenshots/2026-09-26/sku-vision/BUTTONS.md) · [`FINDINGS.md`](../uat-screenshots/2026-09-26/sku-vision/FINDINGS.md) |
| 统计 | **PASS** · FAIL 0 · BLOCK 0 |

---

## 1. 结构

| 区 | 内容 |
|----|------|
| 头 | 入驻说明 · 入驻配置 · 商品管理 |
| Chip | 全部 / 草稿 / 映射中 / 已测试 / 生产（带计数） |
| Tab | 在售商品 · 所有商品 |
| 筛选 | 关键词 · 识别状态 · 查询/重置 · 批量下架 |
| 表 | CrudTable：导出/导入/刷新；行编辑/识别测试（图标）/更多→推进 |

---

## 2. 用例（VIS-*）

| ID | 步骤 | 期望 |
|----|------|------|
| VIS-01 | 打开 `/sku-vision` | 标题识别入驻；共 N↔API；状态中文 |
| VIS-02 | 入驻说明 | 展开说明文案；可收起 |
| VIS-03 | Chip「草稿」 | URL `enrollment=DRAFT`；共 N↔API |
| VIS-04 | Tab「所有商品」 | total ≥ 在售 |
| VIS-05 | 关键词「可乐」 | 共 1；行含可乐（参数 `q`） |
| VIS-06 | 无匹配 | 共 0；空态中文 |
| VIS-07 | 重置 | 恢复在售全量 |
| VIS-08 | 识别状态下拉「草稿」 | 共 N↔API draft |
| VIS-09 | 入驻配置 → 取消 | 弹层关闭不落库 |
| VIS-10 | 行名/编辑 → 取消 | 编辑弹层关闭 |
| VIS-11 | 行「识别测试」→ 关闭 | 弹层「识别测试 · SKU」；不点预览落库 |
| VIS-12 | 勾选→批量下架→取消 | 确认框后取消 |
| VIS-13 | 商品管理 | → `/skus` |
| VIS-U-01 | 900 | 无整页横滚 |

---

## 3. 结案清单

- [x] 共 6；金额 ¥；草稿/未进白名单/端侧质量门禁中文
- [x] Chip/Tab/关键词 `q`/状态筛；空态共 0
- [x] 入驻配置/编辑取消；识别测试关闭；批量下架取消
- [x] BUTTONS / FINDINGS / Changelog；DEVICE_SKU 深链

---

## 4. 执行摘要（2026-09-26）

| 维度 | 结果 |
|------|------|
| 列表 | 共 **6** · 全草稿 · 可乐 ¥3.50 / ¥1.90 |
| 关键词 | 「可乐」共 1；无匹配共 0（`q`；误用 `keyword` 无效属 API 契约） |
| 写 | 配置/编辑取消；批量下架取消；推进未点 |
| 视口 | 窄 900 无横滚 · 结束 1366 |
|

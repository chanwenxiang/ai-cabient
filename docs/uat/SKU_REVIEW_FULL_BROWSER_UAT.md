# 选品诊断 · 全量浏览器 UAT（单页深测册）

> **地位**：侧栏「设备商品 → 选品诊断」**单页执行真源**。比 [`DEVICE_SKU_FULL_BROWSER_UAT.md`](./DEVICE_SKU_FULL_BROWSER_UAT.md) 选品项更细。  
> **工具铁律**：Playwright 真实打开/点击；禁止仅 curl。写路径仅确认→取消（批量下架/保留/行确认下架）。  
> **源码**：`clients/admin-vue/src/views/growth/SkuReviewView.vue`  
> **API**：`GET /growth/sku-review`；`POST /growth/sku-review/run?days=`；`POST …/{skuId}/decide`  
> **视口**：主测 **1366×768**。  
> **版本**：1.0 · 2026-09-26

---

## 0. 元信息

| 字段 | 值 |
|------|-----|
| 执行人 | Cursor Agent + Playwright MCP |
| 日期 | 2026-09-26 |
| 视口 | 1366×768；抽检 900 / 1920 |
| 账号 | 运营超管 `13900000001` |
| 截图 | `docs/uat-screenshots/2026-09-26/sku-review/` |
| 明细 | [`BUTTONS.md`](../uat-screenshots/2026-09-26/sku-review/BUTTONS.md) · [`FINDINGS.md`](../uat-screenshots/2026-09-26/sku-review/FINDINGS.md) |
| 统计 | PASS · FAIL 0 · BLOCK 0（修关键词 total 后复测） |

---

## 1. 结构

| 区 | 内容 |
|----|------|
| 头 | 批量下架/保留 · 近 N 天 · 运行诊断 |
| 筛选 | 关键词 · 查询/重置 |
| 表 | CrudTable：导出/刷新；行动销/评审中文；行建议下架/保留/确认下架 |

---

## 2. 用例（REV-*）

| ID | 步骤 | 期望 |
|----|------|------|
| REV-01 | 打开 `/sku-review` | 共 N↔API；中文动销/评审 |
| REV-02 | 关键词「可乐」 | 共 1（过滤后 total 诚实） |
| REV-03 | 无匹配 | 共 0 |
| REV-04 | 重置 | 恢复全量 |
| REV-05 | 运行诊断 | toast「诊断完成」 |
| REV-06 | 批量下架/保留 → 取消 | 不落库 |
| REV-U-01 | 900 | 无整页横滚 |

---

## 3. 结案清单

- [x] 共 6；可乐销量 9 / ¥31.50；无销量/待评审中文
- [x] 关键词 total 诚实（#210 修复后）
- [x] 运行诊断；批量下架/保留取消
- [x] BUTTONS / FINDINGS / Changelog / lesson #210

---

## 4. 执行摘要（2026-09-26）

| 维度 | 结果 |
|------|------|
| 列表 | 共 **6** · 可乐 NORMAL · 其余无销量 |
| 关键词 | 「可乐」共 1；无匹配共 0 |
| 写 | 批量下架/保留取消；运行诊断 OK |
| 视口 | 窄 900 无横滚 · 结束 1366 |
|

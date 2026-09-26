# 异常中心 · 全量浏览器 UAT（单页深测册）

> **地位**：侧栏「交易履约 → 异常中心」**单页执行真源**。比 [`FULFILLMENT_FULL_BROWSER_UAT.md`](./FULFILLMENT_FULL_BROWSER_UAT.md) §3.4 更细。  
> **工具铁律**：Playwright 真实打开/点击；禁止仅 curl。免单/备注/转派/重试/取消会话一律 **确认框后取消**（禁真写）。  
> **源码**：`clients/admin-vue/src/views/exceptions/ExceptionListView.vue`  
> **API**：`GET /api/v2/ops/admin/exceptions`（**非** `/ops/exceptions`）  
> **视口**：主测 **1366×768**；窄/宽用 MCP `browser_resize`，**禁止**在长脚本里反复 `setViewportSize`+`goto`（会弹回 1366）。  
> **版本**：1.0 · 2026-09-26

---

## 0. 元信息

| 字段 | 值 |
|------|-----|
| 执行人 | Cursor Agent + Playwright MCP |
| 日期 | 2026-09-26 |
| 视口 | 1366×768（DPR≈1）；抽检 900 / 1920 |
| 账号 | 运营超管 `13900000001` |
| 截图 | `docs/uat-screenshots/2026-09-26/exceptions/` |
| 明细 | [`BUTTONS.md`](../uat-screenshots/2026-09-26/exceptions/BUTTONS.md) · [`FINDINGS.md`](../uat-screenshots/2026-09-26/exceptions/FINDINGS.md) |
| 统计 | PASS · FAIL 0 · BLOCK 0（修 #207 后） |

---

## 1. 结构

| 区 | 内容 |
|----|------|
| 头 | 设备运维深链 |
| 筛选 | 级别 · 仅超时 · 查询/重置 |
| Tab | 待处理 / 处理中 / 已解决 / 已关闭 / 已归档 / **全部** |
| 表 | 导出 · 刷新 · 列设置 · 行详情/设备 |
| 抽屉 | 录像 · 开门记录 · 争议审单 · 免单/备注/转派/重试/取消会话（均确认取消） |

---

## 2. 用例（EXC-*）

| ID | 步骤 | 期望 |
|----|------|------|
| EXC-01 | `?status=OPEN` | Tab「待处理」；total↔API |
| EXC-02 | 点「全部」 | URL `status=ALL`；active=全部；total↔无 status API |
| EXC-03 | 仅超时 | 列表/横幅可读；可取消勾选 |
| EXC-04 | 设备运维 | `/device-ops` |
| EXC-05 | 行详情 | 抽屉打开无白屏 |
| EXC-06 | 开门记录 | `/sessions?deviceId=&sessionId=` |
| EXC-07 | 打开争议审单 | `/disputes?...` |
| EXC-08～12 | 免单/备注/转派/重试/取消会话 | 确认框 → **取消** |
| EXC-13 | 行「设备」 | `/devices/{id}` |
| EXC-export | 导出 | CSV 下载 |
| EXC-U-01 | 900 视口 | 无整页横滚；**resize 后勿 goto** |

---

## 3. 结案清单

- [x] OPEN=12 / ALL=111 / RESOLVED=99 ↔ API
- [x] 「全部」Tab 修后 `status=ALL` 不回弹 OPEN（#207）
- [x] 免单/备注/转派/重试/取消会话均取消
- [x] 开门记录 · 争议 · 设备 · 设备运维深链
- [x] 导出 CSV
- [x] 视口 1366 钉死；900/1920 抽检
- [x] BUTTONS / FINDINGS / Changelog / lessons #207

---

## 4. 执行摘要（2026-09-26）

| 维度 | 结果 |
|------|------|
| OPEN | 共 12 条 = API |
| 全部 | 共 **111** 条；URL `?status=ALL`（修前会回弹待处理 12） |
| 已解决 | 共 99 条 |
| 写路径 | 五类确认均取消 |
| 导出 | `异常_20260926_*.csv` |
| 视口结束 | vw=1366 · 窄 900 无整页横滚 |
|

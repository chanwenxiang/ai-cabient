# 开门记录 · 全量浏览器 UAT（单页深测册）

> **地位**：侧栏「交易履约 → 开门记录」**单页执行真源**。比 [`FULFILLMENT_FULL_BROWSER_UAT.md`](./FULFILLMENT_FULL_BROWSER_UAT.md) §3.2 更细。  
> **工具铁律**：Playwright 真实打开/点击；禁止仅 curl。写路径（取消会话）一律 **确认框后取消**。  
> **源码**：`clients/admin-vue/src/views/sessions/SessionListView.vue`  
> **API**：`GET /api/v2/ops/admin/sessions`（滞留用 `stuckOnly=true`，勿只传 `stuck=1`）  
> **版本**：1.0 · 2026-09-26

---

## 0. 元信息

| 字段 | 值 |
|------|-----|
| 执行人 | Cursor Agent + Playwright MCP |
| 日期 | 2026-09-26 |
| 视口 | 1366×768；抽检 900 / 1920 |
| 账号 | 运营超管 `13900000001` |
| 截图 | `docs/uat-screenshots/2026-09-26/sessions/` |
| 明细 | [`BUTTONS.md`](../uat-screenshots/2026-09-26/sessions/BUTTONS.md) · [`FINDINGS.md`](../uat-screenshots/2026-09-26/sessions/FINDINGS.md) |
| 统计 | PASS · FAIL 0 · BLOCK 0 · 取消会话 N/A（无活跃可取消行） |

---

## 1. 结构

| 区 | 内容 |
|----|------|
| 页头 | 导出 |
| Tab×11 | 全部 + 字典态（已创建…已取消） |
| 筛选 | 关键词 · 类型(消费/补货/运维) · 时间 · 仅滞留 · 查询/重置 |
| 表 | 时间线 / 播放 / 看设备 / 取消会话；列设置 · 刷新 · 自动刷新 |
| 抽屉 | 时间线步骤 · 播放录像 · 录像上传队列 · 查看订单 |
| 深链 | `?stuck=1` · `?sessionId=` · `?deviceId=` |

---

## 2. 用例（SES-*）

| ID | 步骤 | 期望 |
|----|------|------|
| SES-01 | 打开 `/sessions` | total ↔ API；中文状态 |
| SES-02 | 十一 Tab | 行集/空态变化 |
| SES-03 | 关键词=会话号 | ≥1 行 |
| SES-04 | 类型筛选 | 空则「共 0」诚实（#205）；有数据可翻页 |
| SES-05 | `?stuck=1` | 勾选仅滞留；活跃∩超时；无已完成泄漏（#195） |
| SES-06 | 导出 | `sessions.csv` |
| SES-07 | 时间线抽屉 | 步骤可读 |
| SES-08 | 播放录像 | 可播或中文失败 |
| SES-09 | 上传队列 / 查看订单 | → upload-queue / orders |
| SES-10 | 设备链 | → `/devices/{id}` |
| SES-11 | 取消会话 | 有按钮则确认取消；无则 N/A |
| SES-U-01 | 900 视口 | 无整页横滚 |

---

## 3. 结案清单

- [x] Tab / 筛选 / 导出 / 列设置 / 刷新 / 分页
- [x] 滞留深链 #195 复测 PASS（UI 共 0；API `stuckOnly=true`=0）
- [x] #205 类型筛选 total 诚实
- [x] 时间线 + 播放 + 上传队列 + 订单/设备链
- [x] BUTTONS / FINDINGS / Changelog

---

## 4. 执行摘要（2026-09-26）

| 维度 | 结果 |
|------|------|
| API 总量 | 57（本页态 COMPLETED/CANCELLED/DISPUTED 等） |
| 仅滞留 | UI/API `stuckOnly` → **0**；空文案「当前无超过 N 分钟的滞留会话」 |
| 样例会话 | `1790420129220699554144` · 已完成 · 订单 ¥3.50 同源 |
| 有片会话 | `1789865740600761262864` · 抽屉「有录像 · 有文件」· 播放开 blob |
| 导出 | `sessions.csv` |
|

# 订单管理 · 全量浏览器 UAT（单页深测册）

> **地位**：侧栏「交易履约 → 订单管理」**单页执行真源**。比 [`FULFILLMENT_FULL_BROWSER_UAT.md`](./FULFILLMENT_FULL_BROWSER_UAT.md) §3.1 更细。  
> **工具铁律**：Playwright 真实打开/点击；禁止仅 curl。资金写路径一律 **确认框后取消**（禁真退/真催付/真关单）。  
> **源码**：`clients/admin-vue/src/views/orders/OrderListView.vue`  
> **API**：`GET /api/v2/ops/admin/orders` · 导出 `…/orders/export`  
> **版本**：1.0 · 2026-09-26

---

## 0. 元信息

| 字段 | 值 |
|------|-----|
| 执行人 | Cursor Agent + Playwright MCP |
| 日期 | 2026-09-26 |
| 视口 | 1366×768；抽检 900 / 1920 |
| 账号 | 运营超管 `13900000001` |
| 截图 | `docs/uat-screenshots/2026-09-26/orders/` |
| 明细 | [`BUTTONS.md`](../uat-screenshots/2026-09-26/orders/BUTTONS.md) · [`FINDINGS.md`](../uat-screenshots/2026-09-26/orders/FINDINGS.md) |
| 统计 | PASS · FAIL 0 · BLOCK 0 · 催付/补扣/关单 N/A（无 PENDING） |

---

## 1. 结构

| 区 | 内容 |
|----|------|
| 页头 | 导出 ▾（按订单 / 按商品） |
| Tab | 全部 / 待支付 / 已支付 / 争议中 / 已退款 / 已取消 |
| 筛选 | 关键词 · 渠道 · 时间 · 仅超时未付(PENDING) · 隐藏零元单 · 查询/重置 |
| 表 | CrudTable · 列设置 · 刷新 · 行：详情/退款/更多 |
| 抽屉 | 会话/设备链 · 播放录像 · 全额/按行退款 · PENDING 催付/补扣/关单 |
| 深链 | `?status=` · `?overdue=1` · `?payChannel=` · `?keyword=` |

---

## 2. 用例（ORD-*）

| ID | 步骤 | 期望 |
|----|------|------|
| ORD-01 | 打开 `/orders` | 有行或中文空态；金额 `¥x.xx` |
| ORD-02 | 六 Tab | 行集/空态变化；URL `status=` |
| ORD-03 | 关键词 / 重置 | 精确单号 1 行；重置回全量页 |
| ORD-04 | 渠道=余额 | URL `payChannel=BALANCE` |
| ORD-05 | 隐藏零元 | 开关后行数变化；**已退款 Tab 不受滤空**（#204） |
| ORD-06 | overdue 深链 | `?status=PENDING&overdue=1` · banner · 空则「无超时未付订单」 |
| ORD-07 | 双导出 | `orders.csv` / `order-lines.csv` |
| ORD-08 | 订单号→抽屉 | 单号/金额/商品与列表一致 |
| ORD-09 | 播放录像 | 可播或中文「录像尚未上传或不存在」 |
| ORD-10 | 全额/按行退款 | 确认/对话框 → **取消** |
| ORD-11 | 行退款 / 更多 | 确认取消；菜单含复制单号/会话/录像 |
| ORD-12 | 会话/设备链 | → `/sessions?…` · `/devices/{id}` |
| ORD-13 | 催付/补扣/关单 | 有 PENDING 则确认取消；无则 N/A |
| ORD-U-01 | 900 视口 | 无整页横滚 |

---

## 3. 结案清单

- [x] Tab / 筛选 / 导出 / 列设置 / 刷新
- [x] 详情抽屉 + 退款门控取消
- [x] 会话/设备深链
- [x] overdue 深链诚实空态
- [x] #204 已退款+隐藏零元复测 PASS
- [x] BUTTONS / FINDINGS / 活文档 Changelog

---

## 4. 执行摘要（2026-09-26）

| 维度 | 结果 |
|------|------|
| API 总量 | 24（PAID 23 · REFUNDED 1 · PENDING 0） |
| 默认隐藏零元 | 列表约 10～23 非零成交；关隐藏可见 20+/页 |
| 样例单 | `1790420215052990851425` · ¥3.50 · 可乐 · 设备 `330449777078` |
| 已退款样例 | `1790242775512664013757` · 金额 ¥0.00 · **已退 ¥3.50** |
| 导出 | `orders.csv` · `order-lines.csv` |
| 录像 | toast「录像尚未上传或不存在」 |
| 资金写 | 全额/按行/行退款均取消；无真退 |
|

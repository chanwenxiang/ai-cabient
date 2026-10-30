# 问题台账（MA-xxx 唯一编号源）

> 规则见 [README.md](README.md)。领号前先回读本表（编号全局递增，禁止撞号）。
> 入账前提：**已在当前 HEAD 实跑复现**（用户拍板：旧文档结论只当线索，复现通过才入账）。

## 索引表

| ID | 级 | 状态 | 端/页面 | 一句话 | 发现人/日期 | 关联 |
|----|----|------|---------|--------|-------------|------|
| （空——首个 MA 编号待 R1 首个实跑复现的问题产生） | | | | | | |

## 待复核线索（未入账，实跑复现后转 MA 编号）

### 线索 #1：供应商对账单页面疑似不可达（R4 复核）→ ✅ 已实证并收口（D28-B15，2026-10-29）

- **来源**（当前代码，非旧文档）：
  - `clients/admin-vue/src/router/index.ts` 有路由 `warehouse-reconciliation`（`/warehouse/reconciliation`，标题「供应商对账单」，meta 无 `perm`）；
  - `clients/admin-vue/src/config/menu.ts` 的 `BASE_NAV` **没有** `warehouse/reconciliation` 条目；
  - `router/index.ts` 的 `beforeEach`：`!nav && !metaPerm` → 一律重定向 `forbidden`（fail-closed），而 `findNavByPath('/warehouse/reconciliation')` 只查 `NAV_ITEMS`，查不到。
- **实跑证实（2026-10-29，Playwright + 超管 13900000001）**：直链 `/admin/warehouse/reconciliation` → `forbidden?from=...`，页面 0 个元素；且全站 grep 无任何入口链到该路由——整页死路，**P1 成立**。
- **额外实测发现**：即使补登记 NAV_ITEMS，挂后端码 `ops:procurement:list` 仍 403——`rbac/me/nav` 的 ACTIVE 菜单表（95 行）没有任何行用该码，`isNavMenuActive` 恒 false；须挂已在 ACTIVE 表里的码（兄弟子页口径 `ops:warehouse:list`）。
- **修复**：见 [admin-vue-debt-tracker.md 十五期 D28-B15](../engineering/admin-vue-debt-tracker.md)（F5 批次）：NAV_ITEMS 补登记 + 菜单码 `ops:warehouse:list`，页内「生成对账单」由 `canList`（=后端注解码 `ops:procurement:list`）收口；Playwright 复测页面可达。R4 无需再开 MA 条目。

## 明细区块（每条一节，追加在文件末尾）

<!-- 模板：
### MA-xxx 标题
- 级别/状态：P1 / open
- 端与页面：admin `/orders`（订单管理 → 退款弹窗）
- 发现人/日期/HEAD：xxx / 2026-10-xx / `<sha>`
- 环境：本地 docker（trade:18080，admin 产物 <sha>）/ 开发者工具 mp-weixin
- 复现步骤：1) … 2) … 3) …
- 期望 / 实际：… / …
- 证据：evidence/MA-xxx-1.png（截图/请求响应/控制台）
- 源码定位：`path/to/file.ts:123`
- 联动影响：consumer-mp `order-detail` 显示 …
- 关联：AU-xxx / D-xx（旧编号，仅线索）
- 修复记录：（fixed 时填：改动、验证方式、门禁结果）
-->

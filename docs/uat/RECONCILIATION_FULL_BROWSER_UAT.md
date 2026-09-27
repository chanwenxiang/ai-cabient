# 对账 · 全量浏览器 UAT（单页深测册）

> **地位**：侧栏「财务商户 → 对账」**单页执行真源**。比 [`FINANCE_MERCHANT_FULL_BROWSER_UAT.md`](./FINANCE_MERCHANT_FULL_BROWSER_UAT.md) 该项更细。  
> **工具铁律**：Playwright 真实打开/点击；禁止仅 curl。写路径仅确认→取消（执行对账）。  
> **源码**：`clients/admin-vue/src/views/reconciliation/ReconciliationView.vue`  
> **API**：`GET …/reconciliation` · `POST …/reconciliation/run` · `GET …/reconciliation/{id}`  
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
| 截图 | `docs/uat-screenshots/2026-09-26/reconciliation/` |
| 明细 | [`BUTTONS.md`](../uat-screenshots/2026-09-26/reconciliation/BUTTONS.md) · [`FINDINGS.md`](../uat-screenshots/2026-09-26/reconciliation/FINDINGS.md) |
| 统计 | **PASS** · FAIL 0 · FINDING 2（status/keyword 后端未滤）· BLOCK 0 |

---

## 1. 结构

| 区 | 内容 |
|----|------|
| 头 | 标题「对账」· hint · **执行对账** |
| Alert | T+1 结算说明（差额 / 未匹配笔数口径） |
| 筛 | 关键词 · 渠道 · 状态 · 查询 · 重置 |
| KPI | 批次总数 · 本页差异 · 本页匹配 |
| 表 | 对账ID/账期/渠道/状态/差额/未匹配/创建时间 · 详情 · 导出/刷新 |
| 弹层 | 执行对账（日期+渠道） |
| 抽屉 | 对账详情（汇总+明细行） |

---

## 2. 用例（REC-*）

| ID | 步骤 | 期望 |
|----|------|------|
| REC-01 | 打开 `/reconciliation` | 共 2↔API；T+1 Alert；KPI；中文状态；¥ |
| REC-02 | 执行对账 → 取消 | 不跑批 |
| REC-03 | 渠道下拉 | 微信/支付宝/其他/余额/未知 |
| REC-04 | 筛微信 | 共 **1** · URL `channel=WECHAT` |
| REC-05 | 状态下拉 | 已平账/存在差异/待处理/失败 |
| REC-06 | 筛存在差异 | URL `status=MISMATCH`；**FINDING**：列表仍 2 行（含已平账） |
| REC-07 | 关键词无匹配 | **FINDING**：仍共 2（后端忽略 keyword） |
| REC-08 | 详情 | 抽屉「对账详情」· 差额/明细 |
| REC-09 | `?status=MISMATCH` | 筛选项同步；列表未滤（同 F-01） |
| REC-10 | 导出 | CSV 下载 |
| REC-11 | 刷新 | 可点 |
| REC-U-01 | 900 | 无整页横滚 |
| REC-U-03 | 结束 | 1366×768 DPR≈1 |

---

## 3. 结案清单

- [x] 列表 2（微信已平账 + 余额差异 −¥3）↔ API
- [x] 执行对账取消；详情抽屉；渠道筛有效；导出 CSV
- [x] FINDING：`status` / `keyword` 后端未过滤（渠道有效）
- [x] BUTTONS / FINDINGS / Changelog；FINANCE_MERCHANT 深链

---

## 4. 执行摘要（2026-09-26）

| 维度 | 结果 |
|------|------|
| 列表 | 共 **2** · KPI 差异1/匹配1 |
| 渠道筛 | WECHAT→1 · BALANCE→1 |
| 状态/关键词 | **无效**（API 仍返回全量） |
| 写路径 | 执行对账取消 |
| 深链 | `status=` 写入筛选项，列表未滤 |
| 视口 | 窄 900 无横滚 · 结束 1366 |
|

# 商户与分账 · 全量浏览器 UAT（单页深测册）

> **地位**：侧栏「财务商户 → 商户与分账」**单页执行真源**。比 [`FINANCE_MERCHANT_FULL_BROWSER_UAT.md`](./FINANCE_MERCHANT_FULL_BROWSER_UAT.md) 该项更细。  
> **工具铁律**：Playwright 真实打开/点击；禁止仅 curl。写路径仅确认→取消（新建/编辑/挂载/确认完结）。  
> **源码**：`clients/admin-vue/src/views/merchants/MerchantSplitsView.vue`  
> **API**：`GET …/merchants` · `GET …/merchants/revenue-splits` · 组织/挂载/确认完结 POST  
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
| 截图 | `docs/uat-screenshots/2026-09-26/merchants/` |
| 明细 | [`BUTTONS.md`](../uat-screenshots/2026-09-26/merchants/BUTTONS.md) · [`FINDINGS.md`](../uat-screenshots/2026-09-26/merchants/FINDINGS.md) |
| 统计 | **PASS** · FAIL 0 · BLOCK 0 |

---

## 1. 结构

| Tab | 内容 |
|-----|------|
| 组织树 | 树节点 · 新建/编辑/挂载货柜 |
| 商户列表 | 关键词 · 功能包开关（现场/经营/团队）· 抽成% |
| 运营配置 | 理货策略等 · 保存（硬写，本轮不点） |
| 分账明细 | 仅记账说明 · 确认完结 / 提交微信 · 导出 |

---

## 2. 用例（MCH-*）

| ID | 步骤 | 期望 |
|----|------|------|
| MCH-01 | 打开 `/merchants` | 默认组织树；hint「仅记账」；树≥1 |
| MCH-02 | 新建商户 → 取消 | 弹层关闭不落库 |
| MCH-03 | 编辑 → 取消 | 弹层关闭 |
| MCH-04 | 挂载货柜 → 取消 | 弹层关闭 |
| MCH-05 | Tab 商户列表 | 共 N↔API；功能包列可见 |
| MCH-06 | 关键词无匹配 | 共 0 |
| MCH-07 | 重置 | 恢复列表 |
| MCH-08 | 功能包开关 | 只验可见，**不拨动**（即时写） |
| MCH-09 | Tab 运营配置 | 策略/保存可见；不点保存 |
| MCH-10 | Tab 分账明细 | 共↔API；仅记账说明 |
| MCH-11 | 行「确认完结」→ 取消 | MessageBox dismiss |
| MCH-12 | `?tab=splits` | 深链落地分账 |
| MCH-13 | 导出 / 刷新 | CSV 可触发 |
| MCH-U-01 | 900 | 无整页横滚 |

---

## 3. 结案清单

- [x] 四 Tab；树 2 节点；商户共 **2**；分账共 **24**
- [x] 新建/编辑/挂载/确认完结均取消；功能包开关未拨
- [x] 深链 `?tab=splits`；导出 `分账明细_*.csv`
- [x] BUTTONS / FINDINGS / Changelog；FINANCE_MERCHANT 册深链

---

## 4. 执行摘要（2026-09-26）

| 维度 | 结果 |
|------|------|
| 组织树 | 2 节点 · 新建/编辑/挂载取消 |
| 商户列表 | 共 **2**（MCH-DEFAULT / MCH-OTHER）· 关键词空 0 |
| 运营配置 | 理货/保存可见 · 未保存 |
| 分账 | 共 **24** · 确认完结取消 · 深链 OK |
| 视口 | 窄 900 无横滚 · 结束 1366 |
|

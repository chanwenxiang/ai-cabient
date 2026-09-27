# Phase 6 增长风控 UAT 执行日志 · 2026-09-26（复测）

> 工具：Playwright MCP · 视口 1366×768 · 运营号 `13900000001`  
> 分册：[`GROWTH_RISK_FULL_BROWSER_UAT.md`](../../../uat/GROWTH_RISK_FULL_BROWSER_UAT.md)  
> 按钮清点：[`BUTTONS.md`](./BUTTONS.md)  
> 环境：全栈 Docker · `http://localhost/admin/`

截图目录：`docs/uat-screenshots/2026-09-26/growth-risk/`

---

## 总览（本轮复测）

| 模块 | 状态 | 摘要 |
|------|------|------|
| 手机验证 | **完成** | 68→深测 **81**；登记取消；**单页深测** [`PHONE_VERIFY_FULL_BROWSER_UAT.md`](../../../uat/PHONE_VERIFY_FULL_BROWSER_UAT.md) |
| 风控 | **完成** | 事件 23→深测 **24**；黑名单空；加黑取消；**单页深测** [`RISK_FULL_BROWSER_UAT.md`](../../../uat/RISK_FULL_BROWSER_UAT.md) |
| 营销活动 | **完成** | 空态；新建取消；**单页深测** [`PROMOTIONS_FULL_BROWSER_UAT.md`](../../../uat/PROMOTIONS_FULL_BROWSER_UAT.md) |
| 优惠券 | **完成** | 1 券；新建/发券/批量均取消；**单页深测** [`COUPONS_FULL_BROWSER_UAT.md`](../../../uat/COUPONS_FULL_BROWSER_UAT.md) |
| 素材库 | **完成** | 空；上传面板取消；**FINDING** 批停无确认；[`AD_ASSETS_FULL_BROWSER_UAT.md`](../../../uat/AD_ASSETS_FULL_BROWSER_UAT.md) |
| 投放计划 | **完成** | 空；新建取消；**FINDING** 行上线/停止无确认；[`AD_CAMPAIGNS_FULL_BROWSER_UAT.md`](../../../uat/AD_CAMPAIGNS_FULL_BROWSER_UAT.md) |
| 积分兑换 | **完成** | 空；新建取消；**FINDING** 行启停无确认；[`POINTS_REDEEM_FULL_BROWSER_UAT.md`](../../../uat/POINTS_REDEEM_FULL_BROWSER_UAT.md) |
| 会员等级 | **完成** | 4 级；新建/编辑/批停取消；**FINDING** 行启停无确认；[`MEMBER_LEVELS_FULL_BROWSER_UAT.md`](../../../uat/MEMBER_LEVELS_FULL_BROWSER_UAT.md) |
| 活动效果 | **完成** | 暂无活动数据；**单页深测** [`MARKETING_ROI_FULL_BROWSER_UAT.md`](../../../uat/MARKETING_ROI_FULL_BROWSER_UAT.md) |
| 消息记录 | **完成** | 2 条；发送/编辑/删/批删取消；**FINDING** 受众OPS裸码·业务未知；[`NOTIFICATIONS_FULL_BROWSER_UAT.md`](../../../uat/NOTIFICATIONS_FULL_BROWSER_UAT.md) |
| 用户反馈 | **完成** | 8 条；回复/删取消；**单页深测** [`FEEDBACK_FULL_BROWSER_UAT.md`](../../../uat/FEEDBACK_FULL_BROWSER_UAT.md) |

**Phase 6 结案（复测）**：附录 B 见 [`BUTTONS.md`](./BUTTONS.md)。写路径均未提交。

---

## 用例结果

| ID | 判定 | 证据 |
|----|------|------|
| GR-PV-01 | PASS | 共 68；登记手机验证取消 |
| GR-PV-02 | PASS | 单页深测：共81/渠道中文/登记编辑删除取消/CSV · `phone-verify/` |
| GR-RISK-01 | PASS | 事件 23；加黑取消；黑名单「暂无」 |
| GR-RISK-02 | PASS | 单页深测：共24/用户链/加黑取消/深链/双导出 · `risk/` |
| GR-PROMO-01 | PASS | 暂无活动；新建取消 |
| GR-PROMO-02 | PASS | 单页深测：空态0/状态中文/新建取消/深链/模板 · `promotions/` |
| GR-CPN-01 | PASS | 1 券 ¥0.50；三写路径取消 |
| GR-CPN-02 | PASS | 单页深测：共1/编辑行发券停用批停取消/深链 · `coupons/` |
| GR-AD-01 | PASS | 素材空；上传 SKIP；投放新建取消 |
| GR-AD-02 | PASS | 单页深测：空态0/上传面板取消；FINDING-1 批停无确认 · `ad-assets/` |
| GR-CAMP-02 | PASS | 单页深测：空态0/新建取消；FINDING-1 行上线/停止无确认 · `ad-campaigns/` |
| GR-PTS-01 | PASS | 暂无兑换项；新建取消 |
| GR-PTS-02 | PASS | 单页深测：空态0/新建取消；FINDING-1 行启停无确认 · `points-redeem/` |
| GR-ML-01 | PASS | 普通/白银/黄金/铂金 |
| GR-ML-02 | PASS | 单页深测：共4/新建编辑取消/批停取消；FINDING-1 行启停无确认 · `member-levels/` |
| GR-ROI-01 | PASS | 暂无活动数据 |
| GR-ROI-02 | PASS | 单页深测：空态0/近7·30·90/导出空toast · `marketing-roi/` |
| GR-NTF-01 | PASS | 2 站内信；发送取消 |
| GR-NTF-02 | PASS | 单页深测：共2/发送编辑删批删取消/导出；FINDING 受众OPS·业务未知 · `notifications/` |
| GR-FB-01 | PASS | 8 反馈；回复取消 |
| GR-FB-02 | PASS | 单页深测：共8/状态筛深链/回复删取消/用户设备链/导出 · `feedback/` |

---

## 本轮缺陷

| 严重度 | 摘要 | 状态 |
|--------|------|------|
| — | 本轮复测 **无新增 FAIL** | — |

备注：测中途会话过期一次，已重登后补完 ROI/消息/反馈。

---

## 下一轮

侧栏「系统」或用户指定。

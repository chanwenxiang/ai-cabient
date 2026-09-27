# 增长风控附录 B · 按钮清点 · 2026-09-26（复测）

> 工具：Playwright MCP · 账号 `13900000001` · 视口 1366×768  
> 分册：[`GROWTH_RISK_FULL_BROWSER_UAT.md`](../../../uat/GROWTH_RISK_FULL_BROWSER_UAT.md)  
> 规则：写路径一律 **弹窗就绪后取消**（#200）；素材「上传」会唤起系统文件框，**只验可见、不真传**。  
> 截图：本目录 `gr-*.png`

---

## B.1 手机验证 `/phone-verify`

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| PV-01 | 列表 | ✓ | 共 68 条；`gr-pv-01.png` |
| PV-02 | 登记验证 → 取消 | ✓ | 「登记手机验证」；`gr-pv-02-reg.png` |
| PV-03 | 查询 / 导出 / 刷新 | ✓ | 导出 CSV 触发 |
| PV-04 | **单页深测 GR-PV-02** | ✓ | [`PHONE_VERIFY_FULL_BROWSER_UAT.md`](../../../uat/PHONE_VERIFY_FULL_BROWSER_UAT.md) · `phone-verify/`（共81/编辑删除取消） |

---

## B.2 风控 `/risk`

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| RISK-01 | Tab 风险事件 | ✓ | 共 23 条 / 本页 20；`gr-risk-01.png` |
| RISK-02 | Tab 黑名单 | ✓ | 「暂无黑名单」；`gr-risk-02-blacklist.png` |
| RISK-03 | 加入黑名单 → 取消 | ✓ | 「加入黑名单」关闭；`gr-risk-02-ban.png` |
| RISK-04 | 导出 / 刷新 | ✓ 可见 | |
| RISK-05 | **单页深测 GR-RISK-02** | ✓ | [`RISK_FULL_BROWSER_UAT.md`](../../../uat/RISK_FULL_BROWSER_UAT.md) · `risk/`（共24/用户链/深链/双导出） |

---

## B.3 营销活动 `/promotions`

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| PROMO-01 | 空态 | ✓ | 「暂无活动」类；共 0；`gr-promo-01.png` |
| PROMO-02 | 新建活动 → 取消 | ✓ | 「新建活动」；`gr-promo-02-new.png` |
| PROMO-03 | 导入 / 导出 / 下载模板 | ✓ 可见 | |
| PROMO-04 | **单页深测 GR-PROMO-02** | ✓ | [`PROMOTIONS_FULL_BROWSER_UAT.md`](../../../uat/PROMOTIONS_FULL_BROWSER_UAT.md) · `promotions/`（深链/模板/行写SKIP） |

---

## B.4 优惠券 `/coupons`

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| CPN-01 | 列表 | ✓ | 1 条「完整轮满减券」¥0.50；`gr-cpn-01.png` |
| CPN-02 | 新建优惠券 → 取消 | ✓ | `gr-cpn-02-new.png` |
| CPN-03 | 手动发券 → 取消 | ✓ | `gr-cpn-03-manual.png` |
| CPN-04 | 批量发券 → 取消 | ✓ | `gr-cpn-04-batch.png` |
| CPN-05 | **单页深测 GR-CPN-02** | ✓ | [`COUPONS_FULL_BROWSER_UAT.md`](../../../uat/COUPONS_FULL_BROWSER_UAT.md) · `coupons/`（编辑/行发券/停用/批停） |

---

## B.5 素材库 `/ad-assets`

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| AD-01 | 空态 | ✓ | 共 0；`gr-ad-01.png` |
| AD-02 | 上传素材 | SKIP* | 按钮可见；**未点**（系统文件选择器） |
| AD-03 | 查询 / 导出 / 刷新 | ✓ 可见 | |
| AD-04 | **单页深测 GR-AD-02** | ✓ | [`AD_ASSETS_FULL_BROWSER_UAT.md`](../../../uat/AD_ASSETS_FULL_BROWSER_UAT.md) · `ad-assets/`（面板取消/FINDING批停无确认） |

\* 与概览大屏全屏同理：自动化不进 OS 文件框。

---

## B.6 投放计划 `/ad-campaigns`

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| CAMP-01 | 空态 | ✓ | 「暂无投放计划」；`gr-camp-01.png` |
| CAMP-02 | 新建投放 → 取消 | ✓ | 「新建投放」；`gr-camp-02-new.png` |
| CAMP-03 | **单页深测 GR-CAMP-02** | ✓ | [`AD_CAMPAIGNS_FULL_BROWSER_UAT.md`](../../../uat/AD_CAMPAIGNS_FULL_BROWSER_UAT.md) · `ad-campaigns/`（FINDING 行上线/停止无确认） |

---

## B.7 积分兑换 `/points-redeem`

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| PTS-01 | 空态 | ✓ | 「暂无兑换项」；`gr-pts-01.png` |
| PTS-02 | 新建兑换项 → 取消 | ✓ | `gr-pts-02-new.png` |
| PTS-03 | **单页深测 GR-PTS-02** | ✓ | [`POINTS_REDEEM_FULL_BROWSER_UAT.md`](../../../uat/POINTS_REDEEM_FULL_BROWSER_UAT.md) · `points-redeem/`（FINDING 行启停无确认） |

---

## B.8 会员等级 `/member-levels`

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| ML-01 | 列表 | ✓ | 4 级：普通/白银/黄金/铂金；`gr-ml-01.png` |
| ML-02 | 新建等级 → 取消 | ✓ | `gr-ml-02-new.png` |
| ML-03 | **单页深测 GR-ML-02** | ✓ | [`MEMBER_LEVELS_FULL_BROWSER_UAT.md`](../../../uat/MEMBER_LEVELS_FULL_BROWSER_UAT.md) · `member-levels/`（编辑取消/批停取消/FINDING 行启停无确认） |

---

## B.9 活动效果 `/marketing-roi`

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| ROI-01 | 空态 | ✓ | 「暂无活动数据」；`gr-roi-01.png` |
| ROI-02 | 近 7/30/90 · 查询/导出/刷新 | ✓ | 可点 |
| ROI-03 | **单页深测 GR-ROI-02** | ✓ | [`MARKETING_ROI_FULL_BROWSER_UAT.md`](../../../uat/MARKETING_ROI_FULL_BROWSER_UAT.md) · `marketing-roi/`（空态0/切档/导出空toast） |

---

## B.10 消息记录 `/notifications`

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| NTF-01 | 列表 | ✓ | 共 2 条站内信；`gr-ntf-01.png` |
| NTF-02 | 发送站内信 → 取消 | ✓ | `gr-ntf-02-send.png` |
| NTF-03 | 删除选中 / 导出 / 刷新 | ✓ 可见 | |
| NTF-04 | **单页深测 GR-NTF-02** | ✓ | [`NOTIFICATIONS_FULL_BROWSER_UAT.md`](../../../uat/NOTIFICATIONS_FULL_BROWSER_UAT.md) · `notifications/`（编辑删批删取消/FINDING 受众OPS·业务未知） |

---

## B.11 用户反馈 `/feedback`

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| FB-01 | 列表 | ✓ | 共 8 条待处理；`gr-fb-01.png` |
| FB-02 | 回复 → 取消 | ✓ | 「回复反馈」；`gr-fb-02-reply.png` |
| FB-03 | 查询 / 导出 / 刷新 | ✓ | |
| FB-04 | **单页深测 GR-FB-02** | ✓ | [`FEEDBACK_FULL_BROWSER_UAT.md`](../../../uat/FEEDBACK_FULL_BROWSER_UAT.md) · `feedback/`（状态深链/删取消/用户设备链/导出） |

---

## Phase 6 Done（复测）

- [x] 侧栏十一页附录 B 勾生效或合法 SKIP  
- [x] 登记验证/加黑/活动/券/发券/投放/兑换/等级/站内信/回复均取消未提交  
- [x] 日志见 [`FINDINGS.md`](./FINDINGS.md)

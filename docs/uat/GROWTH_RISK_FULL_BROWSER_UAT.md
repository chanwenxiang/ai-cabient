# 运营后台 ·「增长风控」全量浏览器 UAT（Phase 6）

> **地位**：第六册（侧栏「增长风控」）。工具铁律同 Overview §1。  
> **版本**：1.1 · 2026-09-26（十一页复测）  
> **截图**：`docs/uat-screenshots/2026-09-26/growth-risk/`

| 序 | 路径 | 标题 |
|----|------|------|
| 1 | `/phone-verify` | 手机验证 |
| 2 | `/risk` | 风控 |
| 3 | `/promotions` | 营销活动 |
| 4 | `/coupons` | 优惠券 |
| 5 | `/ad-assets` | 素材库 |
| 6 | `/ad-campaigns` | 投放计划 |
| 7 | `/points-redeem` | 积分兑换管理 |
| 8 | `/member-levels` | 会员等级规则 |
| 9 | `/marketing-roi` | 活动效果分析 |
| 10 | `/notifications` | 消息记录 |
| 11 | `/feedback` | 用户反馈 |

| 字段 | 值 |
|------|-----|
| 结案 | **Phase 6 Done（复测）**；[`growth-risk/BUTTONS.md`](../uat-screenshots/2026-09-26/growth-risk/BUTTONS.md) + [`FINDINGS.md`](../uat-screenshots/2026-09-26/growth-risk/FINDINGS.md) |

ID：`GR-*`

| ID | 期望 |
|----|------|
| GR-PV-01 | 验证流水有数或中文空态 |
| GR-PV-02 | 手机验证流水单页深测：见 [`PHONE_VERIFY_FULL_BROWSER_UAT.md`](./PHONE_VERIFY_FULL_BROWSER_UAT.md)（共81/渠道中文/登记编辑删除取消/导出） |
| GR-RISK-01 | 黑名单/规则可开；写路径有确认或可取消 |
| GR-RISK-02 | 风控单页深测：见 [`RISK_FULL_BROWSER_UAT.md`](./RISK_FULL_BROWSER_UAT.md)（事件24/用户链/黑名单空/加黑取消/深链/双导出） |
| GR-PROMO-01 | 活动列表或空态 |
| GR-PROMO-02 | 营销活动单页深测：见 [`PROMOTIONS_FULL_BROWSER_UAT.md`](./PROMOTIONS_FULL_BROWSER_UAT.md)（空态0/状态中文/新建取消/深链/模板/导入SKIP） |
| GR-CPN-01 | 券模板有数或空态；发券须确认 |
| GR-CPN-02 | 优惠券单页深测：见 [`COUPONS_FULL_BROWSER_UAT.md`](./COUPONS_FULL_BROWSER_UAT.md)（共1/三发券入口取消/停用批停取消/深链） |
| GR-AD-01 | 素材/投放可开 |
| GR-AD-02 | 素材库单页深测：见 [`AD_ASSETS_FULL_BROWSER_UAT.md`](./AD_ASSETS_FULL_BROWSER_UAT.md)（空态0/上传面板取消/批量停用无确认FINDING） |
| GR-CAMP-02 | 投放计划单页深测：见 [`AD_CAMPAIGNS_FULL_BROWSER_UAT.md`](./AD_CAMPAIGNS_FULL_BROWSER_UAT.md)（空态0/新建取消/行上线停止无确认FINDING） |
| GR-PTS-01 | 积分兑换规则可读 |
| GR-PTS-02 | 积分兑换管理单页深测：见 [`POINTS_REDEEM_FULL_BROWSER_UAT.md`](./POINTS_REDEEM_FULL_BROWSER_UAT.md)（空态0/新建取消/行启停无确认FINDING） |
| GR-ML-01 | 会员等级规则可读 |
| GR-ML-02 | 会员等级规则单页深测：见 [`MEMBER_LEVELS_FULL_BROWSER_UAT.md`](./MEMBER_LEVELS_FULL_BROWSER_UAT.md)（共4/新建编辑取消/批停取消/行启停无确认FINDING） |
| GR-ROI-01 | ROI 有图/表或空态 |
| GR-ROI-02 | 活动效果分析单页深测：见 [`MARKETING_ROI_FULL_BROWSER_UAT.md`](./MARKETING_ROI_FULL_BROWSER_UAT.md)（空态0/近7·30·90/导出空toast） |
| GR-NTF-01 | 消息记录可筛 |
| GR-NTF-02 | 消息记录单页深测：见 [`NOTIFICATIONS_FULL_BROWSER_UAT.md`](./NOTIFICATIONS_FULL_BROWSER_UAT.md)（共2/发送编辑删批删取消/导出/受众OPS裸码FINDING） |
| GR-FB-01 | 反馈列表或空态 |
| GR-FB-02 | 用户反馈单页深测：见 [`FEEDBACK_FULL_BROWSER_UAT.md`](./FEEDBACK_FULL_BROWSER_UAT.md)（共8/状态筛深链/回复删取消/用户设备链/导出） |

执行日志：`docs/uat-screenshots/2026-09-26/growth-risk/FINDINGS.md`  
按钮清点：`docs/uat-screenshots/2026-09-26/growth-risk/BUTTONS.md`

## Done

- [x] 关键 ID 均有 PASS/SKIP + 证据  
- [x] 写路径确认后取消  
- [x] **2026-09-26 复测**：附录 B + `gr-*.png`；上传素材 SKIP  

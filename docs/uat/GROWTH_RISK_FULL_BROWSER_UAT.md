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
| GR-RISK-01 | 黑名单/规则可开；写路径有确认或可取消 |
| GR-PROMO-01 | 活动列表或空态 |
| GR-CPN-01 | 券模板有数或空态；发券须确认 |
| GR-AD-01 | 素材/投放可开 |
| GR-PTS-01 | 积分兑换规则可读 |
| GR-ML-01 | 会员等级规则可读 |
| GR-ROI-01 | ROI 有图/表或空态 |
| GR-NTF-01 | 消息记录可筛 |
| GR-FB-01 | 反馈列表或空态 |

执行日志：`docs/uat-screenshots/2026-09-26/growth-risk/FINDINGS.md`  
按钮清点：`docs/uat-screenshots/2026-09-26/growth-risk/BUTTONS.md`

## Done

- [x] 关键 ID 均有 PASS/SKIP + 证据  
- [x] 写路径确认后取消  
- [x] **2026-09-26 复测**：附录 B + `gr-*.png`；上传素材 SKIP  

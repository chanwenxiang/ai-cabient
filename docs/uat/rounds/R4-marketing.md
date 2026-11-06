# R4 营销/会员/增长 — 轮报告（2026-11-07）

> uat 库真实 API 链。锚 `41a78efc` 后。

## ✅ 全部通过（含 3 条修复回归实证）

### R4-A MA-147 回归（全额退返还券）——修复实证 ✓
用 R1 真实订单（PAID，券 USED 绑定）走运营全额退：
- 退款 1074/1074 对平 ✓
- **券返还 UNUSED ✓**（修复前 USED——MA-147 修复生效）
- 分账 VOIDED ✓、钱包净额 0（SPLIT+SPLIT_REV 对平）✓
- 用户余额复原 18926+1074=20000 ✓

### R4-B 召回幂等（MA-099 回归）✓
- 两次召回：第二次 **409「同券召回间隔过短（60 秒内）」**——修复生效
- 券数 2→2 无重复发放 ✓

### R4-C 积分兑换（MA-096 回归）✓
- 负 pointsCost → **400「不得为负（负数会让兑换时倒增积分 = 持续铸分）」**——修复生效（文案带根因）

## 待白天复验
- 消费端 UI 项（昵称/偏好/注销四守卫）——consumer token 过期，devtools 重开登录后即可
- 活动三型启动（SKU_DISCOUNT/FIXED_PRICE 建+launch 需各配 BANNER 位——机制已在 R1/矩阵 E2E 双向验证）

## 覆盖表新增触达
user_recall 相关（coupon_definition/user_coupon 增量）、member_points_log（读）、growth/points-redeem、growth/user-recall

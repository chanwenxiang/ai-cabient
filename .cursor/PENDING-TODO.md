# 待办交接 — 代码审查跟进

> 更新时间：2026-09-03  
> 来源：审查报告跟进（前端 F/N 轮 + 金额写入/A-7 闭环）  
> 分支：`dev`

---

## 审查项状态

| 编号 | 状态 |
|------|------|
| A-1～A-8、B-1～B-10、B-2、B-3、B-11 Phase1 | ✅ |
| **B-9 余量：消费者发短信强制图形验证码** | ✅ |
| **消费者 logout 调 API 吊销 JWT** | ✅ |
| 前端 F / N 轮（含误报项说明） | ✅ |
| **资金写入 `yuanToCents` 收敛** | ✅（本轮） |
| **listOperators 按 accountType（A-7 闭环）** | ✅（本轮） |
| N-13 / N-15 | ⏳ 卫生项，暂不改 |
| B-11 Phase2 短 access + 长 refresh | ⏳ 暂缓 |

---

## 本轮改动（金额写入 + A-7）

- 优惠券/促销/SKU/仓储付款/场地合同等写入路径：`Math.round(yuan*100)` → `yuanToCents`
- `UserInfoMapper.findOperatorsOrderByUserIdDesc`：`accountType=OPERATOR`，空类型历史数据仍按 userId 兜底
- `OpsOperatorDto.accountType` 已下发；部门成员过滤用 accountType
- trade-service 已 `--build` 重建并 healthy

---

## 验收

1. ✅ trade-service healthy；`/api/v2/ops/admin/rbac/operators` 返回 `accountType`
2. 运营后台登录后可用；优惠券/商品保存金额走 `yuanToCents`
3. B-11 Phase2 仍暂缓

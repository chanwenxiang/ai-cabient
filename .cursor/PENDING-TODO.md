# 待办交接 — 代码审查跟进

> 更新时间：2026-09-03  
> 来源：管理后台审查闭环 + **小程序端审查报告** 验证修复  
> 分支：`dev`

---

## 管理后台（已合入 origin/dev）

| 编号 | 状态 |
|------|------|
| A/B/F/N 轮核心项 | ✅ |
| 资金写入 `yuanToCents`、A-7 accountType | ✅ |
| N-13 / N-15 | ⏳ 卫生项，暂不改 |
| B-11 Phase2 短 access + 长 refresh | ⏳ 暂缓（含 M-8 token 安全存储） |

---

## 小程序端审查

| 编号 | 状态 | 说明 |
|------|------|------|
| **M-7** | ✅ | 不持久化密码；仅记住手机号 |
| **C-4 / M-1/M-2 / C-1 / M-11 / C-5(部分) / C-15 / C-12 / C-18 / C-8 / C-14** | ✅ | 已合入 `8d5e1d1e` |
| **M-13** | ✅（本轮） | 商户 complete：须补货开门会话 + ≥1 张凭证；前端不可再跳过 |
| **M-14** | ✅（本轮） | `GET .../replenishment/devices/{id}/access` + 扫码前校验归属 |
| **M-8 / token 安全存储** | ⏳ | 并入 B-11 Phase2，勿单独做客户端「加密」 |
| M-16 / M-4 / M-21 | ✅（核实） | 已有确认弹窗 / 除零保护 |

---

## 验收备注

- M-13/M-14 单测：`MerchantReplenishmentCompleteGatesTest`
- M-11/M-13 服务端变更需 trade-service 重建部署后生效
- 运营后台 / 联调 `CommercialFlowService.completeTask` 不走商户门禁（仍可无凭证完成）

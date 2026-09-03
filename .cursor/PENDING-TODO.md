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
| B-11 Phase2 短 access + 长 refresh | ⏳ 暂缓 |

---

## 小程序端审查（本轮落地）

| 编号 | 状态 | 说明 |
|------|------|------|
| **M-7** | ✅ | 不再持久化密码；仅记住手机号；启动清除历史混淆口令 |
| **C-4** | ✅ | createSession 前校验 UNUSED 券列表；后端本就校验归属 |
| **M-1/M-2 / C-1** | ✅ | shared-uni `yuanToCents`；提现/改价/充值退余额改用 |
| **M-11** | ✅ | 柜机有坐标时服务端强制经纬度；前端「跳过定位」仅 `showDevTools()` |
| **C-5** | ✅（部分） | 缺 `expiresInSeconds` 抛错；明文 Storage 为小程序常态，B-11 Phase2 级方案暂缓 |
| **C-15** | ✅ | 支付宝 host 精确白名单 + https + name/value 约束 |
| **C-12** | ✅ | marketing `ctaPath` 仅 `/pages/` |
| **C-18** | ✅ | `finishSession` finishing 守卫 |
| **C-8 / C-14** | ✅ | 证据失败按索引删；兑换中禁用全部按钮 |
| M-13/M-14/M-8 等 | ⏳ | 需服务端强校验或会话方案，本轮未全做 |
| M-16 / M-4 / M-21 | ✅（核实） | 已有确认弹窗 / 除零保护，无需再改 |

---

## 验收备注

- 金额：`yuanToCents`/`fmtMoney` 已用 Node strip-types 冒烟（按两位小数收敛）
- UI：原生小程序能力（定位/支付）需开发者工具；H5 可做登录/列表冒烟
- M-11 服务端变更需 trade-service 重建部署后生效

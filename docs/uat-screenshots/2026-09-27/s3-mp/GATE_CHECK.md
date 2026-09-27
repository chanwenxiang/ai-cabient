# mp-seed-gate 台子复检 · 2026-09-27（S3 收口后）

> 命令：`powershell -File scripts/mp-seed-gate.ps1`（**未** `-CleanupFirst`，保留现台子）  
> 产物：`.tmp/mp-seed-gate.json`

## 结果：**pass=true**

| 检查 | 结果 |
|------|------|
| trade_health | OK · `:18080` |
| device_resolve | **166813762350** ONLINE/DEPLOYED · 商户 892485912248 |
| 三端登录 | 消费/商户/运营 OK |
| consumer_balance | **19750**（¥197.50） |
| consumer_orders | 2 · 样例单 `1790493168114576060218` PAID |
| merchant_wallet | available **50440** |

提示：`no cleanup: prefer -CleanupFirst before main chain`（整轮回归再清数）。

## 备注

- `merchant.list=miss total=0`：闸门仍凭 device 行认台子绿；商户列表 API 口径与 device 绑商户不一致时需留意（非本轮 BLOCK）。
- **未**跑 Wipe / `e2e-full-flow-milk`（会动账/可能拆台）。

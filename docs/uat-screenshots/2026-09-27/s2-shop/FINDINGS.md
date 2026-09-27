# S2 购物主链 · Admin 验收（2026-09-27）

空台 S1 主数据 → `e2e-shopping.ps1 -Channel BALANCE -DeviceId 166813762350 -SkipSimulatorStart`

## 结果

| 节点 | 证据 | 结论 |
|------|------|------|
| S2-04/05 开门结算 | order `1790492328427596816232` · ¥3.50 · 已支付 · 余额 · 货道 A1↓6→5 | PASS |
| S2-07 分账 | split `1790492328513181548256` · 商户 ¥3.15 / 平台 ¥0.35 · **仅记账** · 批次 `MS-2026-09-27-892485912248` | PASS |
| S2-08 钱包 | 商户 `892485912248` 余额/可用 **3.15** · ledger `SPLIT_CREDIT` +315 | PASS |
| S2-09/10 KPI | 财务今日营收 ¥3.50 / 订单 1；工作台「分账待跟进」挂本单（余额仅记账预期） | PASS |

## 截图

- `s2-01-order-list.png`
- `s2-02-split-ledger.png`
- `s2-03-merchant-wallet.png`
- `s2-04-workbench.png`
- `s2-05-finance.png`

## 备注

- 会话经 DISPUTED→ops CONFIRM 后出单（mock 识别路径），金额与余额扣减一致（20000→19650）。
- 未跑正式采购 PO（S2-01）；本台库存来自 S1 盘点上架。

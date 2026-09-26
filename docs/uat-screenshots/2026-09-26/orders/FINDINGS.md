# 订单管理 · FINDINGS · 2026-09-26

> [`BUTTONS.md`](./BUTTONS.md) · [`ORDERS_FULL_BROWSER_UAT.md`](../../../uat/ORDERS_FULL_BROWSER_UAT.md)

## 结论

**PASS · FAIL 0 · BLOCK 0**（修 #204 后）

列表 / Tab / 筛选 / 双导出 / 详情抽屉 / 退款门控取消 / 会话·设备深链均实测通过。演示环境无待支付单，催付/补扣/关单记 N/A。

## 数据对照

| 项 | UI | API |
|----|-----|-----|
| 总量（excludeZero=0） | — | 24 |
| PAID / REFUNDED / PENDING | Tab 有数 / 1 / 0 | 23 / 1 / 0 |
| 样例实付 | ¥3.50 | 350¢ · `1790420215052990851425` |
| 已退款样例 | ¥0.00 · 已退 ¥3.50 | `refundedCents=350` · total=0 |

## 本轮修复（#204）

| 现象 | 根因 | 必须怎么做 |
|------|------|------------|
| 「已退款」Tab + 默认「隐藏零元」→ 空表「暂无订单」 | 全额退后 `totalAmountCents=0`，`excludeZero` 仍 `gt(total,0)` 把退款单滤掉 | ① 前端：REFUNDED Tab **不传** `excludeZero`；② 后端：`excludeZero` 保留 `refundedCents>0`；tooltip 写明 |

复测：隐藏零元仍勾选 · Tab 已退款 · **共 1 条** · `ord-retest-refunded-hidezero.png`。

## 口径说明（非 FAIL）

| # | 说明 |
|---|------|
| 1 | PENDING=0 → 催付/补扣/关单不可达，N/A。 |
| 2 | 播放录像：诚实中文失败（片未上传），非静默。 |
| 3 | 关键词 `SKU-DEMO` 无命中属数据空，精确单号可搜到。 |
| 4 | 履约分册曾测过同页；本册为单页深测 + #204 收口。 |

## 证据

`ord-01`…`ord-12` · `ord-retest-refunded-hidezero` · `ord-ux-*` · `ord-api-list.json` · `ord-99-end`
|

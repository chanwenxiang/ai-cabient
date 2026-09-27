# 充值管理 · FINDINGS · 2026-09-26

> [`BUTTONS.md`](./BUTTONS.md) · [`RECHARGES_FULL_BROWSER_UAT.md`](../../../uat/RECHARGES_FULL_BROWSER_UAT.md)

## 结论

**PASS · FAIL 0 · FINDING 0 · BLOCK 0**

列表共 **3** 与 API 一致（已支付 2 + 已取消 1，金额 ¥20.00，渠道微信）。状态筛 / 深链 `?status=PAID` 有效。关键词仅接受正整数 userId（非法 toast；无匹配空态）。退款 MessageBox 取消不落库。导出 CSV。结束视口 **1366×768**。

## 数据对照

| 面 | UI | API |
|----|-----|-----|
| 默认 | 共 **3** | total=3 |
| status=PAID | 共 **2** | total=2 |
| userId=999999999 | 共 **0** · 暂无充值记录 | — |
| 非法关键词 | toast「用户编号须为正整数」 | 不发请求 |

## 本轮缺陷

无 FAIL / FINDING。无需改码。

## 口径说明（非 FAIL）

| # | 说明 |
|---|------|
| 1 | 关键词 placeholder 写明「API 按 userId 筛选」；非数字拦截防后端 Long 转换 500。 |
| 2 | 「退款」仅 `PAID`/`SUCCESS` 行显示；无此类行时操作列整列隐藏。 |
| 3 | 深链支持 `status` / `keyword` / `userId`（`applyRouteQuery`）。 |

## 证据

`rch-01`…`rch-10` · `rch-ux-*` · `rch-99-end` · `充值_*.csv`

---

## MP 回补（2026-09-27）

**DONE · PASS**（mp-weixin · 只读）

消费者 `13800138000` / userId **10001**：MP 我的/余额明细/充值页均为 **¥193.00**，与 Admin `balanceCents=19300` 一致；充值记录单号 `1789658820257378119` 已支付 ¥20 与 Admin 一致。未点硬充。详见 [`MP_BACKFILL.md`](./MP_BACKFILL.md) · `mp-c-p0-*.png`。
|

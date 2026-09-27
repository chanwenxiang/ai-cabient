# 对账 · FINDINGS · 2026-09-26

> [`BUTTONS.md`](./BUTTONS.md) · [`RECONCILIATION_FULL_BROWSER_UAT.md`](../../../uat/RECONCILIATION_FULL_BROWSER_UAT.md)

## 结论

**PASS · FAIL 0 · FINDING 2 · BLOCK 0**

列表共 **2**（微信已平账 / 余额存在差异 −¥3.00）与 API 一致。T+1 Alert、KPI、中文状态、¥ 差额、详情抽屉、执行对账取消、渠道筛选、导出 CSV 均正常。结束视口 **1366×768**。

## 数据对照

| 面 | UI | API |
|----|-----|-----|
| 默认列表 | 共 **2** · KPI 差异1/匹配1 | 2 条：MATCHED/WECHAT · MISMATCH/BALANCE |
| channel=WECHAT | 共 **1** | 1 条 MATCHED |
| channel=BALANCE | —（本轮 UI 抽测微信） | 1 条 MISMATCH |
| status=MISMATCH | 共 **2**（含已平账） | **仍 2**（未按 status 过滤） |
| keyword=zzz… | 共 **2** | **仍 2**（未按 keyword 过滤） |

## 本轮缺陷

| # | 严重度 | 现象 | 根因 | 建议 |
|---|--------|------|------|------|
| F-01 | P1 | 状态下拉 / `?status=MISMATCH` 后仍显示「已平账」行；共条数不降 | `GET …/reconciliation` **忽略 `status`**（前端已传） | 后端按 status 过滤；或前端临时 client-filter 并诚实 total |
| F-02 | P1 | 关键词「对账ID/日期」填无匹配串仍共 2 | API **忽略 `keyword`** | 后端按 reconId/日期模糊匹配；无效时返回空 |

## 口径说明（非 FAIL）

| # | 说明 |
|---|------|
| 1 | **渠道筛有效**（WECHAT/BALANCE 各 1），与 status/keyword 对照鲜明。 |
| 2 | KPI「本页差异」按本页 `MISMATCH`/未匹配笔数计，非服务端 filtered total。 |
| 3 | 余额差异行 `unmatchedCount=0` 且 `diffCents=-300` → 金额轧差（Alert 已说明可独立出现）。 |
| 4 | 深链会 `applyRouteQuery` 同步筛选项文案，但因 F-01 列表仍全量。 |
| 5 | 执行对账为硬写跑批；本轮仅打开→取消。 |

## 证据

`rec-01`…`rec-13` · `rec-ux-*` · `rec-99-end` · API probe（channel OK / status·keyword 无效）
|

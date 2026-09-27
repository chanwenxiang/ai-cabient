# 服务时限监控 · FINDINGS · 2026-09-26

> [`BUTTONS.md`](./BUTTONS.md) · [`SLA_FULL_BROWSER_UAT.md`](../../../uat/SLA_FULL_BROWSER_UAT.md)

## 结论

**PASS · FAIL 0 · BLOCK 0**

只读 KPI 页：8 砖 + 实时六项均与 `GET /api/v2/ops/admin/sla` 对齐。设备总数仅投放（#198）；开门时长格式化为分/秒，无裸超大 ms。本页无写路径。结束视口 **1366×768**。

## 数据对照

| KPI | UI | API |
|-----|-----|-----|
| 快照日期 | 2026-09-24 | snapshotDate |
| 开门成功率 | 25.0% | doorSuccessRate=0.25 · 2/8 |
| 设备在线率 | 0.0% | deviceOnlineRate=0 · deviceTotal=**1** |
| 开门均时长 | 9 分 33 秒 | avgRecognizeMs=572880 |
| 开门时长 P95 | 11 分 12 秒 | p95RecognizeMs=671747 |
| 在线峰值 | 1 | deviceOnlinePeak=1 |

| 实时 | UI | API |
|------|-----|-----|
| 24h 开门成功率 | 100.0% | doorSuccessRate24h=1 |
| 当前在线率 | 0.0% | deviceOnlineRateNow=0 |
| 24h 开门均时长 | 102 ms | avgRecognizeMs24h=102 |
| 争议时限达标率 | 100.0% | disputeSlaCompliance24h=1 |
| 开放 / 逾期争议 | 8 / 8 | disputeOpen / disputeOverdue |

## 本轮缺陷

无 FAIL。无需改码。

## 口径说明（非 FAIL）

| # | 说明 |
|---|------|
| 1 | **设备总数 / 在线率分母仅投放柜**（lesson #198），勿与设备列表全量比。 |
| 2 | 快照 KPI 为日批 `snapshotDate`；实时块为近 24h / 当前，二者可不同日。 |
| 3 | `avgRecognizeMs` 含超时关门样本，故均时长可达数分钟；UI 必须格式化，禁裸毫秒。 |
| 4 | KPI 砖为 `button` + `tabindex=-1`，**非**深链；勿期望点砖跳转。 |
| 5 | 本页无导出/筛选/确认弹层；写路径 N/A。 |

## 证据

`sla-01`…`sla-03` · `sla-ux-*` · `sla-99-end` · `sla-api-probe.json`
|

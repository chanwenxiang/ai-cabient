# 设备可用性 · FINDINGS · 2026-09-26

> [`BUTTONS.md`](./BUTTONS.md) · [`DEVICE_KPI_FULL_BROWSER_UAT.md`](../../../uat/DEVICE_KPI_FULL_BROWSER_UAT.md)

## 结论

**PASS · FAIL 0 · BLOCK 0**

KPI 9 卡与 `device-availability-kpi` 对齐；无样本时长诚实「暂无样本」；无解锁日介入率「无解锁」；未来日禁用；窄 900 无整页横滚。结束视口 **1366×768**。

## 数据对照

| 日期 | UI | API |
|------|-----|-----|
| 2026-09-26 | 总数 3 · 人工解锁 1 · 介入率 100.0% · 时长暂无样本 | deviceTotal=3 · manualUnlock=1 · rate=1 · avg*=null |
| 2026-09-25 | 总数 3 · 人工解锁 0 · 介入率「无解锁」 | deviceTotal=3 · manualUnlock=0 · rate=0 · avg*=null |
| 无 date | 同今日 | bare 与 `?date=today` 一致 |

## 口径说明（非 FAIL）

| # | 说明 |
|---|------|
| 1 | **默认当天实时**：无 `?date=` 与传今日等价；选历史日才查快照。 |
| 2 | **avgLockHours / avgRecoverHours=null** → UI「暂无样本」（非「—」/null）。 |
| 3 | **auto+manual 解锁合计=0** → 介入率「无解锁」（非 0%），避免误读为「零介入」。 |
| 4 | 本页只读 KPI；无锁机/解锁写入口（写路径在设备运维/详情）。 |

## UAT 操作注意

| # | 说明 |
|---|------|
| 1 | 日期改完若面板未关，可 Escape + 点「刷新」确保 reload。 |
| 2 | 会话过期后登录体字段是 `phoneNumber`（非 phone）。 |

## 证据

`kpi-01-home` · `kpi-04-refresh` · `kpi-05-yesterday` · `kpi-06-today` · `kpi-ux-*` · `kpi-99-end`
|

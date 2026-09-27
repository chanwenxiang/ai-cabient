# 固件版本 · FINDINGS · 2026-09-26

> [`BUTTONS.md`](./BUTTONS.md) · [`OTA_FULL_BROWSER_UAT.md`](../../../uat/OTA_FULL_BROWSER_UAT.md)

## 结论

**PASS · FAIL 0 · BLOCK 0**

本页双区：固件版本列表 + 设备升级进度。本机 releases/reports 均为空；空态文案诚实。发布走二次确认后取消，未落库。进度状态下拉为中文六项。结束视口 **1366×768**（DPR=1）。

## 数据对照

| 面 | UI | API |
|----|-----|-----|
| 固件版本默认 | 共 **0** · 「暂无固件版本」 | `GET …/ota/releases` → `[]` |
| 升级进度默认 | 「暂无升级进度上报」 | `GET …/ota/reports` → `[]` |
| 进度 status=FAILED | 仍空态 | `…/reports?status=FAILED` → `[]` |
| 软写后 | 仍共 0 | releases total=0 |

## 本轮缺陷

无 FAIL。无需改码。

## 口径说明（非 FAIL）

| # | 说明 |
|---|------|
| 1 | hint 明示：**后台不下发自动安装**，柜机自行安装；勿当成远程推送失败。 |
| 2 | 进度区依赖 `ota.progress.enabled`；关上报时空表是「没上报」不是页面坏。 |
| 3 | 进度下拉 `value=""`（全部状态）时 Element Plus 常回显「请选择」而非「全部状态」；选项列表含「全部状态」，筛选语义仍为全量。可选后续改 placeholder/`ALL`。 |
| 4 | 空表点「导出」可能无文件下载（无行可导）；按钮仍可点。 |
| 5 | 行「下架」/批量有勾选须有 `PUBLISHED` 行才能点测；本轮 SKIP。 |
| 6 | 发布确认文案含版本、渠道、范围（全量/灰度/定向）、强制开/关。 |

## 证据

`ota-01`…`ota-12` · `ota-ux-*` · `ota-99-end` · `ota-api-probe.json`
|

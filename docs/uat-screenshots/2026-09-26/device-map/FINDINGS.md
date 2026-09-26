# 投放地图 · FINDINGS · 2026-09-26

> [`BUTTONS.md`](./BUTTONS.md) · [`DEVICE_MAP_FULL_BROWSER_UAT.md`](../../../uat/DEVICE_MAP_FULL_BROWSER_UAT.md)

## 结论

**PASS · FAIL 0 · BLOCK 0**

Leaflet 落点与侧栏对齐；默认「全部有坐标」共 3；生命周期「投放」共 1；在线/机器编号/地区/关键字筛选正确；自营无数据时诚实空态；详情深链到发号柜。结束视口 **1366×768**。

## 数据对照

| 项 | UI | API `map-points` |
|----|-----|------------------|
| 全部有坐标 (ALL) | 共 3 | n=3 |
| 投放 (DEPLOYED) | 共 1 · 发号柜 | n=1 |
| 在线勾选 | 共 1 | 前端滤 ONLINE |
| 地区「上海」 | 共 2 | 前端滤 address |
| 自营 | 共 0 | coopMode 均空 |

## 口径说明（非 FAIL）

| # | 说明 |
|---|------|
| 1 | **默认 ALL**：地图首要看全量有坐标点位；勿默认只投「已投放」（产品已钉，源码注释 2026-09-23）。 |
| 2 | **CAB-001** 入库演示点会出现在 ALL；看投放分布请切生命周期「投放」。 |
| 3 | 「自营」按 `coopMode=SELF`；本环境 coop 未填 → 空态「暂无落点（需设备填写经纬度）」文案偏坐标，但列表过滤结果正确。 |
| 4 | 图例/落点计数 ≠ 设备管理 KPI「在售」；勿混读。 |

## UAT 操作注意

| # | 说明 |
|---|------|
| 1 | Leaflet 容器 class 在 `.map-canvas` 自身（`map-canvas leaflet-container`），勿只查子节点。 |
| 2 | 生命周期选项须 **exact「投放」**，勿用模糊 `/投放/` 误点。 |
| 3 | 会话过期后登录体字段是 `phoneNumber`（非 phone）。 |

## 证据

`map-01`…`map-12` · `map-ux-*` · `map-api-list.json` · `map-99-end`
|

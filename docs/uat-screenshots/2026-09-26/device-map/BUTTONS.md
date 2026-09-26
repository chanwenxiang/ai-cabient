# 投放地图 · 按钮清点 · 2026-09-26

> `/device-map` · Playwright **1366×768**  
> [`DEVICE_MAP_FULL_BROWSER_UAT.md`](../../../uat/DEVICE_MAP_FULL_BROWSER_UAT.md)

---

## A 地图 / 计数

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| A-01 | 首页 Leaflet + 落点 | ✓ | 共 3 · `.leaflet-container` · `map-01-home.png` |
| A-02 | 默认生命周期 | ✓ | 「全部有坐标」= ALL（非仅投放） |

## B 筛选

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| B-01 | 在线 | ✓ | 共 1 · `map-02-online.png` |
| B-02 | 自营 | ✓ | 共 0 + 暂无落点 · `map-03-self.png` |
| B-03 | 机器编号 | ✓ | 共 1 · `map-04-machine.png` |
| B-04 | 关键字无匹配 | ✓ | 共 0 · `map-05-keyword-empty.png` |
| B-05 | 生命周期=投放 | ✓ | 共 1 · 发号柜 · `map-06-deployed.png` |
| B-06 | 地区=上海 | ✓ | 共 2 · `map-12-area.png` |
| B-07 | 查询 / 刷新 | ✓ | `map-09-refresh.png` |

## C 侧栏 / 深链

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| C-01 | 点击点位聚焦 | ✓ | `map-07-focus.png` |
| C-02 | 详情 | ✓ | `/devices/777740024057` · `map-08-detail.png` |

## D UX

| # | 项 | 判定 | 证据 |
|---|-----|------|------|
| D-01 | 中文在线/离线 | ✓ | 无裸 ONLINE/OFFLINE |
| D-02 | 900 无整页横滚 | ✓ | `map-ux-narrow.png` |
| D-03 | 1366 / 1920 | ✓ | `map-ux-1366.png` / `map-ux-1920.png` |

## 结案

- [x] 筛选/深链/空态覆盖；视口结束 1366
|

# 设备报表 · FINDINGS · 2026-09-26

> 与 [`BUTTONS.md`](./BUTTONS.md) / [`DEVICE_REPORT_FULL_BROWSER_UAT.md`](../../../uat/DEVICE_REPORT_FULL_BROWSER_UAT.md) 配套。

## 结论

**PASS · FAIL 0 · BLOCK 0**

设备经营报表：KPI、关键词/重置、URL/`KPI` 离线筛选、行详情与名称深链、详情 Tab、金额 `¥`、中文状态、窄视口与商户柜机抽样均通过。

## 复测说明（视口比例 + 详情深链）· 2026-09-26 20:15

| 项 | 实测 |
|----|------|
| 详情是否点进 | **是**。行操作「详情」→ URL `http://localhost/admin/devices/330449777078`；标题「设备详情」；可见概览/二维码/资产；并切到「货道陈列」Tab（A1 可口可乐等） |
| 比例发虚原因 | Playwright 实测 `devicePixelRatio ≈ 0.9`，且本轮曾把视口在 900 / 1366 / 1920 间来回切，窗口与 viewport 不一致时画面会显扁/拉伸 |
| 处理 | 复测固定视口、不再中途乱改；重截 `dr-retest-01-list.png` · `dr-retest-02-detail.png` · `dr-retest-03-slots-tab.png` |
| 业务页本身 | 宽后台布局正常；详情数据齐全（非空白页） |

## API / UI 快照

| 项 | 值 |
|----|-----|
| 设备数 | 3 |
| 离线 | 2（7777… · CAB-001） |
| 累计营收 | ¥33.50（订单 24） |
| 今日营收 | ¥3.50（与工作台一致） |
| 330449777078 | 在线 · 今日 2 单 ¥3.50 · 累计 ¥23.50 |

## 证据

`dr-01`～`dr-07` · `dr-ux-*` · `dr-mp-m-devices.png` · **复测** `dr-retest-01/02/03*.png`
|

# DevOps 中心 · 按钮清点 · 2026-09-27

> `/devops` · Playwright **1366×768**（DPR=1）  
> [`DEVOPS_FULL_BROWSER_UAT.md`](../../../uat/DEVOPS_FULL_BROWSER_UAT.md)  
> 规则：外链 stub；Sonar 离线禁用 / 在线则确认→取消；禁止硬扫。

---

## A 头 / 工具卡

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| A-01 | 打开 · 四卡 | ✓ | Grafana/Prometheus/Sonar/GitHub · `dv-01-home.png` |
| A-02 | 状态 Tag | ✓ | 三「未检测到服务」· GitHub「外部链接」 |
| A-03 | 刷新状态 | ✓ | `dv-02-refresh.png` |
| A-04 | 新窗口打开 ×4 | ✓ stub | 首点 → `/devops/grafana` · `dv-06` |
| A-05 | 下方嵌入看板 | ✓ | 可点 · `dv-05-scroll-grafana.png` |
| A-06 | 重跑 Sonar | ✓ 禁用 | `disabled` · `dv-07-sonar-disabled.png` |

## B PromQL / Grafana

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| B-01 | 服务是否在线 | ✓ stub | `…:9090/graph?g0.expr=up…` · `dv-03` |
| B-02 | 在线设备数 | ✓ stub | `cabinet_devices_online` |
| B-03 | 其余 6 钮可见 | ✓ | 设备总数/离线率/开门成败/结算/MQTT |
| B-04 | Grafana 嵌入空态 | ✓ | 「Grafana 未启动…」· iframe=0 · `dv-04-grafana.png` |

## C UX

| # | 项 | 判定 | 证据 |
|---|-----|------|------|
| C-01 | 900 / 1366 / 1920 | ✓ | `dv-ux-*` · `dv-99-end.png` |

## 结案

- [x] FAIL 0；FINDING 0；未硬扫 Sonar；外链未弹 OS 窗
|

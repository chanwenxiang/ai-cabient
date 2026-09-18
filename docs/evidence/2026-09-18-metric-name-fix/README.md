# 证据：告警/看板指标名静默失效（2026-09-18）

## 一句话

Micrometer 把 `_total` 当 **Counter 的保留后缀**：Counter 追加、**Gauge 剥离**。于是按
`cabinet_devices_total` 写的引用**全部静默失效且不报错**，实证受害 4 处 —— 含
**「全部设备离线」critical 告警**。

## 缺陷与机制

`CabinetMetrics.java` 注册的是 `registry.gauge("cabinet.devices.total", devicesTotal)`，
但运行中的 trade-service `/actuator/prometheus` 里**没有** `cabinet_devices_total`，
只有一个叫 `cabinet_devices` 的 Gauge。

机制用**镜像内自带的 micrometer 1.15.12** 做最小复现（`Probe.java`，可随时重跑）:

```
reg.gauge("cabinet.devices.total", …)   →  cabinet_devices          ← `_total` 被剥掉
reg.gauge("cabinet.devices.count", …)   →  cabinet_devices_count    ← 保留
reg.gauge("cabinet.devices.online", …)  →  cabinet_devices_online   ← 保留
reg.counter("cabinet.door.open", …)     →  cabinet_door_open_total  ← 追加
```

证据文件：`probe-micrometer-naming.txt`（实际输出）、`Probe.java`（源码）。

> 复跑方式：从运行镜像取 jar → 提出 `micrometer-*` / `prometheus-metrics-*` →
> `java -cp "<dir>/*" Probe.java`。本轮即用此法，**不依赖任何外部文档**。

## 受害面（4 处，全部引用 `cabinet_devices_total`）

| 位置 | 后果 |
|---|---|
| `infra/prometheus/alert_rules.yml` `DeviceOfflineRateHigh` | 设备离线率告警**永不触发** |
| `infra/prometheus/alert_rules.yml` `DeviceAllOffline` | **「全部设备离线」critical 告警永不触发** |
| `infra/monitoring/grafana/provisioning/dashboards/json/ai-cabinet-overview.json` | 「设备总数」面板空白 |
| `clients/admin-vue/src/views/system/DevOpsHubView.vue`（2 处） | 运维页「设备总数」「设备离线率」两张卡片取不到数 |

## 修复

1. 注册点改为 `cabinet.devices.count`（`CabinetMetrics.java`，含注释说明为何不能用 `.total`）；
2. 同步 4 处消费方 → `cabinet_devices_count`；
3. `node scripts/build-admin.mjs` 重建 admin 产物（`static/admin/assets/*.js` 内的旧名一并清除）；
4. `docker build` 重建 trade-service 镜像 + `--force-recreate` 重启；
5. 新增门禁 `scripts/check-prometheus-metric-names.mjs`（已接聚合链第 3 位）。

## 验证（从运行镜像取证）

见 `before-after.txt`：

| | `grep -E '^cabinet_devices( \|_)'` |
|---|---|
| 修复前 | `cabinet_devices 3.0` / `cabinet_devices_online 2.0`（`cabinet_devices_total` 命中 **0**） |
| 修复后 | `cabinet_devices_count 3.0` / `cabinet_devices_online 2.0`（`cabinet_devices_total` 命中 **0**） |

## 判据有效性（A/B）

`ab-gate-drift.txt` = `scripts/devops/verify-metric-names-drift.py` 输出，**9/9 例符合预期**：
逐条真注入漂移（注册点改回 `.total`、3 处消费方改回旧名、摘掉草稿标记、把草稿挂进
`rule_files`、两个阈值守卫）→ 全部变红；还原 → 字节级一致且回绿。

`ab-privacy-gate-drift.txt` = `scripts/devops/verify-miniapp-privacy-drift.py`（另一项修复的 A/B），**7/7**。

## 附带的第二项定论：`infra/monitoring/alerts.yml` 是未实现草稿

那 14 条规则**不是**「被取代的旧版」（与 `alert_rules.yml` 只 1 条重名，且连那条指标名也是错的），
而是**从未落地**的设计草稿：

- 从未被任何 `rule_files` 引用（三份 prometheus 配置都只挂 `prometheus/alert_rules.yml`）；
- 指标名与实现不符（`ai_cabinet_*` vs 实际 `cabinet_*`、`hikari_connections_*` vs 实测 `hikaricp_*`）；
- 依赖 blackbox / kafka / redis 三个**本仓从未部署**的 exporter（prometheus 只有
  trade-service / device-service / minio 三个 job）⇒ **即便挂上也不会触发**。

处置：文件头加 `# gate: draft-unimplemented` 显式标记，由 `check:prometheus-metric-names`
的 R1 规则守着（既防它被误挂，也防新规则文件重蹈「写了没人加载」）。

## 仍未做（需外部资源）

**Alertmanager 仍未部署**。`alert_rules.yml` 的 22 条规则会评估，但三份 prometheus 配置
都没有 `alerting:` 段、全仓无 alertmanager service；Grafana 侧有 contact point（email）
与 policy 却**零条 alert rule**，且 compose 未配 `GF_SMTP_*` ⇒ 两条链路末端都是断的。
补齐除需配置外，还需真实的 SMTP / 机器人 webhook 凭据。

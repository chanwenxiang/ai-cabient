# P0-6 ①② 可观测性收口：Grafana 侧假渠道 + 业务 KPI 看板（2026-09-19）

## ① Grafana 侧 contact point 是假的（修）

**改前**：`infra/monitoring/grafana/provisioning/alerting/contact-points.yml` 是
`type: email` + `addresses: ops@aicabinet.local`。三条叠加起来 ⇒ **该渠道永远发不出去，看板上却看不出异常**：

1. `@aicabinet.local` 是保留给 mDNS 的域，公网/内网都没有这个收件域；
2. 全仓 `GF_SMTP_*` **命中数 0** ⇒ Grafana 根本没配 SMTP，触发即报错；
3. 本仓唯一的真实渠道是**飞书**（见 `alertmanager.yml` 顶部说明）。

**改后**：`type: webhook` → `url: http://feishu-alert-relay:8098/webhook`。
依据是桥自己的 docstring：`feishu-relay.py` 的 `render_alert_text` **同时认 Alertmanager 与 Grafana
两家报文**（都读 `status`/`alerts[].labels|annotations`）⇒ Grafana 复用同一个桥即可，**不需要第二个组件**。

**「alert rule 0 条」怎么处理**：**保持 0 条，但把它变成显式且被守住的状态。**
本仓的告警源是单一路径 —— `infra/prometheus/alert_rules.yml`（22 条）→ Alertmanager → 桥 → 飞书。
在 Grafana 侧再补一套规则会让同一条件被两条通道各投递一次（飞书群收到重复告警，阈值还会分叉）。
`policies.yml` 与 `contact-points.yml` 保留是为了「将来真要加 Grafana 规则时立刻能送达」，
并在文件头写清「这不是在暗示现在有规则」。

## ① 新增门禁 R14（`scripts/check-ops-alert-channels.mjs`）

| 子规则 | 判据 |
|---|---|
| R14a | contact point 不得写**本地/保留域**的 email（`.local` / `example.*` / `localhost` / `invalid`） |
| R14b | 写 `email` 型时 compose 必须有 `GF_SMTP_*`，否则该渠道必然发不出去 |
| R14c | webhook 的 `host` 必须是 compose 里真实存在的服务、`port` 必须等于该服务的 **`PORT` 环境变量**（有效值，不是端口映射左半边）、`path` 必须是桥真的接受的路由 |
| R14d | `policies.yml` 的 `receiver` 必须在 `contact-points.yml` 里存在（名字对不上 = 告警静默丢失） |
| R14e | Grafana 规则文件不得与 Prometheus 规则**重名**（防双通道重复告警）；只禁重名，不禁存在 |
| 锚点守卫 | 解析出的 contact point / receiver / prometheus alert 数低于下限 ⇒ 红（防结构漂移导致失去判别力） |

## ② 业务 KPI 看板：7 → 13 面板

路线图 P0-6 列的 6 个 KPI 里，「开门成功率」此前已有。补的四项**全部只用已注册指标**
（注册点在代码里逐条核对过，不是凭指标名猜的）：

| 面板 | 表达式要点 | 注册点 |
|---|---|---|
| 门事件送达完整率 (5m)（gauge） | `device_mqtt_door_total{result="forwarded"}` ÷ (转发成功 + `device_trade_forward_total{result="failure"}`) | `MqttEventListener.java:328` / `:332` |
| 争议率 (5m)（gauge） | `cabinet_session_transition_total{state="DISPUTED"}` ÷ 全部终结态 | `CabinetMetrics.java:38` |
| MQTT 转发失败 (5m)（stat） | `sum(rate(device_trade_forward_total{result="failure"}[5m]))` | `DeviceMqttMetrics.java:33` |
| 结算时长 P95 / 均值（timeseries） | `histogram_quantile(0.95, sum by (le) (rate(cabinet_settlement_duration_seconds_bucket[5m])))` + `_sum/_count` | `CabinetMetrics.java:47` |
| MQTT 转发失败 / 指令 ACK 异常（timeseries） | `device_command_total{result="ack_timeout"|"ack_failure"}` | `DeviceMqttMetrics.java:36-37` |

**第 6 个 KPI「识别准确率」刻意没有面板**：准确率需要「识别结果 vs 人工标注真值」的对照，
而系统里**没有这份真值**（`vision-service` 也不暴露 `/metrics`）。硬凑一个看起来像准确率的数
属于**信号骗读者**，所以改为在同一个看板上加一块 **text 面板**把缺口与现有代理信号
（争议率、对账 MISMATCH、识别耗时）登记清楚，并指向路线图 O1（模型版本管理 + 按版本对比准确率）。

## 证据文件

| 文件 | 内容 |
|---|---|
| `grafana-runtime-verify.txt` | **一次性 `grafana/grafana:10.4.0` 容器**（挂仓库 provisioning）的 API 取证：contact point 已是 `webhook`→`feishu-alert-relay:8098/webhook`、无 `ops@aicabinet.local`、`policies.receiver` 可解析、alert rule 数 0、看板 13 面板全在 —— **14/14 断言 PASS** |
| `contact-points-before-after.txt` | 改前/改后全文对照 + `GF_SMTP_*` 命中数 0 |
| `gate-drift.txt` | `verify-ops-alert-channels-drift.py` 全量 **42/42**（含新增 R14 的 6 条必红 + 2 条反向必绿），字节级还原一致、注入文件已清理 |
| `promtool-panel-exprs.txt` | 13 面板的 20 条 expr 导出成临时 rules 用 `promtool check rules` 校验：**SUCCESS: 20 rules found**（只证语法；指标是否存在由 R3 门禁证） |
| `gates.txt` | 聚合门禁链 23/23、失败 0 |

## ⚠️ 两点如实说明（不要当成"已全部完成"）

1. **飞书送达未在本轮重验**：桥→真飞书群的端到端送达标已于 09-18 实测过；
   本轮 Grafana 侧是从**运行镜像**证到「contact point 内容正确」，
   但**没有**制造一次 Grafana 告警去验端到端送达 —— 因为 Grafana 侧规则数为 0（刻意），
   没有可触发的规则。要真验端到端，得临时加一条规则或用 `POST /api/alert-notifiers` 之外的途径，
   属另一件事。
2. **Grafana 自带默认 receiver**：API 里还能看到 Grafana 内建的 `email receiver`（`<example@email.com>`、
   无 `provenance`）—— 那是 Grafana 自身的默认值，不是仓库 provisioning 的产物；
   只要规则数为 0 就不会被用到。R14 只校验 `provenance: file` 的那部分。

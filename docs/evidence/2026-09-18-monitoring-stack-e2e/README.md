# 监控栈告警链路端到端验证（2026-09-18）

**本次验证的问题**：飞书告警在**真实 compose 栈**上到底通不通？
（此前只有"桥本身"和"配置级"判据，Prometheus 这一段从未端到端跑过。）

**验证方式**：起真实 `prometheus` + `alertmanager` + `feishu-alert-relay`，用**飞书机器人替身**
（`scripts/devops/feishu-sink-mock.py`，严格按飞书契约：HTTP 恒 200、成败只看响应体 `code`）
作为接收端，断言告警能一路变成**正确形状的飞书报文**。

## 这份证据证明了什么

| 结论 | 证据 |
|---|---|
| prometheus 加载到**全部 22 条**告警规则（4 组） | `monitoring-stack-e2e.txt` Phase 1 |
| **prometheus 真的连上了 alertmanager**：`activeAlertmanagers = ['http://alertmanager:9093/api/v2/alerts']` | 同上 ⇒ `prometheus-full.yml` 的 `alerting` 段**真的生效**，不只是"文件里有这行" |
| **触发通知送达**：`【告警触发】共 1 条 / - [触发] E2EProbe…（critical）` | Phase 4 |
| 报文形状正确：`msg_type='text'`（下划线）、**不含 `msgtype` 字面量** | Phase 4 |
| **恢复通知送达**：`【告警恢复】共 1 条 / - [恢复] …`（`send_resolved: true` 的另一半） | Phase 6 |
| **失败必显形**：sink 不可达时 relay 返回 **502**（`/last` 记 `status=502`），Alertmanager 因此重试 | Phase 5 |

**14/14 用例通过，退出码 0。**

## 这份证据**没有**证明什么（诚实边界）

1. **从未连过真实飞书**。接收端是本地替身。真实机器人的**IP 白名单 / 关键词校验 / 签名校验**
   一次都没在真机上验过（协议与失败码按官方文档实现，并有官方签名向量单测，但"真的能发出去"未证）。
2. **Grafana 侧的告警链路未接**。实测 Grafana 的 contact point 只有 email（`ops@aicabinet.local`，
   假地址）；**但它的告警规则是空的**（`/api/v1/provisioning/alert-rules` = `[]`）⇒ 目前**不发告警**，
   所以不构成阻塞，是个**潜在陷阱**。若将来在 Grafana 里建规则，会走 email 到假地址 = 静默丢失。
3. **`infra/monitoring/prometheus.yml`（dev 栈用的那份）既无 `rule_files` 也无 `alerting`**
   ⇒ dev 栈里规则根本不评估。是既存事实，本轮未改。
4. **告警源是"注入 alertmanager"而非"prometheus 真触发"**。prometheus → alertmanager 这一跳
   用 `activeAlertmanagers` 证明已连通，但没有等一条真实业务指标越线（那需要改业务状态或停服）。

## 本轮查出的两个静默缺陷（都已修 + 有对照）

| # | 缺陷 | 危害 | 判据 |
|---|---|---|---|
| 1 | `feishu-relay.py` 用**单线程** `HTTPServer` + HTTP/1.1(keep-alive) | Alertmanager 是 Go、**复用连接** ⇒ 一条空闲连接把桥占死，**第一条告警之后全部静默超时** | `relay-starvation-ab.txt`：同测试下**单线程 `TimeoutError` / 多线程 `HTTP=200`** |
| 2 | `do_POST` 里 `_last = {...}` **漏 `global`** | 模块级 `_last` 永远 `{}` ⇒ **排障接口 `/last` 恒为空**，故障时把人带向"从没告警进来过"的错方向 | `monitoring-stack-e2e.txt` Phase 5（本轮新脚本才逼出来） |

⚠️ **缺陷 1 的复现方式反直觉**：同一连接复用发 N 次请求**两种版本都过**（单线程在循环里处理同一连接）。
必须 **连接 A 完成一次请求后保持打开 + 新连接 B** 才复现得出。
第一版对照就是写错的，差点得出"判据无区分力"的错误结论。

## 文件

| 文件 | 内容 | 复现命令 |
|---|---|---|
| `monitoring-stack-e2e.txt` | 真实栈端到端 14 例（含失败路径、恢复路径） | `python scripts/devops/verify-monitoring-stack-e2e.py` |
| `relay-starvation-ab.txt` | 连接饿死 A/B 对照（单线程 vs 多线程） | `python scripts/devops/verify-relay-starvation-ab.py` |
| `gate-drift.txt` | 门禁 `check-ops-alert-channels.mjs` 的判据有效性 A/B（34 例） | `python scripts/devops/verify-ops-alert-channels-drift.py` |

## 前置条件与复现步骤

```bash
# 1) 起监控栈（复用同一 compose project，勿加 -p；只多起 4 个容器，业务栈不用停）
cd infra && docker compose --env-file .env \
  -f docker-compose.full.yml -f docker-compose.win-ports.yml \
  --profile alerting up -d prometheus grafana feishu-alert-relay alertmanager

# 2) 端到端（脚本自己起/清理飞书替身 sink，并重建 relay 指向它）
python scripts/devops/verify-monitoring-stack-e2e.py          # 完整，约 6 分钟
python scripts/devops/verify-monitoring-stack-e2e.py --fast   # 跳过恢复路径，约 1.5 分钟
```

注：`--fast` 会标注"恢复路径未验证、结论不完整"，退出码仍为 0 —— **信号不骗读者**。

## ⚠️ 当前环境遗留状态

- 监控栈 4 个容器**仍在运行**（业务栈 12 个容器未动）。
- **relay 的 `FEISHU_WEBHOOK_URL` 现指向已被清理的替身 `tmp-feishu-sink:18099`**
  ⇒ 此刻投递会 502（这是**如实报错**，不是故障）。要真发飞书必须注入真实 URL 并重建 relay：

```bash
cd infra && FEISHU_WEBHOOK_URL=https://open.feishu.cn/open-apis/bot/v2/hook/<你的> \
  docker compose --env-file .env -f docker-compose.full.yml \
  --profile alerting up -d feishu-alert-relay
```

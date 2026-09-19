# P0-6 运行期追踪开关：从「改配置+重启」到「运营台即时启停」（2026-09-19）

## 这份证据要回答的问题

用户要求：**「能不能把我们系统的功能做成可以在系统配置的？这样之后就可以随意开启和关闭了，就不用修改代码了」**。

对追踪（tracing）来说，改造前**做不到**：`TracingConfig` 用
`@ConditionalOnExpression("T().hasText('${tracing.otlp.endpoint:}')")` 决定 exporter bean 是否注册
⇒ 想开/关追踪**必须改配置并重启服务**。本轮把它改成了**运行期开关**，并做了端到端实测。

**验证方式**：真实 dev 栈（`trade-service` 已重建为新镜像）＋ 真实 Tempo 接收端，
**不改代码、不重启服务**，只用运营台系统配置 API 反复开关，看在 Tempo 里**有没有新 trace**。

## 这份证据证明了什么

| 结论 | 证据 |
|---|---|
| 新镜像里导出器 bean **常驻注册**（不再被启动期条件卡） | `exporter-lifecycle-log.txt`：`OTLP 导出器已常驻注册：运行期开关=… / 运行期端点=…` |
| 功能开关注册表端点可用，返回 **56 条 / 15 组** | `runtime-switch-e2e.txt` Phase 0 |
| 追踪开关是**三态 SELECT**（`""` / `true` / `false`），不是 BOOLEAN | 同上：`options=['', 'true', 'false']` |
| **端点留空 ⇒ 不外发**（基线 0 条） | Phase 1：`traces = 0` |
| **写入端点后立即外发，无需重启** | Phase 2：`traces = 3`，且日志出现 `OTLP 导出端点已生效: http://tempo:4318/v1/traces`（**同一次运行内**，未重启） |
| **开关置 `false` 后停止外发，无需重启** | Phase 3：`traces = 0` |
| **改回留空后恢复外发**（三态闭合，不是"一关就开不了"） | Phase 4：`traces = 3` |
| **判别力成立**：ON/OFF/ON 单调 | `on=3 off=0 on=3` |
| 前端产物确实消费了新端点 | `admin-bundle-endpoint.txt`：构建后的 `assets/*.js` 内含 `system-configs/feature-flags` |
| 验证后配置已还原为默认关闭（端点与开关均留空） | 脚本 `还原原始配置` 一步 + `psql` 查 `system_config` 均为 `[]` |

**全部断言通过，退出码 0。**

## 判别力设计（为什么这些数字不是"碰巧绿"）

* **Phase 1 是负对照**：端点为空时**必须**是 0。若链路坏掉、端点写错、或 Tempo 没起来，
  这一格同样会是 0 —— 所以单独看它不构成证据，**必须与 Phase 2 配对**才有区分力。
* **Phase 3 是关键反证**：如果开关是**假的**（例如仍被启动期条件卡着、或读的键名写错了），
  `false` 写进去后 trace **照样会涨**，这一格就会红。实测 `traces = 0` ⇒ 开关真的在生效。
* **Phase 4 排除了"单向失效"**：只能关不能开是另一种常见造假的绿灯形态，这里要求恢复。
* **每段窗口内 HTTP 流量都是 24/24 成功**，所以"0 条 trace"不可能是"请求压根没发出去"。

## 这份证据**没有**证明什么（诚实边界）

1. **没有验证 Grafana UI 里的体验**（Loki/Tempo 数据源关联、按 traceId 跳日志）。
   本轮只到"Tempo 检索 API 能查到 trace"这一层；UI 层此前在
   `docs/evidence/2026-09-18-monitoring-stack-e2e/` 里另有一份证据，但**不含 Tempo 面板**。
2. **没有验证真机/生产环境的容器启停**：`infra/observability.ps1` 的一键启停在**本机 dev**验证过；
   生产 compose（`docker-compose.production.yml`）未接 observability profile。
3. **没有做压测下的开关切换**：本轮的 24 次请求是串行轻流量；高并发下 `BatchSpanProcessor`
   的队列行为、以及切换瞬间在途批次的归宿（可能出现"开关后仍有一批外发"）未测。
   ⚠️ 这也是脚本里 `SETTLE_SECONDS`（默认 12s）存在的原因 —— 给在途批次留出排空时间，
   否则会误判成"关了还在发"。
4. **56 条功能开关里，只有 tracking 这两条做了运行期行为实测**。其余 54 条由门禁
   `check-feature-flags.mjs` 保证"注册表 ↔ 种子 ↔ 代码读取点"三方一致，
   但**"改值后行为真的变"** 是逐条各自的语义，不在本轮范围。

## 「两层缺一不可」的边界（重要）

| 层 | 管什么 | 谁来做 | 为什么不能合并 |
|---|---|---|---|
| **容器层** | Loki / Tempo / promtail **在不在跑** | `infra/observability.ps1 on/off` | 系统配置存在 **PG** 里，它**拉不起容器**；且 Docker VM 只有 3.82GB，常驻会和业务抢内存 |
| **应用层** | 应用**发不发**数据 | 运营台「功能开关」（本轮做的） | 容器起没起，应用无法单方面决定 |

⇒ 「可观测开关」= 脚本管容器 ＋ 运营台管数据。**只看其中一层都会得到错误结论**。

## 文件

| 文件 | 内容 | 复现命令 |
|---|---|---|
| `runtime-switch-e2e.txt` | 端到端 12 条断言（含负对照与三态闭合） | `python scripts/devops/verify-observability-runtime-switch.py` |
| `exporter-lifecycle-log.txt` | 真实容器内导出器生命周期日志（常驻注册 + 端点生效） | `docker logs ai-cabinet-trade-service-1` |
| `admin-bundle-endpoint.txt` | 前端构建产物含新端点路径 | `grep -r 'system-configs/feature-flags' services/trade-service/src/main/resources/static/admin/assets/` |

## 前置条件与复现步骤

```bash
# 1) 可观测接收端（Loki + promtail + Tempo）
infra/observability.ps1 on

# 2) 用新代码重建 trade-service（改过 Java 就必须重建，否则测的是旧镜像）
#    注意：改过 admin-vue 要先构建静态产物
node scripts/build-admin.mjs
cd infra && docker compose --env-file .env \
  -f docker-compose.full.yml -f docker-compose.win-ports.yml up -d --force-recreate --no-deps trade-service

# 3) 端到端（脚本自带还原，跑完配置回到默认关闭）
python scripts/devops/verify-observability-runtime-switch.py
python scripts/devops/verify-observability-runtime-switch.py --fast   # 冒烟，非验收证据
```

## ⚠️ 环境遗留状态

* `trade-service` 容器**已重建为新镜像**（`2026-09-19`），运行中。
* Loki / promtail / Tempo 三个容器**仍在运行**（占内存约 832MB 上限）。
  看完请 `infra/observability.ps1 off`。
* `system_config` 中 `ops.observability.*` 已还原为**留空 = 默认关闭**。

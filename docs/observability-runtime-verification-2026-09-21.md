# 可观测性（P0-6）运行期端到端验证报告

- **日期**：2026-09-21
- **范围**：路线图 **P0-6**「可观测性」剩余项 —— **运行期端到端未验**
- **结论**：**已收口**。整条链真跑通并三处互证；P0-6 由 ⚠️ 转 ✅。
- **本报告不含代码改动**（纯验证）；验证后所有运行期配置**已还原为出厂态**。

---

## 1. 被验的是哪条链

```
应用(trade-service) --OTLP/HTTP--> Tempo  ──┐
容器 stdout ──promtail──> Loki ────────────┼──> Grafana（Loki / Tempo / Prometheus 三数据源）
运行期开关(系统配置) ──> RuntimeSwitchableOtlpSpanExporter（免重启）
```

## 2. 环境事实（起栈前后实测）

| 项 | 实测 |
|---|---|
| Docker VM 内存 | 3913 MB（起栈前已用约 1832 MB） |
| 起栈命令 | `infra/observability.ps1 on`（full.yml ＋ observability overlay ＋ `--profile observability`） |
| 健康 | `tempo healthy`、`loki healthy`、`loki ready: 200`、`tempo ready: 200` |
| 端口 | Loki `127.0.0.1:13100`｜Tempo `13200`（OTLP HTTP `14318` / gRPC `14317`）｜Grafana `13000` |
| Grafana 数据源 | Loki→`http://loki:3100`、Prometheus→`http://prometheus:9090`、Tempo→`http://tempo:3200`（带 `tracesToLogsV2`→loki、`filterByTraceID:true`） |
| 数据源连通 | loki `"Data source successfully connected."`｜prometheus `"Successfully queried the Prometheus API."` |

⚠️ **两个易误读点**

1. `GET /api/datasources/uid/tempo/health` 返回 **404 `plugin.notImplemented`** —— Tempo 数据源插件**未实现**该 health 方法，**不是连不通**（连通性由第 4 节的真 trace 查询证明）。按「连不通」处理即为**假红**。
2. Grafana / Prometheus **不在 observability overlay 内**（定义于 `docker-compose.full.yml`，无 profile），当时处于 `Exited (0)`。起它们必须带上与运行栈**同一组** overlay 并加 `--no-recreate`：

   ```bash
   cd infra && docker compose -f docker-compose.full.yml -f docker-compose.win-ports.yml \
       -f docker-compose.observability.yml --profile observability up -d --no-recreate grafana prometheus
   ```

   若漏掉 `docker-compose.win-ports.yml`，compose 会**重建 trade-service**，端口从 `18080` 掉回 `8080`，破坏 dev 栈。

## 3. 运行期下发（走真实运维台，**免重启**）

- 导出器 bean **常驻**（`TracingConfig.runtimeSwitchableSpanExporter`，无条件注册）；每次 `export()` 现读系统配置：
  - `ops.observability.tracing_enabled`：`true`/`1` 强制开｜`false`/`0` 强制关｜**留空/非法值 = 跟随**
  - `ops.observability.otlp_endpoint`：留空回退 Spring 属性 `tracing.otlp.endpoint`（环境变量 `OTLP_ENDPOINT`）
- `SystemConfigService.getValue` **无缓存**（直查库，且空串按缺省处理 ⇒ 空串 ≡ 跟随）。
- 两键**已在功能开关注册表** `ops/feature-flags.json`（组「可观测性（追踪/日志）」，TEXT / SELECT），运维台「功能开关」页可直接改。
- 写入端点：`PUT /api/v2/ops/admin/system-configs`，`Authorization: Bearer <运维令牌>`。

**免重启证据**（同一运行进程内，20 秒完成切换）：

```
07:21:34  PUT ops.observability.otlp_endpoint = http://tempo:4318/v1/traces   -> code:0
07:21:54  INFO [trade-service,,] RuntimeSwitchableOtlpSpanExporter - OTLP 导出端点已生效: http://tempo:4318/v1/traces
```

## 4. 三处互证（同一 traceId）—— 最强判据

由本次手工发起的一个请求取得 traceId `fccef2b03e9fa3d3f455cf26ad8d377c`
（`07:22:44Z` `GET /api/v2/ops/admin/finance/report`）：

| 位置 | 取证方式 | 结果 |
|---|---|---|
| Tempo | `GET /api/traces/<id>` | **200**，`rootTraceName = http get /api/v2/ops/admin/finance/report`，`startTime = 07:22:44Z` |
| 应用日志 | `docker logs ai-cabinet-trade-service-1 \| grep -c <id>` | **87 行**（MDC `[trade-service,<traceId>,<spanId>]`） |
| Loki | `query_range?query={service="trade-service"} \|= "<id>"` | 命中，条目时间 `1789975364506486735` |

⇒ 一个请求，同一 traceId 在「接收端 ＋ 应用日志 ＋ 日志聚合」三处同时出现，端到端闭环成立。

## 5. A/B 负向对照（证明判据有分辨力）

用**只有本次会调用**的端点 `/api/v2/ops/admin/system-configs`，并取 Tempo 自计数
`tempo_receiver_accepted_spans` 作**不受采样影响**的确定性判据：

| 段 | `tracing_enabled` | 请求数 | Tempo 收 span Δ | 该端点 trace 条数 |
|---|---|---|---|---|
| A | `true` | 30 | **+12** | **8** |
| B | `false` | 30 | **0** | **0** |

⇒ 「开→有、关→无」，判据**不是恒真**。验证后两键已**还原为空**（出厂跟随态）。

## 6. 两条判据陷阱（本次踩到并纠正）

### 6.1 `/api/search` 不是完整清单

同一条 trace 可用 `/api/traces/<id>` 查到、却**不出现在 `/api/search` 结果里**（同窗口、同 id，人工复核）。
⇒ 用 search 判「有没有」会得到**假阴性**。**判据必须按 ID 直查**；若只有聚合视图，先用一个「必然存在」的样本证明其完整性。

### 6.2 日志里有 traceId ≠ 该 trace 已导出

Micrometer/OTel 在**未采样**时仍会创建 span（`nonRecordingSpan`）：`traceId`/`spanId` 照样进 MDC、日志照样打，
**但该 span 永远不会被导出**。

本次用「唯一可归因」的方式量了一次比率：向 `/api/v2/public/ops-branding` 发 **8 个单请求**，
每个请求靠它**独有**的配置键参数行（`ops.brand.*`）把 traceId 归因到自己，再逐个直查 Tempo ⇒
**命中 1/8 ≈ 12.5%**，与 Spring Boot 默认 `management.tracing.sampling.probability = 0.1` 吻合。

> ⚠️ 本次在这条上**连续错了两轮**：先是假阳性（「MDC 有 id ⇒ 已导出」），后是假阴性（「30 个 public 请求 0 条 ⇒ public 端点被排除追踪」）。
> 实查 `ObservationPredicate` / `shouldNotObserve` / 排除配置 **全部 0 命中**，仓内也**没有任何显式采样配置** ⇒ **不存在端点级排除**。

**可复用原则**：判「送达」看**接收端**、按 key 直查；要得到**比率**就发**可归因的单请求**，不要发一串无法归因的批量请求。
写「X 从不发生」之前先问：**能一击判定的最小实验是什么？**

## 7. 遗留

1. 🔴 **采样率未显式配置、未登记**（依赖框架默认 0.1）。排障时「为什么没有数据」无法自解释 ⇒ 建议在 `application.yml` 显式写出并在功能开关注册表登记。
2. ⚠️ `tempo_data` 卷内**残留 2026-09-20 的 5 条旧 trace** ⇒ 判「有没有新数据」必须比较 `startTime`，**不能只看条数**。
3. 监控栈容器当前**处于运行状态**（约占 1 GB；Docker VM 仅 3.9 GB）。停止并保留数据卷：`infra/observability.ps1 off`。
4. 本报告的验证面为 **Testcontainers/容器级真环境**（真 PG/Redis/真 HTTP 管线），**未跑浏览器 UAT**。

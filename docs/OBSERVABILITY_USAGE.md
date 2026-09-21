# 日志与链路查看手册

> 面向两个日常问题：**「我要看日志」** 和 **「我要查一次调用」**。
> 本文只讲怎么用；怎么部署/配置见 [../infra/README.md](../infra/README.md)、`infra/docker-compose.observability.yml`。
> 端到端取证记录（含实测数字）见 [observability-runtime-verification-2026-09-21.md](./observability-runtime-verification-2026-09-21.md)。

所有地址在 2026-09-21 逐条实测过 HTTP 状态码，标注 ✅ 的为当场返回 200。

---

## 0. 快速查找表

| 我想…… | 去哪 |
|---|---|
| **在运营后台里直接看** | 运营后台 → **系统 → 日志中心**（`/admin/index.html#/observability`），页签按主题切 |
| 看所有服务的原始日志 | Grafana 页面 **全栈日志流** |
| 只看报错 | Grafana 页面 **错误与告警** |
| 看日志量有没有异常 | Grafana 页面 **日志速率（按服务）** |
| 看谁在报错、错误有没有变多 | Grafana 页面 **ERROR 行计数（按服务）** |
| 按 traceId 串起一次调用 | Grafana 页面 **一次调用追踪（traceId）**；或 Tempo `/api/traces/<traceId>` |
| 在终端跟一个服务的日志 | `docker logs -f ai-cabinet-trade-service-1 --tail 100` |
| 看业务指标（订单/开门/对账） | Grafana → `AI Cabinet 运营概览` 看板 |
| 脚本/自动化里取日志 | Loki HTTP API（见 §1.3） |
| 打开 Grafana 发现是空的 | Loki/promtail 容器没起 → `infra/observability.ps1 on` |

---

## 1. 四个入口

### 1.1 运营后台 → 日志中心（最省事）

```
运营后台 → 系统 → 日志中心      ✅ /admin/index.html#/observability
```

页面顶部有 6 个页签（5 个日志主题页 +「运营概览」），点哪个嵌哪个，
并带 Grafana 在线探测和「新窗口打开」。**不用记 Grafana 地址、不用单独登录。**

权限复用 `ops:devops:view`，与 DevOps 中心同权（`V249__revoke_devops_non_admin.sql` 之后**仅 admin 可见**）。

### 1.2 Grafana（日常主入口）

```
http://localhost/devops/grafana/          ✅ 200
账号：admin / admin（GF_SECURITY_ADMIN_PASSWORD 可改）
```

🔴 **记住 `localhost/devops/grafana/` 这个形式**，不要记 `localhost:13000`：

- Grafana 设了 `GF_SERVER_SERVE_FROM_SUB_PATH=true` + `GF_SERVER_ROOT_URL=http://localhost/devops/grafana/`，
  所以直连 `http://127.0.0.1:13000/` 只会回一个 **301**，跳到 `/devops/grafana/`；而 `/devops/grafana/` 前缀由
  gateway（容器 `gateway`，宿主 80 端口）提供（实测 200）。
- ⚠️ 那个跳转目标**硬编码了 `localhost`**：从别的机器/手机访问要改 `DEVOPS_GRAFANA_ROOT_URL`
  （见 `infra/docker-compose.full.yml` 的 grafana 段），或直接用 `http://127.0.0.1:13000/` 手改路径。

看板（文件夹 `AI Cabinet`）—— **日志按主题拆成 5 个页面，一页一个面板**：

| 页面 | uid | 看什么 | 直链 |
|---|---|---|---|
| 全栈日志流 | `ai-cabinet-logs-stream` | 所有服务原始日志（可多选服务 / 关键词过滤） | http://localhost/devops/grafana/d/ai-cabinet-logs-stream/ |
| 错误与告警 | `ai-cabinet-logs-errors` | 只留 error/warn/exception/fail 的行 —— **排障先看这页** | http://localhost/devops/grafana/d/ai-cabinet-logs-errors/ |
| 日志速率（按服务） | `ai-cabinet-logs-rate` | 每服务行速率：突增＝刷屏，骤降＝卡住 | http://localhost/devops/grafana/d/ai-cabinet-logs-rate/ |
| ERROR 行计数（按服务） | `ai-cabinet-logs-errorcount` | 5m 窗口 ERROR 行数（日志计数，非失败率） | http://localhost/devops/grafana/d/ai-cabinet-logs-errorcount/ |
| 一次调用追踪（traceId） | `ai-cabinet-logs-trace` | 粘 traceId 串起一次调用的全部日志 | http://localhost/devops/grafana/d/ai-cabinet-logs-trace/ |
| AI Cabinet 运营概览 | `ai-cabinet-overview` | 业务指标（Prometheus） | http://localhost/devops/grafana/d/ai-cabinet-overview/ |

- 5 个日志页都带 **标签 `ai-cabinet-logs`** ⇒ 每页右上角「日志中心」链接可直接在 5 页间跳转（切页保留变量与时间范围）。
- 🔴 **`ai-cabinet-logs`（旧的单页合并看板）已拆掉**：文件删除后 Grafana 的 file provider（`disableDeletion: false`）
  会自动下线对应看板，手删文件后**重启 Grafana 即生效**，不用手工去 UI 里删。

### 1.3 终端

```powershell
# 单个容器（推荐；日志来自容器 stdout）
docker logs -f ai-cabinet-trade-service-1 --tail 100

# 经 infra 脚本（等价，但服务名用 compose 名）
cd infra
.\observability.ps1 logs trade-service
```

常用容器名：`ai-cabinet-trade-service-1`、`ai-cabinet-device-service-1`、`ai-cabinet-vision-service-1`、`ai-cabinet-gateway-1`。

### 1.4 Loki HTTP API（脚本 / 自动化）

```bash
curl --noproxy "*" -G "http://127.0.0.1:13100/loki/api/v1/query_range" \
  --data-urlencode 'query={service="trade-service"} |= "412"' \
  --data-urlencode 'limit=50' --data-urlencode 'since=1h'
```

- 标签只有三个：`service`（compose 服务名，**查询优先用它**）、`container`（容器名）、`stream`（stdout/stderr）。
- ⚠️ Windows 上 **`--noproxy "*"` 必须带**：宿主注入的 `http_proxy` 会让 `127.0.0.1` 的请求走代理，
  拿到 502/000，把「没监听」误读成「服务坏了」。

---

## 2. 5 个日志页面各怎么用

**分工**：先看「日志速率 / ERROR 行计数」判**有没有事**（趋势页），再进「错误与告警」看**是什么事**（内容页），
手里有 traceId 或 orderId 时用「一次调用追踪」看**这一次怎么走的**。

| 页面 | 变量 | 面板 |
|---|---|---|
| **全栈日志流** | 服务（多选）、关键词 | 原始日志流（倒序，最新在上；每行一条不换行，超长横向滚动） |
| **错误与告警** | 服务（多选）、关键词 | 只留含 error/warn/exception/fail 的行（正序） |
| **日志速率（按服务）** | 服务（多选） | `sum by (service) (rate(...[1m]))`，表格式图例给出 Last / Max |
| **ERROR 行计数（按服务）** | 服务（多选） | `sum by (service) (count_over_time(... \|~ "(?i)error" [5m]))` |
| **一次调用追踪（traceId）** | 服务（多选）、traceId | 该 traceId 的全部日志（正序）；留空＝捞全部，属预期 |

变量说明：

| 变量 | 作用 |
|---|---|
| **服务** | 多选；默认 `All`（= 所有 service）。来自 Loki 现有 label 值 |
| **关键词** | 日志包含过滤，留空不过滤。示例：`412`、`CO2026`、`PaymentCallback` |
| **traceId** | 32 位 traceId（或任意唯一串，如 orderId），仅「一次调用追踪」页有 |

> ⚠️ 「服务」下拉里可能出现 `sonarqube` 等**已停掉**的容器 —— 变量值取自 Loki 里已有的 label，
> 7 天保留期内历史数据仍在。选了查不到新日志属正常。
>
> ⚠️ 每页的面板高度是**整屏一行**（`w=24`）⇒ 竖屏/小窗口里会比合并看板多滚动几屏，这是刻意的：
> 单个面板独占整行，日志行才有足够宽度不被截断。

---

## 3. 常用 LogQL

```logql
# 某服务的全部日志
{service="trade-service"}

# 包含关键词（|= 是「包含」，!= 是「排除」）
{service="trade-service"} |= "412"
{service="trade-service"} != "DEBUG"

# 正则（(?i) 大小写不敏感）
{service=~".+"} |~ "(?i)error"

# 多服务
{service=~"trade-service|device-service"}

# 度量：日志速率 / ERROR 计数
sum by (service) (rate({service=~".+"}[1m]))
sum by (service) (count_over_time({service=~".+"} |~ "(?i)error" [5m]))

# 按 traceId
{service=~".+"} |= "fccef2b03e9fa3d3f455cf26ad8d377c"
```

🔴 **`[5m]` 必须写在过滤条件之后**：`{...} |~ "x" [5m]` ✅ ；`{...} [5m] |~ "x"` ❌ 语法错。
（这条踩过：写反了在 Grafana 里只显示 query error，不会静默返回空。）

---

## 4. 链路追踪（Tempo）

```
Tempo HTTP：http://127.0.0.1:13200/ready     ✅ 200
OTLP：HTTP 14318 / gRPC 14317
Grafana Explore → 数据源选 Tempo → 输入 traceId
```

- 应用日志里的 traceId 位于 MDC `[service,traceId,spanId]` 段，可直接复制。
- Tempo 数据源已配 `tracesToLogsV2` 联动 Loki，从 Span 能一键跳到该 traceId 的日志。

🔴 **判「某条链路在不在」只认 `/api/traces/<traceId>`，不要用 `/api/search`** ——
`/api/search` 返回的不是完整清单，用它判「不在」会得到假阴性。

---

## 5. 🔴 六个会咬人的坑

1. **promtail 只保留最近 10 分钟**（`promtail-config.yml` 的 `drop.older_than: 10m`，用于挡住启动回灌）。
   ⇒ 可观测性 `off` 超过 10 分钟再 `on`，**中间那段日志永久丢失**。要长期留存就得让 promtail 常驻，或调大该值。
2. **日志里有 traceId ≠ 这段链路被上报到 Tempo**：采样默认 `probability=0.1`（≈10%），未采样的是
   `nonRecordingSpan`（不进 Tempo，但日志照样打 traceId）。实测单次请求命中率约 1/8。
   ⇒ 看到 traceId 却在 Tempo 查不到，**先怀疑采样，不要先怀疑链路断了**。
3. **`/api/search` 不能当「不存在」的判据**（见 §4）。
4. **数据源存在 ≠ 有数据**：Loki/promtail/tempo 在 `observability` profile 下，**默认不启动**。
   容器没起时 Grafana 里看到空结果是预期，不是故障 —— 先 `observability.ps1 status`。
5. **保留期**：Loki **7 天**（`retention_period: 168h`）、Tempo **1 天**（`block_retention: 24h`）。
   要更长改 `infra/monitoring/loki-config.yml` / `tempo-config.yml` 并重建容器。
   ⚠️ 别和运营台里的 `ops.log_retention.*` 混淆 —— 那个管的是**业务日志表**归档，与此无关。
6. **「没日志」≠「服务挂了」**：空闲服务（device-service、vision-service、minio…）不产生日志是正常的。
   判存活请看容器状态或 Prometheus 的 `up` 指标，别用「Loki 里搜不到」下结论。

---

## 6. 启停与资源

```powershell
cd infra
.\observability.ps1 status   # 状态 + Docker VM 内存提示
.\observability.ps1 on       # 启动 loki + promtail + tempo（等健康）
.\observability.ps1 off      # 只停这三个，**保留数据卷**
.\observability.ps1 logs loki
```

- 内存上限：loki 384MB + promtail 128MB + tempo 320MB。
- 数据卷：`loki_data` / `tempo_data` / `promtail_positions` —— `off` 不删数据，`on` 可续用。
- 🔴 `off` 用的是 `docker compose rm -sf <三个服务>`，**不是 `down`** —— `down` 会把 full.yml 里的业务容器一起拆掉。
- 应用侧「发不发数据」由运营台**功能开关**控制（`ops.observability.tracing_enabled` / `otlp_endpoint`）；
  容器在不在跑由本脚本控制。**两层各管一半，缺一不可**。

---

## 7. 相关文件

| 文件 | 作用 |
|---|---|
| `infra/observability.ps1` | Loki/promtail/tempo 一键启停 |
| `infra/docker-compose.observability.yml` | 三个容器的定义（profile: `observability`） |
| `infra/monitoring/loki-config.yml` | Loki 保留期 7 天、限速 |
| `infra/monitoring/promtail-config.yml` | 抓 Docker stdout、`drop.older_than: 10m` |
| `infra/monitoring/tempo-config.yml` | Tempo OTLP 接收、`block_retention: 24h` |
| `infra/monitoring/grafana/provisioning/dashboards/json/ai-cabinet-logs-*.json` | 5 个日志主题页（一页一面板：stream / errors / rate / errorcount / trace） |
| `clients/admin-vue/src/views/system/ObservabilityView.vue` | 后台「日志中心」页（6 个页签内嵌上面这些看板） |
| `docs/observability-runtime-verification-2026-09-21.md` | 运行期端到端取证记录 |

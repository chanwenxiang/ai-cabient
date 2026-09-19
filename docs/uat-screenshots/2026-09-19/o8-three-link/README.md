# O8 三链路压测证据（2026-09-19）

> 本目录是 **O8**（`docs/COMPETITOR_BENCHMARK_AND_ROADMAP_2026-09-17.md` 的容量基线项）的实测证据。
> 目标：用**真实压测数据**替换 `docs/PERFORMANCE_TESTING.md` §3/§8 里**无实测支撑**的容量估算表。
> 入库口径：只提交 `summary.json`（原始 `results.jtl` 单个 20MB+、`token.txt` 是凭据，均已在
> `.gitignore` 忽略）。

---

## 1. 结论摘要

| # | 结论 | 证据 |
|---|---|---|
| C1 | **轮询（读）链路在本开发宿主上稳定支撑 600 VU**：p95 **379ms**、错误率 **0%**、吞吐 ≈**2470/s** | §3 分级升压表 |
| C2 | **吞吐在 200 VU 即封顶 ~2470 TPS**，之后加 VU 只推高延迟（p95 221→305→379ms）⇒ 已进入**饱和区**，不是线性扩容区 | §3 |
| C3 | **1000 VU 不是应用容量结论**：ramp 跑满瞬间连接层被整片拒绝（`Connection refused`），随后宿主机 Docker 引擎整体不可用 | §4 |
| C4 | **开门/结算写路径不具备「并发」语义**：单用户单活跃会话 + 小时级风控（建会话 20/时/用户、开门 30/时/用户、60/时/设备）⇒ 容量由**策略**决定，不由服务器决定 | §5 |
| C5 | 写路径**串行 10 轮全绿**：建会话 p50 34ms / 结算 p50 45ms，0 错误 | §5 |
| C6 | 现网容器**完全没配 Tomcat 参数**（`maxThreads`/`acceptCount` 走默认 200/100），而 `mem_limit: 1024m` + `cpus: 1.5` | §2、§6 |

**一句话**：本开发宿主能给出的可信基线是「**≥600 VU 读链路、p95 379ms、≈2470 TPS、0 错误**」；
**1000 VU 不可复现**，且原因已定位到**宿主/容器配额**而非应用逻辑（§4、§6）。

---

## 2. 测试环境（硬约束，全部取证）

| 项 | 实测值 | 取证方式 |
|---|---|---|
| 宿主 CPU | 20 逻辑核 | `os.cpu_count()` |
| 宿主物理内存 | **31.6 GB**（当时可用 15.5 GB） | `GlobalMemoryStatusEx` |
| **Docker VM 内存** | **3.82 GB** ← 由 `C:\Users\cwx\.wslconfig` 的 `memory=4GB`（`swap=2GB`）决定 | `docker info .MemTotal` + 读 `.wslconfig` |
| `trade-service` 容器 | **`mem_limit: 1024m`、`cpus: 1.5`** | `infra/docker-compose.apps.yml:134-135` |
| `device-service` / `vision-service` | 768m/1.0、512m/1.0 | 同文件 `:172`、`:219` |
| 业务栈 idle 占用 | **≈2.46 GB / 3.82 GB（64%）** | `docker stats` 连续采样（13 容器稳定 3 分钟不掉） |
| Tomcat 调优 | **无**（`application.yml` 只有 `server:`，无 `tomcat.*`；也无 `WebServerFactoryCustomizer`） | `grep -rn 'tomcat\|acceptCount\|maxThreads' services/trade-service/src/main` |
| JMeter | Apache JMeter 5.6.3（桌面 `apache-jmeter-5.6.3`），`java -jar ApacheJMeter.jar -n` | 运行器日志 |

> ⚠️ **注意 `mem_limit`/`cpus` 是 2026-08-10（`ed064eac`）引入的**，不是本轮新加 ——
> 所以「1000 VU 跑不动」不能简单归因于「最近加了限制」，需与 §4 的现场证据合起来看。

---

## 3. 轮询链路 · 分级升压（核心数据）

- 计划：`scripts/perf/poll_scale.jmx`（3 个端点：`GET /api/v2/sessions/active`、`GET /api/v2/devices/{id}/status`、`GET /api/v2/orders`）
- 运行器：`scripts/perf/run-o8-staged.sh`（逐级升压 + **级间健康检查** + 拐点即停）
- 参数：每级 `duration=60s`、`ramp=10s`、级间静默 20s；判据 `p95 ≤ 800ms` 且 `错误率 ≤ 0.1%`

| USERS | 样本 | 吞吐 | 错误率 | p50 | **p95** | p99 | max | 判据 |
|---|---|---|---|---|---|---|---|---|
| 100 | 118,623 | ≈1,977/s | 0.00% | 35 | **119ms** | 164 | 373 | ✅ |
| 200 | 146,340 | ≈2,439/s | 0.00% | 49 | **221ms** | 325 | 977 | ✅ |
| 400 | 148,573 | ≈2,466/s | 0.00% | 126 | **305ms** | 400 | 728 | ✅ |
| **600** | **150,666** | **≈2,470/s** | **0.00%** | 204 | **379ms** | 476 | 892 | ✅ |
| 1000 | 62,528 | — | **70.16%** | 2 | 15,006ms | 15,020 | 15,056 | ❌ |

600 VU 下分链路：

| 端点 | n | p50 | p95 | p99 |
|---|---|---|---|---|
| `GET sessions/active` | 50,421 | 184 | 266 | 324 |
| `GET orders` | 49,986 | 193 | 277 | 334 |
| `GET device status` | 50,259 | 282 | 445 | 530 |

> `device status` 最重（p95 445ms）—— 它要**跨服务调 `device-service`**（`AICABINET_DEVICE_SERVICE_URL`），
> 而 `device-service` 只有 `cpus: 1.0`。这是一条**扇出**链路，扩容时要单独算。

**饱和特征**：吞吐从 200 VU 起就在 2,440–2,470/s 之间徘徊，而 p95 随 VU 单调上升 ⇒
服务端已到吞吐上限，多出来的并发只是排队。**继续加 VU 不会提高容量，只会推高尾延迟**，
並最终在某个点（(600, 1000] 区间内）越过稳定性边界 —— 见 §4。

对 `trade-service` 的内存采样（贯穿 100→600 VU）：**稳在 827–872 MB**（占 `mem_limit: 1024m` 的 ~85%）
⇒ 内存**不随并发增长**，瓶颈不在堆。

---

## 4. 1000 VU：为什么这不是「应用容量」结论

首次直接盲打 1000 VU（`duration=180s`）的结果是 70.16% 错误。逐 20s 桶拆时间轴：

| 窗口 | 样本 | `Connection refused` | 读超时 | **成功** |
|---|---|---|---|---|
| 0–20s | 12,471 | 0 | 64 | **12,407** |
| 20–40s | 3,092 | 0 | 0 | **3,092** |
| 40–60s | 4,153 | 0 | 995 | 3,158 |
| **60–80s** | 38,266 | **38,080** | 186 | **0** |
| 80–100s | 2,993 | 1,083 | 1,910 | 0 |
| 100–120s | 1,553 | 465 | 1,088 | 0 |

读法：

1. **前 40 秒 15,499 个请求 100% 成功** —— 应用在渐进加压下是能服务的；
2. **t=60s（ramp 刚好跑满）服务被打死**，此后**再无一次成功**；
3. 失败构成里 **39,628 条是 `Connection refused`**（TCP 层 RST，`bad p50 = 1ms` —— 瞬时拒绝，
   不是超时），只有 4,243 条是 `Read timed out`。**连接被拒 ≠ 服务过载**（过载表现为超时）；
4. 压测结束后 **Docker Desktop 引擎整体不可用**（`docker ps` 全程 500 / 命名管道消失），
   需人工重启才恢复。

⇒ 该轮的「1000 VU 失败」**不能读作「应用只支持 <1000 VU」**。可归因的现场约束有三条，
且彼此叠加：

- **容器 CPU**:`cpus: 1.5` —— 压测期间 `trade-service` CPU 打满；
- **Tomcat 默认值**:`maxThreads=200` + `acceptCount=100` —— 1000 条并发连接远超接受队列，
  队列溢出时内核直接回 RST；
- **Docker VM 只有 3.82 GB**: 业务栈 idle 已占 2.46 GB，1000 VU 下叠加 `vmmem`/代理层开销后
  余量极薄，引擎自身先崩。

**因此本轮回溯的边界是**：可信区间上界 = **600 VU**（实测通过）；**拐点落在 (600, 1000] 区间内**，
未进一步二分（继续加压会把宿主引擎再打死，见 §7）。

---

## 5. 开门 + 结算闭环（写路径）

- 计划：`scripts/perf/open_settle_cycle.jmx`（1 线程串行 × **10** 轮，每轮 `POST /api/v2/sessions` → 等 6s → `POST /api/v2/sessions/{id}/demo-close`）
- 为什么**必须**串行且只能 10 轮：见下「风控上限」

| 端点 | n | 错误 | p50 | p95 | p99 |
|---|---|---|---|---|---|
| `POST create session`（开门） | 10 | 0 | **34ms** | 259 | 259 |
| `POST demo-close`（结算） | 10 | 0 | **45ms** | 88 | 88 |

总计 20 采样、**0 错误**、avg 52ms、max 259ms —— 端到端（开门→关门→结算出单）跑通。

跑完 Redis 计数（**实测**，证实风控确实按次计）：

```
aicabinet:rate:session_create:10001        = 10
aicabinet:rate:open_door:10001             = 10
aicabinet:rate:open_door_device:330449777078 = 10
```

**风控上限**（`ApiRateLimitService` + `RateLimitProperties`）：

| 动作 | 上限 | 窗口 |
|---|---|---|
| 建会话 | **20 / 用户** | 1 小时 |
| 开门 | **30 / 用户** | 1 小时 |
| 开门 | **60 / 设备** | 1 小时 |

⇒ 写路径的「TPS」在数学上就**被策略钉死**：单柜每小时最多 **60 次开门**（= 0.017 次/秒）。
「同一用户同时只允许一个活跃会话」进一步排除并发。**这是架构约束，不是性能缺陷**；
任何「订单创建 TPS ≥ 500」的目标值在本设计下都无意义（除非改风控策略）。

循环次数设 **10**（而非 20）的理由：建会话上限 20/时/用户，跑 10 次只吃一半额度，
同一小时内可**重复跑两轮**；跑满 20 次会顶到上限、第 20 次多半 429，且一小时内无法复跑。

---

## 6. 建议（按优先级）

1. **给 `trade-service` 显式配 Tomcat**（当前全靠默认值，`acceptCount=100` 在高并发下必然排队溢出）：
   `server.tomcat.threads.max` / `server.tomcat.accept-count` / `server.tomcat.max-connections`
   应显式给出并与 `cpus` 配额匹配。**这是最便宜、收益最直接的一条**。
2. **放开 Docker VM 内存**：`.wslconfig` 的 `memory=4GB` 相对宿主 31.6GB 过小。
   压测/联调时建议 ≥12GB，并把 `infra-sonarqube`（`mem_limit: 3g`）等 **devops 容器停掉**再压
   —— 它们与业务栈抢同一块 4GB。
3. **压测时固定环境**：单独一轮只跑业务栈；记录 `docker stats` 与容器 `RestartCount`
   （本轮 1000 VU 那一轮的容器驻留情况无法事后完全复原，这是 §4 无法更精确定责的原因）。
4. **`device status` 扇出链路单独容量核算**：它依赖 `device-service`（`cpus: 1.0`），
   在 600 VU 下 p95 已达 445ms，是最先劣化的一条。
5. **容器 `mem_limit` 与 JVM 堆要成对设计**：现在 `mem_limit: 1024m` 下 JVM 自动取 ~256MB 堆，
   实测 RSS 稳在 ~850MB；若调大并发目标，堆与非堆（线程栈/元空间）要一起算。

---

## 7. 局限与未覆盖（不要过度解读）

- **拐点未精确二分**：只知道落在 (600, 1000]，未跑 700/800/900。继续加压会把本机 Docker 引擎
  再打死（本轮已发生两次，恢复需人工重启），性价比不足，故主动停在 600。
- **本轮不是生产容量规划**：以上全部是**单机开发宿主**（4GB VM、容器 1.5 核）的数字，
  **不可线性外推**到生产集群。它回答的是「本机联调基线」与「当前配置的短板在哪」。
- **`sessions/active` 是对照新增端点**：PERF-1（2026-09-13）用的是 orders/account/status；
  本轮把 `account` 换成 `sessions/active`（更贴近真实轮询），因此**两者数字不完全可比**。
- **未覆盖**：真实硬件、真实支付通道、多实例（`--scale trade-service=2`）、
  长稳（soak ≥1h）、网络抖动/弱网。

---

## 8. 复现方法

```bash
# 前置：dev 业务栈已起且 trade-service health UP
#   curl -s http://127.0.0.1:18080/actuator/health   # => {"status":"UP"}

# ① 分级升压（轮询链路）；默认级别 100 200 400，可覆盖
bash scripts/perf/run-o8-staged.sh
POLL_STAGES="600" STAGE_DURATION=60 bash scripts/perf/run-o8-staged.sh

# ② 开门+结算闭环（消耗 10 次建会话额度；一小时内最多跑两轮）
bash scripts/perf/run-o8-three-link.sh       # 含轮询 1000 VU + 闭环；⚠️ 1000 VU 会打崩本机引擎
#   只跑闭环见 §5 的命令，或直接从 jmx 单跑：
#   java -jar <JMETER>/bin/ApacheJMeter.jar -n -t scripts/perf/open_settle_cycle.jmx \
#     -l <out>/results.jtl -JTOKEN_FILE=<out>/token.txt -JDEVICE_ID=330449777078 -JWAIT_MS=6000
```

`token.txt` 的 JWT 必须**落文件后再用 `__FileToString` 读入**，不能用 `-JTOKEN=` 传
（Windows cmd/bash 都会截断）。设备 ID 以 `device-simulator` 实际在线者为准
（本轮为 `330449777078`；`order_read_scale.cmd` 里写的 `777740024057` 当时是 `OFFLINE`）。

---

## 9. 文件清单

| 路径 | 内容 |
|---|---|
| `poll/summary.json` | **1000 VU 失败轮**摘要（§4 的证据，70.16% 错误率） |
| `poll-staged/stage-{100,200,400,600}/summary.json` | 分级升压四级摘要（§3 核心数据） |
| `open-settle/summary.json` | 开门+结算闭环摘要（§5） |
| `../../PERFORMANCE_TESTING.md` | 已按本文实测值改写 §3/§8（原为无依据估算） |

> 原始 `results.jtl`（各级 17–22 MB）与 `token.txt` 保留在本地工作区，
> 按 `.gitignore` 不入库；需要时按 §8 复跑即可重建。

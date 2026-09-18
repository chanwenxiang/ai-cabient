# compose 停机宽限（stop_grace_period）落地证据 — 2026-09-18

## 一句话结论

本机 Docker **没有** 10 秒引擎默认值：不写 `stop_grace_period` 起出来的容器宽限就是 **1 秒**，
`docker stop` 1 秒后直接 SIGKILL（exit 137）。已给 **10 个 compose 文件里的 54 个 service**
全部补上宽限，并顺手修掉两处「装了宽限也用不上」的 PID-1 缺陷。

## 三个实测结论（都不是从文档推的）

### 1. 默认宽限 = 1 秒，且**真的**只用 1 秒

| 场景 | `Config.StopTimeout` | `docker stop` 实测 |
|---|---|---|
| 不声明（`docker run` / compose 均如此） | **1** | 1s 后 SIGKILL，exit **137** |
| `--stop-timeout 30` / compose `stop_grace_period: 30s` | 30 | 真的等满 **30–32s** 才 SIGKILL |

⇒ 「不给值引擎会兜底 10 秒」这个假设**在本机不成立**，所以必须显式声明。
另：宽限是**上限不是延时** —— 进程退出后 `docker stop` 立即返回（实测 mysql 0.57s 退出、
redis 4ms 落盘完成，都没多等），所以给宽了不花钱，给窄了会丢数据。

### 2. 装了宽限 ≠ 能优雅停机：容器里 PID 1 会**忽略**没装 handler 的信号

内核特例：**PID 1 对「默认行为」的信号直接忽略**（只认 SIGKILL/SIGSTOP）。
于是「进程能收到 SIGTERM」和「进程会因 SIGTERM 退出」是两件事：

| 探针 | 宽限 | 结果 |
|---|---|---|
| 不装 handler 的 PID 1 | 30s | 日志里有 `GOT SIGTERM`（收到了），但**等满 30s 后被 SIGKILL，exit 137** |
| 装 handler 的同款探针 | 30s | **0s 退出，exit 0** |

本仓库正撞在这条上，且**两个服务都是**（`command: python …` ⇒ 脚本就是 PID 1）：

| 服务 | 修复前（HEAD） | 修复后 |
|---|---|---|
| `infra/monitoring/feishu-relay.py` | 宽限 10s → 等满 10s 后 exit **137** | **1s，exit 0**，日志 `已优雅退出` |
| `scripts/sms-webhook-mock.py` | 宽限 10s → 等满 10s 后 exit **137** | **1s，exit 0**，日志 `已优雅退出` |

（30s 宽限下的同款对照也做过：修复前**等满 33 秒**后 137，说明宽限一秒不差地全浪费了。）

### 3. 「怎么停机」由镜像的 `STOPSIGNAL` 决定，别按信号名猜

| 镜像 | `STOPSIGNAL` | 含义 |
|---|---|---|
| `postgres:16-alpine` | **SIGINT** | fast shutdown：断开客户端→checkpoint→退出，实测 **1s** 干净退出 |
| `nginx:alpine` | **SIGQUIT** | 优雅排空在飞请求 |
| `redis:7-alpine` / `mysql:8.0` | (空) | SIGTERM；redis 会先落最终 RDB |

⚠️ **`docker kill --signal=TERM` 会绕过 `STOPSIGNAL`**：给 postgres 直接发 SIGTERM 触发的是
**smart shutdown —— 一直等客户端断开**（实测 8 秒后仍在跑）。这条差点让我得出
「postgres 需要 60s 宽限才不会崩」的错误结论；真相是 `docker stop` 走 SIGINT，1 秒就干净退出了。

## 改了什么

**① 54 处 `stop_grace_period`（10 个 `infra/docker-compose*.yml`）**

分层（宽限是上限，故落盘型给足）：

| 分层 | 值 | 服务 |
|---|---|---|
| 落盘型 | **60s** | postgres / xxl-job-mysql / redpanda / minio / sonarqube-db |
| 常驻型 | **30s** | 其余全部（含 nginx / 两个 python 服务 / Java 服务 / Go 组件） |
| 一次性 | **10s** | minio-init |

叠加层（`win-ports`/`ha`/`staging`/`admin-static`/`xxljob`）里同名服务给了**同值**，
所以合并结果不会因叠加而变化 —— 这一点用 `docker compose … config` 的**生效值**验证过，不是推理。

**② 两处 PID-1 信号处理**（否则宽限对该服务等于没加）

`feishu-relay.py` 与 `sms-webhook-mock.py` 各加一个 SIGTERM/SIGINT handler：
`threading.Thread(target=server.shutdown).start()` —— 必须换线程，因为 `shutdown()` 会阻塞等待
`serve_forever()` 返回，而 handler 就跑在 `serve_forever` 的主线程里，直接调用会自等死锁。

**③ 门禁 `scripts/check-compose-stop-grace.mjs`**（接在聚合链**第 3 位**）

判据只设**下限**不钉死具体值（`30s→45s` 是合法改动，不该假红）：每个 service 必须声明，
且 落盘型 ≥60s / 常驻型 ≥30s / 一次性 ≥10s。三条防恒真锚点：文件数 ≥8、service 数 ≥30、
`STATEFUL` 里的名字必须在文件中真的出现（否则分层表与文件脱节、规则静默失效）。

## 验证汇总

| 项目 | 结果 | 证据文件 |
|---|---|---|
| 行为级验证（本机 Docker 实测） | **16/16** | `stop-grace-behavior-verification.txt` |
| 判据有效性 A/B（注入漂移必红 + 反向用例不许假红） | **11/11** | `stop-grace-gate-and-drift.txt` |
| 门禁正向 + 全表 | OK，10 文件 / 54 service | 同上 |
| 合并生效值（full + win-ports） | 15 个 service 全带宽限 | `stop-grace-effective-merged.txt` |
| 聚合门禁链 | **23 个门禁，失败 0 个** | 见会话记录 |
| prettier / eslint | 0 problems | — |

> `stop-grace-effective-merged.txt` 里是 **15** 个 service 而不是 17：`feishu-alert-relay` 与
> `alertmanager` 挂在 `profiles: ["alerting"]` 下，默认组合不启用（要 `--profile alerting`）。

A/B 的 11 例里有两例是**反向**用例，专门防假红：`redis 30s→45s` 与 `postgres 60s→1m`
（等价写法）都必须**仍绿**；另有两例是锚点守卫（`services:` 改名、`postgres` 全体改名）。
反向用例不是凑数：同一天刚被「判据绑死具体值 → 合法重写假红」坑过一次。

## ⚠️ 尚未生效于**正在运行**的容器

`StopTimeout` 是**创建期**字段，`docker update` 不支持改它（`docker update --help` 无 `stop-timeout`）。
所以：**文件改了，但当前这 12 个在跑的容器仍是 1 秒宽限**，要等重建才生效：

```powershell
# 在仓库根；会重建配置有变的容器（postgres/mysql 等会重启，数据在卷里不受影响）
.\docker-up.ps1 -NoBuild
```

本次**未执行**该命令（会重启整套栈、可能打断并行的 E2E），留待确认。

## 顺带记录

- 本次改的是 `infra/**`，而**同一时间另一个会话也在改 `infra/**`**（把端口绑回环、给
  Grafana 加网关 Basic 认证、新增 `infra/gateway/grafana.htpasswd`）。我的插入是纯增行，
  与那些改动不冲突；提交时建议用 `git add -p` 分辨归属。
- `edge/device-simulator`（Java）与 `vision-service`（uvicorn）**本身有信号处理**，
  只需宽限、不需要代码改动。

# 证据：飞书告警渠道与通知链路（2026-09-18）

本目录是「公司用飞书、没有钉钉/企微」这一改动**可复核**的证据链。
所有 `.txt` 都是**脚本真实运行的输出**（文件头写了生成命令），不是手写结论。

## 结论一句话

- **应用内链路**（`OpsAlertDispatcher` → 飞书）：已原生支持飞书，含**业务码失败判定**与运营台「测试发送」；
- **监控栈链路**（Prometheus 22 条规则 / Grafana）：已补 `feishu-relay` 转换桥 + `alertmanager.yml`，
  并用**真实 alertmanager 容器**证明了 `alertmanager → 桥 → 飞书报文` 端到端可达。

## 文件

| 文件 | 内容 | 重新生成 |
|---|---|---|
| `sign-vector.txt` | 飞书签名算法与**官方样例向量**的独立复算（`demo/1599360473 → l1N0gAcBjdwBvGm1xMjOF0XSyaLRpR7tuO5dHfhAYc8=`） | 见文件内说明 |
| `feishu-bot-protocol.txt` | 飞书自定义机器人协议要点（`msg_type` 下划线、失败也返回 200 只给 `code` 等） | 见文件内说明 |
| `ab-gate-drift.txt` | `check-ops-alert-channels.mjs` 门禁的**判据有效性** A/B（33 例：29 例真注入必红 + 4 例反向必须仍绿） | `python scripts/devops/verify-ops-alert-channels-drift.py` |
| `e2e-feishu-relay.txt` | 链路端到端：Phase A（本地桥 6 例）+ Phase B（真实 alertmanager 容器 6 例），共 12 例 | `python scripts/devops/verify-feishu-relay-e2e.py` |
| `port-race-guard.txt` | 「端口竞态」的负向对照 + 连续 3 次稳定性 + 完整 A+B | `python scripts/devops/verify-feishu-relay-portguard.py`、`python scripts/devops/verify-feishu-relay-e2e.py` |
| `mvn-ops-alert-dispatcher-test.log` | 当前工作区的 **clean** 编译 + 单测：`clean:3.2.0:clean` 三个模块都执行、重编 384/705/274 个源文件（非增量）、`Tests run: 21, Failures: 0, Errors: 0`、BUILD SUCCESS | `mvn -B -l <log> -pl services/trade-service -am clean test -Dtest=OpsAlertDispatcherTest -Dsurefire.failIfNoSpecifiedTests=false` |

> **编码规范化说明**：`mvn-ops-alert-dispatcher-test.log` 原始日志里，javac 对
> `RedissonConfig.java` / `OrderReadModelOpenApiCustomizer.java` 的告警是 **GBK 字节**，
> 在 UTF-8 视图下为乱码。本文件**逐行**尝试 UTF-8、失败则按 GBK 解码，**只做编码转换、正文一字未改**（140 行中 11 行被归一）。
> 所有判据行（`clean:` / `Compiling` / `Tests run` / `BUILD SUCCESS`）本就是 ASCII，未受影响。

## 🔴 这份证据**没有**证明什么（别过度引用）

1. **从未连过真实飞书**。Phase A/B 里的「飞书」是一个本地 mock sink，
   它**按飞书契约**回 `HTTP 200 + {"code":0}`（以及用 `code=19024` 模拟"关键词不匹配"被拒）。
   ⇒ 真实机器人（含 IP 白名单、关键词校验）**尚未验证过一次**；
   拿到 Webhook URL 后请用运营台「测试发送」补这一枪。
2. **Prometheus 侧未端到端跑过**：Phase B 覆盖 `alertmanager → 桥 → 飞书`，
   没有起 Prometheus 去真的触发那 22 条规则（本机资源有限，devops 栈未启动）。
   规则文件到 alertmanager 的那一段只做了**配置级**判据（`alerting.alertmanagers.targets`）。
3. **`infra/monitoring/prometheus.yml`（dev 栈用的那份）既无 `rule_files` 也无 `alerting`**，
   即 dev 栈里告警规则**根本不评估**。这是既存事实，本轮未改动它。

## 一个可复用的教训（已写进门禁注释）

`prom/alertmanager` 镜像的 entrypoint **就是** `alertmanager` 二进制，
所以 `docker run ... prom/alertmanager:v0.28.1 amtool check-config ...` 会把 `amtool`
当成 `alertmanager` 的参数，报 `unexpected amtool` 并 exit 1。
**这是调用姿势错（假红），不是配置坏** —— 正解是加 `--entrypoint amtool`。
同类的还有：`verify-feishu-relay-e2e.py` 里四个 Phase A 用例曾共用同一个宿主端口，
前一个进程没退干净时后一个会失败成 `relay=0`，**红得指错地方**（已改为逐用例独立端口 + 起停时显式等端口状态）。

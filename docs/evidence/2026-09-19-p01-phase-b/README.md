# P0-1 阶段 B：边缘盒监控 + 识别准确率（**不做云端识别**）（2026-09-19）

## 0. 一条指令改变了方案

阶段 B 的原始定义（`docs/VISION_QUECTEL_INTEGRATION.md:54`）写的是「识别准确率报表（**端侧 vs 云端复核对比**）」
——即对同一段视频调一次 `vision-service` 重新识别，与端侧结果比对。**用户明确指令「不做云端识别」**
⇒ 这套方案被**整体撤销**（已删 5 个文件：`RecognitionCrosscheckService` / `RecognitionCrosscheck`(domain) /
`RecognitionCrosscheckMapper` / `V280__recognition_crosscheck.sql` / `RecognitionCrosscheckServiceTest`），
连带清掉所有悬挂引用（`VisionResultIngestService` 的调用、`CabinetMetrics` 的两个方法、
`RecognitionCrosscheckDisagreementHigh` 告警、`application.yml` 的 `vision.crosscheck` 配置块）。

改用什么做真值？——见 §1。

## 1. 识别准确率：真值 = **人工复核结论**

不调云端，系统里唯一的人工标注就是**争议结案本身**：

| 结案类型 | 含义 | 对识别的判定 |
|---|---|---|
| `KEEP` | 维持原账单 | **识别正确** |
| `WAIVE` | 免单退款 | 识别有误 |
| `ADJUST` | 人工改单 | 识别有误 |
| `CONFIRM` | 按确认清单结算 | 识别有误 |

新增指标 `cabinet.recognition.human_verdict{verdict,review_code}`，唯一落点在
`DisputeService` 的**三个人工结案分支**（运营结案 / 商户结案 / 异常中心同步结案）——三者都要求
`canActWhileOpen`，**互斥 ⇒ 同一工单只计一次**，分母不会被重复计数。

准确率 = `correct / (correct + wrong)`。

> 🔴 **口径诚实声明（写进代码注释、告警注释、看板 description）**：分母是**被提起争议**的会话。
> 用户只在觉得被扣错时才提争议 ⇒ 这是「**争议子集上的准确率**」（有偏样本），**会系统性低于**全量准确率。
> 它用于**趋势与下钻**（按 `review_code` 看 MOCK/EMPTY/LOW_CONF/UNMAPPED 哪类失败最多），
> **不可**读作「全站识别准确率」。把它当全站准确率就是「信号骗读者」。

配套：`cabinet.recognition.recorded{need_review}`（**代理**信号，分母=平台采纳次数）保留，
用 `RecognitionNeedReviewRateHigh` 做提前预警；新增 `RecognitionHumanVerdictWrongRateHigh`（真值告警）。

## 2. 边缘盒监控

| 信号 | 指标 | 说明 |
|---|---|---|
| 边缘盒固件漂移 | `cabinet.edge.firmware.outdated.count` | **在线**设备中固件 ≠ `aicabinet.edge.expected-firmware` 的台数 |
| 端侧通道健康度 | `cabinet.edge.ingest{outcome}` | `PROCESSED` 长期为 0 ⇒ 端侧通道断了；`TOO_EARLY`/`ALREADY_HANDLED` 偏高 ⇒ 端侧报得太早/在重发 |
| 设备可用性（已有） | `cabinet.devices.count` / `cabinet.devices.online` | 已有 `DeviceOfflineRateHigh` / `DeviceAllOffline` 告警 |

**刻意用 `-1` 表示「未配置期望固件版本」**（而不是 0）：告警判据写 `> 0`，
0 会被读成「全部合规」而**掩盖未配置**，-1 则天然不触发。

## 3. 本轮挖出的两个**既有缺陷**（都不是本批引入的）

### 3.1 🔴 指标名门禁在 CRLF 工作区**完全失去 R2 覆盖**

`check-prometheus-metric-names.mjs` 的 `exprsOfYaml()` 用 `content.split('\n')` 切行，
再用 `/^(\s*)expr:\s*(.*)$/` 匹配。Windows 工作区 checkout 出来的 yml 行尾是 `\r\n`
⇒ 行是 `"        expr: |\r"`，而 `$`（非 multiline）**只在字符串末尾匹配**、`.` **又不吃 `\r`**
⇒ 正则**整条匹配失败**，**R2（告警规则里的指标名校验）在这些文件上一行都没校验**。

**实测（不是推理）**：在 CRLF 工作区把 `cabinet_devices_count` 全量替换成**并不存在的** `cabinet_devices_total`，
门禁**仍打印 OK**：

```
$ python -c "…把 alert_rules.yml 的 cabinet_devices_count 换成 cabinet_devices_total…"
replaced: 3 -> 3
$ node scripts/check-prometheus-metric-names.mjs
[check-prometheus-metric-names] OK: 38 个注册点 → 29 个有效指标名；…        ← 判据已空转
```

**修法**：`split(/\r?\n/)`。修完后 A/B 负向对照由 **8/9 → 9/9**：

```
[PASS] 注册点改回 gauge 的 .total: exit=1
[PASS] 告警规则改回 cabinet_devices_total: exit=1        ← 修复前此项 FAIL（exit=0）
[PASS] Grafana 看板改回 cabinet_devices_total: exit=1
[PASS] admin 运维页改回 cabinet_devices_total: exit=1
[PASS] 摘掉 alerts.yml 的草稿标记: exit=1
[PASS] 把草稿挂进 rule_files: exit=1
[PASS] MIN_REGISTERED 抬到 99: exit=1
[PASS] MIN_REFS 抬到 999: exit=1
[PASS] 全部还原后回绿: exit=0
汇总：9/9 例符合预期
```

> 这条缺陷的意义：本批新增的 `RecognitionHumanVerdictWrongRateHigh` 引用了新指标
> `cabinet_recognition_human_verdict_total` —— **在修复前，这条引用根本没被校验过**。

同批还修了看板扫描的同类截断：`"expr": "([^"]+)"` 会在转义的 `\"` 处截断，
把 `…{verdict=` 这类**没闭合花括号**的残片喂给解析器 ⇒ 标签名被当成指标名误报
（历史面板只因 `result/state/status` 恰好在关键字白名单里才没暴露）。改为整体捕获再反转义。

### 3.2 🔴 `DisputeOrderRefundTest` 依赖**测试执行顺序**（隐藏跨类依赖）

跑过滤子集时 `refundByOperator_full_reopensResolvedTicket_thenBlocksSecondRefund` 报：

```
ResponseStatusException: 500 INTERNAL_SERVER_ERROR
  "can not find lambda cache for this entity [com.aicabinet.trade.domain.DisputeTicket]"
```

`DisputeService.ensureDisputeTicketForFullRefund` 会走 MyBatis-Plus 的 `lambdaUpdate()`
（`reopenUpdateWrapper`），而该 API 需要**实体的 `TableInfo` 已被初始化**——纯 Mockito 单测里
没有 MP 上下文。模块内有大量 `@SpringBootTest`，**只要它们先跑，缓存就被填好**，该用例就"绿"。

**A/B 取证**（`git worktree add --detach` 出**纯净 HEAD**，用**同一个** `-Dtest=` 过滤跑）：

```
$ mvn -pl services/trade-service -am clean test -Dtest=DisputeOrderRefundTest
DisputeOrderRefundTest.refundByOperator_full_reopensResolvedTicket_thenBlocksSecondRefund:118
  ? ResponseStatus 500 INTERNAL_SERVER_ERROR "can not find lambda cache for this entity […DisputeTicket]"
Tests run: 2, Failures: 0, Errors: 1        BUILD FAILURE   EXITCODE=1
```

⇒ **在 HEAD 上同样失败**（只在行号上差 2 行，因本批给该测试加了一行 import）
⇒ **与本批改动无关，是既有的测试隔离缺陷**。本批**不改**（属独立批次），此处登记。

## 4. 验证证据

**单元测试**（`mvn -pl services/trade-service -am clean test -Dtest=<6 个类>`）：

按 **`<testcase>` 元素**计数（`Tests run:` 在 `@Nested`/IT 下会写 0，不可信）：

| 测试类 | testcase | fail | err |
|---|---|---|---|
| `VisionResultIngestServiceTest` | 16 | 0 | 0 |
| `RecognitionResultWriterTest` | 13 | 0 | 0 |
| `DisputeTicketSyncTest` | 7 | 0 | 0 |
| `DisputeReviewCodeTest` | 4 | 0 | 0 |
| `DuplicateCallbackTest` | 4 | 0 | 0 |
| `DisputeOrderRefundTest` | 2 | 0 | **1（§3.2 既有）** |
| **合计** | **46** | **0** | **1** |

**门禁**：`node scripts/run-audit-gates.mjs` ⇒ **32 个门禁，失败 0**（EXITCODE=0）。
含 `check:prometheus-metric-names`（§3.1 修复后）、`check:line-endings`、`check:audit-gates-wiring`。

**门禁自证**：`python scripts/devops/verify-metric-names-drift.py` ⇒ **9/9**（§3.1）。

## 5. 一处**运行期未验**（如实说明）

新增的 `cabinet.recognition.human_verdict` 与看板 3 个新面板（13/14/15）**没有在运行的
trade-service 上取过 `/actuator/prometheus` 实证** —— 本批只证到「代码编译通过 + 单测覆盖落点 +
门禁认指标名 + 看板 JSON 合法」。要看真实序列，需要起 dev 栈并触发一次争议结案。

> 顺带更正：`docs/evidence/2026-09-19-p06-observability/README.md` 里
> 「第 6 个 KPI 识别准确率刻意没有面板 / 只在看板登记缺口」**已被本批取代**（看板 13 → 15 面板），
> 该文件顶部已加指向本文件的说明（正文保留原样，不改历史）。

## 6. 另一项交付

**孤儿表定策** → `docs/ORPHAN_TABLE_DISPOSITION.md`（含 185 → 42 张零引用的分类、B1 重复表实证、
门禁决策与判据设计）。复现：`python scripts/devops/orphan-table-triage.py`。

# P0-1 端侧识别结果直报入站 · 证据

> 日期：2026-09-19 ｜ 提交：见 `git log` 中本批 commit
> 结论：**端侧（第三方）识别完成后，可直接把结果 POST 给平台并驱动完整结算** ——
> 真实栈端到端 16/16 通过（含 4 条负对照），入口与幂等均有硬证据。

## 一、为什么是这件事（缺口取证，不是推测）

`vision-service/app/recognition/quectel_recognizer.py` 的模块 docstring 白纸黑字写着设计意图：

```
- 端侧识别结果建议直接上报 trade-service 内部接口（复用现有结算链路）；
- 端侧异常事件通过 POST /internal/v1/vision/anomaly-events 上报 …
```

**异常事件**那条已实现（`VisionInternalController#anomalyEvents`）；**识别结果**这条一直没有落点：

| 判据 | 实测 |
|---|---|
| `grep -rn "edge-results\|edge_results"` 全仓（java/py/ts/mjs/yml） | **0 命中** |
| `/internal/v1/vision` 下已有端点 | 只有 `anomaly-events`（+ 另一个控制器里的 mappings/context/default-sku） |
| 识别结果现有的唯一通道 | Kafka：`vision-service` 自己跑识别后回写 `aicabinet.vision.recognize.result`（`VisionRecognitionListener:47` 消费） |

也就是说：**现有通道是「平台请求 → vision-service 识别 → 回结果」**，
而端侧自己识别完、主动把结果报上来的形态**没有入口**。

⚠️ 一个容易误判的点：本地栈上 `KAFKA_ENABLED=false`（vision 侧）且
`aicabinet.vision-async.enabled=false`（trade 侧）⇒ 那条 Kafka 通道**在本机根本没在跑**。
所以本端点不只是"补一个入口"，它还是当前唯一可用的"结果由外部送入"路径。

## 二、交付物

| 文件 | 作用 |
|---|---|
| `services/common/common-core/.../dto/VisionRecognitionResultDto.java` | 入站契约。字段名与 vision-service 回写 Kafka 的 payload **完全一致**（`sessionId/taskId/traceId/items[{skuId,quantity,confidence}]/overallConfidence/needReview/modelVersion/detectedClasses/provider/occurredAt`），端侧两种入口语义相同 |
| `services/common/common-core/.../dto/VisionResultIngestResponseDto.java` | 受理回执 |
| `services/common/common-core/.../enums/VisionIngestOutcome.java` | `PROCESSED / ALREADY_HANDLED / TOO_EARLY / CANCELLED` |
| `services/trade-service/.../service/VisionResultIngestService.java` | 入站逻辑 |
| `services/trade-service/.../api/VisionInternalController.java` | `POST /internal/v1/vision/edge-results`（路径取自路线图 P0-1 与 `docs/VISION_QUECTEL_INTEGRATION.md` 阶段 A 的既定契约） |
| `services/trade-service/src/test/.../VisionResultIngestServiceTest.java` | 16 例 |
| `scripts/devops/verify-vision-result-ingest.py` | 真实栈端到端验证（本目录 `e2e-verify.txt` 为其输出） |

## 三、设计与取舍

**① 结算逻辑一行没改。** 采纳时调用的是 `SessionService.completeAsyncRecognition` ——
与 Kafka 通道 `VisionRecognitionListener` 调的是**同一个方法**，保证「同一份结果从哪个入口进来都得到同一个结果」。

**② 唯一新增的两件事**：

- **明确回执**：结算侧 `SessionSettleService.doCompleteAsyncRecognition` 在非 `RECOGNIZING` 态是
  `log.warn` + **静默 return**。走 Kafka 无所谓（fire-and-forget），但 HTTP 调用方拿不到任何信号 ——
  端侧无法区分「已结算」与「被丢弃」，这正是门禁失效第六形态「信号在骗读者」。
  现在回执显式给出 `outcome`，端侧据此决定是否重发。
- **入口 fail-closed**：`modelVersion` 必填。原因是查出来的一个**真实可绕过点** ——
  `SettlementService.blocksSilentSettle` 依据 *modelVersion 是否含 mock/fallback* 判定
  「非生产精度不得静默扣款」；Kafka 通道该字段来自 vision-service 代码常量、外部构造不了，
  而 HTTP 入站**可以**被外部构造 ⇒ 留空即可绕过该闸，故在入口直接 400。

**③ 只有 `RECOGNIZING` 态受理**（不自动推进状态）。`SHOPPING/CREATED/OPENING` → `TOO_EARLY`；
`SETTLING/COMPLETED/DISPUTED/FAILED` → `ALREADY_HANDLED`；`CANCELLED` → `CANCELLED`。
刻意**不**在这里做状态跃迁（如 `SHOPPING → RECOGNIZING`），因为那会改动既有结算语义，
属产品决策，不在本项范围。

## 四、实证

### 4.1 真实栈端到端（`e2e-verify.txt`，16/16）

前置：`-Daicabinet.vision-async.enabled=true`（见第五节说明）。

| 断言 | 实测 |
|---|---|
| A 开门 → SHOPPING | ✅ |
| B 关门 → **RECOGNIZING**（停在识别态等端侧结果，未被自动结算） | ✅ |
| C 直报 → `accepted=true / outcome=PROCESSED` | ✅ |
| D1 会话终态 `COMPLETED` | ✅ |
| D2 订单生成且 `PAID` | ✅（`总额=350`） |
| D3 **订单行内容 = 端侧上报内容**（sku / 数量 / 置信度 0.96） | ✅ |
| D4 账目自洽：总额 350 + 优惠 0 = 行金额 350 | ✅ |
| D5 余额精确扣减 12700 − 350 = **12350** | ✅ |

负对照（4 条，全部通过）：

| 场景 | 期望 | 实测 |
|---|---|---|
| 二次上报同一结果 | 不重复采纳 | `ALREADY_HANDLED`，余额不变（12350→12350），该会话订单数仍 1 |
| 缺 `modelVersion` | 400 | **400** + 指明"留空会导致精度校验被绕过" |
| `quantity=0` | 400 | **400** |
| 会话不存在 | 404 | **404** |
| 无 `X-Internal-Api-Key` | 401 | **401** |

服务端日志（2026-09-19 14:03，采集自当时运行的容器，原文见 `server-log-sample.txt`）——
一条会话的完整三段：关门 → 发识别请求（无人消费）→ 端侧直报被采纳；紧接着第二次上报被判 `ALREADY_HANDLED`：

```
INFO  SessionDoorService            - door closed, recognizing sessionId=1789826603465737808511 deviceId=330449777078 userId=10001 video=null
INFO  VisionRecognitionProducer     - published vision request session=1789826603465737808511 task=T-1789826603465737808511 fusion=SINGLE
INFO  VisionResultIngestService     - 端侧识别结果已采纳 session=1789826603465737808511 task=EDGE-1789826603465737808511 provider=QUECTEL 终态=COMPLETED
INFO  VisionResultIngestService     - 端侧识别结果未采纳 session=1789826603465737808511 state=COMPLETED outcome=ALREADY_HANDLED
```

> 🔴 **路径对齐**：本端点最初实现为 `/recognition-results`，回查路线图后确认
> **两份文档**（路线图 P0-1 阶段 A、`docs/VISION_QUECTEL_INTEGRATION.md` 阶段 A）都指定的是
> `/internal/v1/vision/edge-results` ⇒ 已改为对齐文档并**重编译、重建镜像、重跑本验证**（16/16）。
> 对外契约写在哪就以哪为准，否则第三方照文档接入会拿到 404。

### 4.2 A/B 漂移证明（`.tmp/ab-vision-ingest-gate.py`）

只认 surefire XML 的 `<testcase>` 元素个数；每轮跑前删 XML（无产物≠绿）；每轮无条件还原。

| 轮次 | `<testcase>` | 失败 | 失败用例 |
|---|---|---|---|
| 基线 | 16 | 0 | — |
| 漂移 A：移除 `modelVersion` 必填校验 | 16 | **2** | `ingest_nullModelVersion_rejected`、`ingest_blankModelVersion_rejected` |
| 漂移 B：移除「非识别态早返回」 | 16 | **5** | `..._isNotSettledAgain`、`..._areTooEarly`、`..._isIdempotent`、`..._isNotSettledAgain`、`..._reportsCancelled` |
| 还原 | — | — | 源文件**字节一致 = True** |

⇒ 两条核心判据都能被指出「让它红的方式」，不是恒真判据。

### 4.3 构建与部署

- `mvn clean compile` → `BUILD SUCCESS`，新类 `.class` 落盘；
- 镜像 `ai-cabinet/trade-service:local` 重建成功，**从镜像产物内取证**：`BOOT-INF/classes/.../VisionResultIngestService.class` 存在；
- 运行容器实测：无密钥 401、带密钥空体 400 ⇒ 端点在线且 fail-closed。

## 五、验证时的临时配置（已还原）

端侧直报需要会话停在 `RECOGNIZING`。默认 `aicabinet.vision-async.enabled=false` 时，
关门会**立即同步调 vision-service 并结算**，会话不会停在识别态 ⇒ 没有受理窗口。
验证时用一次性 compose 覆盖（`.tmp/compose.vision-async.yml`）注入
`JAVA_TOOL_OPTIONS=-Daicabinet.vision-async.enabled=true`（用 `-D` 精确指定属性名，
避免依赖 Spring 松弛绑定对环境变量名的猜测）。

**验证后已还原**：容器内 `JAVA_TOOL_OPTIONS` 已消失、服务健康 200、端点仍在。
该 override 文件未入库。

> 这也说明一件事：**要真正用上端侧直报，部署侧需开异步识别**（这正是"端侧自己识别、不上传视频"的形态）。

## 六、本批发现、但未做的缺口（显式记录，不装作没看见）

1. **`recognition_result` 表自 V1 起就存在，却无任何运行期写入方。**
   `V1__init_schema.sql:35-48` 定义（注释「AI 识别结果」，含 `task_id` PK、`session_id` 索引、
   `model_version`、`need_review`、`fusion_mode`）；`grep -rn "recognition_result" --include=*.java --include=*.xml`
   **0 命中**，表内当前 0 行（仅 `V222` 的 demo 种子针对已完成会话插过，现也无数据）。
   ⇒ 两条通道（Kafka / HTTP）**都不落库**。本批**未**补，原因：补它要动两条通道的共同上游，
   属需要单独验证的改动；且它牵涉归档/保留策略。**建议独立立项**（对账与 O1 准确率看板都以它为数据源）。
2. **`infra-github-runner-1` 处于崩溃重启循环**（`RestartCount=173`，`exit=1`）：
   `POST https://api.github.com/actions/runner-registration` → **404 Not Found**。
   与 P0-1 无关，属运行环境问题，另行处理。
3. **端侧直报在 `WAITING_UPLOAD` 态不被采纳**（返回 `TOO_EARLY`）。
   该态出现条件是关门时 `uploadStatus ∈ {LOCAL_QUEUED, UPLOADING}`（`SessionDoorService#isWaitingForUpload`）。
   若产品希望"端侧直报可唤醒 WAITING_UPLOAD 的会话"，那是一次显式的状态机语义变更，需另行决策。

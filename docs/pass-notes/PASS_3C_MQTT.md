# Pass 3C · MQTT / 门事件深读笔记

> **日期**：2026-09-07  
> **范围**：开门指令下发、柜机事件上报、去重/ACK、trade 内部门事件与结算衔接  
> **源码**：`MqttCommandPublisher`、`MqttEventListener`、`DoorEventDeduplicator`、`DeviceCommandService`/`Tracker`、`TradeServiceClient`（device）、`DeviceServiceClient`（trade）、`SessionInternalController`、`SessionService.handleDoorEvent`、`DeviceSimulator`  
> **前置**：[PASS_3A_MONEY.md](PASS_3A_MONEY.md) · [PASS_3B_DISPUTE.md](PASS_3B_DISPUTE.md)

---

## 1. 端到端链路

```
trade SessionService.createSession
  → DeviceServiceClient.requestOpenDoor  (@Retry deviceService)
      → device POST /internal/v1/devices/{id}/open-door
          → assert deviceExists(trade)
          → MQTT publish cabinet/{deviceId}/cmd  OPEN_DOOR + commandId
          → DeviceCommandTracker PENDING

柜机 / 模拟器
  → ACK on cabinet/{id}/evt
  → DOOR OPEN → … → DOOR CLOSED (+ video/upload/gravity)

device MqttEventListener ($share/aicabinet/cabinet/+/evt)
  → 去重 → TradeServiceClient.notifyDoorEvent (×3 退避)
      → trade POST /internal/v1/sessions/door-event
          → SessionService.handleDoorEvent
              → applyDoorEvent (commit 门状态)
              → settleAfterClose / finishRestock / 运维完成
```

**Topics**（`MqttTopics`）：

| 方向 | Topic |
|------|-------|
| 下行 cmd | `cabinet/{deviceId}/cmd` |
| 上行 evt | `cabinet/{deviceId}/evt` |
| 共享订阅 | `$share/aicabinet/cabinet/+/evt`（多 device-service 实例只消费一次） |

QoS：发布与订阅均为 **1**。

---

## 2. 开门指令

### 2.1 Payload（OPEN_DOOR）

| 字段 | 说明 |
|------|------|
| commandId | UUID |
| type | OPEN_DOOR |
| sessionId / userId | 会话上下文 |
| operatorMode | 运维开门 |
| expireAt | now + 60s |

另有：`SET_TARGET_TEMP`、`LOCK`/`UNLOCK`/`REBOOT`。

### 2.2 门禁

- trade→device：Resilience4j `@Retry(name="deviceService")`
- device 发令前：`trade.deviceExists`；trade 不可达 → **视为不存在**，404（不向未知柜发令）
- Publisher / Listener **分 clientId**（`-pub-` / `-evt-`）+ 文件持久化目录

### 2.3 ACK 状态机（`DeviceCommandTracker`）

| 状态 | 含义 |
|------|------|
| PENDING | 已发布，等 ACK（超时 **15s**） |
| ACKED / FAILED | 设备成功/失败 ACK |
| TIMEOUT | 超时 |
| LATE_ACK / LATE_FAILED_ACK | 超时后再到 ACK |
| DUPLICATE_ACK | 终态后重复 ACK |
| ACKED_UNKNOWN / FAILED_UNKNOWN | 未知 commandId |

存储：优先 Redis（hash + pending ZSET）；失败回退本地 Map。  
查询：`GET /internal/v1/devices/commands/{commandId}`。

**注意**：ACK 超时**不会**自动取消 trade 会话；会话仍靠开门超时扫（SessionService expire OPENING）。

---

## 3. 门事件上报与去重

### 3.1 事件体（DOOR）

必填：`sessionId`、`doorState`（OPEN/CLOSED/…）  

可选：`deviceId`、`videoUri`、`uploadStatus`、`videoClipsJson`/`video_clips`、`cameraFusionMode`、`gravityDeltasJson`/`gravity_deltas`、`eventSeq`/`event_seq`

### 3.2 校验

| 条件 | 行为 |
|------|------|
| topic deviceId ≠ body deviceId（且 topic 非 unknown） | **丢弃**（打 warn） |
| 缺 sessionId / doorState | 丢弃 |
| doorState 非法枚举 | clear 去重键后丢弃 |
| 共享订阅 topic 带 `$share/...` 前缀 | 剥前缀再解析 deviceId |

### 3.3 去重（`DoorEventDeduplicator`）

键：`aicabinet:door-dedup:{sessionId}:{doorState}:{fingerprint}`  
TTL：**60s**（Redis SET NX 或本地 ConcurrentHashMap）

指纹：

- 有 `eventSeq` → `seq:{eventSeq}`（稳定，**推荐柜机实现**）  
- 无 → `videoUri|uploadStatus|clips|fusion|gravity` 拼接  

转发 trade **失败** → `clear` 去重键，允许 MQTT 重投 / 客户端重试。

### 3.4 转发 trade

`TradeServiceClient.notifyDoorEvent`：**最多 3 次**，间隔 200ms × attempt。  
心跳失败只打 warn + 指标 dropped，**不影响购物**。

---

## 4. trade 侧门事件（与 3A 衔接）

`SessionInternalController` → `handleDoorEvent`：

1. **先** `applyDoorEvent`（`session:life` 锁）提交门状态 / 视频元数据  
2. CLOSED 且非 WAITING_UPLOAD：  
   - OPS_REMOTE → 已 COMPLETED，返回  
   - RESTOCK + RECOGNIZING → `finishRestockSnapshot`  
   - 否则 → `settleAfterClose`  

### 4.1 onDoorClosed 分支（再强调）

| uploadStatus | 会话 |
|--------------|------|
| LOCAL_QUEUED / UPLOADING | → **WAITING_UPLOAD**（不立刻结算） |
| 其他 / 空 | → RECOGNIZING → settle |

后续 `attachVideo`（`POST /internal/v1/sessions/video`）：WAITING_UPLOAD→RECOGNIZING→settle。  
模拟器离线模式：先 CLOSED+LOCAL_QUEUED，再 HTTP attachVideo。

### 4.2 乱序 / 重复

| 场景 | 期望 |
|------|------|
| 重复 CLOSED 同 fingerprint（60s 内） | device 层丢弃，trade 不二次结算 |
| CLOSED 时已非 SHOPPING | `onDoorClosed` 早退；settle 见非 RECOGNIZING 早退 |
| OPEN 时已 SHOPPING | `onDoorOpened` 仅 OPENING→SHOPPING，幂等 |
| OPENING 直接 CLOSED | 先当 SHOPPING 再关，打 warn |

---

## 5. 模拟器行为（联测契约）

`DeviceSimulator`：

1. 收 OPEN_DOOR → ACK → 线程：DOOR OPEN →（购物延时）→ completeDoorClose  
2. 关门可带 `UPLOADED` 视频 + 可选重力 JSON（环境变量）  
3. 或 `LOCAL_QUEUED` + 延时 `postAttachVideo`  
4. 运维 operatorMode：默认不注入重力（防误扣）  
5. HTTP 手动关门 UI（full 栈常见 `:18089`）  

**同 deviceId 勿并行真机 + 模拟器。**

---

## 6. 指标（device）

| Metric 用途 | 方法 |
|-------------|------|
| MQTT 入站 | recordMessageIn |
| 门事件转发 / 去重 | recordDoorForwarded / Deduped |
| 心跳成功 / 丢弃 | recordHeartbeatForwarded / Dropped |
| ACK / trade 失败 | recordAck / recordTradeFailure |
| 命令发布 / ACK 成败超时 | recordCommandPublished / Ack* |

健康：`MqttHealthIndicator` 要求 publisher+listener 均连接。

---

## 7. 已有测试 vs 缺口

### 已有

| 资产 | 覆盖 |
|------|------|
| DoorEventDeduplicatorTest | 本地去重 |
| DeviceCommandTrackerTest | ACK/超时本地（含 Q5 TIMEOUT） |
| MqttEventListenerDoorTest | Q1/Q2/Q3/Q4（listener 去重 / 失败清键 / deviceId / eventSeq） |
| TradeServiceClientRetryTest | Q4（door-event 3 次重试） |
| DeviceCommandServiceTest | Q8（未知柜不发 MQTT） |
| SessionAttachVideoSettleTest | Q6（WAITING_UPLOAD→settle 一次） |
| SessionDoorClosedIdempotencyTest | Q3 trade 侧二次 CLOSED 不双扣 |
| e2e-shopping / verify-local | 经模拟器的快乐路径 |
| SessionLifeConcurrencyTest | trade 侧生命周期锁（非 MQTT） |

### 缺口大（device MQTT **无**集成测）

| ID | 场景 | 断言 | 落地 |
|----|------|------|------|
| Q1 | topic/body deviceId 不一致 | 不调 trade | ✅ `MqttEventListenerDoorTest#deviceIdMismatch_skipsTrade` |
| Q2 | 同 session CLOSED 重放（无 eventSeq，同 fingerprint） | 第二次 dedup；settle 一次 | ✅ `MqttEventListenerDoorTest#closedReplay_sameFingerprint_forwardsOnce` |
| Q3 | 有 eventSeq 的合法二次 CLOSED（不同 seq，带新 video） | 第二次可转发；trade 幂等不双扣 | ✅ `MqttEventListenerDoorTest#closedDifferentEventSeq…` + `SessionDoorClosedIdempotencyTest` |
| Q4 | trade 短暂 500 后重试 | 3 次内成功；失败 clear dedup | ✅ `TradeServiceClientRetryTest` + `MqttEventListenerDoorTest#tradeFailure_clearsDedup…` |
| Q5 | ACK 超时 15s | command TIMEOUT；会话仍可由 expire OPENING 扫 | ✅ `DeviceCommandTrackerTest#forceExpire_marksTimeout`（会话扫见 Session 侧既有） |
| Q6 | WAITING_UPLOAD → attachVideo | 结算只发生一次 | ✅ `SessionAttachVideoSettleTest#attachVideo_waitingUpload_settlesOnce` |
| Q7 | 共享订阅双实例 | 单事件只被一个 listener 处理（+ Redis 去重） | ✅ Redis：`DoorEventDeduplicatorTest` + `MqttEventListenerDoorTest#q7_sharedRedisDedup…` + `$share` topic 剥前缀；✅ broker：`EmqxSharedSubscriptionIT`（Testcontainers EMQX 5.5，`disabledWithoutDocker`） |
| Q8 | OPEN_DOOR 未知柜 | 404，broker 无消息 | ✅ `DeviceCommandServiceTest#openDoor_unknownDevice…` |

优先余：商户 Device/Inventory/Team 切片（已落地见 3F）；ACK 告警挂钩等优化项。

---

## 8. 优化建议（测后）

1. **柜机强制 eventSeq**：指纹不依赖易变视频字段，减少误去重 / 漏去重。  
2. **ACK 超时告警挂钩会话**：TIMEOUT 时可选通知 trade（现仅本地指标）。  
3. **device→trade door-event 契约测**：Testcontainers MQTT + WireMock trade，补齐「仅 2 个单测」缺口。  
4. **心跳与门事件隔离**：已做到；保持 trade 宕机时心跳降级不拖死 listener 线程（现同步 RestClient，高峰需关注阻塞）。

---

## 9. Pass 进度

| Pass | 状态 |
|------|------|
| 3A 金钱 | ✅ |
| 3B 争议 | ✅ |
| **3C MQTT** | ✅ 本文 |
| 3D 库存货道 | ✅ |
| 单测落地 M*/D*/Q* | **Q1–Q8 表征已落地**；Q7 broker 层 ✅ `EmqxSharedSubscriptionIT` |

建议下一动：OpsCommercial 按域拆 Controller（文档/导航收益），或 3C §8 优化项。

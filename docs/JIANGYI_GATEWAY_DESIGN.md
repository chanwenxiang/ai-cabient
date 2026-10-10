# 将邑开门柜接入网关（jiangyi-gateway）设计 — CB-022

> 2026-10-09 实施。权威方案：WorkBuddy 计划 `electric-forging-tesla-n6eohtlS.md`（已批准）。
> 本文是落码后的**现状速查**，与方案冲突时以方案 §10 待确认项为准。

## 0. 核心判断（复述）

- **枢纽**：将邑 `orderNo` = 我方 `sessionId`（V16 §4.3.2.7「商户服务器生成、需唯一」）——免映射。
- **金额不采信设备侧**：识别上报只带 classId+quantity（无金额字段）⇒ 结算金额 = Σ(映射 SKU 现价 × qty)（分）；映射未命中 fail-closed 转 DISPUTED。
- **模式一无关门上报**：识别上报到达时 gateway 先合成 door-event CLOSED（SHOPPING→RECOGNIZING）再转发 edge-results；`uploadDoorState`（拉门成功）→ door-event OPEN（OPENING→SHOPPING）。
- **零侵入**：device-info / trade 业务服务 / common-core / 前端不动；唯一现有 Java 改动 = `DeviceServiceClient`（路由分流）。

## 1. 模块与文件

| 模块 | 文件 | 职责 |
|---|---|---|
| trade | `V332__jiangyi_device.sql` / `V333__jiangyi_class_mapping.sql` | 设备目录（SN/identifier/token_version）+ class 映射 |
| trade | `domain/JiangyiDevice(·ClassMapping)` + `mapper/*Mapper` | MyBatis-Plus 实体（house style：@TableName + BaseTradeMapper） |
| trade | `service/JiangyiDeviceDirectory` | isJiangyi(deviceId)=BOUND 才路由；TTL 缓存 60s/负 10s |
| trade | `service/JiangyiClassMappingService` / `JiangyiRecognitionTimeoutService` | 映射解析 / C09 即时版（释放冻结→DISPUTED→争议单） |
| trade | `client/JiangyiMerchantClient` / `service/JiangyiOnboardingService` | 商户云 login2/setDomain/getIdentifier/绑租户（env-only） |
| trade | `api/JiangyiInternalController` | `/internal/v1/jiangyi/**`：设备/映射/超时/入驻（internalApiAuthInterceptor 自动覆盖） |
| trade | `client/DeviceServiceClient`（●改） | isJiangyi→gateway（**不重试**，双开门风险）；operatorMode→501；else→device-service（编程式 Retry） |
| gateway | `auth/DeviceTokenService` / `DeviceAuthInterceptor` | JWT HS256 签发/验签；Bearer 拦 /jiangyi/api/**（排除 token 签发端点）；吊销=claims.ver<表 ver |
| gateway | `ws/DeviceHandshakeInterceptor` / `DeviceWebSocketHandler` | WS 握手校验 query token+identifier 一致；openDoor 下发；单设备单连接顶替 |
| gateway | `api/DeviceTokenController` | `POST /jiangyi/api/token/openDoorDeviceStatus`（+`/jiangyi/api/token` 别名），envelope `{status,msg,data:{token,identifier}}` |
| gateway | `api/DeviceReportController` | 文档路径照抄：uploadLockState/uploadDoorState/addRecognitionGoodsToOrder/uploadOrderError/openDoubleDoorError/uploadFaultOrder |
| gateway | `api/OpenDoorController` | `/internal/v1/jiangyi/devices/{id}/open-door`：WS 下发 + tracker 登记 |
| gateway | `tracker/CommandTracker` / `SessionWatchdog` | Redis hash+ZSET 三组 pending；15s 开门 / 300s 识别 / 10min doing 兜底 |
| gateway | `normalize/DoorStateNormalizer` / `RecognitionNormalizer` | success|fail 与 bigModel/BigModel 大小写归一；forms→items；modelVersion 截断 64 |
| 部署 | `infra/docker-compose.full.yml`、`infra/gateway/nginx*.conf` ×3 | jiangyi-gateway `127.0.0.1:18084:8083`；`/jiangyi/` + `/websocket/`（proxy_read_timeout 3600s） |
| 模拟器 | `simulator/jiangyi/JiangyiDeviceSimulator` | `java -jar … jiangyi`：取 token→WS→收 openDoor→锁/门/识别上报全链路 |

## 2. 端点契约（设备面路径照抄 V16 文档，domain 尾段=/jiangyi/api）

| 设备动作 | 端点 | 关键字段 |
|---|---|---|
| 换 token | `POST /jiangyi/api/token/openDoorDeviceStatus` | `{deviceSn}` → `{status:200,data:{token,identifier}}` |
| 开锁状态 | `POST /jiangyi/api/device/uploadLockState` | `{orderNo, lockStatus}` fail→trade open-failed |
| 拉门状态 | `POST /jiangyi/api/device/uploadDoorState` | `{orderNo, doorStatus}` success→door-event OPEN |
| 识别结果 | `POST /jiangyi/api/orderProduct/addRecognitionGoodsToOrder` | `{orderNo, forms:[{classId,quantity,specialId}]}` → 先合成 CLOSED → 归一化 → edge-results |
| 异常/大模型 | `POST /jiangyi/api/device/pokerOrder/uploadOrderError` | `bigModel:"doing"`→挂起 10min 兜底；否则→DISPUTED |
| 双门占用/故障单 | `/jiangyi/api/order/openDoubleDoorError`、`/order/uploadFaultOrder` | 仅审计 |

## 3. 鉴权（两把钥匙 + 设备 JWT）

- `JIANGYI_TENANT_ID` / `JIANGYI_SECRET`：仅 setDomain（入驻）。
- `JIANGYI_MERCHANT_MOBILE` / `JIANGYI_MERCHANT_PASSWORD`：商户云 login2 → Bearer（缓存 25min，401 重登一次）。
- `JIANGYI_JWT_SECRET`（≥32 字节，无默认值→503 fail-closed）：设备 token；claims sn/deviceId/identifier/ver/exp(7d)；吊销=表 token_version+1（`/token-revoke`），签发只记 `token_issued_at` 不 bump。

## 4. 部署要点

- compose：`docker compose -f infra/docker-compose.full.yml build jiangyi-gateway && docker compose -f infra/docker-compose.full.yml up -d --no-deps --force-recreate jiangyi-gateway`（拆两步，铁律 #41）。
- 内网穿透（联调）：frp/cpolar → 本机 nginx 80/443 → gateway `127.0.0.1:18084`；setDomain 的 domain=`https://<穿透域名>/jiangyi/api`、socketUrl=`wss://<穿透域名>/websocket`。
- 验证顺序（联调日 1）：入驻 register→bindDomain（setDomain）→ 设备通电 → token 签发 → WS 在线（`/internal/v1/jiangyi/devices/{id}/routable`）→ 扫码开门 → 模拟器/真机上报 → 结算 COMPLETED。

## 5. 已知边界与待办

- 一期只开放购物开门；强制开门空 msgContent 结构性拒绝；operatorMode 501。
- specialId 不采信（V16 §4.2.10 唯一出口、来源未定义）。
- 二期：视频（getTempUploadToken/uploadVideoUrl 逗号拼接容错）、模型同步预生成映射、补货开门真路由。
- 待办：`pnpm gen:api-types` 重跑（无新增对外 API，暂无影响）、真机联调回填识别率、商户端月度结算单 UI（另任务）。

## 6. 本地模拟器联调结果（2026-10-09）

### 6.1 正向链路（通过）

登录（图形验证码→Redis 明文→mock 短信码 123456）→ 建 session（body 携带 deviceId+idempotencyKey）→ WS 下发 openDoor（msgContent=sessionId）→ 模拟器四步上报 → 结算。

实测断言：session `COMPLETED`（openTime 建单后 136ms，未触发 15s watchdog）；订单 350 分 `PAID`/`BALANCE`；`device_sku_inventory` 10→9；识别 `SKU-WATER-001×1` 置信度 1.0；trade 日志 `async session completed`。closeTime−openTime≈3.08s（模拟器购物时长 3000ms，符合）。

### 6.2 负向三连（fail-closed 全通过）

| 用例 | 操作 | 实测 |
| --- | --- | --- |
| 映射未命中 | 模拟器 CLASS_ID=999 识别上报 | session→`DISPUTED`（暂未扣款）；`ops_exception`：`JIANGYI_CLASS_MAPPING_MISS`/HIGH + `RECOGNITION_STUCK`/HIGH；争议工单 OPEN/HIGH/RECOGNITION、SLA 48h |
| 密钥未配置 | gateway 不带 `JIANGYI_JWT_SECRET` 启动 | token 端点 503（先于参数校验拒签，日志 `not configured`） |
| 设备离线 | 停模拟器（WS 1006 断开） | gateway 即时 `ws offline reported` → `device_info.online_status=OFFLINE` → 建 session 409「设备离线」；同 idempotencyKey 重试仍 409，gateway 下行 0 次 openDoor（无重试） |

### 6.3 联调暴露并落码的缺陷（①-⑦）

| # | 缺陷 | 修复 |
| --- | --- | --- |
| ① | uploadLockState success 未 complete(OPEN) → watchdog 15s 误判超时 | 成功分支即 `commandTracker.complete(OPEN)` |
| ② | `markOpenDoorFailed` CAS 只豁免终态 → RECOGNIZING 会话被腰斩 FAILED 并误释预授权 | CAS 改为仅 `CREATED`/`OPENING` 可置 FAILED |
| ③ | 将邑合成 CLOSED 直接触发 `settleAfterClose` → edge-results 到达已 ALREADY_HANDLED | `handleDoorEvent` 对将邑设备关门后停在 RECOGNIZING（照 ops-remote 豁免模式） |
| ④ | 心跳不回发 → 真机会判离线重连（V16 §4.3.1 服务端必须回 `{status:200,msgType:"heartBeat",msgContent:"PONG"}`） | WS handler 心跳分支回发 PONG |
| ⑤ | uploadDoorState fail 只等 watchdog 兜底 | fail 分支即时 `postOpenFailed`（V16 §4.2.8：开门失败不上报订单结果） |
| ⑥ | **在线状态链路断裂**：gateway `wsOnline` 是死代码且 trade 只写 `jiangyi_device`，而开门校验查 `device_info.online_status` → 恒 409「设备离线」 | gateway 建连/心跳节流(60s)/断开三时点上报；trade `wsOnline` 同步置 `device_info` ONLINE 刷 `updated_at`（对齐巡检 2min 判活）+ 新增 `ws-offline` 即时置离线 |
| ⑦ | `TradeInternalClient` 两个 post 重载 `RestClient.body(null)` NPE（void 版 wsOnline/wsOffline/tokenIssued、泛型版 recognizeTimeout 全中） | body 为 null 时不设请求体 |

### 6.4 联调操作注意

- 模拟器不发心跳：建 session 须在 WS 建连后 2 分钟巡检窗口内（或后续给模拟器加 heartBeat 循环）。
- 进程持锁时 `spring-boot:repackage` 可能 BUILD SUCCESS 但产物未落盘——重打包前先停服务，打包后校验产物内容（如提取 `BOOT-INF/lib/common-core-*.jar` 字节数、grep 类常量池标志）。
- common-core 改动后须 `mvn -o -pl services/common/common-core install` 同步本地仓库，否则旧 fat jar 会嵌旧版（症状：运行时 `Lookup method resolution failed`）。
- 种子五表（device_info→jiangyi_device→mapping→price→inventory）psql 直插、ON CONFLICT 幂等，不入仓库（铁律 #29 的 R__ 惯例保留给长期基线数据）。

## 7. 二期：采集→学习→模型闭环 + 商品库挂接 + 采集编排（CB-023，2026-10-10）

### 7.1 依据与范围

依据《将邑科技商品采集接口文档(移动端)v1.13.0》（**PDF 扫描件逐页视觉核对**——docx 转换稿有偏差：4.1.2.4 name 行串行错位、4.4.4 示例 industrialControlModel 陷阱）。范围 A+B+C 全量（用户拍板）：

- **A 模型同步闭环**：模型列表预览 → classes.txt 解析 → 映射预生成（MODEL_SYNC/DISABLED）→ 人工激活 → WS updateModel 下发 → downloadModelNotify 回执回填。
- **B 商品库挂接**：我方 SKU ↔ 将邑商品（新关联表，不动 sku_catalog 主数据）。
- **C 采集编排**：进入/退出采集模式（营业开门 409）、触发学习（finishNotify 一次性凭据）、进度聚合、审核列表。

### 7.2 关键设计决策（CB-023 台账）

1. **industrialControlModel 存原值不解释**：PDF §4.4.4.5 示例中 rk3588.rknn 与 rk3576.rknn 的该字段**同为 "88"**，"76"/"88" 与主板的对应关系文档自证不了。下发校验用**字符串相等**（设备登记值 vs 模型值），不做语义映射。
2. **预生成不自动生效**：预生成行 status=DISABLED，人工核对 classId↔textName↔SKU 对照后才激活——错位映射一旦激活直接错误扣款。classes 行数 ≠ 模型 quantity 即拒绝预生成（JiangyiClassesParser，含前后 3 行 diff 摘要）。
3. **独立关联表**（V335 sku_jiangyi_link）：jiangyi_product_id UNIQUE（一个将邑商品至多挂一个我方 SKU，防识别歧义）；barCode 双方均非空且不等即拒（将邑 barCode 可空且 barCodeSource=manual 不可靠，仅作一致性校验不作唯一键）；RETIRED 留痕可复活。
4. **采集只编排我方可治理部分**：4.3.x 采集批次操作留在将邑商户 App 人工完成；采集开门（§4.2.3）由将邑直接下发不经我方 gateway——采集模式锁（jiangyi_device.gather_locked_at）只管我方营业开门（DeviceValidationService 409「设备商品采集中」），账务天然隔离（将邑侧开门不产生我方会话）。
5. **classIdBase 可配（0/1）**：classes 行号与 classId 的对应方向文档未明示（PDF 无说明），admin 预览人工确认为兜底。
6. **finishNotify 三重防伪**（gateway 公开面 `/jiangyi/api/gather-finish-notify`，GatewayWebConfig 排除鉴权）：①finishNotifyId 必须命中 PENDING ticket 否则静默 202；②反查将邑 trainedProducts 交叉验证 productId；③CAS 一次性消费（PENDING→FINISHED/FAILED，重放 no-op）。回执到了但 trained 列表还没有 → 保留 PENDING（异步延迟容忍）。
7. **学习范围=将邑侧已采集待学习集合**（§4.4.3 参数表无 productId，只有 modelName/finishNotifyUrl/finishNotifyId；文档参数表与请求示例自身不一致——按示例带 modelName，productId 语义待真机实锤）。
8. **MODEL_SYNC 覆盖保留已挂 SKU**：Mapper.upsert 的 MP NOT_NULL 策略下 sku_id=null 不进 SET 子句，预生成覆盖 MANUAL 行不丢已挂 SKU。

### 7.3 链路与时序

```
[admin] 模型预览 GET /jiangyi/models            ← JiangyiGatherClient.modelFiles + classes.txt 拉取解析
[admin] 预生成 POST .../model-sync              ← jiangyi_class_mapping upsert(MODEL_SYNC/DISABLED)
[admin] 激活  POST .../class-mappings/activate   ← activatePregenerated（DISABLED→ACTIVE）
[admin] 下发  POST .../model-push               ← jiangyi_model_deployment(SENT) → gateway WS updateModel
[gateway] tracker(MODEL kind) 600s 超时 → trade push-timeout → FAILED（幂等 CAS）
[设备]   downloadModelNotify(WS 上行) → gateway → trade model-confirmed → CONFIRMED + jiangyi_device 回填
[admin] 采集 start-gather → 置锁 → 将邑 gatherOpenDoor（失败回滚锁）
[admin] 采集 start-training → ticket(UUID 凭据) → commitTraining(modelName, finishNotifyUrl, finishNotifyId)
[将邑]   → finishNotifyUrl → gateway 公开面 → trade 三重防伪 → FINISHED
```

### 7.4 迁移与文件

- **V335** sku_jiangyi_link；**V336** jiangyi_device +industrial_control_model +gather_locked_at、jiangyi_model_deployment、jiangyi_training_ticket。
- trade 新增：JiangyiGatherClient / JiangyiGatewayClient / JiangyiClassesParser / JiangyiModelSyncService / SkuJiangyiLinkService / JiangyiGatherService / AdminJiangyiCatalogController；AdminJiangyiController +模型/采集面；JiangyiInternalController +model-confirmed / push-timeout / gather-finish-notify。
- gateway 新增：ModelPushController / GatherNotifyController / DeviceWebSocketHandler +updateModel+downloadModelNotify / CommandTracker +Kind.MODEL / SessionWatchdog +sweepModel。
- admin：DeviceJiangyiCard +模型同步/采集编排两区块；SkuJiangyiLinkDialog（搜索挂接/新增到将邑/回填 textName/解挂）+ SkuListView「将邑挂接」行操作。
- 模拟器：updateModel 接收→模拟下载→WS 上行 downloadModelNotify；JIANGYI_SIM_TEXT_NAME 按 classes 行号对照取 classId（JIANGYI_SIM_CLASS_ID_BASE 可配）。

### 7.5 单测

trade 60 用例（JiangyiClassesParserTest 8 / JiangyiModelSyncServiceTest 12 / SkuJiangyiLinkServiceTest 12 / JiangyiGatherServiceTest 12 / AdminJiangyiControllerTest 12 / DeviceValidationServiceTest 4）+ gateway DeviceWebSocketHandlerTest 8（updateModel 报文契约 / downloadModelNotify 宽容解析 / 心跳 / 未知消息忽略）。

### 7.6 待真机实锤清单

- classId 行号方向（0/1-based）→ classIdBase 可配 + 预览人工确认兜底。
- "76"/"88" 与主板对应关系 → 存原值字符串相等，不解释。
- downloadModelNotify msgContent 真实结构 → gateway 宽容解析（对象/字符串均取 modelName）。
- 采集期 WS 共存行为（采集开门时营业 WS 会话是否被将邑侧复用）。
- finishNotifyUrl 公网可达性（需部署侧 JIANGYI_PUBLIC_BASE_URL + 反代 /jiangyi/api/gather-finish-notify）。
- §4.4.3 学习是否真的集合粒度（无 productId）。

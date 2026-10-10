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
[设备]   downloadModelNotify(HTTP §4.2.5 主通道；WS 兜底) → gateway → trade model-confirmed → CONFIRMED + jiangyi_device 回填
[admin] 采集 start-gather → 置锁 → 将邑 gatherOpenDoor（失败回滚锁）
[admin] 采集 start-training → ticket(UUID 凭据) → commitTraining(modelName, finishNotifyUrl, finishNotifyId)
[将邑]   → finishNotifyUrl → gateway 公开面 → trade 三重防伪 → FINISHED
```

### 7.4 迁移与文件

- **V335** sku_jiangyi_link；**V336** jiangyi_device +industrial_control_model +gather_locked_at、jiangyi_model_deployment、jiangyi_training_ticket。
- trade 新增：JiangyiGatherClient / JiangyiGatewayClient / JiangyiClassesParser / JiangyiModelSyncService / SkuJiangyiLinkService / JiangyiGatherService / AdminJiangyiCatalogController；AdminJiangyiController +模型/采集面；JiangyiInternalController +model-confirmed / push-timeout / gather-finish-notify。
- gateway 新增：ModelPushController / GatherNotifyController / DeviceInfoController（§4.2.4/5/13，PDF 复读补缺）/ DeviceWebSocketHandler +updateModel+downloadModelNotify(兜底) / CommandTracker +Kind.MODEL / SessionWatchdog +sweepModel。
- admin：DeviceJiangyiCard +模型同步/采集编排两区块；SkuJiangyiLinkDialog（搜索挂接/新增到将邑/回填 textName/解挂）+ SkuListView「将邑挂接」行操作。
- 模拟器：updateModel 接收→模拟下载→HTTP POST downloadModelNotify（§4.2.5 对齐真机）；JIANGYI_SIM_TEXT_NAME 按 classes 行号对照取 classId（JIANGYI_SIM_CLASS_ID_BASE 可配）。

### 7.5 单测

trade 60 用例（JiangyiClassesParserTest 8 / JiangyiModelSyncServiceTest 12 / SkuJiangyiLinkServiceTest 12 / JiangyiGatherServiceTest 12 / AdminJiangyiControllerTest 12 / DeviceValidationServiceTest 4）+ gateway DeviceWebSocketHandlerTest 8（updateModel 报文契约 / downloadModelNotify 宽容解析 / 心跳 / 未知消息忽略）+ DeviceInfoControllerTest 6（getDetail / HTTP 回执转发 / identifier 错配以 token 为准 / 空 modelName 不转发 / trade 失败仍 200 / 视频模式默认 false）。

### 7.5.1 V16.0.0 PDF 原件复读修正（2026-10-10，继采集文档 PDF 教训后对 SAAS 对接文档同法核对）

V16.0.0（110 页纯扫描件 0 文字层，PyMuPDF 渲染 + 视觉阅读）对照 docx 转换稿/一期实现，**一致项**：WS 路径 `/websocket/device/{identifier}?token=`、心跳契约（机器发 heartBeat/PONG、服务端回 `{status:200,msgType:"heartBeat",msgContent:"PONG"}`）、updateModel msgContent `{quantity,modelUrl,textUrl,modelName}`、uploadLockState/uploadDoorState/addRecognitionGoodsToOrder 参数、token 端点 `/token/openDoorDeviceStatus`。**偏差与补缺**：

1. **downloadModelNotify 主通道是 HTTP 不是 WS**（§4.2.5）：设备 POST 商户服务器 `/deviceInfo/downloadModelNotify`（Authorization 头 + 参数 identifier/modelName，响应 `{status:200,data:"0"}`）。此前按转换稿走 WS 上行属误判；gateway 新增 `DeviceInfoController`（HTTP 主通道），WS 分支保留为宽容兜底；模拟器改为 HTTP 对齐真机。
2. **§4.2.4 `/deviceInfo/getDetail` 一期缺失**：真机取 token 后须调它换「设备编码（identifier）」再拼 WS 路径——一期只靠 token 响应 data.identifier 自描述是文档没有的宽容。已补端点；模拟器启动序列对齐（token→getDetail→WS，不一致以 getDetail 为准）。
3. **§4.2.13 `/deviceInfo/getArtificialCheckByIdentifier` 补 stub**：视频上传模式查询，默认 data=false（只传异常视频）。
4. 视频链路 **§4.2.6 `/aliYunOss/getTempUploadToken`（OSS STS）+ §4.2.14 `/device/pokerOrderVideo/uploadVideoUrl`（分片 serialNum/videoQuantity/videoUrls 上下摄像头逗号分隔）→ CB-024 已实现**（详见 7.6）：设备端阿里云 SDK 凭 STS 临时凭证直传商户 OSS 桶，视频流量不过我方服务器（easygo 旧模式=服务端中转 putObject，与将邑设备端协议不同构未采用）。
5. §4.2.9 上报货柜正在使用中路径实为 `/order/openDoubleDoorError`（doorPosition R/L/D）——gateway 已有同路径端点，语义=上一笔未结算时上报，非错误。
6. §4.2.11（进入大模型）与 §4.2.12（订单异常）**同一路径** `/device/pokerOrder/uploadOrderError`，靠 bigModel=doing 区分——gateway OrderErrorRequest 双字段归一实现正确。

### 7.6 待真机实锤清单

- classId 行号方向（0/1-based）→ **已实锤 0 起**（V16 第 98 页 §5.5.3 响应示例首商品 classId=0；1 起体系不会出现 0）。classIdBase 默认 0 与代码一致（AdminJiangyiController defaultValue="0"），预览人工确认兜底保留。
- "76"/"88" 与主板对应关系 → **用户拍板默认登记 "88"**（2026-10-10：采集文档第 44 页 §4.4.4.5 示例 rk3588/rk3576 两型号均为 "88"，是当前证据下最合理取值）。校验机制不变：设备登记值与模型值字符串相等，不解释语义；若真机首推校验失败，改登记值即可，无需改代码。
- ~~downloadModelNotify msgContent 真实结构~~ → **已由 PDF 原件核实**（§4.2.5：HTTP POST identifier+modelName），WS 分支仅兜底。
- 采集期 WS 共存行为 → **降级（2026-10-11）**：采集/学习为运营**现场**操作（人站柜机前），与顾客扫码撞车概率极低 ⇒ 不专门安排，**真机进场当天顺手测一次**即可（关注点：采集开门时营业 WS 会话是否被将邑侧复用）。
- finishNotifyUrl 公网可达性 → **已完成**（2026-10-10 阶段 0 穿透联调，提交 `fcac1942`）：花生壳内网穿透 `130954bm9ka83.vicp.fun` → `192.168.0.26:80`（gateway）；经穿透 `POST /jiangyi/api/gather-finish-notify` 返回 **200**（body `{"msg":"accepted","status":202}` 静默语义），WS 升级亦到达应用层。`JIANGYI_PUBLIC_BASE_URL` 已入 `infra/.env`（gitignore）并经 `docker-compose.full.yml` 透传给 trade（重建后 printenv 确认）；`jiangyi-gateway` 重建后公开面白名单生效（此前旧镜像对回调 401）。⚠️ 依赖穿透客户端在线（花生壳 502 = 客户端离线）；正式上线建议换固定公网域名/专线。
- ~~§4.4.3 学习是否真的集合粒度（无 productId）~~ → **已实锤：集合粒度**（2026-10-11 依据采集文档 p42-43 接口签名：`commitTrainingTenantOne` 请求体仅 modelName+finishNotifyUrl+finishNotifyId，**无任何商品参数**；产物 §4.4.4 = modelUrl + modelTextUrl 模型商品映射文件 + quantity 模型商品数量 ⇒ 触发学习即「整柜样本训练一个模型」，不存在单品下发选项）。
- **设备端视频保留策略/容量上限**（2026-10-10 新增）：默认只上传识别异常单视频，正常单视频留柜机本地、WS 按单拉取复核；但协议全文无「设备存储容量 / 保留时长 / 覆盖清理策略」任何描述（doc_full_text.txt 全文检索 0 命中）。真机进场须问将邑：柜机本地能存多少条/多少 GB、最老视频多久被清、拉取指令能否指定超过保留期的单。若保留期短而抽检需求长，评估改 §4.2.13 全量上传 + OSS 生命周期规则兜底。**OSS 侧清理方案已落** `docs/DEPLOYMENT_CHECKLIST.md §3.1.1`（`jiangyi-video/` 前缀**桶级生命周期规则**，由 OSS 服务执行、**不依赖**角色 `DeleteObject`；天数 N 待与本条一并定稿）。角色 `jiangyivideoupload` 已于 2026-10-11 补 `oss:DeleteObject`，但那只服务「运营手动删单条」的**服务端**调用，**当前未开放**（无业务需求，且删对象会让 `jiangyi_order_video` 台账变死链）；保留期清理**一律走生命周期规则**，见铁律 #68。
- ~~视频链路是否纳入范围~~ → **CB-024 已实现**（2026-10-10）：§4.2.6 getTempUploadToken = gateway OSS STS AssumeRole（aliyun-java-sdk-core CommonRequest，inline Policy 收窄到 PutObject 桶/dirName* 前缀，15 分钟会话）+ §4.2.14 uploadVideoUrl = gateway 转发 trade jiangyi_order_video 落库（V338，uk(order_no,serial_num) 幂等）。bucket=ai-cabinet-by（oss-cn-shenzhen，用户已建）。**部署配置已完成（2026-10-10 实测 ALL GREEN）**：RAM 子账号（只授 AliyunSTSAssumeRoleAccess）+ 直传角色 jiangyivideoupload（信任=本账号 root sts:AssumeRole，权限策略 jiangyi-video-put=oss:PutObject 仅限 ai-cabinet-by/jiangyi-video/*）；JiangyiVideoUpload ARN 已填 infra/.env JIANGYI_OSS_*，gateway 容器重建后 env 注入确认。实测：AssumeRole 真实签发 ✓ / jiangyi-video/ 内 PutObject 200 ✓ / 目录外 PutObject 403（收窄生效）✓。MinIO 转存归档属二期可选。

### 7.7 异常兜底实证：开锁未开门 / 指令无回执（2026-10-11 代码核证）

真机前进场前专项核证「锁开了但门没开 / 指令发了设备不回」是否被兜住——结论：**三层兜底，不卡单、不误扣款**。下表每条均**读代码确认**（非转述）：

| 层 | 位置 | 触发/机制 | 终态 |
|---|---|---|---|
| ① 网关 watchdog | `jiangyi-gateway` `SessionWatchdog.sweepOpen`（`@Scheduled` 每 10s） | 开门指令下发后 `OPEN_TIMEOUT_MS=15_000` 仍无任何设备回执（lockStatus/doorStatus 均未到） | `postOpenFailed("将邑柜开门指令15秒无设备回执")` → `SessionOpenService.markOpenDoorFailed` → **FAILED** |
| ②a 锁回执 | `DeviceReportController.uploadLockState`（§4.2.7） | `lockStatus=success` → `CommandTracker.complete(OPEN)` **撤销 watchdog**（2026-10-09 联调缺陷：漏补导致已开锁会话被 15s 腰斩，已修）；`fail` | `fail` → `postOpenFailed("将邑设备上报开锁失败")` → **FAILED** |
| ②b 门磁回执 | `DeviceReportController.uploadDoorState`（§4.2.8） | `doorStatus=success` = 用户拉门打开 → door-event OPEN（OPENING→SHOPPING）；`fail` = 拉门失败 | `fail` → **立即** `postOpenFailed("将邑设备上报拉门失败")` → **FAILED**（不等 300s CLOSE 兜底：§4.2.8「如果开门失败，不会上报订单结果」⇒ 必无后续识别上报） |
| ③ trade 兜底清扫 | `trade-service` `SessionExpireService.expireStaleOpeningSessions`（`@Scheduled(fixedRate=30_000)`） | 覆盖「锁开了人走了、设备什么都不报」：对 `{OPENING, CREATED}` 且 createdAt 早于 `openingSeconds`（**90s**）的会话 | `releaseIfFrozen` + `transition(CANCELLED)` + 报 `OPEN_TIMEOUT`(HIGH) |

**为什么不会误扣款**：`SessionOpenService.markOpenDoorFailed` 的状态 CAS 只允许 `CREATED/OPENING → FAILED`（`SHOPPING` 及之后一律跳过——防 watchdog 与识别链路赛跑腰斩，2026-10-09 实测教训已落码），且转 FAILED 时 `releaseIfFrozen` 释放预授权；扣款只发生在「识别结果 → 结算」链路，而拉门失败必然没有识别上报（§4.2.8 原文）。**设备「几秒后自动回锁」是将邑固件行为（协议未写秒数），我方状态机不依赖「锁回位」事件，对它完全透明。**

> 核证附带修一处陈旧注释：`uploadDoorState` 上原**叠了两块 javadoc**、均写「fail → WARN 不推进（watchdog 兜底）」，与实现（立即转 FAILED）矛盾，已合并为一块与代码一致的说明。

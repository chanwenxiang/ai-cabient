# 最终结论：旧云识别链与将邑能否关联（2026-10-11 复审）

> 触发：用户拍板——**只保留将邑端侧识别一种，旧云识别链不要，DeepSeek 也不要**；要求不采信日志/转述，亲读 PDF 原件 + 亲读源码后给最终结论。
> 本文所有证据均为本轮**亲手取证**：V16 原件（110 页扫描版）抽页亲读 16 页；采集文档原件（54 页扫描版）抽页亲读 2 页关键段；代码全部直接读源码（非子代理转述、非历史日志）。

---

## 1. 原文档亲验记录（本轮逐页看图核对）

**《将邑科技对接SAAS所需接口及流程V16.0.0.pdf》**（扫描版，pypdf 抽内嵌页图逐页读）：

| 原件页 | 核到内容 | 与代码 |
|---|---|---|
| p14 | setDomain（商户→将邑平台）：tenantId/secret/domain/socketUrl 全 M | ✓ `JiangyiOnboardingService` |
| p15–16 | §4.2.3 token：POST `/token/openDoorDeviceStatus`，req `{deviceSn M}`，resp `data:{token}` | ✓ `DeviceTokenController` |
| p17 | §4.2.4 getDetail：Authorization 头 + token 参数，resp `data:{identifier}` | ✓ `DeviceInfoController` |
| p18–19 | §4.2.5 downloadModelNotify：POST `/deviceInfo/downloadModelNotify`，`identifier+modelName`，resp `data:"0"`；「模型下发成功后机器端会重启」 | ✓（HTTP 主通道+WS 兜底） |
| p19、21 | §4.2.6 OSS：`data={accessKeyId,accessKeySecret,securityToken,endpoint,bucketName,dirName}`（六字段逐字） | ✓ `AliyunOssController` |
| p22 | §4.2.7 uploadLockState `{orderNo, lockStatus M success/fail}`，resp `data:"0"` | ✓ |
| p22–23 | §4.2.8 uploadDoorState 同构；**原文「如果开门失败，不会上报订单结果」** | ✓（fail 即时 FAILED 的依据） |
| p25 | §4.2.10 addRecognitionGoodsToOrder：`{orderNo M, forms O}`；forms=`{classId Integer M, quantity Integer M, specialId String C}` | ✓（specialId 不采信为既定决策） |
| p27 | §4.2.11/12 uploadOrderError 同路径：`bigModel M "doing"/不设置=异常订单`，errorMsg O | ✓（双字段归一） |
| p29 | §4.2.13 getArtificialCheckByIdentifier：`data boolean M`（true=全部上传 false=只传异常视频；json 示例笔误 `data:"0"`，以参数表为准） | ✓ stub `data:false` |
| p30 | §4.2.14 uploadVideoUrl：`{orderNo M, serialNum M, videoQuantity M, videoUrls Array M}`——上下摄像头地址逗号隔开，失败示例 `[,]`/`[url,]` | ✓（空项=失败位，split limit=-1 保尾随空项） |
| p38 | §4.3.1 WS：`wss://…/websocket/device/<identifier>?token=`；心跳机器发 `{msgType:"heartBeat",msgContent:"PONG"}` 服务端回 `{status:200,…,"PONG"}`；心跳断开=离线 | ✓ `DeviceWebSocketHandler` 逐字一致 |
| p39–41 | §4.3.2 指令：reboot / 强制开门(可选,空内容) / 强制双门(可选) / 补货开门 `sendOpenStockDoor`(msgContent=补货单号，**补货视频同购买视频获取方式**) / 关门(可选) / 购物开门 `openDoor`(msgContent=订单号,商户生成唯一) / 双门购物 | ✓ 一期只接购物开门，其余明确边界 |
| — | §4.3.2.9 updateModel `msgContent={quantity,modelUrl,textUrl,modelName}`（转换稿核对+联调实证，本轮未重读该页，非承重项） | ✓ |

**《将邑科技商品采集接口文档(移动端)v1.13.0(1).pdf》**（54 页扫描版）：

| 原件页 | 核到内容 | 结论 |
|---|---|---|
| p42 | §4.4.3 开始学习 `/merchant/selfhelpGatherProduct/commitTrainingTenantOne`：参数表只有 `finishNotifyUrl`（回调 body `{"finishNotifyId","msg"}`）+ `finishNotifyId`；请求示例另带 `modelName`；响应无参数。**无任何商品/图片参数** | 学习=集合粒度，实锤 |
| p43 | §4.4.4 查询模型 `/merchant/modelFile/getModelFileByApi`：产物=`modelUrl`+`modelTextUrl`（模型商品映射文件）+`quantity`+`industrialControlModel`（固定值 "76"/"88"） | **识别模型由将邑云端训练后下发到柜机（rknn），推理在设备端** |

> 采集文档商户面 API 全集（转换文本正则提取）：`tenantGather`（采集）、`selfhelpGatherProduct`（学习）、`stdSku/getList|getStdSkuQueryByTextNames`（标准商品）、`modelFile/getModelFileByApi`（模型）、`tenantGather/picSubmit|picDelete`（采集图片）——**全部是「采→学→模型」闭环，不存在任何「商户服务器做云端图片识别」的接口**。

**这最后一行就是本判定的事实根基：将邑体系里，商品图片只流向将邑（采集上传），识别在柜机端 rknn 模型上推理，商户服务器只收 `classId+quantity` 结果——云端识别引擎在我们这边没有任何可接入点。**

## 2. 核心问题的直接回答

**「旧云识别链的模块和将邑的可以关联吗？」**

分两层：

### 2.1 可以关联——而且**已经关联上了**（这些是两链共用的地基，必须保留）

| 共用模块 | 证据 | 角色 |
|---|---|---|
| `POST /internal/v1/vision/edge-results`（`VisionInternalController` → `VisionResultIngestService`） | gateway `TradeInternalClient.java:173` 亲验打到此路径 | 将邑识别结果的**唯一受理入口** |
| `VisionRecognitionResultDto`（common-core） | `RecognitionNormalizer` 产出该 DTO | 将邑归一化结果的载体 |
| 会话状态机 `RECOGNIZING→SETTLING→COMPLETED` 与结算/扣款/库存/争议全链 | `SessionSettleService.doCompleteAsyncRecognition`（亲读：带结果直接 `processRecognitionResult`，**不调 vision-service**） | 结算大脑完全复用 |
| 争议链（DISPUTED/争议工单/三端展示） | `DisputeRequiredException` 分支 | 复用 |
| 三端展示（订单明细/判定明细/「识别参考≠扣款」分层） | 前轮已核 | 复用 |

即：**"链路的下半段"（结果受理→结算→资金→争议→展示）就是将邑在用的地基；旧链真正独有的只有"上半段"（云端图片识别引擎及其配套）**。

### 2.2 不可关联——协议互斥 + 你已拍板不要（删除清单见 §3）

- **云端识别引擎本身**（vision-service `/recognize*`、mock/quectel 工厂、YOLO 等死 ML 模块、训练装置）：将邑图片根本不经过我方服务器，无图可识别。
- **DeepSeek**（`/suggest-class`、`/dispute-suggest` + admin「智能识别建议」UI）：你已拍板不要。
- **旧 YOLO/阿里云映射**（`sku_vision_mapping` 表 + CRUD + 内部映射端点）：将邑映射在 `jiangyi_class_mapping`/`sku_jiangyi_link`，两套并存只会诱导配错表=静默不参与结算。
- **旧边缘视频链**（android-app 录像→MinIO→`video_uri`→门事件透传→旧播放端点）：将邑视频走 OSS `jiangyi-video/` 台账（§4.2.6/§4.2.14），互不相通。
- **补货快照识别**（`RestockSnapshotService.recognizeInventorySnapshot`）：将邑补货开门（二期）按原文 §4.3.2.4「补货视频同购买视频获取方式」，不走云识别。

## 3. 退役清单（逐模块，全部本轮亲验）

### A. 保留（将邑地基，勿动）
`VisionInternalController.edge-results`、`VisionResultIngestService`、`VisionRecognitionResultDto`、`SessionSettleService.completeAsyncRecognition`、状态机/结算/争议链、`jiangyi_order_video`+OSS presign 读路径、`jiangyi-gateway` 全部。

### B. 删除（旧链独有；按依赖排序，每批可独立过 CI）

| 批次 | 删除对象 | 亲验证据 | 连带处置 |
|---|---|---|---|
| **批1 入口封死** | 非 `isJiangyi` 设备的开门/会话入口改为 409「仅支持将邑柜机」（或冻结）；`handleDoorEvent` 的 `settleAfterClose` 同步分支随之死亡 | `SessionDoorService.java:77-93`（jiangyi 豁免亲读） | 模拟器旧 MQTT 模式退役为 jiangyi-only；`consumer-h5-uat.mjs:457-480` 与争议种子脚本切 `JiangyiDeviceSimulator`（铁律 62：删功能必须同步删钉住它的用例） |
| **批2 结算同步识别** | `SettlementSettleOrchestrator.settle` 的 `visionClient.recognize` 分支（:66）与 `withGravityFallback`；重力兜底 | 亲读 | 保留 `processRecognitionResult`（异步路径在用）；相关单测重写为 edge-results 驱动 |
| **批3 vision-service 整体** | `vision-service/` 目录（/recognize、/recognize/upload、/recognize/async 假端点、mock/quectel 工厂、Kafka worker、yolo/hybrid/delta/deepseek 死模块、training/、scripts/、requirements-ml.txt）+ 四份 compose 的 vision-service 定义 + `AICABINET_VISION_SERVICE_URL`/`VISION_API_KEY`/`RECOGNIZER_BACKEND`/`VISION_MOCK_ENABLED` env + prometheus vision 告警 + `VisionServiceClient` + `VisionMappingInternalController` | 亲验：仅 5 个调用方（§4） | ⚠️ **Kafka/Redpanda 不能删**：`NotificationDispatchListener/Producer` 在用（亲验）；MinIO 不能删：争议证据图/OTA CDN/媒体资产在用（亲验），只摘 `cabinet-videos` 会话视频用途 |
| **批4 DeepSeek + 识别测试** | `OpsRecognitionController` 三端点（recognition-preview / recognition-upload / dispute-suggest）+ `RecognitionTestService` + admin `DisputeListView`「智能识别建议」UI + endpoints 死常量 + DeepSeek key 配置 | preview/upload 前端零调用（grep 亲验）；dispute-suggest 仅 `DisputeListView:755` 消费 | 删 UI 时同步删 `disputeSuggestHint` 块 |
| **批5 旧映射/入驻** | `VisionMappingAdminController`（yolo/aliyun CRUD）+ `sku_vision_mapping` 表 + `SkuVisionEnrollmentService/Controller` + `VisionMappingService` + `VisionMappingInternalController` + `SkuCatalog.yoloClassName/visionEnrollmentStatus/referenceImageUrlsJson` 列 + 拍照盘点 `applyVisionCounts`（OpsWarehouseController:129 + WarehouseStocktakeService + admin WarehouseStocktakeDialogs `@stocktake-photo`）+ admin `endpoints.ts` skuVision*/visionMappings 死常量与路径清单条目 | VisionMappingView 已纯将邑化（亲验 grep 只调 deviceJiangyi*）；skuVision*.vue 零调用（亲验）；**DemoDataService 也种 vision 映射需同步摘除**（2026-10-11 深挖补充） | V339 迁移：先 `CREATE TABLE *_bak AS SELECT` 备份再 DROP 表/列；`check-edge-vision-wired` 门禁随 SkuDeltaCalculator 决策一并处置；删除 VisionServiceClient 时 records（RecognitionResult/RecognizedItem）迁 `trade/recognition` 新家并全仓 sed |
| **批6 旧视频链** | android-app 录像上传（CabinetController 录像段/MinioUploader/OfflineUploadQueue/TradeVideoClient/VideoClipJson）+ device-service 门事件 `videoUri/uploadStatus/videoClipsJson/cameraFusionMode` 透传 + `SessionDoorService` 写 `video_uri` + `SessionService.attachVideo` + `SessionInternalController /internal/v1/sessions/video*` + `/ops/sessions/{sessionId}/video`（AdminDashboardController:295）+ admin `useSessionVideo` blob 回退 + merchant `video.vue` EDGE 源 + `AdminSessionDto.videoPreviewUrl` 死字段 | 全部亲验（grep 定位到文件行号） | 若 EDGE 柜确认已无在册，可一次性删；`shopping_session.video_uri` 历史行**保留只读**（DisputeService 将邑台账优先已在前，不影响将邑单），不做清列迁移 |

**落地进度（2026-10-17 更新，就地追加）**：批 1 ✅（`ensureJiangyiOnlyShopping`，mock 环境豁免闸口本身）·批 2 ✅ `chore/dc27-remove-vision`（分支）批 2 提交 ·批 3 ✅ `d83a9380`（151 文件 +242/−8003）·批 4 ✅ ·批 5 ✅（V339/V344 已落）·**批 6 ✅ 主体落地**：6-A `cd74d31c`（旧录像链下游死代码）· 6-B `5c80a4c9`（模拟器上传链 + 门事件 5 字段）· 6-C `fac98345`（用户拍板「全部撤下」历史 `video_uri`/`upload_status` 运营可见面：工作台卡片与文案、列表筛选与 CSV 列、`AdminSessionDto` 三字段、`applyVideoMetadata` 写入侧、商户端 EDGE 死链；**DB 四列与历史行不清**）· 6-D `f30635ba`（vision-async Kafka 传输链整体删除：Producer/Listener/`SettlementVisionAsyncService`/`VisionAsyncProperties`/视觉 topic 全撤，`@EnableKafka` 改挂 `aicabinet.notify.async-enabled`——顺带修掉「`NotificationDispatchListener` 即便 `NOTIFY_ASYNC_ENABLED=true` 也不注册消费」的静默失效；识别结果现役唯一入口 = HTTP `POST /internal/v1/vision/edge-results`）。批 6 剩余（`MinioVideoService` 死注入、异常扫描族 `UPLOAD_STUCK`、reserved Kafka SCRAM 用户 `vision` 等待拍板项）见 [AUDIT_LEDGER_2026-10-13.md](AUDIT_LEDGER_2026-10-13.md) 「批 6 剩余」行。批 2 的实测口径与批 3 的清单见 [AUDIT_LEDGER_2026-10-13.md](AUDIT_LEDGER_2026-10-13.md) 文末「2026-10-17 DC-27(b) 批 2/批 3 落地」。批 2 落地内容比原表多两件事：`settlement.recognition_mode` 与 `aicabinet.gravity-fallback-settle` 两个开关整键删除（V385 + application.yml/4 份 env/compose/`ops/feature-flags.json`），以及 `GravitySettlementHelper` 只保留取证/展示 API（`mergeGravityJson`/`parse`/`fromRequestItems`/`toRecognizedItems`/`hasSlotSpecificDeltas`——门事件、实时购物车、补货货道快照在用），删掉 `mergeWithVision`/`reconcileWithGravity`。

### C. 历史数据（默认建议，可单独拍板）
- `shopping_session.video_uri` 历史行：保留只读（删链不删数据，争议回看旧单仍可用；将邑单走台账不受影响）。
- `sku_vision_mapping` 表与 sku_catalog 三列：随批 5 drop（先 `CREATE TABLE xx_bak AS SELECT` 备份）。
- Kafka topic `aicabinet.vision.recognize.request/.result/.dlt`：无人生产/消费后自然废弃，无需迁移。

## 4. 风险与次序约束

1. **批 1 必须最先**：入口不封，删引擎会让遗留旧设备会话落入「RECOGNIZING 挂起→10min 超时→DISPUTED」——fail-closed 不误扣款，但会产生争议噪音；封入口后天然无此流量。
2. **批 3 依赖批 1/2**：`VisionServiceClient` 在 settle 分支摘除前不能删。
3. **UAT/CI 同步**：`consumer-h5-uat`（force-need-review 开关失效）、`create-open-dispute.ps1`、`e2e-vision-*` 系列脚本随批 1 切 jiangyi 模拟器或标退役；`check:audit-gates` 内 `check-edge-cloud-mqtt-contract`/`check-edge-vision-wired` 两个门禁的判据随删码同步收窄。
4. **compose 修正（前轮 D1/D2）并入批 3**：vision-service 从栈里摘除的同时，`jiangyi-gateway` 补进 `docker-compose.apps.yml` 基座（否则叠加栈 `/jiangyi/` 502 的缺口依旧）。

## 5. 结论一句话

**旧链与将邑唯一能"关联"的，是已经共用的下半段（edge-results 受理→状态机→结算→争议→三端展示）——将邑今天就跑在上面；旧链的上半段（云端识别引擎、DeepSeek、旧映射、旧视频、重力兜底）与将邑协议互斥（图片不经过我方服务器、识别在柜机端 rknn 推理），无任何接入点，按你"只要将邑一种"的拍板应整体退役，共 6 个批次，入口封死先行，Kafka/MinIO 因另有用途保留。**

执行令已于 2026-10-17 下达并按 §3 批次动工（隔离分支 `chore/dc27-remove-vision`，每批独立提交+门禁+CI 绿，跑完再合 dev）；进度见上方「落地进度」。

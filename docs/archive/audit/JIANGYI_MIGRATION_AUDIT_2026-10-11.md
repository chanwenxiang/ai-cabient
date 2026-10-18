# 将邑迁移后三端审计报告（2026-10-11）

> 触发：用户要求——识别已全面切将邑端侧，旧识别全废弃；重新审计三端代码、对照将邑文档、实跑测试、保障三端口径/数据/接口一致、确认无大 bug。
> 方法：主审逐条对照（协议↔代码）+ 两路只读子代理扫查（旧链残留 / 三端一致性）+ 全部结论**源码与实跑复核**（子代理自报不采信，关键项均二次验证）。关键 P1/P2 修复已在本次会话落码并验证。

---

## 0. 结论速览

| 维度 | 结论 |
|---|---|
| 将邑协议契约（V16 §4.2 HTTP 面 + §4.3 WS 面） | **全部一致** ✓（逐条对照表见 §2） |
| 三端口径/数据/接口（admin / consumer-mp / merchant-mp + shared-types） | 主体一致 ✓；发现并**已修** 1 个 P1 功能 bug、1 个 P2 文案口径漂移、2 个 P3 术语残留（§6）；余项入修复队列（§7） |
| 旧识别/视频链路残留 | **仍有双轨残留**：5 条 D 级（需要拍板/排期，§7.1），其中 2 条是部署面缺口（叠加栈缺 jiangyi-gateway、生产钉死已退役识别后端） |
| 测试实跑 | gateway **58/58**、trade 将邑面 **73/73**（修复后含新增 2 用例 **38/38**）、admin **88** + vue-tsc 绿、consumer-mp **133** + tsc 绿、merchant-mp **159** + tsc 绿，全绿 |
| 大 bug | 「新增到将邑商品库」按钮**从未成功过**（恒 400）——已修（§6.1）。除 §7 D 级部署/架构项外，未发现其它「主链路不可用」级缺陷 |

---

## 1. 范围与方法

- 三端：`clients/admin-vue`（运营后台）、`clients/consumer-mp`（消费者小程序，mp-weixin 唯一验收权威）、`clients/merchant-mp`（商户小程序）；后端 `services/trade-service` + `services/jiangyi-gateway`（:8083）；共享 `packages/shared-*`。
- 协议文本：`将邑对接资料/doc_full_text.txt`（V16.0.0 SAAS 对接 + 采集文档转换文本；采集文档主体为**纯扫描件无文字层**，其结论以 `docs/JIANGYI_GATEWAY_DESIGN.md §7.5.1/§7.6` 的 PDF 逐页视觉核对记录 + `将邑对接确认记录.txt` 为准）。
- 基线：`dev @ bd276aa7`（工作树干净）。审计期间发现的缺陷修复以独立提交落码。

## 2. 将邑协议 ↔ 代码契约逐条对照

设备面路径按 V16 文档「domain+文档路径硬拼」，gateway 以 `/jiangyi/api` 为 domain 尾段（nginx `/jiangyi/`、`/websocket/` 保留前缀转发）。信封一律 `{status:200, msg:"success", data:"0"}`。

| V16 条目 | 文档路径/契约 | 实现 | 一致 |
|---|---|---|---|
| §4.2.3 获取 token | POST `token/openDoorDeviceStatus`，req `{deviceSn}`，resp `data:{token}` | `DeviceTokenController`（+`/jiangyi/api/token` 别名）；未登记 404 / 未绑定 403 / 密钥未配 503 fail-closed；`data` 附带 `identifier`（§4.2.4 亦有独立端点，宽容不冲突） | ✓ |
| §4.2.4 获取设备编码 | POST `devicelnfo/getDetail`，resp `data:{identifier}` | `DeviceInfoController.getDetail`（identifier 以 token 解出为准） | ✓ |
| §4.2.5 模型回执 | POST `devicelnfo/downloadModelNotify`，`identifier+modelName`，resp `data:"0"`；空 modelName 允许 | `DeviceInfoController.downloadModelNotify`（HTTP 主通道；WS 分支宽容兜底；空名只记日志不转发仍 200） | ✓ |
| §4.2.6 OSS 直传凭证 | POST `/aliYunOss/getTempUploadToken`，`data:{accessKeyId,accessKeySecret,securityToken,endpoint,bucketName,dirName}` | `AliyunOssController` + `OssStsService`（STS AssumeRole，inline 收窄 `jiangyi-video/*` PutObject；未配置 500 fail-closed）——字段名逐字一致 | ✓ |
| §4.2.7 开锁状态 | `device/uploadLockState` `{orderNo, lockStatus:success/fail}` | `DeviceReportController`：success→`complete(OPEN)` 撤销 watchdog；fail→`postOpenFailed` 即时 FAILED | ✓ |
| §4.2.8 拉门状态 | `device/uploadDoorState` `{orderNo, doorStatus:success/fail}` | success→door-event OPEN + 登记 CLOSE 300s 兜底；fail→**立即** postOpenFailed（§4.2.8「开门失败不上报订单结果」） | ✓ |
| §4.2.9 双门占用 | `order/openDoubleDoorError` `{orderNo, doorPosition R/L/D}` | 同路径，审计日志（可选接口） | ✓ |
| §4.2.10 识别结果 | `orderProduct/addRecognitionGoodsToOrder` `{orderNo, forms:[{classId,quantity,specialId}]}` | 同路径；先合成 door-event CLOSED（模式一无关门上报）→ `RecognitionNormalizer` 归一（未命中 fail-closed→DISPUTED；specialId 不采信为既定决策）→ 转发 edge-results 结算 | ✓ |
| §4.2.11/12 大模型/订单异常 | 同路径 `device/pokerOrder/uploadOrderError`，`bigModel/BigModel="doing"` 区分 | 双字段归一（大小写两存）；doing→挂起 10min 兜底；异常→recognize-timeout 转 DISPUTED | ✓ |
| §4.2.13 视频模式查询 | `deviceInfo/getArtificialCheckByIdentifier` | stub `data:false`（只传异常视频，既定一期边界） | ✓ |
| §4.2.14 视频分片上报 | `device/pokerOrderVideo/uploadVideoUrl` `{orderNo,serialNum,videoQuantity,videoUrls[]}`，空数组=该片失败 | `OrderVideoReportController`→trade `uk(order_no,serial_num)` 幂等落库（V338）；空数组保留失败位；split 带 `limit=-1` 保尾随空项 | ✓ |
| §4.2.20 故障单 | `order/uploadFaultOrder` `{errorMsg,orderNo}` | 同路径仅审计（一期边界，watchdog 兜底） | ✓ |
| §4.3.1 WS 心跳 | 机器发 `{msgType:"heartBeat",msgContent:"PONG"}`，服务端必须回 `{status:200,msgType:"heartBeat",msgContent:"PONG"}` | `DeviceWebSocketHandler` 回发一致 + 心跳节流 60s 桥接 trade 在线态 | ✓ |
| §4.3.2.7 购物开门 | `{msgType:"openDoor", msgContent:"订单号"}`（商户生成、唯一） | `sendOpenDoor` msgContent=sessionId（枢纽：orderNo≡sessionId 免映射） | ✓ |
| §4.3.2.9 模型下发 | `{msgType:"updateModel", msgContent:{quantity,modelUrl,textUrl,modelName}}` | `sendModelUpdate` 字段一致；600s tracker 超时→FAILED 幂等 | ✓ |

其余 WS 指令（reboot / 强制开门 / 补货开门 / closeDoor / 双门购物）为一期明确边界：强制开门空 msgContent 结构性拒绝、补货开门真路由列二期——与设计文档 §5 记录一致，不算偏差。

## 3. 三端口径 / 数据 / 接口一致性

**核对一致 ✓（要点）**：

1. **录像链路**：服务端统一下发 `videoPreviewUrl`（MinIO presign 优先 → 将邑台账 `jiangyi_order_video` 回退）——admin 争议/异常工作台、merchant 争议/购物视频页均已消费；订单侧走「柜机取货视频」弹窗（`ops:device:video` 独立授权点）；consumer-mp 按 CB-030 干净下线（全端零视频引用）。
2. **shared-types 与后端零漂移**：`OpsExceptionDto.videoPreviewUrl`、`DisputeTicketDto` 36 字段、AdminJiangyi/AdminJiangyiCatalog 全部路径、merchant gather 三端点、`OrderVideoPlaylistDto`、`JiangyiOrderVideo.videoUrls` 均已生成且字段对齐（fe6d0557 生效）。
3. **采集模式同一套闸门**：admin `DeviceJiangyiCard` 与 merchant `device-detail` 最终都进 `JiangyiGatherService.startGather/exitGatherMode`；营业开门 409 由 `DeviceValidationService.isGatherLocked` 统一闸断。
4. **SKU↔将邑挂接**：`SkuJiangyiLinkDialog` 与 `AdminJiangyiCatalogController` 端点/字段一一对应。
5. **识别结果展示**：三端统一「识别参考 ≠ 最终扣款」分层（admin reviewCode 词表 / merchant 识别参考明细 / consumer 后端 consumerCopy+amountDiffNote）；无数值置信度冲突（归一化 confidence 恒 1.0，仅作链路标记不下发前端）。
6. **金额口径**：双端共用 `shared-uni/fmtMoney`（分→元），订单金额以支付流水为准。

**发现（已修见 §6，余项见 §7）**：P1 主图恒 400（§6.1）、P2 采集锁 409 文案被消费端误分类（§6.2）、旧术语残留（§6.3/6.4）、`AdminSessionDto.videoPreviewUrl` 下发但前端零消费且无将邑兜底（双重死字段，§7.3）、争议/异常**列表**逐行 presign+将邑台账查询的 N+1 外部调用（§7.2）、旧 YOLO/阿里云映射后端面+前端死常量并存（§7.1-5）。

## 4. 旧识别/视频链路残留（全仓扫查结论）

总体形态：**刻意保留的双轨**。将邑链只服务 `jiangyi_device` 登记柜机；旧云识别链（MQTT 关门 → 同步 HTTP 调 vision-service）对**非将邑设备**（android-app EDGE 柜、CAB-001 模拟器）仍端到端活着且会真实扣款；vision-service 被重新定位（YOLO 工厂已移除，仅剩 mock/quectel stub + DeepSeek 辅助，`/suggest-class`、`/dispute-suggest` 为 2026-10-06 拍板保留），但其 Kafka worker、死 ML 模块、训练装置、旧链 e2e 脚本仍大面积钉着。

详见 §7.1 D 级清单（5 条，需拍板/排期）与 §7.3/§7.4 C 级清理项。**本次未擅自动任何旧链下线**——按铁律 58（竞品台账结论冲突时正确处置可能是下线，须先回读台账）与「删功能必须同步删钉住它的用例与入口」（铁律 62），D 级项应走显式决策后批量清理。

## 5. 测试实跑（本机，`dev @ bd276aa7` + 修复提交）

| 套件 | 结果 |
|---|---|
| `jiangyi-gateway` 全量单测（`mvn -o -pl services/jiangyi-gateway -am test`） | **Tests run: 58, Failures: 0, Errors: 0, Skipped: 0** |
| trade 将邑面 8 类（Jiangyi*/AdminJiangyi*/SkuJiangyiLink*/DeviceValidationServiceTest） | **73/73 绿** |
| 修复后复跑（SkuJiangyiLinkServiceTest 含新增 2 用例 + AdminJiangyiControllerTest + JiangyiGatherServiceTest） | **38/38 绿** |
| admin-vue：vue-tsc + vitest | type-check 绿 + **88/88** |
| consumer-mp：vue-tsc + vitest | type-check 绿 + **133/133** |
| merchant-mp：vue-tsc + vitest | type-check 绿 + **159/159** |

（`DeviceAuthIT` 等 Testcontainers 用例按设计在无 Docker 场景 skip；CI 集成 job 每次推送真跑。）

## 6. 本次已修（均源码复核 + 实跑验证）

### 6.1 P1「新增到将邑商品库」恒 400（功能 bug，已修）

- **实锤**：`SkuJiangyiLinkDialog.vue` 的 `createForm.mainImg` 恒为空串（无输入控件），提交发 `mainImg: ''`；后端 `CreateRequest.mainImg` `@NotBlank` → **该按钮从未成功过**，且无任何测试覆盖此路径。
- **修法**（三层）：① 弹窗 `open(row)` 预填我方 SKU 主图（`imageUrl`）+ 新增可编辑主图输入（带预览）；② 后端 `@NotBlank` 放开为可选，`SkuJiangyiLinkService.createInJiangyi` 空值时回退 SKU `imageUrl`（将邑侧主图必填 M，采集文档 §4.1.5），两边都空 409 明示「先在商品管理登记图片」；③ 顺带修复同页另一必 400 源：`catId` 原先要求运营手填「将邑分类编号」——现消费既有 `GET /jiangyi/product-categories` 端点做下拉（加载失败降级手填）。
- **验证**：新增 2 单测（回退 SKU 主图捕获请求体断言 / 双空 409 且不调将邑），trade 38/38 绿；admin vue-tsc + 88 用例绿。

### 6.2 P2 采集锁 409 文案三方漂移（已修）

- **实锤**：后端 409 文案「设备商品采集中，暂停营业，请稍后再试」含「暂停营业」，被 `shared-uni/classifyOpenError` 命中 `/暂停营业/` → 消费者看到「柜机已暂停营业」（误以为被锁机/故障）；商户端说「顾客无法开门购物」、admin 入驻页说会提示「设备商品采集中」，均与实况不符。
- **修法**：`classifyOpenError` 增加专属 `device_gathering`（`/商品采集|采集中/`，**先于** 暂停营业 判定），文案「柜机正在商品采集上新，暂停开放，请稍后再试或换一台」；consumer 首页 `landingErrorTitle` 补标题分支、`deviceOffline` 判定把采集视为「在线但暂不可开」；admin 入驻页 FAQ 引导文案改为与消费者实况一致。
- **验证**：consumer/merchant vitest 133/159 全绿 + 双端 tsc 绿。

### 6.3 P3 术语口径两处（已修）

- admin 会话列表 RECOGNIZING 提示「视觉识别处理中」（旧边缘术语）→「识别商品中」（对齐 shared-dict 单源）。
- 其余术语残留（merchant 采集卡「采集中 · 停止营业」vs admin「采集中」、未计费文案三端三样、「智能识别建议」实为 DeepSeek 辅助）保留现状，见 §7.4。

## 7. 修复队列（待拍板/排期，本次未动）

### 7.1 D 级（建议尽快决策）

| # | 问题 | 证据 | 建议 |
|---|---|---|---|
| D1 | **staging/production 叠加栈缺 `jiangyi-gateway` 服务定义**，而 nginx（`nginx.compose.conf:143-158`）照常把 `/jiangyi/`、`/websocket/` 路由到 `jiangyi-gateway:8083` → 拉起即 502，将邑设备面整体不可用；旧 vision-service 却照常部署 | `docker-compose.full.yml:287`（仅 full 有）；apps/staging/production grep 0 命中 | 把 jiangyi-gateway 服务定义搬进 `docker-compose.apps.yml`（含 `JIANGYI_JWT_SECRET`/OSS env），或显式声明这些栈暂不支持将邑柜 |
| D2 | **production compose 把生产钉死在已退役的旧识别后端**：`RECOGNIZER_BACKEND:?set (not mock)`（工厂只剩不可用的 quectel stub）+ vision 侧 `KAFKA_ENABLED:=true`（而 trade `vision-async.enabled` 默认 false，无人生产 topic） | `docker-compose.production.yml:31-42`；`factory.py:14-25` | 识别主链已切将邑 ⇒ vision-service 生产定位收窄为「DeepSeek 辅助 + 盘点/识别测试」：允许 `MOCK_ENABLED`（或 mock recognize）+ 关 Kafka，或干脆从生产栈摘除 vision-service |
| D3 | **android-app（EDGE 柜）「录像→MinIO→门事件→同步旧 vision 结算」整链仍活且会真扣款**；stub 后端下全部 need_review→争议 | `CabinetController.kt:125-229`、`TradeVideoClient.kt:26-97`、`SessionSettleService.java:74-82` | 若现场已无 EDGE 柜：冻结入口+摘除结算分支（须同步删钉住用例）；若仍有：显式声明双轨并在台账登记 |
| D4 | **UAT/种子链钉死旧链**：`consumer-h5-uat.mjs:457-480` 依赖 vision mock `force-need-review` 完结 CAB-001（模拟器）会话；`create-open-dispute.ps1`+three-end 争议 UAT 依赖「模拟器+MQTT+vision」造数 | 同左；CI 内已是 0 断言假绿 | 改用 JiangyiDeviceSimulator 造数或补 REST 等价种子；与 D3 一并拍板 |
| D5 | **旧 YOLO/阿里云识别映射管理面仍在线**：`VisionMappingAdminController`（yolo/aliyun CRUD 无调用方）、admin `endpoints.ts:799-809` 死常量、`VisionMappingInternalController`+`mapping_client.py` 只喂 dev mock——与新 `JiangyiClassMapping` 并存，配错表=静默不参与将邑结算 | 同左 | 排期下线：删前端死常量、后端 yolo/aliyun 端点标 `@Deprecated`→删，权限位收紧；保留 dev mock 所需最小面 |

### 7.2 P2（性能/口径）

- **争议/异常列表 N+1 外部调用**：`DisputeService.listTickets`→`toDto` 逐行 `presignPlaybackUrl`（MinIO）→空则 `jiangyiVideoFallbackUrl`（查台账 + **gateway 真实网络 presign**，`JiangyiOrderVideoService.playUrlItem` 每分片一次）；`OpsExceptionService.toDto` 同构。列表 20-50 行 → 数十次 HTTP/STS 调用，gateway/OSS 抖动直接拖垮列表。建议：列表 DTO 省略 `videoPreviewUrl`（详情/单行操作才签发）或 PlayUrl 加短 TTL 缓存；`/merchant/exceptions` 下发该字段但商户端零消费，可裁剪。
  - ⚠️ 修前列 must 与 admin 列表行内播放交互对齐（DisputeListView 当前行直接消费 previewUrl）。

### 7.3 P3（卫生）

- `AdminSessionDto.videoPreviewUrl`：后端下发（且无将邑兜底）、三端零消费 → 删字段或补台账兜底后明确消费方。
- admin `endpoints.ts:242-254` skuVision/visionMapping 系死常量（与 D5 同批）。
- 文案：「智能识别建议」实为 DeepSeek 关键帧辅助，改名防与将邑识别混淆；「采集中」admin/merchant 措辞统一；未计费文案三端收敛 shared-uni 常量。
- `SkuJiangyiLinkDialog` 手写类型改导 shared-types `JiangyiSkuJiangyiLink`；`SkuListView` 透传遗留 `yoloClassName` 清理。
- vision-service 死 ML 模块/训练装置/`/recognize/async` 假端点；`prometheus` vision 模型告警；`scripts/` 旧链 e2e 工具标退役（与 D3/D4 同批）。

### 7.4 已知非缺陷（核实为刻意设计，勿「修」）

- merchant-mp 购物视频双源（EDGE 旧字节流 + JIANGYI 台账）是 CB-030 有意共存降级；vision-service `/suggest-class`、`/dispute-suggest` 是 2026-10-06 拍板保留的 DeepSeek 辅助；staging `RECOGNIZER_BACKEND=quectel` 起不来是「fail-loud」定案；consumer H5 非验收面（mp-weixin 唯一权威）。

---

## 8. 台账回填

- 本报告：`docs/JIANGYI_MIGRATION_AUDIT_2026-10-11.md`
- PROJECT_KNOWLEDGE Changelog：2026-10-11 行
- lessons-learned：#294（表单恒发空串 + 后端 @NotBlank = 永远 400，新按钮必须端到端点按一次）
- WorkBuddy：`.workbuddy/memory/2026-10-11.md` 追加会话小结

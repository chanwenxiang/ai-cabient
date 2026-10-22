# 将邑对接接口清单（逐条对账：文档 ↔ 我方实现 ↔ 还差什么）

> 生成 2026-10-21。用途：回答「对接还差什么」，并作为**长期对账入口**——以后任何一条厂商接口变了，改这张表，不改聊天。
>
> **证据口径**（三条都满足才算「已接」）：文档节号 + `.tmp/docx/v16_text.txt` / `v113_text.txt` 行号 + 我方 `文件:行号`。
> 文档行号指**派生文本**（PDF 抽取），页码/章节以原 PDF 复核过的条目已在 §H 标注。

## 0. 状态图例

| 标记 | 含义 |
|---|---|
| ✅ | 已实现且语义与文档/云侧实测对齐 |
| 🟡 | 已实现但**形态或语义待验**（写清待验点） |
| ⛔ | **刻意不实现**（选型/安全决定，不是漏）——理由必须写在行里 |
| ❌ | **未实现**（缺口） |
| 🔁 | 文档没有、按固件实测补的（我方对文档的增量） |

**关键选型事实（2026-10-09 拍板，勿反复）**：模式一·普通对接。我方网关 `jiangyi-gateway` **顶替**将邑 SaaS 成为设备侧服务器；
出单/扣费/结算/争议全在我方。⇒ 第五章 §5.2（设备→商户的另一套入向面）**只作为第四章的超集参照**、
§5.4.x（商户→将邑回调）**整体不接**、§5.5/§5.6（将邑云侧读/审核面）**按需接**。

---

## 1. 总账

| 面 | 文档条数 | 我方实现 | 缺口 |
|---|---|---|---|
| A 设备面 HTTP（V16 §4.2，不含 §4.2.1 说明节与 §4.2.2 出向） | **17** | **17** | **0** |
| A′ 设备面 HTTP（固件实测有、§4.2 没有 ⇒ 我方补） | 45 条已观测 | 我方共 **46** 具体路径 | 见 §G-7（§G-3 三档回执 2026-10-21 已落码） |
| B WS 下行（V16 §4.3.2.1–17） | **17** | **6** 个 msgType（+1 共用字面量） | **10 未发**（§G-10） |
| C WS 上行（V16 §4.3.1 只定义 heartBeat） | **1** | 我方处理 **5** 类 | 0（未知上行走 `JIANGYI_WS_MSG_UNHANDLED` WARN 台账，§G-8 已落码） |
| D 出向 `/merchant/`、`/platform/`（V1.13 + V16 §5.4） | V1.13 **34** 条唯一路径 | 我方 **16** 条（V1.13 内 14 + V16 `setDomain`/`confirmActivate`） | **20 条未接**（§G-5） |
| E V16 §5.2 独有入向 3 条 | 3 | 0 | ⛔ 选型一致（§G-12） |
| F V16 §5.4 出向回调 9 条 | 9 | 1（`confirmActivate`） | ⛔ 8 条选型一致 |
| G′ V16 §5.5/§5.6 商户侧读面 | 6 | 0 | ❌ 其中 §5.5.3 有明确价值（§G-9） |

我方设备面具体端点 **46** 条 + `/jiangyi/api/token` 别名 + `/jiangyi/api/**` catch-all 台账；
内部端点 **6** 条（仅 compose 网内，`nginx location /internal/ → 403`）；
运营台将邑相关 **37** 条（`AdminJiangyiController` + `AdminJiangyiCatalogController`）。

---

## 2. A｜设备面 HTTP（V16 第四章 §4.2）

| 文档节 | 路径（商户服务器地址+尾段） | 文档行 | 我方实现 | 状态 / 说明 |
|---|---|---|---|---|
| §4.2.1 | 说明节（无接口） | v16:431 | — | — |
| §4.2.2 | `platform/tenant/setDomain` | v16:466 | `JiangyiMerchantClient.java:71` | ✅ 出向，见 §D |
| §4.2.3 | `token/openDoorDeviceStatus` | v16:557 | `DeviceTokenController.java:62` | ✅ 另接固件别名 `/jiangyi/api/token`；未激活 403、密钥缺失 503 |
| §4.2.4 | `deviceInfo/getDetail` | v16:654 | `DeviceInfoController.java:82` | 🟡 拓扑值按实测元组回（`doorNum=1/locks=[1]/Lu=1/Lb=3/Ru=0/Rb=0/modelNum=2`）；⚠️ 云侧回 `id:10720` 数字、我方回 deviceId 字符串，**类型差异容忍度未实证**；拓扑属**每机配置**，我方库里无拓扑列 ⇒ 逐台建档前此值=我方声称 |
| §4.2.5 | `deviceInfo/downloadModelNotify` | v16:732 | `DeviceInfoController.java:125-129` | ✅ 回执主通道（转发 trade `modelConfirmed`；异常吞 WARN 仍回 200）。响应**逐字照抄文档示例** `{status:200,msg:"success",data:"0"}`（v16:789）⇒ `ok()` 形态正确；见 §G-4 的未实锤观察。🔁 同一 handler 另收固件三条分档变体（见 §3 A′ 表；§G-3 已落码） |
| §4.2.6 | `aliYunOss/getTempUploadToken` | v16:813 | `AliyunOssController.java:40` | ✅ STS 直传我方 OSS；🔁 另接固件实测变体 `getTempToken`（`:47`） |
| §4.2.7 | `device/uploadLockState` | v16:929 | `DeviceReportController.java:69` | ✅ fail→trade open-failed |
| §4.2.8 | `device/uploadDoorState` | v16:997 | `DeviceReportController.java:122` | ✅ success→door-event OPEN |
| §4.2.9 | `order/openDoubleDoorError` | v16:1070 | `DeviceReportController.java:418` | ✅ 仅审计（单门柜不会触发）。🔁 真机「正在使用中」实际走 `deviceInfo/uploadDeviceOpenStatus`（`:133`） |
| §4.2.10 | `orderProduct/addRecognitionGoodsToOrder` | v16:1128 | `DeviceReportController.java:230` | ✅ 先合成 CLOSED→归一化（thirdPid 优先）→edge-results；金额**不采信设备**，映射未命中 fail-closed 转 DISPUTED。🔴 10-22 起裸/`/device` 两种拼写都注册，理由见 H-18（识别上报的「将邑腿」`uploadBuyGoodsInfo2JYServer` 用 `COMMON_URL`、「其他腿」`…2OtherServer` 用 `URL`，同一字面两个 base）|
| §4.2.11 | `device/pokerOrder/uploadOrderError`（`bigModel:"doing"`） | v16:1216 | `DeviceReportController.java:353` | ✅ 与 §4.2.12 **同一路径、靠字段分流**（doing→挂起 10min 兜底；否则→DISPUTED）。🔴 10-22 起该 handler 收**四种**拼写（`pokerOrder|order` × 裸/`/device`）：尾串随对接版本换（`NORMAL`→`/order/uploadErrorOrder`，其余→`/pokerOrder/uploadOrderError`，`UploadInfo2Server.smali:1197-1214`，body 同为 `ErrorOrderEntity`），前缀随 base 字段换，见 H-18|
| §4.2.12 | `device/pokerOrder/uploadOrderError` | v16:1301 | 同上 `:348` | ✅ 同一个 handler |
| §4.2.13 | `deviceInfo/getArtificialCheckByIdentifier` | v16:1364 | `DeviceInfoController.java:145` | ✅ 回 `data:false`=只上传异常单视频（2026-10-19 拍板 `isAutoUploadVideo=false` 同口径）。🔁 固件另打 `deviceInfo/isAutoUploadVideo`（`DeviceReportController.java:410`） |
| §4.2.14 | `device/pokerOrderVideo/uploadVideoUrl` | v16:1437 | `OrderVideoReportController.java:43` | ✅ 落 `jiangyi_order_video`（§4.3.2.11 拉取链的闭合点）。⚠️ 2026-10-21 一次子代理盘点漏掉本条，已纠正 |
| §4.2.15 | `deviceInfo/getDeviceSimInfo` | v16:1518 | `DeviceReportController.java:442` | ✅ 标题「上报」而路径叫 `get…`（文档笔误，见 §H-3） |
| §4.2.16 | `deviceInfo/getDoorInfo` | v16:1617 | `DeviceReportController.java:481` | ✅ |
| §4.2.17 | `deviceInfo/changeWifi` | v16:1686 | `DeviceReportController.java:490` | ✅ wifi 连接结果上报（与 WS 下行 §4.3.2.15 `sendChangeWifiCommand` 配对，后者未接） |
| §4.2.18 | `deviceInfo/queryNetwork` | v16:1750 | `DeviceReportController.java:498` | ✅ 在线状态上报 |
| §4.2.19 | `advertisingDevice/getAdvertisingByDeviceId` | v16:1810 | `DeviceExtensionController.java:138` | ✅ 我方无广告位⇒回空数组 |
| §4.2.20 | `order/uploadFaultOrder` | v16:1899 | `DeviceReportController.java:427` | ✅ 仅审计 |

**结论：V16 第四章设备面文档的 17 条，我方 17 条全接，无遗漏。**

## 3. A′｜固件实打、文档没有的路径（`.tmp/fw_paths.txt` 43 条 + 10-20/21 新增）

| 路径 | 我方实现 | 状态 / 口径 |
|---|---|---|
| `tenantOrder/needCheck` | `DeviceCompatController.java:42` | ✅ 照抄云侧 `data:false`×2 |
| `product/tenantPidAndClassId` | `:58` | 🟡 照抄 `{}`——只有 2 次可绑定 URL，保守空映射（宁空不造商户商品 ID 表） |
| `deviceInfo/getOtgStatus` | `:65` | ✅ `"open"`×13 |
| `dictionary/getPicSwitch` | `:72` | ✅ `"on"`×2 |
| `tenant/getDeviceBasicConfig` | `:84` | ✅ 15 字段整组×27，取众数组 |
| `tenant/getOrderRecheckProduct` | `:130` | ✅ 2026-10-21 补；200+`[]`（依据=固件 `String[]` 契约，非云侧样本；不可回 `""`） |
| `deviceInfo/getTenantPvalueConfig` / `…Close` | `:145` / `:153` | ✅ 照抄 `null`（云侧对同一台也回过 8/6 次 null） |
| `deviceInfo/getBaiduAudioCode` | `:166` | ✅ 照抄 `status:666` 原文案 |
| `deviceInfo/getPValue` / `order/pValueByTransactionId` | `:184` / `:192` | ✅ 摄像头调光遥测，**非识别结果**，`data:null` 只留档 |
| `order/uploadAddGoodsCloseStatus` | `:206` | ✅ `data:null` 留档 |
| `deviceInfo/selfCheckReport` / `reportDeviceStatus` / `reportCameraRealInfo` / `updateTrouble` / `uploadDeviceDataCardNo` | `:214`/`:222`/`:230`/`:239`/`:247` | ✅ `data:null` 只留原始日志 |
| `floor/getDeviceSellProductClassIds` | `DeviceExtensionController.java:52` | ✅ 真机在售面（`floor/getFloorByDevice` 真机 0 命中，见下） |
| `floor/getFloorByDevice` | `:106` | 🟡 按文档 §5.5.2/补货语义实现，**真机从未请求过**。🔴 10-22 两次复核纠正：0 命中**不能**只归因于「没跑补货开门」——`GetReplenishGoodsInfo` 对 XY/YP/NO_NET 直接 `onResult("suc")` 不发 HTTP，只有 NORMAL 才 POST（`project-jiangyi-mname-dictvalue-trap` 记忆），而本表 0 命中与「对接版本非 NORMAL」一致。⚠️ 早班写的「唯一自洽解释是 `M_NAME=YP`」**已降级**：真机那 20 次 `/device/pokerOrder/uploadOrderError` 出自六处「字面自带 `/device` + `URL`」的站点（`uploadErrorOther:1247`、`CameraPreviewNew:7184`、`OrderRknnResultDeal:2999`、`UploadIdentifyResult:57095`、`UploadLocalOrder:759`、`UploadInfo2ServerStock:1316`，六处逐个读了门＝NORMAL 即 `return-void`）⇒ 只证到 **`M_NAME ∉ {NORMAL, NO_NET}`，XY 与 YP 现有证据分不开**（`OkhttpUtils.postRequest*` 只拦 NO_NET、不拦 XY）。另按 H-18 把本表注册成裸/`/device` 双拼写（原判「COMMON_URL 多一段 /device」已作废，见 §11）（判据=字面 `"status":200`） |
| `deviceInfo/receiveCurrentTxtUrl` | `DeviceInfoController.java:165` | ✅ 照抄云侧 `666`（2026-10-19 拍板：勿顺手对齐成 200） |
| `deviceInfo/isAutoUploadVideo` | `DeviceReportController.java:410` | ✅ 回 false（拍板：只传异常单视频） |
| `deviceInfo/uploadDeviceOpenStatus` / `order/uploadOrderLockStatus` / `operationMode/sync` | `:133` / `:80` / `:391` | ✅ 真机补 |
| `aliYunOss/getTempToken` | `AliyunOssController.java:47` | ✅ 真机变体 |
| `domainConfig/getConfigDomainByIdentifier` | `DomainConfigController.java:94` | ✅ 拍板 A 自钉：照抄云侧怪形态 `{"status":null,"msg":null,"data":"<base>/jiangyi/api"}` |
| `domainConfig/getConfigStatusByIdentifier` | `:111` | 🟡 恒回 `data:1`。10-20 晚逐行重核订正：该值实为固件 **USE_TYPE**（使用模式）写入槽、非配置状态位；1/2 语义=厂商问题 18 |
| `domainConfig/getConfigSocketUrlByIdentifier` | `:122` | 🟡 换域交接端点（打 `COMMON_URL`+`COMMON_TOKEN`；真机 4 份 logcat **0 次**）。值形态无单一正确形态（MZ 与 CARD_258/CD_WB 拼法互斥、NORMAL 不读）⇒ 维持 `wss://<host>/websocket/device/` |
| `deviceApp/updateAppVersion` / `updateAppVersions` / `updateSystemFirmware` | catch-all `DeviceUnknownPathController.java:40` | ⛔ **刻意 404 + WARN 台账**（拍板 B：反代是改造不是配置——设备带我方 HS256/384、将邑云只认自家 HS512，原样转发必 401）。代价：这批柜停在 V9.10.806 |
| `feature/getFeatureByDevice` / `getRecognitionPolicy` / `reportPackApplied` | catch-all | ⛔ 同上，**厂商催办第一优先**（双摄识别策略是云侧 flag `reason=customer_release_dual`）；404 后固件回落行为未知 |
| `token/getAliyunAkToken` | catch-all | ⛔ 桩掉可接受（将邑自有日志桶凭证，与我方无关） |
| `deviceInfo/getFaceModelConfig` / `getDeivceFrameRate` | catch-all | ⛔ 未实测⇒一律 404（不回 200 空值伪装「配置就是空」） |
| `replenishmentHistoryBatch/uploadOpenMsg` / `replenishmentHistoryVideo/create` | catch-all | ⛔ 桩掉可接受（少一路厂商补货历史） |
| `deviceInfo/downloadBaseModelNotify` / `downloadBigModelNotify` / `downloadThirdModelNotify` | `DeviceInfoController.java:125-129`（与 §4.2.5 同一 handler） | ✅ **2026-10-21 落码**（原为 catch-all 404）：固件三档模型回执各一条独立路径（log4 行 24231/30621/17847，body 与 §4.2.5 逐字同形 `{identifier,modelName}`）。三档语义**未向厂商核实⇒不区分处理**，错档回执由 trade `confirmModel` 的 modelName 匹配（不匹配 409）挡住，不会伪造 CONFIRMED。判据待真机：开机一轮 4 条 notify 全 200（§G-3） |
| `pokerOrder/getChessRoomOpenStatus` | catch-all ⇒ **404** | ❌ 真机每 20s 一次（10-20 15:44 起）。语义/云侧响应**未取证**⇒ 禁止照抄猜值；见 §G-7 |

> ⚠️ `DeviceUnknownPathController` 的类注释仍把 `domainConfig/*` 列为「不接管」，与 `DomainConfigController` 已实装相矛盾（显式映射优先于 catch-all，行为正确、注释陈旧）⇒ 顺手项，不紧急。

---

## 4. B｜WS 下行（V16 §4.3.2，商户→设备共 17 条）

拼接式（V16 §4.3.1，v16:1962）：`wss://<host>/websocket/device/` + 设备编号 + `?token=` + 商户 token。

> **§5.3「服务器 WebSocket 接口」（v16:3881）已单独核过**：只列 5 条下行（`reboot`、`openDoor`、`closeDoor`、带订单号的 `openDoor`、`openDoubleDoor`），
> **是 §4.3.2 的真子集 ⇒ 不新增 msgType**，本表 17 行即全集（2026-10-21 23:10 补核，见 §11）。

| 文档节 | msgType | 文档行 | 我方 | 状态 |
|---|---|---|---|---|
| §4.3.2.1 机器重启 | `reboot` | v16:1980-1981 | — | ❌ 未发。现场断电仍需人工合闸⇒ 有价值（§G-10） |
| §4.3.2.2 强制开门 | `openDoor`（`msgContent:""`） | v16:2007-2008 | 同一字面量恒带 sessionId（`DeviceWebSocketHandler.java:230`） | ⛔ 空 msgContent **结构性拒绝**（购物开门必须有会话号，空值=无审计开门） |
| §4.3.2.3 强制双门开门 | `sendForceOpenDoubleDoor` | v16:2035-2036 | — | ⛔ 单门柜无此概念 |
| §4.3.2.4 补货开门 | `sendOpenStockDoor`（补货单号） | v16:2067-2068 | `:217` | ✅ 补货会话 id ≡ 补货单号 |
| §4.3.2.5 补货双门开门 | `sendOpenStockDoubleDoor` | v16:2108-2109 | — | ⛔ 单门柜 |
| §4.3.2.6 关门 | `closeDoor` | v16:2162-2163 | — | ❌ 未发（门锁自锁+300s CLOSE watchdog 兜底；远程关门属运维便利） |
| §4.3.2.7 购物开门 | `openDoor`（订单号） | v16:2189-2190 | `:230` | ✅ 我方唯一实际开门路径 |
| §4.3.2.8 双门购物开门 | `openDoubleDoor` | v16:2217 | — | ⛔ 单门柜（文档自己写明「一笔订单只能开一个门」） |
| §4.3.2.9 模型下发 | `updateModel`（对象 `{quantity,modelUrl,textUrl,modelName}`） | v16:2278-2279、参数表三行全 M | `:250` | ✅ 出站已实现；**卡的是输入**：§4.4.4 列表空⇒`requireModel` 404、classes host 白名单⇒`parseClasses` 400（§G-2） |
| §4.3.2.10 灯带控制 | `sendDeviceLightStripFlag` | v16:2356-2357 | — | ❌ 未接（可选，柜无灯带需求） |
| §4.3.2.11 拉取视频 | `sendUploadErrorOrderVideos` | v16:2390-2391 | `DeviceCommandController.java:105` | ✅ 2026-10-20 上线。🔴 **文档示例 `msgContent:"24005355433545_MSG"` 带 `_MSG` 后缀，固件 `DealSocketMsg.smali:186-200` 按 `/sdcard/{orderNo}-left.mp4` 找文件⇒不带后缀才对**（§H-6）。上游同字面回传=二义（重传请求 or 无文件），我方**只记日志绝不重发**（`DeviceWebSocketHandler.java:176`） |
| §4.3.2.12 获取门磁状态 | `getDoorState` | v16:2433-2434 | — | ❌ 未接（现场巡检有用，P2） |
| §4.3.2.13 获取设备基本信息 | `getDeviceInfo`（文档 OCR 作 `getDevicelnfo`） | v16:2468-2469 | — | ❌ 未接（同上） |
| §4.3.2.14 检查在线状态 | `sendQueryNetwork` | v16:2515-2516 | — | ❌ 未接（现用 WS 会话存活判在线，够用） |
| §4.3.2.15 连接 Wi-Fi | `sendChangeWifiCommand` | v16:2552-2553 | — | ❌ 未接⇒**换场地配网只能现场手工**（§G-10） |
| §4.3.2.16 声音开关 | `sendDeviceSoundSwitch`（`"0"`/`"1"`） | v16:2613-2614 | `DeviceCommandController.java:58` | ✅ |
| §4.3.2.17 音量大小 | `sendDeviceSoundVolume`（`"0"`大/`"1"`中/`"2"`小） | v16:2649-2650 | `:67` | ✅ |

## 5. C｜WS 上行（设备→商户）

**文档 §4.3.1 只定义一条上行：`heartBeat`（v16:1964-1967）。其余全是我方从 logcat/反编译补的（🔁）。**

| 上行 msgType | 出处 | 我方 | 状态 |
|---|---|---|---|
| `heartBeat` | 文档 v16:1967 | `DeviceWebSocketHandler.java:122`，回 `{status:200,msgType:heartBeat,msgContent:PONG}`（`:126-129`） | 🟡 **文档写设备发 PONG、实为 PING**（log3 实测 + 我方注释 `:118`；§H-5）。我方不校验入值⇒两形皆可用 |
| `openDoorSure` | 固件实测 | `:134-135` | 🔁 开门回执 |
| `sendOpenStockDoorSure` | 固件实测 | `:134` | 🔁 补货开门回执 |
| `updateModelSure` | 固件实测（**文档全章 0 次出现 `Sure`**） | `:145`→`completeByDevice(Kind.MODEL)` | 🔁 模型回执兜底（主通道=HTTP §4.2.5） |
| `downloadModelNotify`（WS 分支） | 文档只给 HTTP 形态 | `:155` | 🔁 宽容兜底 |
| `sendUploadErrorOrderVideos` | 固件实测 | `:176` | 🔁 二义⇒只记日志 |
| `updateBaseModelSure` / `updateBigModelSure` / `updateThirdModelSure` | 固件实测（log2/3/4 tally 各 1-2 次，与同名下行成对） | 未处理 ⇒ 落 WARN 台账（`:186`） | ⛔ **刻意不处理**：回执 msgType 与下行 msgType 同名成对，而我方下行**只发 `updateModel`**（`MSG_TYPE_UPDATE_MODEL`，`ModelPushController.java:18`）⇒ 这三条在我方链路里不可能出现，接了就是死代码。若将来厂商侧代下分档模型，台账会先把它们照出来 |
| **其它任意 msgType** | — | `:186` | ✅ **2026-10-21 落码**：`log.debug` → `JIANGYI_WS_MSG_UNHANDLED` WARN 台账（带 msgType + 原文），与 HTTP 面 `JIANGYI_PATH_UNIMPLEMENTED` 对称；仍**不当事务数据**（§G-8）。心跳不得进台账（真机每份日志 1119 次，会淹没台账）——单测钉住 |

真机首握手实证：2026-10-20 15:44:25 `deviceId=EG943322621391 identifier=CQYB10720` 101 升级成功，此后每 10s 心跳、我方回 PONG；
`?token=` 是**我方 HS384** ⇒ 「WS token 归属」那堵墙实证不存在（offnet 推送把 `COMMON_URL` 一并换到我方，见 `docs/JIANGYI_GATEWAY_DESIGN.md` §5）。

---

## 6. D｜我方 → 将邑 出向（V1.13 商户 API + V16 §5.4）

我方实际调用 **16** 条：`JiangyiGatherClient.java` 12 条 + `JiangyiMerchantClient.java` 4 条（`login2` 两边共用）。
鉴权两把钥匙：`JIANGYI_TENANT_ID`+`_SECRET` 仅 `setDomain`；`_MERCHANT_MOBILE`+`_PASSWORD`→`login2` Bearer（缓存 25min，401 重登一次）。

| 文档节 | 路径 | 文档行 | 我方 | 状态 |
|---|---|---|---|---|
| V1.13 §3.4.1 | `merchant/authentication/login2` | v113:350 | `JiangyiGatherClient.java:252`、`JiangyiMerchantClient.java:136` | ✅ |
| V1.13 §3.6.1 | `merchant/deviceInfo/getIdentifier`（文档 OCR `devicelnfo/getldentifier`） | v113:399 | `JiangyiMerchantClient.java:84` | ✅ 兜底按 SN 换 identifier |
| V1.13 §4.1.2 | `merchant/stdSku/getList` | v113:464 | `JiangyiGatherClient.java:79` | ✅ 商品主数据（挂接来源 `sku_jiangyi_link.std_sku_id`） |
| V1.13 §4.1.4 | `merchant/productCategory/getTopList` | v113:647 | `:100` | ✅ |
| V1.13 §4.1.5 | `merchant/product/create` | v113:699 | `:116` | ✅ |
| V1.13 §4.1.6 | `merchant/product/delete` | v113:881 | — | ❌ 未接（我方商品删除只动本地库⇒将邑侧残留脏商品；§G-6） |
| V1.13 §4.1.7 | `merchant/product/updateMainImg` | v113:916 | — | ❌ 未接（换主图不同步） |
| V1.13 §4.2.2 / §4.3.2 | `merchant/selfhelpGatherProduct/waitGatherProduct` | v113:1005 / 1273 | `:130` | 🟡 **方法在、无调用方=死代码**（§G-11） |
| V1.13 §4.2.3 | `merchant/selfhelpGatherProduct/gatherOpenDoor` | v113:1121 | `:138` | 🟡 已调，但**开门指令经将邑云下发**，而柜 WS 已指我方⇒能否到达未实证（§G-6） |
| V1.13 §4.2.4 | `merchant/selfhelpGatherProduct/doorStatus` | v113:1163 | `:145` | ✅ |
| V1.13 §4.3.3–4.3.14 | `productInfo`/`startGather`/`confirmStart`/`gatherError`/`endOne`/`gatherProducts`/`goOnGather`/`endAll`/`uploadInfoNew`/`forgoUploadNew`/`goOnUploadNew`/`deleteBatchNoNew` | v113:1405 / 1509 / 1607 / 1649 / 1700 / 1754 / 1809 / 1855 / 1893 / 1959 / 1985 / 2018 | — | ❌ **12 条全无**⇒ 自采自训无法由我方驱动（§G-5，最高业务价值缺口） |
| V1.13 §4.4.2 | `merchant/product/getPageData`（`gatherStatus=training`） | v113:2111 | `:156` | ✅ 学习资格判定 |
| V1.13 §4.4.3 | `merchant/selfhelpGatherProduct/commitTrainingTenantOne` | v113:2239 | `:180` | ✅ 带 `finishNotifyUrl`+`finishNotifyId`（⇒ 我方 `GatherNotifyController.java:36` `/jiangyi/api/gather-finish-notify`） |
| V1.13 §4.4.4 | `merchant/modelFile/getModelFileByApi`（**请求参数=无**） | v113:2313 | `:193` | ✅ 模型唯一来源；`.4` 响应字段 `industrialControlModel` 中文名也叫「模型类型」（§H-7）。**租户 426 实测真回 `[]`**（厂商侧无学习完成的模型） |
| V1.13 §4.4.5 | `merchant/selfhelpGatherProduct/studyingProduct` | v113:2412 | `:185` | ✅ |
| V1.13 §4.4.6 | `merchant/selfhelpGatherProduct/gatherCheck` | v113:2500 | `:218` | ✅ 已改发 `productIds:null`（传 `[]` 会被厂商 MyBatis 拼非法 SQL 回 555） |
| V1.13 §4.5.1–4.5.6 | `tenant/create`、`addressdata/provinces`(以及 `cities`、`areas`)、`tenant/findSubByPid`、`tenant/updateSubTenantLimited` | v113:2607/2699/2731/2782/2834/2866 | — | ❌ 未接（我方商户体系自建，**不打算接**；需与厂商一句确认「商户归属以谁为准」） |
| V1.13 §4.6.1 | `merchant/deviceInfo/modifyAffiliationTenant` | v113:2906 | `JiangyiMerchantClient.java:104` | ✅ 换绑租户 |
| V16 §5.2.2/§4.2.2 | `platform/tenant/setDomain` | v16:466 / 2776 | `JiangyiMerchantClient.java:71` | ✅ `domain`+`socketUrl` 两槽 10-19 写入、10-20 厂商云回查实锤生效 |
| V16 §5.4.1–5.4.8、5.4.9 | `pokerOrderNotify/sendOrderAmt`、`/sendOrderRecheck`、`/uploadOrderPayError`、`/uploadRecheckOrderPayError`、`deviceInfo/updateDeviceModelByPoker`、`floor/floorPokerCreate`、`device/getOnlineStatusAndVersion`、`selfhelpGatherProduct/receiveGatherProductZipData` | v16:4207 / 4377 / 4587 / 4715 / 4819 / 4930 / 5073 / 5242 | — | ⛔ **选型一致、非缺陷**（模式一下订单数据不落将邑云，回调只是喂他们的展示账本；见 `docs/archive/audit/AUDIT_TRACK_E_JIANGYI_INTERFACE_GAP_2026-10-13.md` E-2） |
| V16 §5.4.10 | `merchant/deviceInfo/confirmActivate` | v16:5378 | `JiangyiMerchantClient.java:120`、`AdminJiangyiController.java:210` | ✅ **Track E 原判「P1·扩机阻塞」已闭合**（10-13 台账那句「全仓零调用」作废） |

---

## 7. E｜V16 §5.2 独有的三条入向（云→商户）——刻意不接

模式一下设备识别/异常上报直发我方 domain，**订单数据根本不落将邑云** ⇒ 云侧「二次甄别订单列表」无数据来源，不会向商户推这三条。
| 路径 | 文档行 | 状态 / 若日后接的约束 |
|---|---|---|
| `pokerOrderProduct/addProductToOrder`（§5.2.11 二次甄别扣款通知） | v16:3512 | ⛔ 资金语义在我方，不能由云侧推 |
| `thirdRecheckOrder/recheckOrder`（§5.2.12 重审退款/补扣，`amount` 单位分） | v16:3616 | ⛔ 同上（退款方向风险最高） |
| `pokerOrder/sendTimeOutError`（§5.2.14 订单超时，**文档明文「不用 token 验证」**） | v16:3835 | ⛔ 免鉴权入向写路径。若接：`GatewayWebConfig` 显式豁免 + 只允许「置失败」+ 按 orderNo 幂等，**禁止任何放款/发货语义**。我方超时兜底已自实现（OPEN 15s / CLOSE 300s / recognize 10min→DISPUTED） |

## 8. G′｜V16 §5.5 / §5.6 将邑云侧读面——6 条 0 实现

| 文档节 | 路径 | 文档行 | 判定 |
|---|---|---|---|
| §5.5.1 | `merchant/stdSku/getList` | v16:5521 | ✅ 已由 V1.13 §4.1.2 接（同一端点） |
| §5.5.2 | `merchant/floor/getFloorBySn` | v16:5676 | ❌ 未接。我方 `floor/getFloorByDevice` 是**下行给设备的补货表**，不是这条云侧读；两者不同源，勿混 |
| **§5.5.3** | **`merchant/stdSku/getStdSkuQueryByTextNames`** | **v16:5885** | ❌ **未接**，但**已实跑通（2026-10-22 00:1x，`.tmp/probe-553.mjs`）**：入 `modelTextUrl`（必填， classes 文件地址），出 `data[] = {stdSkuId, stdSkuName, textName, barCode, classId}`。用厂商后台那次下发的 `classes_auto_6.txt` 实测回 `status:200` + **2 行**：`{stdSkuId:1911,"测试egg",textName:"egg",barCode:null,classId:4}` / `{stdSkuId:2423,"测试toy",textName:"toy",barCode:null,classId:5}`。三条硬结论：① **端点是活的、租户 426 有权调**；② 🔴 **`classId` 就是 classes.txt 的 0-based 行号**（该文件 6 行 `bottle/bowl/bag/box/egg/toy`，egg=下标 4、toy=下标 5，与厂商回值逐字一致）⇒ 我方 `JiangyiClassesParser` 那个「文档未明示 0/1-based、靠 `classIdBase` 可配 + 人工确认」的悬案**到此钉死为 0**；③ **它只回能解析成 stdSku 的行**（bottle/bowl/bag/box 四行整条消失）⇒ 不能拿它当唯一行源，正确用法是「自己下载 txt 数行号 + 用 §5.5.3 补 `stdSkuId/barCode`」的混合法。⇒ **厂商官方替我方做 classes↔SKU 解码**。见 §G-9 |
| §5.6.1 | `merchant/tenantGather/searchGatherProductPageData` | v16:6076 | ❌ 采集图审核面（依赖 §4.3 采集链） |
| §5.6.2 | `merchant/tenantGather/searchGatherProductPicPageData` | v16:6264 | ❌ 同上 |
| §5.6.3 | `merchant/tenantGather/picDelete` | v16:6459 | ❌ 同上 |
| §5.6.4 | `merchant/tenantGather/picSubmit` | v16:6590 | ❌ 同上 |
| §5.6.5 | `merchant/deviceInfo/getTenantInfoByDeviceSn` | v16:6721 | ❌ 未接（我方按自有 SN 建档，可用作录入校验） |

---

## 9. 现在还差什么（按爆炸半径排序，每条都是「谁做 + 做什么 + 判据」）

| # | 缺口 | 后果（不做会怎样） | 判据（怎么算通了） | 谁做 |
|---|---|---|---|---|
| **G-1** | **柜机不上线** | 开门→取货→识别→结算端到端**一次都没跑过**；所有 WS/下行链路只有我方探针。**2026-10-21 23:35 复核仍未变**：网关自 20:39 重启后设备侧流量 **0 条**（`docker logs --since 3h ai-cabinet-jiangyi-gateway-1` 里 `jiangyi ws`/`deviceInfo`/`domainConfig`/`tenant` 前缀各 0 命中），`jiangyi_device.last_ws_online_at` 仍停在 18:14 ⇒ §G-3/§G-8 的真机判据、以及 §G-2 的下发验证**全都卡在这一条** | nginx access.log 出现 **`okhttp/4.9.3` UA 的 `/websocket` 101** + 网关 `onlineDevices=1` | **现场**（合闸/接网） |
| **G-2** | **模型链输入为空** | `model-sync`/`model-push` 永远失败：§4.4.4 回 `[]`⇒`requireModel` 404（`JiangyiModelSyncService.java:221`）；classes host 不在白名单⇒`parseClasses` 400⇒**设备端侧没有任何可识别 class，识别上报恒 fail-closed 转 DISPUTED** | `GET /jiangyi/models` 真回**两条**（76/88）+ `model-sync` 预生成出 `classId↔textName` 行 | **白名单侧已闭合（2026-10-21 23:25）**：`infra/.env` 加 `AICABINET_JIANGYI_MODEL_SYNC_ALLOWED_HOSTS=hunanjysmart.com,https://jianyidev.oss-cn-chengdu.aliyuncs.com` + `up -d --no-deps trade-service` **重建容器即生效，无需重建镜像**——jar 里 `BOOT-INF/classes/application.yml:117` 就是 `${AICABINET_JIANGYI_MODEL_SYNC_ALLOWED_HOSTS:...}`（容器内 `unzip -p` 实证），逗号分隔→`List<String>` 由 `JiangyiModelSyncServiceTest:45` 的 Spring 上下文钉住。host 真实性=设备实测 `modelUrl/textUrl` 落在该桶（`.tmp/device-log.txt`）+ 其 STS `bucketName:"jianyidev"`。🔴 **仍未闭合的是厂商侧**：`GET /ops/admin/jiangyi/models?classIdBase=0` 23:26 实跑回 `{"code":0,"data":[]}` ⇒ 白名单这条改动**今晚无法端到端验证**（没有 modelTextUrl 可拉），判据要等厂商给模型（「含我方在售 SKU 的 88 商品模型 + 其 classes」），或按 V1.13 §3.1 四步自采。🔴 **自采图片数旧记载有误，2026-10-22 更正**：V1.13 `:1689`（§4.3.6.4）逐字「(图片不足2000张也是返回no,**不算错误**)」、`:1932`（§4.3.11.4）`weak:图片不足2000张` 只是**上传状态四档之一的标签**（`success/fail/wait/weak`），**不是进学习列表的门槛**；真正的数在 V16 §5.6 引言（v16:6060 附近）：「目前系统是设置的**提交合格图片 200 张就可以了**，但是最好多提交一些」⇒ 自采量级是 **200 张/商品**，不是 2000。 |
| **G-3** | ~~三条分档模型回执我方 404~~ → **已落码，待真机验证**（2026-10-21 23:15） | 原状：固件按档次分三条路径回执（log4 行 24231 base / 30621 big / 17847 third，body 与 §4.2.5 同形），我方只接 `downloadModelNotify` ⇒ 分档下发结果不可见、`deployment` 停 SENT→push-timeout。现四条路径同一 handler（`DeviceInfoController.java:125-129`） | 网关日志出现 `jiangyi http model confirmed path=/jiangyi/api/deviceInfo/download*ModelNotify`；开机一轮 `JIANGYI_PATH_UNIMPLEMENTED` 里不再有这三条 | **已完成**（+2 单测：四条路径映射断言 + 变体转发）。⚠️ 三档语义仍需厂商一句确认；未确认前**不区分处理**，靠 `confirmModel` 的 modelName 匹配（不匹配 409）兜底 ⇒ 不可能伪造 CONFIRMED |
| **G-4** | §4.2.5 的 `data` 语义仍是黑盒（**观察项，不是偏离**） | 我方回 `data:"0"` 是**逐字照抄文档 json 示例**（v16:789；同节说明栏却写「数据 对象」，v16:783-786 自相矛盾⇒照抄示例，见 §H-11）。log4 行 11436 邻域云侧有一次回 `[{barCode,textName,bgType}]`，但**归属证据只是同 PID + 0.27s 的「上报成功」行、不是同行 URL 绑定**⇒按取证铁律记「未实锤」，且现在更倾向它不属于 §4.2.5 | 柜连我方后若出现「回执 200 但设备本地 class 表空」，再回来质疑这一格 | 观察（G-1 达成后自然有读数）；厂商问题清单可挂一句「§4.2.5 的 data 商户端到底该回什么」 |
| **G-5** | **V1.13 §4.3.3–4.3.14 采集编排 12 条全无** | 「上架厂商库里没有的商品」这条业务路径**我方无法驱动**——只能求厂商或走人工。我方在售 11 SKU 全是全国流通品，所以**今天不卡**，扩品必卡 | `gather/*` 页能走完 新增→开门→采集→完成批次→上传 五步 | **待拍板**：先问厂商「采集流程是否由柜机 APK 自己完成、我方只需 `commitTraining`」（logcat 里 `selfhelpGatherProduct` **0 命中**，但从未跑过采集，不能据此断言）⇒ 判据=真跑一次采集看 nginx 有无这些路径 |
| **G-6** | **采集开门经将邑云，而柜 WS 已指我方** | `JiangyiGatherService.startGather`（`:70-88`）→ `gatherOpenDoor`（`JiangyiGatherClient.java:138`）打到厂商云；厂商若要开这台柜的门，得走它自己的 WS 会话——**它已经没有了**（设备 `COMMON_URL` 与 prefs `socketurl` 都是我方）⇒ 采集开门可能**结构性打不开门** | 真点一次 `gather/start`，看柜是否开门 + 我方 nginx 是否收到 `device/uploadDoorState` | **我方**（若开门不到，改法=我方 `sendOpenStockDoor` 顶替，属设计变更需拍板）。⚠️ 顺带：`waitGatherProduct`（`JiangyiGatherClient.java:130`）无调用方=死代码 |
| **G-7** | **`pokerOrder/getChessRoomOpenStatus` 每 20s 404** | 真机自 10-20 15:44 起的固定轮询（body `{identifier}`）。语义未知⇒每次 404 走设备失败分支，日志噪音 + 设备侧可能有重试/告警副作用 | 🔴 **2026-10-22 新证据：契约不用问厂商，我方自己那个安装包里就有**（`.tmp/jadxtool/smali/.../control/DealStringMsg.smali` + `DealStringMsg$9.smali`）：`getLockState()`/`getLockStateOther()` POST `{identifier}`；回调成功判据是**字面量含 `"status":200`** → 解 `BaseEntity` → `getData()` 字符串 **`equals("open")`** → `OpenLockStateRecord.writeLockState(Z)`。⚠️ **但真/假两条分支随后都调 `DealStringMsg->openLock()`**（`:goto_35` / `:cond_3b`）⇒ 我方回 `open` 还是回 `closed` 都会触发一次开锁动作，差别只在是否写那条本地记录——**这正是不能顺手回个 200 的原因**。仍未取证：404 时 okhttp 走 `onError` 还是 `onFailed`（决定「补 200」到底改变了什么） | **维持 404 + WARN 台账**（禁照抄猜值）。要接必须先满足其一：① 厂商一句话给语义；② 可控实测看 404→200 后设备行为是否变化。厂商催办清单里此项**优先级低于上电** |
| **G-8** | ~~WS 未知上行静默~~ → **已落码**（2026-10-21 23:15，`DeviceWebSocketHandler.java:186`） | 原状：HTTP 缺口有 `JIANGYI_PATH_UNIMPLEMENTED` WARN 台账，WS 缺口是 `log.debug` ⇒ 生产 INFO 阈值下**等于静默丢帧**。现改 `JIANGYI_WS_MSG_UNHANDLED` WARN（带 msgType + 原文），仍**不当事务数据**处理（铁律不变） | 真机连上后台账出现真实未处理 msgType（预期候选：`sendDeviceDomainSure` 5-6 次、`sendDeviceUsedStatusSure` 3 次、`uploadLogSure` 2-4 次、`rebootSure` 2 次——log2/3/4 tally） | **已完成**（+2 单测：两条未知帧各产生一条 WARN、心跳不得产生 WARN）。⚠️ 台账量级安全：这些都是**每条下行一次**的回执，不是周期帧；唯一的周期帧 heartBeat（1119 次/份日志）已单独处理 |
| **G-9** | ~~§5.5.3 `getStdSkuQueryByTextNames` 未用~~ → **✅ 2026-10-22 已接入（只读展示）** | 原状：`class-mappings` 挂接全靠**人工核对 `classId↔textName↔SKU`**（textName 是 `kkkl_xb_qsnmw_pttz_330ml` 这种拼音缩写串，人读不动） | 🔴 **拍板口径（用户 2026-10-22）：只作显示/辅助人工挂接，绝不写 `sku_id`**——§5.5.3 回的是**厂商标准商品**，不是我方 SKU；自动挂等于把「厂商认为这是什么」当成「这个柜按哪个价结算」，挂错即错扣款（与「结算绿须端侧挣得」同源） | 落码四处：`JiangyiGatherClient.stdSkuByTextNames`（:106，字段按**真机回包**取 `stdSkuId/stdSkuName/textName/barCode/classId`，文档 json 示例写的 `stdSkuld`/`name` 是错的 → H-17）；`JiangyiModelSyncService.decodeVendorSkuByClasses`（:125，**只读**）；`GET /devices/{id}/jiangyi/class-decode`（`AdminJiangyiController.java:355`，`ops:device:list`）；后台设备卡「查厂商商品对照（只读）」。**两个设计点**：① 合并键用 `textName` **不是** `classId`——该页有 classIdBase 旋钮，按 classId 合并在拨到 1 时整表错行且看不出来，厂商 classId 原样回显成 `vendorClassId` 让冲突肉眼可见；② 回包**稀疏**（69 行只回 67 行）⇒ 未解析行显式显示「厂商库无对应」，不拿别的行凑 | **我方**（已完成，23/23 单测含新增 7 条） |
| **G-14** | 🔴 **「必须让厂商把模型挂到租户 426」是我方自己加的门槛** | 复核依据（逐字）：① V16 §4.3.2.9 `updateModel` 的 `msgContent` 只有 `{quantity, modelUrl, textUrl, modelName}`——**全是 URL/字符串，协议层没有任何"模型须登记在租户名下"的要求**；② V16 §5.5.3 的入参就是**裸 `modelTextUrl` 字符串** ⇒ 厂商自己把 classes 当成"任意可传的地址"；③ 我方网关内部 `POST /internal/v1/jiangyi/devices/{id}/model-push` 收的正是显式 URL（`ModelPushRequest`），**不查 §4.4.4、不下 classes**。⇒ 把链堵死的是 `JiangyiModelSyncService.requireModel` 只认 §4.4.4（`[]` ⇒ 404），不是厂商侧的配置缺失 | ✅ **2026-10-22 已落码**（原建议「后台加显式入口」）：`POST /api/v2/ops/admin/devices/{id}/jiangyi/model-sync-explicit` 与 `.../model-push-explicit`，载荷 = §4.3.2.9 四字段（modelName/modelUrl/textUrl/quantity），**不再调 `requireModel`**；仍保留 BOUND + 机型已登记 + 无未决 SENT + classes 过 SSRF 白名单 + 行数==quantity，映射照旧落 `MODEL_SYNC`/`DISABLED` 需人工激活。✅ **2026-10-22 07:15 本地 dev 栈已重建生效**（`/v3/api-docs` paths=666、两条 explicit 在列、启动 0 ERROR；今晚柜测走 vicp 隧道⇒本地栈，这条已够用），✅ **07:2x 生产 trade-service 已部署 9935133d**（健康检查 40s 内 healthy）。判据 = 柜在线时 `jiangyi_model_deployment` 走 SENT→CONFIRMED（现值 **0 行**⇒我方从未发过模型，这是上电后第一个动作）。⚠️ 待发载荷已从本柜旧 logcat 逐字取到（→ G-15），厂商那句机型问法**不再需要**。 | **我方** |
| **G-15** | 🔴 **覆盖雪碧的商品模型已到手，零厂商依赖**（2026-10-22 00:4x 从真机旧 logcat 挖出） | `.tmp/log4.txt` 里 09-28 / 10-08 的三条 `updateModel` 载荷给出 `modelUrl`+`textUrl`+`quantity`，全部在同一个公共 dev 桶：`hysxr20260930120329732_20260930120413844_3_50_3576.rknn`(69 类) 与 `ZQ_hysxr…_3_50_3576.rknn`(65 类)。实测：三个 `.rknn` HEAD 仍 **200**；classes 行数与 `quantity` 逐字相等（69/65）；§5.5.3 解出 **67 / 63 行**（64 / 60 行带 `barCode`），`classId` 范围 0~68 / 0~64 ⇒ **0-based 行号在 69 行真文件上再次自证**；🔴 **雪碧 = `stdSkuId 1018 / 6954767430386 / textName kkkl_xb_qsnmw_pttz_330ml`，在 65 类模型是 classId 25、在 69 类模型是 classId 29** ⇒ 结算链的输入端不再缺东西 | 判据（柜上电后）：网关 `ws downstream updateModel` → §4.2.5 `downloadModelNotify` 回同 modelName → 真取一瓶雪碧 → `jiangyi_recognition_report` 出现 classId=29（或 25）→ `cabinet_order_line` 有真实单价×数量 ⇒ **第一条非 0 结算绿**。⚠️ **边界**：这批是**别的商户训练**的模型（`hysxr`/`ZQ_` 前缀），只作链路验证；上线仍须厂商把我方在售商品训进 426 的模型。⚠️ 文件名是 **rk3576**，与本柜 10-21 登记的 `industrial_control_model='88'` 冲突 ⇒ ✅ **2026-10-22 已按实据把该柜登记值改为 '76'**（用户拍板；`JIANGYI_GATEWAY_DESIGN.md` §7.6 同步改判）。⚠️ 仍未实测：这台柜的工控机**加载** 76 模型从未观察过，首推若无 `downloadModelNotify` 先怀疑取值。 | **我方**（下发）+ **厂商**（上线用模型须训进 426） |
| **G-16** | 🔴 **「按地址生成对照」会把唯一可结算的映射行打成不可用，且批量激活救不回来** | 源码逐行实证：`writePregenerated` 无条件 `setStatus("DISABLED")`（`JiangyiModelSyncService.java:142`），而 `upsert` 按 `UNIQUE(device_id, class_id)` 覆盖并**保留原 `source`**（`JiangyiClassMappingMapper.java:49`，本柜 classId 29 那行是 `MANUAL`+`ACTIVE`+sku 100013）⇒ 生成 69 行会把这行改成 `MANUAL`+`DISABLED`；「批量激活」条件要求 `source='MODEL_SYNC'`（同文件 :77）**命中不到它**，只能在识别映射页单条重新启用。识别解析只按 `deviceId+classId+ACTIVE` 点查（:15-19），一旦 DISABLED 就 fail-closed 转 DISPUTED | 判据：柜上电后**先只点「按地址发到柜机」**（`pushModelByUrl` 仅做行数校验、不写映射表，`JiangyiModelSyncService.java:181-190`）→ 拿到第一条非 0 结算绿；确需铺 69 行对照时，生成后**必须回识别映射页单条启用 classId 29** | 我方（操作顺序，非代码缺陷；若要改代码需先拍板「预生成是否该保留已激活行」） |
| **G-10** | **WS 下行 10 条未发**：`reboot`、`closeDoor`、`getDoorState`、`getDeviceInfo`、`sendQueryNetwork`、`sendChangeWifiCommand`、`sendDeviceLightStripFlag`、`openDoubleDoor`、`sendForceOpenDoubleDoor`、`sendOpenStockDoubleDoor` | 后 3 条单门柜**不需要**（⛔ 与选型一致）；前 7 条的代价=**现场运维全靠人手**（重启靠合闸、配网靠现场、门磁/设备信息查不到） | 按运维需求逐条开；优先 `reboot` + `getDoorState`/`getDeviceInfo`（排障最有用） | **待拍板**（不阻塞上线） |
| **G-11** | `waitGatherProduct` 死方法 | 无功能后果；误导后续读者以为采集链已通 | 删除或接进 G-5 流程 | 我方（顺手） |
| **G-12** | §5.4.x / §5.2.x **不接** | **不是缺口**。⛔ 模式一下订单不落将邑云；§5.2.14 是免鉴权写路径。**别当新缺口重提**（出处：`docs/JIANGYI_GATEWAY_DESIGN.md` §5 与 E-2 台账） | — | — |
| **G-13** | 非接口前置：**prod 库空 + 缺商户云凭据** | `jiangyi_device=0`、`merchant=0`；prod `.env.production` 的 `JIANGYI_MERCHANT_MOBILE/_PASSWORD` 长度 0 ⇒ 即使柜连上，prod 也拿不到登记与 `login2` 能力 | 运营台建商户+柜机、SN 登记并激活到 BOUND（判据=库里 status 字面量）+ 补两条凭据并重启 trade | **运营/运维**（顺序铁律：登记→激活→setDomain 不可颠倒） |

**一句话回答「对接还差什么」（2026-10-22 07:3x 订正，覆盖此前所有版本）**：文档第四章设备面 17 条 HTTP **一条不缺**；代码侧也已无卡点——②「缺覆盖在售商品的 modelUrl/classes」2026-10-22 00:4x 从本柜旧 logcat 到手（65/69 类商品模型，§5.5.3 解出**雪碧 = classId 25 / 29**，→ G-15），③ 三条模型回执变体已落码**且生产 `jiangyi-gateway` 已单独重建到 HEAD（2026-10-22 07:33，启动 0 ERROR）**——deploy-prod.sh 刻意只重建 trade-service，这条属脚本范围外、已人工补上；⑤ §5.5.3 官方解码器**已接入并只读落地（2026-10-22 09:2x，→ G-9）**：后台设备卡多一个「查厂商商品对照（只读）」，把 `textName` 拼音串翻成厂商品名+69 码并列出本平台同条码候选，**不写 `sku_id`**（拍板：自动挂=错扣款）。**真正还差的只剩三条**：① **柜没上电**（库里 `last_ws_online_at=2026-10-21 18:14` 北京时间，端到端 0 次；这条我方做不了）；② **我方一次模型都没发过**（`jiangyi_model_deployment=0 行`，显式下发入口已上线生效 → G-14），上电后第一个动作就是点「按地址发到柜机」；③ **首推若失败先怀疑 76/rk3576 从未实测**（→ H-10、G-15 边界），别判「通道不通」。另有操作顺序坑 **G-16**：别先点「生成对照」，它会把唯一可结算的 classId 29 行打成 DISABLED 且批量激活救不回来。自采自训 12 条（G-5）与 `getChessRoomOpenStatus`（G-7）不阻塞今晚；§5.4/§5.2/双门是**拍板不接**，不是忘。

---

## 10. H｜文档笔误与不一致（照抄会错的一律钉在这）

| # | 位置 | 文档写的 | 实际 | 我方处理 |
|---|---|---|---|---|
| H-1 | §4.2.4/§4.2.5/§4.2.13/§4.2.15–18 全部 | `devicelnfo`（小写 L）、`getArtificialCheckByldentifier`、`getDeviceSimlnfo`、`Deviceld` | `deviceInfo`、`ByIdentifier`、`ByDeviceId` | 我方按**正确拼写**建映射（Grep 文档 OCR 时注意） |
| H-2 | §4.2.13「设置视频上传模式」 | 路径写 `deviceInfo/getArtificialCheckByIdentifier` | 与 §4.2.11/12 同路径复用不是笔误，但**函数名与标题语义相反**（`ArtificialCheck`=人工复核，字段义=是否上传所有视频） | 回 `false`（只传异常视频），`isAutoUploadVideo` 同口径 |
| H-3 | §4.2.9「上报货柜正在使用中」 | 路径 `order/openDoubleDoorError`，描述行是从 §4.2.10 复制的「将识别结果上报」 | 真机实际走 `deviceInfo/uploadDeviceOpenStatus` | 两条都实现；`openDoubleDoorError` 仅审计 |
| H-4 | §4.2.16「上报门磁状态」 | 描述整段是广告接口的文字 | 路径 `getDoorInfo` + 参数 `doorState/lockState` 与标题一致 | 按标题+参数实现 |
| H-5 | §4.3.1 心跳 | 「机器端发送 `{msgType:heartBeat, msgContent:PONG}`」 | 固件发 **PING**，服务器回 PONG（log3 实测） | 不校验入值、恒回 PONG ⇒ 两形皆绿 |
| H-18 | 本表 10-22 早班批次的「`COMMON_URL` 比 `URL` 多一段 `/device`」 | 固件 `Constans.<clinit>` 把 `URL` 与 `COMMON_URL` 赋成**同一个串**（`Constans.smali:2396/2403` 与 `:2405/2408` 两次 `const-string` 完全相同 = `https://lingshouapi.hunanjysmart.com/device`），我方自钉 base 是 `…/jiangyi/api`；`InitAppServerInfoControl$19:144` 把回值**原样**写入 `URL`，`:146-156` 只做 `equals` 比较，**全程没有任何地方给 COMMON_URL 追加 `/device`** | nginx 里 `/jiangyi/api/device/…` 那四条（`uploadDoorState`/`uploadLockState`/`pokerOrderVideo/uploadVideoUrl`/`pokerOrder/uploadOrderError`）全部来自**字面自带 `/device/`** 的调用点（`UpDoorState.smali:1927`、`OrderVedioInfo2Server.smali:1576`、`CameraPreviewNew.smali:7184`），不是 base 带的；而 `tenant/getOrderRecheckProduct` 那 22 次裸命中出自 `BaseActivity:428` 的 **`URL`**，**不能**反过来当 COMMON_URL 的证据 ⇒ **COMMON_URL 现值在 nginx 里无从判明** | 设备面同一尾串的两种拼写一律都注册；只信「日志里见过哪种」会把没跑到的路径判成已验证 |
| H-19 | 早班「20 次 `/device/pokerOrder/uploadOrderError` ⇒ `M_NAME=YP`（唯一解）」 | 该 `/device` 路径有**六处**站点会发（`UploadInfo2Server.uploadErrorOther:1247`、`CameraPreviewNew:7184`、`OrderRknnResultDeal:2999`、`UploadIdentifyResult:57095`、`UploadLocalOrder:759`、`UploadInfo2ServerStock:1316`），六处逐个读了门都是 `equals(NORMAL)` 为真即 `return-void` ⇒ 只证到 **`≠NORMAL`**；`OkhttpUtils.postRequest`/`postRequest_json` **只对 `NO_NET` 短路**（`:759-774` 等四处），不拦 XY，而 XY 下这些站点照样发 | 真机那 20 次的 body 无法二分：`uploadErrorOther` 的 HashMap 键就叫 `errorMsg`/`errorDetailMessage`，与 `ErrorOrderEntity` 同名，我方日志只落这两个字段、没存原文 | 定档结论一律写 **`M_NAME ∉ {NORMAL, NO_NET}`，XY/YP 未分**；下次开机的判别探针＝**裸 `/pokerOrder/uploadOrderError` 若与 `/device` 那条成对出现 ⇒ 定 YP 且证明 COMMON_URL 落我方域**（`uploadError` 在 YP 腿会连发 JY+Other 两条，XY 在 `:cond_85` 就跳走了）。对 #90 无影响：`uploadBuyGoodsInfo2Server:645`（XY→`:cond_3f`、YP→`:cond_3f`、NO_NET→`:goto_3f`）三档同处置＝`dealNormalOrder` 只发 AIDL、零 HTTP |
| H-20 | 10-22 14:41:51 与 14:42:36 两次真机开门＝判据 **C 命中，A/B/D 全未检验** | 设备侧（UA `okhttp`）14:41–14:43 窗口只打了 8 条：`tenant/getOrderRecheckProduct`×2、`floor/getDeviceSellProductClassIds`×2（我方回 `classIds=[29]`）、`device/uploadLockState`×2、`device/uploadDoorState`×2、`device/pokerOrder/uploadOrderError`×2、`device/pokerOrderVideo/uploadVideoUrl`×1、`aliYunOss/getTempUploadToken`×1 ⇒ **`orderProduct/addRecognitionGoodsToOrder`（A）与 `pokerOrderProduct/addProductsToOrder`（B）两条腿 0 命中**，`jiangyi_recognition_report` 仍 **0 行**，`device_sku_inventory` 仍 1（未扣款，符合 fail-closed） | 🔴 **本轮没有「识别成功」样本**：两次都是设备主动转人工（14:42:09 `errorMsg=再次确认`，成因 **`幻影放回抑制链冲突转人工:T2 kkkl_xb_qsnmw_pttz_330ml(OUT计费与被抑制IN同链不可分)`**＋cam=1 调光未确认；14:42:54 `errorMsg=人工复核`，成因两摄 `开门后调光未确认:reason=settle_budget`）⇒ 既没证成 AIDL 档（D），也没暴露「第四档 ⇒ 我方缺 `addProductsToOrder` 路由」（B）。我在 14:37 把 D 写成「最可能」属**无成功样本时的外推**，本轮不改变「XY / YP / 第四档」三态未分 | ①潜伏缺口如实登记、**不提前实现**：`addProductsToOrder` 固件有两处字面——`UploadInfo2Server:349`（`COMMON_URL`+裸串，JY 腿在 `M_NAME≠NORMAL` 时改走它）与 `:625`（`URL`+`/device` 串，且受 `IS_OPEN_DOOR_ALAWYS` 开关），我方**均未注册**，真打到就是 401/404，责任在我方缺路由（payload 未文档化，等实路径出现再补）；②下轮要拿的判别样本＝**一次干净的取货**（只拿一瓶、手不碰其它货道、不放回、取完即关门），若出现 `addRecognitionGoodsToOrder` ⇒ 第四档、补路由即可通；若仍零上行 ⇒ 才轮到 AIDL 档成立 |
| H-21 | 10-22 14:53:05 一次「干净取货」＝**又落 `uploadOrderError`（判据 C），但设备自报成因被现场协议消掉两条** | 会话 `1792651983860343089366` DISPUTED、不扣款，`fail_reason` 尾部原文 `人工复核｜开门后调光未确认:cam=1,reason=settle_budget；原图证据不足，保留复核` ⇒ **14:42 的 `幻影放回抑制链冲突` 与 11:43 的 `副键弱证据换类（魔爪/雪碧）` 本轮均未复现**（膜已撕、柜内 3 瓶、另开一次门只取一瓶、不举到镜头前、取完即关门）；`addRecognitionGoodsToOrder`/`addProductsToOrder` 仍 **0 命中**、`jiangyi_recognition_report` 仍 **0 行**、`device_sku_inventory` 仍 3、视频 14:53:47 迟到 31 秒才落 `jiangyi_order_video` | 🔴 **#90 剩下的唯一设备侧闸＝`cam=1 reason=settle_budget`「开门后调光未确认」**，与商品/保护膜/开门时长/动作序列全无关（#92 已证 39.2s 也不消），我方无旋钮 ⇒ 只能厂商解释或修。另：网关日志 `addRecognitionGoodsToOrder` 那 4 条命中全是本人 14:26 的 `curl` 探针（`outcome=missing_token remote=172.18.0.12`＋401） | ①现场协议**已验证有效**，后续取证一律按「放/取分两次开门、只取一瓶、不放回、取完即关门」；②判「设备打到过某端点」**只认 nginx 台账 `UA=okhttp/4.9.3`**，应用日志同字面须先看 remote；③判「视频缺失」要等 ≥40 秒再查库 |
| H-6 | §4.3.2.11 拉取视频示例 | `msgContent:"24005355433545_MSG"` | 固件 `DealSocketMsg.smali:186-200` 按 `/sdcard/{orderNo}-left.mp4` 找文件 ⇒ **不带 `_MSG`** | 我方透传会话 id，不加后缀 |
| H-7 | V1.13 §4.4.4.4 `industrialControlModel` | 中文说明也叫「模型类型」 | 厂商后台弹窗里对得上商户 API 的是**「工控型号」**那栏；「模型类型」是另一档（大模型/基础模型/商品模型，**商户 API 无对应字段**） | 校验只做字符串相等、不解释语义；默认登记 `88`（2026-10-10 拍板） |
| H-8 | V16 第四章 vs 第五章 | 两章各有一套 §x.2 设备面 | **入向面逐条完全一致**，第五章只多 3 条（§G-12）；`domainConfig/*` 两条都**没有**（固件自用未文档化面） | 以第四章为契约，`domainConfig` 走自钉 |
| H-9 | 文件名判模型类型 | — | 🔴 **不可靠**（实证推翻）：厂商「大模型」列表里的 `best_800rk3588_0310_800senpt.rknn` 载荷 `modelType:"product"` | 只信 `modelType` |
| H-10 | 工控机型线索 | 文档全文 grep「工控」**0 命中**（V16） | 真机下载的是 **rk3576** 系列（log4：`best640s_box_rk3576_0708enpt.rknn`、`ZQ_..._3_50_3576.rknn`、`best_800rk3576_0310_800senpt.rknn`），而 10-21 厂商后台推给我方的是 **rk3588** + `industrialControlModel:"88"` | ⚠️ **今晚测试前必读**：若 `updateModel` 真到设备而模型加载失败，**先怀疑 76/88 与 rknn 编译目标不匹配**，别判「通道不通」。76/88 语义=厂商问题清单在案项 |
| H-12 | V1.13 §4.3.6.4 `:1689` + §4.3.11.4 `:1932` | 「图片不足2000张」出现在**错误查询响应的括注**和**上传状态枚举的标签**里 | 两处都**不是门槛**：前者逐字「也是返回 no，**不算错误**」，后者只是 `success/fail/wait/weak` 四档标签。真数在 V16 §5.6 引言「**提交合格图片 200 张就可以了**」 | ⛔ 别再引用"自采要 2000 张"（我方 2026-10-21 一度据此判"自采不现实"，已更正） |
| H-13 | V1.13 §4.1.2 `/merchant/stdSku/getList` | 请求参数只有 `name`(O) / `barCode`(O) / `applyMachineId`(M 固定 21)，**无 page/pageSize**；响应参数也无 `total` | 🔴 **空查询回 20 条是服务端截断，不是库总量**。实测：`name=雪碧` 回 11 条（id 最大 **4083**）、`name=可口可乐` 回 20 条且 id 从 127 散到 3061 ⇒ 库里**至少 4000+ 条**标准商品 | 判"某商品在不在厂商库"**只能带 `barCode` 精确查**，不能拿列表长度或首条当结论（`.tmp/probe-stdsku.mjs`）。雪碧 `6954767430386` 精确命中 1 条 = `id 1018 / JY-001018`，与我方 `sku_jiangyi_link` 逐字对上 |
| H-14 | 同库脏行 | 「雪碧」11 条里 `id=3563 test雪碧听装330ml`、`id=4083 雪碧330ml` 两条 `barCode=null`，另有 4 条同 330ml 规格不同 69 码 | 名称模糊搜索**必然**命中测试/仿行 | 已按 H-13 收紧：`SkuJiangyiLinkService.pullTextName` 2026-10-22 起「条码精确相等且 textName 唯一」才回填，多条不同 textName ⇒ 409 列候选要求人工（此前是循环里最后一条静默覆盖） |
| H-15 | §5.5.3 的用法（2026-10-22 实测确立） | 接口中文名「根据 txt 文件地址查询商品信息」+ §4.3.2.9 把 `quantity` 说明写成「模型**商品**数量」、`textUrl`「模型对应 class 下载地址」 | ⇒ **classes 的每一行就是一个商品名**，所以「某个模型能不能用于结算」**不必问厂商**，直接拿它的 `textUrl` 调 §5.5.3 就自证 | 🔴 判据：返回行的 `barCode` 必须能对上我方在售 69 码。实测 10-21 那份 `_auto_6` 载荷（`classes_auto_6.txt`）只回 **2 行** `egg→stdSkuId 1911「测试egg」`、`toy→2423「测试toy」`，`barCode` 均 `null`，`bottle/bowl/bag/box` 四行整条消失 ⇒ **通用包装形态模型 = 结算不可用**，只能用于下行通道验证（任务 #75）。`textName` 与 `stdSkuName` 是模糊匹配（`egg`→`测试egg`），别指望它精确回原名。探针 `.tmp/probe-553.mjs`（改 URL 即可复查任何模型） |
| H-16 | 真机 logcat（`.tmp/log2/3/4.txt`、`device-log.txt`，本柜自报 `identifier=CQYB10720`） | 模型下行的 msgType **不止文档 §4.3.2.9 的 `updateModel`** | 实测四档**下行 16/16/8/8 + 同名回执 8/8/4/4**：`updateModel`、`updateThirdModel`、`updateBaseModel`、`updateBigModel`。🔴 **各档用的 classes 文件不同**：`updateModel`→`…_3.txt`（quantity 65/69，真商品数）；`updateBaseModel`/`updateThirdModel`→`classes_auto7.txt`（hand/bottle/bowl/bag/box/egg/toy）；`updateBigModel`→**`classes_auto_6.txt`** ⇒ **10-21 厂商后台点的那次下发（quantity 6 + classes_auto_6）用的是「大模型」档同一个 classes**，它从来不是商品模型，这解释了为什么它不可能识别 SKU。⚠️ 但厂商后台 HTTP 载荷写的是 `modelType:"product"` ⇒ `modelType`→WS msgType 的映射**是我方按 classes 文件名反推的，未实测**，别当定论；我方网关目前只发 `updateModel`（商品模型档），与此一致 | 若柜在线后推 65/69 类模型仍不加载，按档位（而非通道）排查；台账里 `update*ModelSure` 四档我方均已合并到同一 handler（G-3） |
| H-11 | §4.2.5 返回说明 vs 示例 | 说明栏 `data` =「数据 / 对象 / 无用，固定格式」（v16:783-786），**json 示例却是 `"data":"0"`**（v16:789） | 同一节自相矛盾 | **照抄示例**（我方 `ok()` 回 `data:"0"`）——说明栏那三个词不构成字段规格；我 10-21 起草本表时曾把它判成「我方偏离云侧」，被 v16:789 推翻，已订正 |
| H-17 | §5.5.3 返回参数表 + json 示例（v16:6008-6042） | 参数表写 `Skuld`/`stdSkuName`/`class`，json 示例写 `"stdSkuld"`/`"name"`/`"classId"`——**同一节两套键名** | 🔴 真机回包两套都不是：`.tmp/probe-553-real.mjs` 2026-10-22 逐字打印 = `stdSkuId` / `stdSkuName` / `textName` / `barCode` / `classId` | 编码**只按真机回包**（`JiangyiGatherClient.stdSkuByTextNames:106`）。教训：文档示例连键名都不可信，凡是「照抄示例字段名」的接入点都要先打一次真回包 |

## 11. I｜本表怎么复核出来的（含被抓到的错）

- 三条独立盘点对撞：① V16 第四章/第五章逐节抠路径+行号；② V1.13 逐节抠 `/merchant/`；③ 我方 `jiangyi-gateway` / `trade-service` 逐 controller/client 抠映射与方法。设备路径计数**我本人重数过**：46 具体 + 1 别名 + catch-all。
- **抓到的子代理错**：① 漏报 `OrderVideoReportController.java:43`（差点被写成「§4.2.14 未实现」的假缺口）；② 设备路径计数偏低。⇒ 本表所有「未实现」结论逐条回源码确认后才落笔。
- **抓到的我自己的旧结论错**：① 「我方无法从 API 判某模型覆盖了哪些 stdSku」——**错**，V16 §5.5.3 就是干这个的（§G-9）；② Track E 台账里 `confirmActivate`「全仓零调用/新柜无激活通道」——**已闭合**（`JiangyiMerchantClient.java:120`、`AdminJiangyiController.java:211`）；③ 本表初稿把 §4.2.5 我方响应判成「偏离云侧实测」——**被文档自己的 json 示例推翻**（v16:789 逐字 `"data":"0"`），改记 §H-11。
- **2026-10-22 14:4x 抓到自己在开跑前的一处外推**：我把「识别成功 ⇒ 零 HTTP（AIDL 档）」写成「最可能的结果」，但 14:41/14:42 两轮实测**根本没有成功样本**（设备两次都主动转人工），所以这条既没被证实也没被证伪 ⇒ 定档仍是 `M_NAME ∉ {NORMAL, NO_NET}`、XY/YP/第四档三态未分（→ H-20）。**教训＝把「按代码推演最可能」写成「本轮会验证到」，会让下一轮跑空手而归**；判别探针要先确认「本轮有没有可能产生那个分支的样本」。
- **2026-10-22 14:2x 同批改掉我的第二处过度断言**：「20 次 `/device/pokerOrder/uploadOrderError` ⇒ `M_NAME` 唯一解 = YP」。逐站点重读后发现该路径有六处调用点、且 `OkhttpUtils` 只拦 NO_NET 不拦 XY ⇒ 证据只撑到 `∉{NORMAL, NO_NET}`（→ H-19）。**教训＝一条路径被多处代码共用时，它只能证「这些处的共同门」，不能证「我挑的那一处」**；本表凡按「设备发了 X 所以是 Y 发的」定的档，都要回查 X 还有谁在发。
- **2026-10-22 14:0x 抓到自己今日早班的一条假判据**：为「COMMON_URL 在我方域多一段 `/device`」举的 nginx 实证（`/jiangyi/api/device/token/openDoorDeviceStatus` 2 次）**是我方自己的 curl 探测**（UA=`curl/8.13.0`、来源 172.18.0.1、10-20 06:42），不是设备。⇒ 该结论降级为「无从判明」，处置改成两种拼写都收（H-18）。
- **2026-10-22 00:0x–00:2x 复核（用户三连质疑「v1.13 看了吗 / 只有雪碧是真的 / 自采不需要那么多图 / 确定要他们把模型挂到租户？」）——四处旧结论被推翻，逐条**：
  ① 「自采逐商品约 2000 张」**错**（→ H-12，真数 200 张，V16 §5.6 引言）；
  ② 「其余 10 个 SKU 要补挂接」**作废**（用户口径：真商品只有雪碧，且 `6954767430386` 实测精确命中 stdSku 1018、`sku_jiangyi_link` 已 BOUND ⇒ 真商品侧挂接是完成态）；
  ③ 「商品库 20 条」**是被服务端截断的假象**（→ H-13，`name=雪碧` 回 11 条、id 到 4083 ⇒ 至少 4000+ 条；挂接判据必须带 `barCode` 精确查）；
  ④ 🔴 **「必须让厂商把模型挂到租户 426」不是厂商侧限制，是我方 `requireModel` 自己加的门**（→ G-14）：V16 §4.3.2.9 载荷全是裸 URL 字符串、§5.5.3 入参就是裸 `modelTextUrl`、我方网关 internal `model-push` 收显式 URL 且不查模型库 ⇒ 要问厂商的只有一句话「覆盖 6954767430386 的 88 模型，modelUrl 和 classes 地址各是什么」。
  同批实测：**§5.5.3 端点是活的**（`status:200`，租户 426 有权调），且其 `classId` 与 classes.txt **0-based 行号逐字一致** ⇒ `JiangyiClassesParser` 的「0/1-based 文档未明示」悬案由实测判死（→ §8 该行、G-9）。
  代码落点（未提交）：`SkuJiangyiLinkService.pullTextName` 改「条码精确相等且 textName 唯一才回填，多条不同 textName ⇒ 409 列候选、空 textName ⇒ 404 不清值」，`SkuJiangyiLinkServiceTest` 26 条全绿（新增 3 条）。
- **仍未实锤、不许当结论用**的四条：G-4 里 `[{barCode,textName,bgType}]` 的归属、G-5 的「采集由柜机 APK 自走」、H-10 的 76/88↔rknn 目标对应、`floor/getFloorByDevice` 真机是否会请求（~~真机 0 命中只说明没跑过补货开门~~ **10-22 二次已判**：本表 0 命中与「对接版本非 NORMAL」一致，但**不能**定档到 YP ⇒ 见 H-19（`M_NAME ∉ {NORMAL, NO_NET}`，XY/YP 未分））。
- **全文覆盖度（2026-10-21 23:10 被问「确定文档都核对了？」后补的一次穷举）**：V16 目录里**所有**节都已打开看过，不是只挑 §4.2/§5.2/§5.4/§5.5/§5.6——
  §4.1 与 §5.1 是流程叙述**无端点**；§4.2（17 条，§2 表）；§4.3.1 上行只定义 `heartBeat`（§5 表）；§4.3.2 下行 17 条（§4 表）；
  **§5.3「服务器 WebSocket 接口」（v16:3881）此前确实没看，本次补看**：只列 5 条下行（`reboot`/`openDoor`/`closeDoor`/带订单号 `openDoor`/`openDoubleDoor`），
  **是 §4.3.2 的真子集⇒不新增任何 msgType**，§4 表不用改；§5.2 独有 3 条（§7 表，⛔）；§5.4 出向 9 条（§6 表）；§5.5/§5.6 读面 6 条（§8 表）。
  V1.13 全文到 §4.6.1 结束（2984 行），`/merchant/` 唯一路径 34 条已全部进 §6 表 ⇒ **两份文档没有第六个面**。
- **2026-10-21 23:15 落码批次（用户授权 ①②，未提交）**：G-3（四条回执同映射）+ G-8（WS 未知上行 WARN 台账）+ `DeviceUnknownPathController` 头注释里
  `domainConfig/*`「不接管」的**过期表述**（10-20 起已改自钉）；G-2 的白名单条目按**重建容器**而非重建镜像落地（jar 早已绑定 env，见 G-2 格）。
  验证：`mvn -o -B -pl services/jiangyi-gateway test` 28 条单测全绿（含新增 4 条）+ `verify` 阶段 IT 全绿 BUILD SUCCESS；trade-service 重建后 healthy、`data:[]` 实跑留证。
  **已部署到 dev 栈**：gateway 镜像重建 + 容器重建（23:39），判据=运行中容器 `/app/app.jar` 里
  `DeviceInfoController.class` 含 `downloadThirdModelNotify`、`DeviceWebSocketHandler.class` 含 `JIANGYI_WS_MSG_UNHANDLED`（各 1 次，`docker cp` + `unzip -p` 实证，
  避免「改了没生效」那类假绿）；trade-service 用 `--no-deps --no-build` **只重建容器不重建镜像** ⇒ 并行会话未提交的
  `SkuDelistReview*` 脏文件没有被打进任何镜像。
- **2026-10-22 14:3x 独立复核（另一会话，用户令核对「本表 ↔ 两份 PDF ↔ 代码」一致性）**：
  ① **文档侧全对**。从两份 PDF 派生文本逐条重抽比对：§4.2 十七条、§4.3.2 十七条 msgType、§5.2 三条、§5.4 九条、§5.5/§5.6 八条、V1.13 三十四条，**路径+行号全部命中**；语义钉死项复核通过——§5.6 引言「提交合格图片 200 张就可以了」（v16:6066 附近逐字）、§5.2.14「并不用token 验证」（v16:3833 逐字）、§4.4.4「请求参数(无)」、§5.5.3 同节两套键名（参数表 `Skuld`/`class` vs 示例 `stdSkuld`/`name`/`classId`）。仅三处行号漂移（派生文本再生，内容逐字不变）：v113 1689→1691、1932→1935；confirmActivate v16 5378→5377。
  ② **代码侧路径全在，但本表大量「文件:行号」已过期**（G-3/G-9/G-14 插行后未刷新；漂 +5～+26，符号全部存在）：`DeviceInfoController` getArtificialCheck :145→:157、receiveCurrentTxtUrl :165→:177；`JiangyiGatherClient` getTopList :100→:126、create :116→:142、waitGatherProduct :130→:156、gatherOpenDoor :138→:164、doorStatus :145→:171、getPageData :156→:182、commitTraining :180→:206、studyingProduct :185→:211、getModelFileByApi :193→:219、gatherCheck :218→:244、login2 :252→:278；`DeviceReportController` sync :360→:385、isAutoUploadVideo :379→:404、openDoubleDoorError :387→:412、uploadFaultOrder :396→:421、getDeviceSimInfo :411→:436、getDoorInfo :450→:475、changeWifi :459→:484、queryNetwork :467→:492；`DeviceExtensionController` getAdvertising :129→:138；`DeviceWebSocketHandler` 三条下行 send :217/:230/:250→msgType 实位 :222/:235/:255；`DeviceCommandController` :58/:67/:105→:45/:76/:95；`JiangyiModelSyncService` DISABLED :142→:236、pushModelByUrl :181-190→:275、requireModel :221→:351；G-9 行「`MSG_TYPE_UPDATE_MODEL`（ModelPushController.java:18）」实为 `DeviceWebSocketHandler.java:40`（:18 只是 javadoc 提及）。⇒ 本表行号一律按「符号名+大致位置」读，别点查。
  ③ 🔴 **运行时缺口（本次复核发现，未闭合）**：10-22 双前缀修复（§4.2.10/§4.2.11/floor 两拼写，防御性双收）**只在工作区、未提交**（源文件 mtime 14:12），而本地 jiangyi-gateway 容器 **~11:00 已启动**——`docker cp`+解包实证容器内 `DeviceReportController.class` 无 `/jiangyi/api/device/orderProduct/…` 与 `/order/uploadErrorOrder`、`DeviceExtensionController.class` 无 `/jiangyi/api/device/floor/…`（G-3 三档 notify 在容器 ✓ ⇒ 容器=10-21 版本）。设备到底打不打 `/device` 前缀本就「无从判明」（见上 14:0x 条），防御双收正是为此——**柜测前必须重建 gateway 容器**，否则一旦设备真走该拼写，识别/异常上报落 catch-all 404、且无人察觉台账以外的事。
  **15:5x 订正**：该修复的「未提交」半句已作废——源码已随 `b734741d` 落 dev 分支工作树；但**「容器内 jar 是否已含这四种拼写」仍未复核**，判据照旧是 `docker cp` 出 `/app/app.jar` 后在 `DeviceReportController.class` / `DeviceExtensionController.class` 字节里查路径字面量（镜像无 unzip/python，别用 `unzip` 探测），**别把「已提交」当「已生效」**。
  ④ 本表 §3 顶部「DeviceUnknownPathController 注释仍列 domainConfig 不接管」的 ⚠️ 本身已过时（注释 10-20 起已改「已从本清单移出」，工作区实证）；但该注释同段「一切资金前缀（orderProduct/**、device/pokerOrder/**）刻意失败」与 10-22 双前缀显式注册形成新的表述矛盾（显式映射优先、行为正确，注释待同步）。
  ⑤ 工作区实跑：`mvn -o -B -pl services/jiangyi-gateway verify` **137 单测 + 22 IT 全绿 BUILD SUCCESS**（DeviceCompatRoutingIT 9 条含 10-22 新增 2 条双前缀用例）。

## 12. 相关文档

- `docs/JIANGYI_GATEWAY_DESIGN.md` §2 端点契约 / §5 已知边界与待办（本表是它的**逐条展开**，冲突处以本表的行号证据为准，并回去订正对方）
- `docs/archive/audit/AUDIT_TRACK_E_JIANGYI_INTERFACE_GAP_2026-10-13.md`（E-2 选型结论仍有效；R2 与 confirmActivate 已实现）
- `将邑对接资料/将邑socketUrl缓存卡点_分析与人厂沟通清单_2026-10-19.md`（域名/WS 目标切换机制，最新修订 10）
- `docs/JIANGYI_LOCAL_TUNNEL_FALLBACK.md`（断点调试备用入口）

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
| B WS 下行（V16 §4.3.2.1–17） | **17** | **7** 个 msgType（+1 共用字面量；2026-10-22 18:0x 纠正：旧记 6 漏了 `getDeviceInfo`） | **9 未发**（§G-10） |
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
| `order/setOrderNoVideo` | catch-all ⇒ **404** | ❌ **2026-10-23 新台账**：柜机 `EG943322621391` 自 09:46 起**每 3 分钟一次、56 次全部同一 `transactionId=1792657421176609136722`**（`docker logs ai-cabinet-jiangyi-gateway-1 --since 24h` 里 `JIANGYI_PATH_UNIMPLEMENTED uri=/jiangyi/api/order/setOrderNoVideo` 计数），body=`{tenantType=common, serialNum=1, videoQuantity=1, transactionId=…}` ⇒ 设备把「本单没有视频」这件事**报不成功就一直重投**，是**无退避的永久重试**，不是偶发。定性：V1.13 商户文档 / V16 全文 **零命中**（`grep -i setOrderNoVideo` 在 `.tmp/doc_v113.txt`、`将邑对接资料/doc_full_text.txt`、`extracted_text.txt`、`将邑对接确认记录.txt` 各 0 行），它是厂商**设备侧私有协议**——调用点 `.tmp/jy563/smali/com/rockchip/gpadc/demo/control/generateOrderVideo/tool/OrderVedioInfo2Server.smali:1022`、`:1140`，拼法是 `Constans.COMMON_URL + "/order/setOrderNoVideo"` 走 `OkhttpUtils.postRequest` ⇒ 域名切我方后必然落到这里。**处置=维持 404 + WARN（G-7 先例）**：该路径在 `order/**` 资金前缀下，`DeviceUnknownPathController` 的类注释明文「资金类上报宁可让设备侧看到失败并重试/告警，也不能被我方吞成成功」⇒ 回 200 空值要先知道厂商期望的 data 形态，**禁照抄猜值**。见 §G-17 |
| `replenishmentHistoryBatch/uploadOpenMsg` / `replenishmentHistoryVideo/create` | catch-all | ⛔ 桩掉可接受（少一路厂商补货历史） |
| `deviceInfo/downloadBaseModelNotify` / `downloadBigModelNotify` / `downloadThirdModelNotify` | `DeviceInfoController.java:125-129`（与 §4.2.5 同一 handler） | ✅ **2026-10-21 落码**（原为 catch-all 404）：固件三档模型回执各一条独立路径（log4 行 24231/30621/17847，body 与 §4.2.5 逐字同形 `{identifier,modelName}`）。三档语义**未向厂商核实⇒不区分处理**，错档回执由 trade `confirmModel` 的 modelName 匹配（不匹配 409）挡住，不会伪造 CONFIRMED。判据待真机：开机一轮 4 条 notify 全 200（§G-3） |
| `pokerOrder/getChessRoomOpenStatus` | catch-all ⇒ **404** | ❌ 🔴 **2026-10-22 18:0x 按 nginx 逐秒重算，旧记「真机每 20s 一次固定轮询」作废**：真机共 **98** 次 okhttp 命中，形态是**事件触发的 burst**（每组 3–6 条、间隔 1–2s，10-22 散布在 03:43/04:44/06:02/06:26/07:03/07:50/08:07/08:30/09:42 UTC；**09:42:06 那条正落在网关 17:38 重建后设备重连的同一秒**）⇒ 触发点像「连上门/状态变化」而非定时器。🔴 **且这 98 次里 401=70、404=28，同一次 burst 内两种码并存**（如 08:30:55–57 三 401 + 一 404）⇒ 该路径有**两个带不同凭据的调用点**，401 那条带的不是我方签发（或缺失）的 token；**它是全表唯一长期吃 401 的路径**（其他路径的 401 全集中在 10-19 无 token 期）⇒ **即便补了 200 路由，3/4 的调用仍然进不来**。语义/云侧响应**未取证**⇒ 禁止照抄猜值；见 §G-7 |

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
| §4.3.2.13 获取设备基本信息 | `getDeviceInfo`（文档 OCR 作 `getDevicelnfo`） | v16:2468-2469 | `DeviceCommandController.java:76`（入口 `POST /api/v2/ops/admin/devices/{id}/jiangyi/device-info`） | ✅ **已实现，🔴 但真机从未点过**（2026-10-22 18:0x 复核纠正本表旧记「❌ 未接」——该行漏读了上面这个文件:行；代码自 2026-10-15 `167f51d7` 就在）。回执是 HTTP 上行 §4.2.15 `deviceInfo/getDeviceSimInfo`，同样 **0 命中** ⇒ 判据见 §9B R2 |
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
| V1.13 §4.1.6 | `merchant/product/delete` | v113:881 | `JiangyiGatherClient.productDelete` | 🟢 **2026-10-23 已接并真跑通**：`DELETE /skus/{skuId}/jiangyi-link/{linkId}/jiangyi-product`，🔴 **只放开 `CREATED`**（`BOUND` 是标准库共享条目 ⇒ 409 让走解挂）；厂商侧失败**不动**我方挂接行（502），成功才 RETIRED。11:28 删 41159 实测：厂商 `waitGatherProduct` 归零＝**真删**（→ §9B.8） |
| V1.13 §4.1.7 | `merchant/product/updateMainImg` | v113:916 | — | ❌ 未接（换主图不同步）。⚠️ 文档这条路径 OCR 成 `updateMainlmg`（小写 L），照抄必 404 ⇒ 见 H-24 |
| V1.13 §4.2.2 / §4.3.2 | `merchant/selfhelpGatherProduct/waitGatherProduct` | v113:1005 / 1273 | `JiangyiGatherClient.waitGatherProduct(name)` | 🟢 **2026-10-23 已接（只读）**：`GET /jiangyi/pending-gather` → 挂接弹窗「将邑待采集」。回包**无 `barCode` 字段**；`name` 真过滤、**传条码空转**（§G-11） |
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
| **G-5** | **V1.13 §4.3.3–4.3.14 采集编排 12 条全无**（🔴 **「要不要问厂商」这一问已被文档自己回答，2026-10-23**） | 「上架厂商库里没有的商品」这条业务路径**我方无法驱动**。⚠️ 旧记「只能求厂商或走人工 / 必须有厂商商户 App 账号」**是误读**：文档标题的「移动端」= 调这批 REST 的**客户端角色**，不是手机 App——§4.3.3 采集准备/§4.3.4 摄像头就绪/§4.3.5 开始采集/§4.3.7 结束当次 的入参**只有 `identifier`(设备编号) + 商品 id**，没有任何手机专有输入，而 §4.3.4 的名字本身就说明**拍照的是柜机自己的摄像头**；凭据就是我们已经在用的 `login2` token（§4.1.5 新增、§4.2.3 开门、§4.4.3 学习都是我方服务端在调）⇒ 这个角色我方本来就能当 | `gather/*` 页能走完 新增→开门→采集→完成批次→上传 五步 | **待拍板（纯实现范围，不再卡厂商问句）**：12 条接口 + 一次真机操作（要有人站在柜前放商品、关门）。2026-10-23 用户先选了「只读 + 修 41159，向导下一轮」⇒ 本轮只落 §4.3.2 只读（→ G-11）。logcat 里 `selfhelpGatherProduct` 0 命中仍**不能**当反证（从未跑过采集） |
| **G-6** | **采集开门经将邑云，而柜 WS 已指我方** | `JiangyiGatherService.startGather`（`:70-88`）→ `gatherOpenDoor`（`JiangyiGatherClient.java:138`）打到厂商云；厂商若要开这台柜的门，得走它自己的 WS 会话——**它已经没有了**（设备 `COMMON_URL` 与 prefs `socketurl` 都是我方）⇒ 采集开门可能**结构性打不开门** | 真点一次 `gather/start`，看柜是否开门 + 我方 nginx 是否收到 `device/uploadDoorState` | **我方**（若开门不到，改法=我方 `sendOpenStockDoor` 顶替，属设计变更需拍板）。⚠️ 顺带：`waitGatherProduct` 已接进后台只读展示（→ G-11，不再是死代码）。🔴 **2026-10-23 只读探针（不改变本条结论）**：`/merchant/deviceInfo/getIdentifier` 用 `deviceSn=YPKJ1JYKJ00000000000250` 回 `"CQYB10720"`（与库里 `jiangyi_device.identifier` 逐字一致 ⇒ 编号可自解，不必人工抄）；`doorStatus(CQYB10720)`→`"close"`、`goOnGather`→`"wait"`（§4.3.9.4：等待设备返回状态）、`uploadInfoNew`→`null`、`gatherProducts`→**503**、`studyingProduct`→`[]`。⚠️ **`doorStatus` 有值不等于厂商手里有活会话**——它完全可能是库里存的门态，本条的「结构性打不开门」仍未证伪，判据只有一次真的 `gatherOpenDoor` |
| **G-7** | **`pokerOrder/getChessRoomOpenStatus` 真机 98 次命中全失败（70×401 + 28×404）** | 触发形态见 §3 A′ 同路径行（🔴 旧记「每 20s 固定轮询」已按 nginx 逐秒重算作废；实为事件触发的 3–6 条 burst）。语义未知⇒每次都走设备失败分支，日志噪音 + 设备侧可能有重试/告警副作用。🔴 **新增硬事实：401 占 70/98 ⇒ 光补路由不够，还得先定「另一条腿带的是什么 token」**（判据见 §9B R7） | 🔴 **2026-10-22 新证据：契约不用问厂商，我方自己那个安装包里就有**（`.tmp/jadxtool/smali/.../control/DealStringMsg.smali` + `DealStringMsg$9.smali`）：`getLockState()`/`getLockStateOther()` POST `{identifier}`；回调成功判据是**字面量含 `"status":200`** → 解 `BaseEntity` → `getData()` 字符串 **`equals("open")`** → `OpenLockStateRecord.writeLockState(Z)`。⚠️ **但真/假两条分支随后都调 `DealStringMsg->openLock()`**（`:goto_35` / `:cond_3b`）⇒ 我方回 `open` 还是回 `closed` 都会触发一次开锁动作，差别只在是否写那条本地记录——**这正是不能顺手回个 200 的原因**。仍未取证：404 时 okhttp 走 `onError` 还是 `onFailed`（决定「补 200」到底改变了什么） | **维持 404 + WARN 台账**（禁照抄猜值）。要接必须先满足其一：① 厂商一句话给语义；② 可控实测看 404→200 后设备行为是否变化。厂商催办清单里此项**优先级低于上电** |
| **G-8** | ~~WS 未知上行静默~~ → **已落码**（2026-10-21 23:15，`DeviceWebSocketHandler.java:186`） | 原状：HTTP 缺口有 `JIANGYI_PATH_UNIMPLEMENTED` WARN 台账，WS 缺口是 `log.debug` ⇒ 生产 INFO 阈值下**等于静默丢帧**。现改 `JIANGYI_WS_MSG_UNHANDLED` WARN（带 msgType + 原文），仍**不当事务数据**处理（铁律不变） | 真机连上后台账出现真实未处理 msgType（预期候选：`sendDeviceDomainSure` 5-6 次、`sendDeviceUsedStatusSure` 3 次、`uploadLogSure` 2-4 次、`rebootSure` 2 次——log2/3/4 tally） | **已完成**（+2 单测：两条未知帧各产生一条 WARN、心跳不得产生 WARN）。⚠️ 台账量级安全：这些都是**每条下行一次**的回执，不是周期帧；唯一的周期帧 heartBeat（1119 次/份日志）已单独处理 |
| **G-9** | ~~§5.5.3 `getStdSkuQueryByTextNames` 未用~~ → **✅ 2026-10-22 已接入（只读展示）** | 原状：`class-mappings` 挂接全靠**人工核对 `classId↔textName↔SKU`**（textName 是 `kkkl_xb_qsnmw_pttz_330ml` 这种拼音缩写串，人读不动） | 🔴 **拍板口径（用户 2026-10-22）：只作显示/辅助人工挂接，绝不写 `sku_id`**——§5.5.3 回的是**厂商标准商品**，不是我方 SKU；自动挂等于把「厂商认为这是什么」当成「这个柜按哪个价结算」，挂错即错扣款（与「结算绿须端侧挣得」同源） | 落码四处：`JiangyiGatherClient.stdSkuByTextNames`（:106，字段按**真机回包**取 `stdSkuId/stdSkuName/textName/barCode/classId`，文档 json 示例写的 `stdSkuld`/`name` 是错的 → H-17）；`JiangyiModelSyncService.decodeVendorSkuByClasses`（:125，**只读**）；`GET /devices/{id}/jiangyi/class-decode`（`AdminJiangyiController.java:355`，`ops:device:list`）；后台设备卡「查厂商商品对照（只读）」。**两个设计点**：① 合并键用 `textName` **不是** `classId`——该页有 classIdBase 旋钮，按 classId 合并在拨到 1 时整表错行且看不出来，厂商 classId 原样回显成 `vendorClassId` 让冲突肉眼可见；② 回包**稀疏**（69 行只回 67 行）⇒ 未解析行显式显示「厂商库无对应」，不拿别的行凑 | **我方**（已完成，23/23 单测含新增 7 条） |
| **G-14** | 🔴 **「必须让厂商把模型挂到租户 426」是我方自己加的门槛** | 复核依据（逐字）：① V16 §4.3.2.9 `updateModel` 的 `msgContent` 只有 `{quantity, modelUrl, textUrl, modelName}`——**全是 URL/字符串，协议层没有任何"模型须登记在租户名下"的要求**；② V16 §5.5.3 的入参就是**裸 `modelTextUrl` 字符串** ⇒ 厂商自己把 classes 当成"任意可传的地址"；③ 我方网关内部 `POST /internal/v1/jiangyi/devices/{id}/model-push` 收的正是显式 URL（`ModelPushRequest`），**不查 §4.4.4、不下 classes**。⇒ 把链堵死的是 `JiangyiModelSyncService.requireModel` 只认 §4.4.4（`[]` ⇒ 404），不是厂商侧的配置缺失 | ✅ **2026-10-22 已落码**（原建议「后台加显式入口」）：`POST /api/v2/ops/admin/devices/{id}/jiangyi/model-sync-explicit` 与 `.../model-push-explicit`，载荷 = §4.3.2.9 四字段（modelName/modelUrl/textUrl/quantity），**不再调 `requireModel`**；仍保留 BOUND + 机型已登记 + 无未决 SENT + classes 过 SSRF 白名单 + 行数==quantity，映射照旧落 `MODEL_SYNC`/`DISABLED` 需人工激活。✅ **2026-10-22 07:15 本地 dev 栈已重建生效**（`/v3/api-docs` paths=666、两条 explicit 在列、启动 0 ERROR；今晚柜测走 vicp 隧道⇒本地栈，这条已够用），✅ **07:2x 生产 trade-service 已部署 9935133d**（健康检查 40s 内 healthy）。判据 = 柜在线时 `jiangyi_model_deployment` 走 SENT→CONFIRMED（现值 **0 行**⇒我方从未发过模型，这是上电后第一个动作）。⚠️ 待发载荷已从本柜旧 logcat 逐字取到（→ G-15），厂商那句机型问法**不再需要**。 | **我方** |
| **G-15** | 🔴 **覆盖雪碧的商品模型已到手，零厂商依赖**（2026-10-22 00:4x 从真机旧 logcat 挖出） | `.tmp/log4.txt` 里 09-28 / 10-08 的三条 `updateModel` 载荷给出 `modelUrl`+`textUrl`+`quantity`，全部在同一个公共 dev 桶：`hysxr20260930120329732_20260930120413844_3_50_3576.rknn`(69 类) 与 `ZQ_hysxr…_3_50_3576.rknn`(65 类)。实测：三个 `.rknn` HEAD 仍 **200**；classes 行数与 `quantity` 逐字相等（69/65）；§5.5.3 解出 **67 / 63 行**（64 / 60 行带 `barCode`），`classId` 范围 0~68 / 0~64 ⇒ **0-based 行号在 69 行真文件上再次自证**；🔴 **雪碧 = `stdSkuId 1018 / 6954767430386 / textName kkkl_xb_qsnmw_pttz_330ml`，在 65 类模型是 classId 25、在 69 类模型是 classId 29** ⇒ 结算链的输入端不再缺东西 | 判据（柜上电后）：网关 `ws downstream updateModel` → §4.2.5 `downloadModelNotify` 回同 modelName → 真取一瓶雪碧 → `jiangyi_recognition_report` 出现 classId=29（或 25）→ `cabinet_order_line` 有真实单价×数量 ⇒ **第一条非 0 结算绿**。⚠️ **边界**：这批是**别的商户训练**的模型（`hysxr`/`ZQ_` 前缀），只作链路验证；上线仍须厂商把我方在售商品训进 426 的模型。⚠️ 文件名是 **rk3576**，与本柜 10-21 登记的 `industrial_control_model='88'` 冲突 ⇒ ✅ **2026-10-22 已按实据把该柜登记值改为 '76'**（用户拍板；`JIANGYI_GATEWAY_DESIGN.md` §7.6 同步改判）。⚠️ 仍未实测：这台柜的工控机**加载** 76 模型从未观察过，首推若无 `downloadModelNotify` 先怀疑取值。 | **我方**（下发）+ **厂商**（上线用模型须训进 426） |
| **G-16** | 🔴 **「按地址生成对照」会把唯一可结算的映射行打成不可用，且批量激活救不回来** | 源码逐行实证：`writePregenerated` 无条件 `setStatus("DISABLED")`（`JiangyiModelSyncService.java:142`），而 `upsert` 按 `UNIQUE(device_id, class_id)` 覆盖并**保留原 `source`**（`JiangyiClassMappingMapper.java:49`，本柜 classId 29 那行是 `MANUAL`+`ACTIVE`+sku 100013）⇒ 生成 69 行会把这行改成 `MANUAL`+`DISABLED`；「批量激活」条件要求 `source='MODEL_SYNC'`（同文件 :77）**命中不到它**，只能在识别映射页单条重新启用。识别解析只按 `deviceId+classId+ACTIVE` 点查（:15-19），一旦 DISABLED 就 fail-closed 转 DISPUTED | 判据：柜上电后**先只点「按地址发到柜机」**（`pushModelByUrl` 仅做行数校验、不写映射表，`JiangyiModelSyncService.java:181-190`）→ 拿到第一条非 0 结算绿；确需铺 69 行对照时，生成后**必须回识别映射页单条启用 classId 29** | 我方（操作顺序，非代码缺陷；若要改代码需先拍板「预生成是否该保留已激活行」） |
| **G-10** | **WS 下行 9 条未发**：`reboot`、`closeDoor`、`getDoorState`、`sendQueryNetwork`、`sendChangeWifiCommand`、`sendDeviceLightStripFlag`、`openDoubleDoor`、`sendForceOpenDoubleDoor`、`sendOpenStockDoubleDoor`（🔴 2026-10-22 18:0x 纠正：旧列 10 条里的 `getDeviceInfo` **早已实现**（`DeviceCommandController.java:76`，自 `167f51d7`），只是真机没点过 ⇒ 属「未测」不属「未接」，见 §9B R2） | 后 3 条单门柜**不需要**（⛔ 与选型一致）；前 6 条的代价=**现场运维全靠人手**（重启靠合闸、配网靠现场、门磁查不到） | 按运维需求逐条开；优先 `reboot` + `getDoorState`（排障最有用；`getDeviceInfo` 从本行剔除，它只差一次点击） | **待拍板**（不阻塞上线）。⚠️ 与 G-10 并列的**真缺口是「已实现未实测」**五条：`sendOpenStockDoor`、`updateModel`、`getDeviceInfo`、`sendDeviceSoundSwitch`、`sendDeviceSoundVolume`（真机 downstream tally 只有 heartBeat/openDoor/sendUploadErrorOrderVideos）⇒ 全部排进 §9B R2–R4 |
| **G-11** | ~~`waitGatherProduct` 死方法~~ → **✅ 2026-10-23 已接（只读展示）** | 原状：客户端有方法、零调用方。真实代价不是「死代码」而是**盲区**：我方新建的商品在 §4.1.2 商品库里**搜不到**（41159 实测只出现在待采集列表，`stdSku/getList` 查不到），运营只看挂接弹窗会判成「将邑库里没有」，还会拿这个结论去问厂商 | 后台挂接弹窗新增「将邑待采集」段（`GET /api/v2/ops/admin/jiangyi/pending-gather`，`ops:sku:list`），列出 productId/名称/**将邑侧规格**/品牌/主图，并回标「我方哪个 SKU 建的」 | 我方（已完成）。🔴 **实测口径钉死三件事**：① `name` 是真模糊过滤（冰红茶 1 条 / 雪碧 0 / 农夫山泉 0）；② `barCode` **完全空转**（传 `0000000000000` 仍返回同一条）⇒ 面板不给条码搜索框；③ **回包没有 `barCode` 字段**，文档 §4.2.2「barCode/name 至少一项」也不成立（两项留空=全量） |
| **G-17** | **`order/setOrderNoVideo` 无退避永久重试（56 次/同一单）** | 设备每 3 分钟把「本单无视频」重投一次、永远不成功 ⇒ 日志噪音持续累积，且**厂商侧至今不知道这个柜的这单没有视频回执**。爆炸半径有限（不参与结算判定），但它是 §3 A′ 里**唯一在真机上线后自己长出来的新常态缺口** | 厂商回话「该端点期望的响应形态是什么」⇒ 按原文照抄落 `DeviceCompatController`；判据=网关日志里该 uri 的 WARN 计数停止增长 | **厂商**（催办清单挂一条，优先级低于上电/模型）。我方侧**维持 404**：`order/**` 属资金前缀，`DeviceUnknownPathController` 类注释明文不得吞成成功（G-7 同因） |
| **G-12** | §5.4.x / §5.2.x **不接** | **不是缺口**。⛔ 模式一下订单不落将邑云；§5.2.14 是免鉴权写路径。**别当新缺口重提**（出处：`docs/JIANGYI_GATEWAY_DESIGN.md` §5 与 E-2 台账） | — | — |
| **G-13** | 非接口前置：**prod 库空 + 缺商户云凭据** | `jiangyi_device=0`、`merchant=0`；prod `.env.production` 的 `JIANGYI_MERCHANT_MOBILE/_PASSWORD` 长度 0 ⇒ 即使柜连上，prod 也拿不到登记与 `login2` 能力 | 运营台建商户+柜机、SN 登记并激活到 BOUND（判据=库里 status 字面量）+ 补两条凭据并重启 trade | **运营/运维**（顺序铁律：登记→激活→setDomain 不可颠倒） |

**一句话回答「对接还差什么」（2026-10-22 07:3x 订正，覆盖此前所有版本）**：文档第四章设备面 17 条 HTTP **一条不缺**；代码侧也已无卡点——②「缺覆盖在售商品的 modelUrl/classes」2026-10-22 00:4x 从本柜旧 logcat 到手（65/69 类商品模型，§5.5.3 解出**雪碧 = classId 25 / 29**，→ G-15），③ 三条模型回执变体已落码**且生产 `jiangyi-gateway` 已单独重建到 HEAD（2026-10-22 07:33，启动 0 ERROR）**——deploy-prod.sh 刻意只重建 trade-service，这条属脚本范围外、已人工补上；⑤ §5.5.3 官方解码器**已接入并只读落地（2026-10-22 09:2x，→ G-9）**：后台设备卡多一个「查厂商商品对照（只读）」，把 `textName` 拼音串翻成厂商品名+69 码并列出本平台同条码候选，**不写 `sku_id`**（拍板：自动挂=错扣款）。**真正还差的只剩三条**：① **柜没上电**（库里 `last_ws_online_at=2026-10-21 18:14` 北京时间，端到端 0 次；这条我方做不了）；② **我方一次模型都没发过**（`jiangyi_model_deployment=0 行`，显式下发入口已上线生效 → G-14），上电后第一个动作就是点「按地址发到柜机」；③ **首推若失败先怀疑 76/rk3576 从未实测**（→ H-10、G-15 边界），别判「通道不通」。另有操作顺序坑 **G-16**：别先点「生成对照」，它会把唯一可结算的 classId 29 行打成 DISABLED 且批量激活救不回来。**上面这些「差什么」怎么一次性验完，见 §9B 的七轮测试流程（R0–R7，含每轮判据与互斥表）。**自采自训 12 条（G-5）与 `getChessRoomOpenStatus`（G-7）不阻塞今晚；§5.4/§5.2/双门是**拍板不接**，不是忘。

---

## 9B｜未实测接口的测试流程（2026-10-22 18:0x 设计；R0–R7 逐轮跑，一轮填一行结果）

**为什么按「轮」排而不是按「接口」排**：设备面一条路径能不能被走到，取决于**柜机此刻在哪个流程**（开机 / 营业开门 / 补货开门 / 采集），不取决于我方代码写没写。⇒ 轮次=设备状态机里的位置，接口挂在轮次上，一轮只能解锁一批。本节把 §9 的「待真机」和 §2/§3 的 44 条 0 命中路径一次性分进七个可执行轮次。

🔴 **小节编号=R 编号（便于回查），不等于执行顺序**；真正跑的次序只看 §9B.10 那张表（`R0 → R2 → R1 → R6 → R4 → R5 → R3 → 结算复跑 → R7 另议`）。两者刻意不同：R2 零成本先白拿，R1 可能改档位要尽早，R3 有覆盖模型的风险所以压到最后。

### 9B.0 判据口径（每轮通用，跑之前先读）

| 要判什么 | 唯一可信取法 | 会骗人的取法 |
|---|---|---|
| 「设备打到过这条 HTTP」 | `docker logs ai-cabinet-gateway-1` 里 **UA=`okhttp/4.9.3`** + 状态码 200，按路径计数（nginx 全史自 10-19 起在这一个容器里） | 我方 curl 探测恒 401（已两次污染计数）；应用日志同字面须先看 remote 是不是 172.18.0.x；🔴 **401 不能判路由存在与否**——`DeviceAuthInterceptor` 盖整个 `/jiangyi/api/**`，实现与未实现都回 401 |
| 「这行代码在跑着的容器里」 | `docker cp ai-cabinet-jiangyi-gateway-1:/app/app.jar` + 扫 class 常量池字面量（`.tmp/jar-class-grep.mjs`） | alpine JRE **无 unzip**⇒容器内解压探测恒假阴性；源码里有≠容器里有 |
| 「下行指令真发出去了」 | 网关 `ws downstream … payload` 里的 `msgType` 字面量（`send()` 返回 false 会 503，不会静默） | 后台点了按钮就认为设备收到 |
| 缺口有没有新面孔 | `JIANGYI_PATH_UNIMPLEMENTED`（HTTP）/ `JIANGYI_WS_MSG_UNHANDLED`（WS，心跳不进台账） | — |
| 「设备侧报 5xx/4xx 是不是设备的锅」 | **先对齐我方栈的动作时间轴**：10-22 16:26:39–49 `device/pokerOrderVideo/uploadVideoUrl` 被真机打了 **15 次全 503**、16:33:42 起恢复 200 ⇒ 那段是我方侧的窗口，不是设备故障。顺带实测到固件的重试节奏=**10 秒内 15 次后退避** | 直接把 5xx 记成设备侧异常；把 burst 当周期轮询（`getChessRoomOpenStatus` 就是这么被我写错的，见 §3 A′） |

🔴 **凭据分档（18:0x 新出的一条判据）**：同一秒的同一次 burst 里可以既有 401 又有 404（`getChessRoomOpenStatus` 70:28）⇒ **设备对不同调用点带不同 token**。所以「设备连上了」不等于「所有设备调用都带得上有效凭据」；某条路径**长期吃 401** 时，先查凭据来源再补路由，否则补了也只救 1/4。

**现场互斥铁律**（撞了本轮就白跑）：两次开门间隔 **≥10s**（固件 `uploadErrorJY` 自带 10 秒节流）；补货开门窗口与采集模式都会把消费者购物 **409** 冻住（`REPLENISHMENT_IN_PROGRESS` / `gather_locked_at`）；🔴 **取证期间绝不重建网关容器**（容器日志随容器一起删）；视频迟到 31s～2min，判「视频缺失」要等 ≥40s 再查库；结算绿只能由设备侧链路挣得。

### 9B.1 基线（2026-10-22 18:0x 实测重算，覆盖此前所有计数）

| 面 | 实测数 | 取法 |
|---|---|---|
| 设备面注册的 `/jiangyi/api/**` 字面 | **59** 种拼写（含双前缀别名；与总账「46 具体路径」不矛盾，那是去重后的路径数） | `grep -rh '"/jiangyi/api/…"' services/jiangyi-gateway/src/main/java` |
| 真机 `okhttp` 命中 | **16** 条 | 同上 UA 过滤，`sort -u` |
| 注册但真机 **0 命中** | **44** 条 ⇒ 逐条清单 `.tmp/never-hit.txt`，本节轮次就是把它们分进能到达的流程 | `comm -23` |
| WS 下行我方**会发** | **8** 个 msgType：`openDoor` / `sendOpenStockDoor` / `updateModel` / `sendUploadErrorOrderVideos` / `getDeviceInfo` / `sendDeviceSoundSwitch` / `sendDeviceSoundVolume` / `closeDoor`（§4.3.2.6 可选关门，2026-10-23 用户拍板补接：采集开门会话解锁后柜门无法回锁） | gateway 源码 `send(` 调用点 |
| WS 下行真机**实际发过** | **3** 种：回 `heartBeat` PONG 1084、`openDoor` 11、`sendUploadErrorOrderVideos` 6（两个日志窗口 tally 一致） | `.tmp/round-20261022/gateway.log` |
| 出向 D 面 | `jiangyi_training_ticket` **0 行** ⇒ 采集/学习 8 条从未真跑；`jiangyi_model_deployment` **0 行** ⇒ 模型下行从未真发；`jiangyi_recognition_report` 1 行（17:02:59 FORWARDED） | dev 库 |

⇒ 🔴 **结论：真正的空白不是「没实现」，是「实现了但一次没点过」**——`sendOpenStockDoor`、`updateModel`、`getDeviceInfo`、音量两条，全部排进 R2–R4。

### 9B.2 R0｜前置对账（我方，10 分钟，不开门）

| 步 | 做什么 | 判据 / 动作 |
|---|---|---|
| ① 在线 | `SELECT status,last_ws_online_at FROM jiangyi_device` | `BOUND` + 近 5 分钟；nginx 心跳在打。不在线 ⇒ 本轮作废，先合闸（G-1） |
| ② **token 寿命** | 设备 token TTL=`aicabinet.jiangyi.token-ttl-seconds:604800`=**7 天**（`DeviceTokenService.java:44`），现 token 签于 10-20 ⇒ **约 10-27 到期** | 🔴 R1 若排在 10-27 之后，判据要换成「设备重新 `token/openDoorDeviceStatus` 拿到新 token」——**换新与沿用都算通过，别判故障** |
| ③ 映射行 | `jiangyi_class_mapping` 该柜 `class_id=29 → sku_id=100013 ACTIVE` | 缺行 ⇒ R3 后复跑的结算必 fail-closed |
| ④ 账面库存 | `device_sku_inventory` 该柜该 SKU | 🔴 须 ≥ 设备可能上报的最大件数：无 lot 柜 `applyDelta` **扣成负数即抛 409**，而 412/409 会毒化共享事务（H-22④）。账面是手写值非盘点 ⇒ 放货后进场肉眼核对 |
| ⑤ **当前模型留档**（R3 的回滚依据） | 抓 `deviceInfo/receiveCurrentTxtUrl` 上行原文（柜自报的 classes URL/modelName） | 落 `.tmp/round-…/current-model.txt`。R3 会覆盖它 ⇒ 没留档就别点 R3 |
| ⑥ 栈选择 | 本轮全走**本地 vicp 隧道栈**（`ai-cabinet-jiangyi-gateway-1` :8083 + nginx :80） | 🔴 **不在生产上点 R3/R5**（生产 `.env.production` 的 `JIANGYI_MERCHANT_MOBILE/_PASSWORD` 长度 0，D 面出向必失败；G-13） |
| ⑦ UI 无按钮的入口 | `POST /api/v2/ops/admin/devices/{id}/jiangyi/device-info` **只有 API、后台没有按钮**（已核 `DeviceJiangyiCard.vue` 无引用） | 用 `.tmp/admin-session-probe.mjs` 同型脚本登录拿 ops Bearer 后直接 fetch（运营登录要图形验证码，验证码值在 redis 里读）；**要不要补按钮另拍板** |

### 9B.3 R1｜断电重启恢复（现场拔合闸 + 我方观察 ~20 分钟）

**目的**：换域交接是 10-20 靠 offnet WS 推送完成的，**重启后能不能自己回来从未验证**（现 token 有效到 10-27，正好盖住）。顺带解锁开机簇 5 条 0 命中路径。

涉及接口（照抄拼写）：`domainConfig/getConfigDomainByIdentifier`、`domainConfig/getConfigStatusByIdentifier`、`domainConfig/getConfigSocketUrlByIdentifier`（我方自钉，A′ 表，真机 0 命中）；`token/openDoorDeviceStatus`、`deviceInfo/getDetail`（已命中，重启后再验）；**`tenant/getDeviceBasicConfig`（0 命中；10-19 云侧 log3 是每次开机 ~9 次）**；`deviceInfo/downloadBaseModelNotify` / `downloadBigModelNotify` / `downloadThirdModelNotify`（G-3 三条分档回执，落码后待真机）。

步骤：① 现场拔电 60s 再合闸 → ② 我方按 9B.0 口径盯 nginx okhttp 计数 + 网关 `ws … 101` → ③ 等一轮完整开机（≤3 分钟）。

判据（三档，**缺一档就是一条新发现，如实记**）：
1. **活着回来**：nginx 有 `token/openDoorDeviceStatus` 200(okhttp) + 网关 `onlineDevices=1` + 心跳恢复 ⇒ 「换域后重启要不要人工介入」闭合。
2. **开机配置簇**：`tenant/getDeviceBasicConfig` 是否出现。🔴 **若出现，我方回的 `dictValue:"NORMAL"` 会被固件写进 `Constans.M_NAME`**（覆盖逻辑见 §3 A′ 与 `getDeviceBasicConfig` 注释）⇒ 这**不只是连通性测试，可能改变设备侧协议档位**（XY/YP/NORMAL 三态正是当前悬案 H-19）。所以 **R1 必须排在 R3/R5 之前，且 R1 结束后立刻复跑一次购物结算轮**，确认 #90 那条绿没被档位变化打断。
3. **判别副产物**（两种结果都要接受，别预设）：重启后若出现 `floor/getFloorByDevice` 或**裸** `/jiangyi/api/pokerOrder/uploadOrderError`（无 `/device`）⇒ 与 `M_NAME=NORMAL` 一致；仍只出 `/device/pokerOrder/uploadOrderError` ⇒ 与 `∉{NORMAL, NO_NET}` 一致。
4. 分档回执：开机一轮 `download*ModelNotify` 四条全 200 ⇒ G-3 真机判据达成（§G-3 遗留的「三档语义未确认」不因这条改变，我方仍不区分处理）。
5. **凭据重启效应**（顺手测，零成本）：重启后按 9B.0 口径数一遍 `getChessRoomOpenStatus` 的 401/404 比例。🔴 若 401 **消失**（全部变 404）⇒ 那 70 次带的是**旧会话残留 token**，重连即愈，G-7 只剩路由问题；若仍 401/404 并存 ⇒ 设备确实有两个凭据来源（厂商 token 残留于 prefs），**G-7 的补 200 方案直接减半**，必须先问厂商。取数：`docker logs ai-cabinet-gateway-1 | grep okhttp | grep getChessRoomOpenStatus`，按 `" 401 "`/`" 404 "` 分列计数。

失败分叉：设备没回来 ⇒ 先看它有没有拉 `domainConfig/*`（我方回的是自己的 base）；拉了却仍连厂商 ⇒ 拍板 A 的自钉失效，属**设计缺陷**，登记在 G-13 旁边；压根没拉且没连 ⇒ 回落分支（拉取失败时设备用什么）第一次暴露真值，这是文档和反编译都没答的那题。

### 9B.4 R2｜三条零风险下行（我方点 + 现场听，5 分钟）

**目的**：把「已实现、不改设备状态、从没点过」的三条一次清账。

| 步 | 谁做 | 操作（入口） | 涉及接口 | 判据 |
|---|---|---|---|---|
| ① 静音 | 我方 | 设备详情将邑卡片「设备控制（信号/音量）」→ 音量设置关（`PUT /devices/{id}/jiangyi/sound` `switchOn:false`） | WS §4.3.2.16 `sendDeviceSoundSwitch` `msgContent:"0"` | 网关 `ws downstream … sendDeviceSoundSwitch` + **现场听** |
| ② 开声 | 我方 | 同上 `switchOn:true` | 同上 `"1"` | 现场恢复出声 |
| ③ 音量三档 | 我方 | volume 依次 `0`/`1`/`2`（大/中/小） | WS §4.3.2.17 `sendDeviceSoundVolume` | 现场三档可分辨。⚠️ **音量两条无文档回执⇒本表唯一「人耳判据」**，务必留现场人 |
| ④ 拉设备信息 | 我方 | `POST /devices/{id}/jiangyi/device-info`（🔴 **无 UI 按钮**，走 R0⑦ 的 fetch） | WS §4.3.2.13 `getDeviceInfo` → 上行 HTTP §4.2.15 `deviceInfo/getDeviceSimInfo` | nginx 出现 `deviceInfo/getDeviceSimInfo` **200(okhttp)**（这条现在 0 命中）+ 后台「信号强度」由 `未上报` 变 `N/4`（`GET …/signal` 非 null `reportedAt`） |

副作用已知（别互相误判）：固件音量帧走 `AudioManager.setStreamVolume`、**会解除静音**（`DeviceCommandController.java:62` 注释）⇒ 顺序必须①→②→③，**不要在静音状态下发音量帧**，否则会把「静音成功」判成失败。

### 9B.5 R3｜商品模型下行（我方，15 分钟 + 一次结算复跑；🔴 有覆盖柜上模型的回归风险 ⇒ **实际执行放在 R1/R4/R5/R6 之后**，见 §9B.10）

涉及接口：WS §4.3.2.9 `updateModel`（四字段 `{quantity,modelUrl,textUrl,modelName}`）；HTTP 回执 §4.2.5 `deviceInfo/downloadModelNotify`（+三条分档变体）；WS 上行 `updateModelSure`（兜底通道）。出向 §4.4.4 `modelFile/getModelFileByApi` 本轮**刻意绕开**（租户 426 仍回 `[]`），走 #78 的显式 URL 入口。

🔴 **10-23 15:5x 三条只读重跑（`.tmp/jy-model-list-444.mjs`，凭据从 `infra/.env` 取）**：§4.4.4 `getModelFileByApi` → **`data=[]`**、§4.4.5 `studyingProduct` → **`[]`**、§4.4.2 `getPageData{gatherStatus:"training"}` → **`data=null`** ⇒ **厂商侧至今没有任何属于本租户的模型、也没有在学/学过的商品** ⇒ 「下发」这条路能验链路，但**下发不出新商品**：可下的只有借来的那颗 69 类（`model-push-explicit` 收裸 URL，G-14 已开）。要出现「我们自己的模型」必须先走 §4.2.3 采集开门 → §4.3 每商品 200 张 → §4.4.3 开始学习。
🔴 **同时订正我 10-23 14:4x 写的那句「我方没有判据证明这台柜加载哪颗类表」**：文档给了判据，只是**它只在下发之后才触发**——§4.2.5 目的原文「如果没有上报模型名字，则表示下发模型失败」，参数 `modelName`=「当前设备使用的模型名字」，且描述第(2)条**「模型下发成功后，机器端会重启」**⇒ 首次下发轮必须把「现场要等一次柜机重启」算进时间预算；换来的是设备自报 `modelName`（此后"柜机在用哪颗模型"才有官方口径）。

🔴 **10-23 16:0x 追问「厂商那个 `getModelFile` 我们有吗」（用户贴了 413 的请求/回包）⇒ 没接、也不该接**：① 我方只有文档里的 §4.4.4.1 `getModelFileByApi`（**无参数**、租户由 token 定，`JiangyiGatherClient.java:250`）；② 用户贴的 `POST /merchant/modelFile/getModelFile` **两份厂商文档都没有**（全文 `getModelFile` 唯一出现就是 `getModelFileByApi` 那一行，`commonTenantId` **0 命中**），必须自填 `commonTenantId` 才回数据 ⇒ 是**厂商后台自用面**；③ 四档实测（`.tmp/jy-modelfile-probe.mjs`，同一 Bearer）：`getModelFileByApi{}`→**0**、`getModelFile{76,product,413}`→**5**、`{…,426}`→**0**、**不带 `commonTenantId`→1544 条**（textUrl 里 418/411/374/393… 各租户都有）⇒ **没有租户隔离**，这种端点接进产品＝跨租户数据面；④ 业务上也不解渴：413 那 5 颗用 §5.5.3 逐行解出来是 `bj_phone_10g`/`bj_watch_8g` 两个干扰类 + 百事可乐330罐/脉动600/东鹏补水啦555/东鹏特饮/红牛250/康师傅冰红茶**500ml**(6921311105014)/丽芝士威化/东方树叶茉莉花+乌龙/农夫山泉550/维他柠檬茶——**没有雪碧、没有可口可乐**，冰红茶还是 500ml ≠ 我方 310ml ⇒ 下发任何一颗都会把柜上 **26/27/28/29 四个类整片换掉**，R8 的多商品测试反而做不了；⑤ 顺带坐实借的那颗在厂商列表里的身份：**id 5247 / 5242**（`hysxr20260930120329732_76_20260930120413844_50` / `_10`，quantity **69**、76、textUrl `…_3.txt`）。要**新**商品可识别只有两条路：R5 自采（每商品 200 张）或让厂商给**租户 426** 采集/训练出模型（H-26）。

载荷（G-15，已从本柜旧 logcat 逐字取到）：69 类 `hysxr20260930120329732_20260930120413844_3_50_3576.rknn` + 同名 `_3.txt`，`quantity=69`，雪碧=**classId 29**（⚠️ 只对当前版本成立：65 类时是 25，重训即平移 → H-27）。入口=后台「按地址发到柜机」= `POST /devices/{id}/jiangyi/model-push-explicit`。

🔴 **顺序坑 G-16，动手前必读**：**只点「按地址发到柜机」**（`pushModelByUrl` 仅做行数校验、不写映射表，`JiangyiModelSyncService.java:181-190`）；**别点「生成对照」**——`writePregenerated` 无条件 `setStatus("DISABLED")`（`:142`）且 `upsert` 保留原 `source`（`JiangyiClassMappingMapper.java:49`），会把唯一可结算的 classId 29 行打成 `MANUAL`+`DISABLED`，而「批量激活」只认 `source='MODEL_SYNC'`（`:77`）**救不回来**，只能在识别映射页单条重新启用。确需铺 69 行对照 ⇒ 生成后**回识别映射页单条启用 classId 29**，再复跑结算轮。

判据链（**全绿才算通**，断在哪一环就归因到哪一环）：
网关 `ws downstream … updateModel` → `jiangyi_model_deployment` 该行 **SENT**（`sent_at` 有值）→ nginx `deviceInfo/downloadModelNotify` **200(okhttp)** 且 body `modelName` 与发出**逐字相同** → 该行 **CONFIRMED**（`confirmed_at`）→ **复跑一次购物结算轮**：`jiangyi_recognition_report` 出现第二条 `outcome=FORWARDED`、`recognition_result` 含 `100013×1`、订单 **PAID 350**、会话 COMPLETED。

分叉归因：① 有 downstream 无 notify ⇒ **先怀疑 76/rk3576 与模型编译目标不匹配**（H-10；本柜登记值已按实据改 `'76'`，但**这台工控机加载 76 模型从未观察过**），别判「通道不通」；② HTTP 无回执但 WS 回了 `updateModelSure` ⇒ 走的是兜底分支（`DeviceWebSocketHandler.java:145`），主通道另有问题；③ 停 SENT 到 push-timeout ⇒ 记下 `fail_reason` 原文，先查厂商是否代下了分档模型（台账里会先照出 `update*ModelSure`）。
回滚：若复跑结算轮转 DISPUTED，立刻用 R0⑤ 留档的 modelName/URL 走同一入口推回。

### 9B.6 R4｜补货开门 + 补货表下行（我方 + 现场，20 分钟；期间购物 409）

涉及接口：WS §4.3.2.4 `sendOpenStockDoor`（`msgContent`=补货会话 id ≡ 补货单号）+ 上行 `sendOpenStockDoorSure`；HTTP `floor/getFloorByDevice`（裸 + `/device` 双拼写）；`floor/getDeviceSellProductClassIds`（已命中，本轮验**内容随映射行变化**）；`replenishmentHistoryBatch/uploadOpenMsg`、`replenishmentHistoryVideo/create`（当前 catch-all **404**，本轮看设备会不会打——打了就是新增缺口台账）。

步骤：① 后台补货页**新建补货任务**，在**任务行**上点「开门」（🔴 开门按钮只挂在任务行；页面顶部那个「补货调度/补货开门」跳的是路线规划页，点不出开门）⇒ `POST /api/v2/ops/restock/open-door`（`useReplenishmentTaskActions.ts:141`）；② 现场拉门、按补货流程放/取货；③ 关闭补货会话；④ 退出后确认购物面恢复（点一次消费者开门不被 409）。

判据：网关 `ws downstream … sendOpenStockDoor`；`sendOpenStockDoorSure` 回来 ⇒ **撤销 OPEN 15s 超时登记**（`DeviceWebSocketHandler.java:133-135`）；nginx 出现补货会话的 `device/uploadDoorState`；🔴 **`floor/getFloorByDevice` 是否第一次出现**（两种拼写都算）；补货表内容 = `jiangyi_class_mapping` 里满足四条件的行（ACTIVE + class_id 非空 + sku_id 非空 + SKU ACTIVE，`JiangyiFloorGoodsService.listFloorGoods`）。

🔴 **两种结果都要接受，0 命中不等于我方有漏**：`GetReplenishGoodsInfo` 在 `M_NAME ∈ {XY,YP,NO_NET}` 时**不发 HTTP、直接回调 "suc"** ⇒ 若 R1 已证档位仍非 NORMAL 而本轮无 `getFloorByDevice`，判定=「设备档位决定、我方无从触发」，处置是挂厂商问题清单（挨着问题 18），**不是**漏接。
成功判据的硬细节：设备用**字符串包含 `"status":200`**（`contains`，不解析 JSON）判成功 ⇒ 序列化不能带空格；我方 `ok()` 形态满足，人工核对时从 `docker logs` 抓响应原文。
顺带清账：门磁面（§4.2.16 `deviceInfo/getDoorInfo`，0 命中）**本轮也测不到**——它由 WS 下行 `getDoorState` 触发，而那条我方未实现（G-10）⇒ 门磁只剩「换固件/厂商」两条路，如实记「不可自测」。

### 9B.7 R5｜自采自训端到端（我方 REST 驱动 + 🔴 现场有人放样本，半天；采集期间购物 409）

涉及接口（出向 V1.13，本轮全部是**第一次真跑**）：§4.2.3 `selfhelpGatherProduct/gatherOpenDoor`、§4.2.4 `doorStatus`、§4.2.2/§4.3.2 `waitGatherProduct`（~~死代码~~ **2026-10-23 已接成后台只读展示**，G-11）、§4.4.6 `gatherCheck`、§4.4.2 `product/getPageData`、§4.4.3 `commitTrainingTenantOne`、§4.4.5 `studyingProduct`、§4.1.2 `stdSku/getList`、§4.1.4 `productCategory/getTopList`、§4.1.5 `product/create`；入向：§4.4.3 回调 `POST /jiangyi/api/gather-finish-notify`（`GatherNotifyController.java:36`）。🔴 **§4.3.3–4.3.14 十二条采集编排仍是 0 实现**（G-5，本轮拍板只做只读）——所以 R5 现在**只能靠脚本按 §4.3 序列驱动**，后台没有采集向导。

🔴 **三条结构性前置，先解决再排期**：
1. **G-6**：采集开门 `gatherOpenDoor` 打的是**厂商云**，而柜 WS 已指我方 ⇒ 厂商侧对这台柜**没有会话**，采集开门可能**结构性打不开门**。判据=点了「进入采集模式」后柜门开不开 + 我方 nginx 有没有对应 `device/uploadDoorState`。不到门的处置=我方 `sendOpenStockDoor` 顶替（**属设计变更，需拍板**）。
2. ~~**拍照采集的操作在将邑商户 App 里完成** ⇒ 必须有厂商商户 App 账号~~ → 🔴 **2026-10-23 推翻（用户指认「移动端不只是手机」+ 逐节核对文档）**：V1.13 标题里的「**移动端**」= 调这批 REST 的**客户端角色**（封面把平板/手机/PC 三类都标成移动端），**不是手机 App**。§4.3.4「查询摄像头和配置是否准备好」(`startGather`) 与 §4.3.7「结束当次商品采集」(`endOne`) 的入参**只有 `identifier`(设备编号) + `id`(采集商品信息 id)**，没有任何手机专有输入；🔴 而 §4.3.4 的**标题本身就说明出图的是柜机自己的摄像头**——它在问「摄像头就绪了没」。⇒ R5 的对外依赖**不是账号**，而是：①一个能拿商户 token 的 HTTP 客户端（我方 `JiangyiGatherClient` 已在），②柜机在线 + **现场有人在柜前放样本/收图**。后台挂接弹窗那句提示语已按此订正为「采集拍照由柜机自身摄像头完成，采集向导尚未接入后台」。
3. **G-5**：V1.13 §4.3.3–4.3.14 十二条编排 API 我方 **0 实现** ⇒ 若 App 流程要求我方驱动，本轮会当场卡住——**那正是 G-5 的判据**（看厂商云/我方 nginx 是否留下这些路径的痕迹），卡住就是结论，不是白跑。

步骤：① 选一个**厂商库里没有**的商品做样本（先按 H-13 用 `barCode` **精确查**确认不在厂商库，别拿列表长度当结论；真商品只有雪碧，所以用测试 SKU 100011 或新建一条）→ ② 后台「进入采集模式」（`POST …/jiangyi/gather/start`，置 `gather_locked_at`）→ ③ 现场确认门 → ④ **柜机自身摄像头**出图（🔴 2026-10-23 用户口径：**不需要 200 张**，采到现场认为够为止；`error` 的 `no` 与张数无关，见 H-12；出图方=柜机，不是手机 App，见本节前置 2）→ ⑤ `GET …/gather/progress` 轮询（内部串 `doorStatus` + `studyingProduct` + `gatherCheck(null)`）→ ⑥ 「触发学习」（`POST …/gather/training`）→ ⑦ 等 `gather-finish-notify` → ⑧ **「退出采集模式（恢复营业）」必须点**，否则购物一直 409。

**采集脚本（2026-10-23 新增）**：`node scripts/jiangyi-gather-r5.mjs <子命令>`——只打 V1.13 §4.3.2–4.3.14 的厂商序列，外加 §4.4.4/4.4.5/4.4.6 只读；凭据只从环境变量或本机 dev 容器读、**不打印**，写操作必须显式 `--yes`。我方后台仍负责「进入采集模式 / 退出 / 触发学习 / 进度」（`JiangyiGatherService` 会建 `jiangyi_training_ticket`）⇒ 🔴 **不要用脚本直打 `commitTrainingTenantOne`**：finishNotifyId 是我方一次性凭据，绕过 ticket 会让回调被 `handleFinishNotify` 丢弃。`JIANGYI_IDENTIFIER`=厂商登记编号（本柜 `CQYB10720`），`JIANGYI_GATHER_PHONE`=endAll 的通知号码。

| 步 | 子命令 | 判据（不过就停，别往下点） |
|---|---|---|
| 0 | 后台「识别映射 → 映射复核」 | `verified=true` 且「柜机版本」= 当前 `classes_version`；未复核先复核（H-28），否则采集后也无法结算 |
| 1 | `list`（§4.3.2） | 待采集列表含目标行。**2026-10-23 实测 1 条**：`productId=41175 / 康师傅冰红茶柠檬口味茶饮品 / 康师傅 / 310ml / bottle` |
| 2 | `prep --product-id 41175 --name … --brand 康师傅 --specs 310ml --category bottle --yes`（§4.3.3） | 回包 `data[0].id` = **采集商品信息 id**（记下，后续 ready/start/error/end-one 全用它）；404 先查 H-24 的 `productInfo` 拼写 |
| 3 | `ready --gather-id <id>`（§4.3.4） | `cameraStatus=yes` + `configParamStatus=yes` + `start=yes`；任一 `no` 停 |
| 4 | `start --gather-id <id> --yes`（§4.3.5） | 现场柜门打开/进入采集；同时我方 nginx 出现 `device/uploadDoorState`。**门不开=G-6**（厂商云对这台柜无会话），改用 `sendOpenStockDoor` 属设计变更，需拍板 |
| 5 | 现场放样本，柜机自采（🔴 2026-10-23 用户口径：**张数无硬性下限，不需要 200 张**，采到够为止）→ `error --gather-id <id>`（§4.3.6） | `data=no`。⚠️ 文档明说「图片不足 2000 张也返回 no」⇒ **no ≠ 张数够**，张数以现场/设备为准 |
| 6 | `end-one --gather-id <id> --yes`（§4.3.7） | 回包 `data`=采集次数 |
| 7 | `products`（§4.3.8） | 列表含该商品 `productInfo` |
| 8 | `go-on`（§4.3.9） | `yes` 才可采下一商品；`wait` 等设备；`no` 查 `error`/上传状态 |
| 9 | `end-all --yes`（§4.3.10） | `data=null`；此后本批次不能再补商品 |
| 10 | `upload-info`（§4.3.11） | 逐条 `wait → success`；`weak`=图片不足、`fail`=重采；**有 `wait` 时不要 clear-batch** |
| 11 | `clear-batch --yes`（§4.3.14） | 仅当上传状态**全无 `wait`** 才调；失败可用 `forgo-upload`/`go-on-upload`（§4.3.12/13） |
| 12 | 后台「触发学习」（**不是脚本**，§4.4.3） | ticket `PENDING`；`gather-finish-notify` 到达后 `FINISHED`；`studying`=pass；`gather-check` 含 41175；`models` 出现新模型（`modelTextUrl` 用 `.tmp/jy-decode-url.mjs` 复核行号） |
| 13 | 学习完成后 | 🔴 **先做映射复核再开门**：新模型 classes 会平移行号（H-27/H-28），复核页「拉取差异 → 确认复核并应用」后 `verified=true` |

判据：`jiangyi_training_ticket` 由 **0 行**变 ≥1 行且 PENDING→FINISHED；`GET /api/v2/ops/admin/jiangyi/gather-check` 回包含该商品；🔴 学习完成后 §4.4.4 `modelFile/getModelFileByApi` **由 `[]` 变成有货**——这条是 G-2 厂商侧卡点的真解，也是 R3 之后模型链的**正式来源**（借别商户模型只算验链路）；防伪验证顺手做一次：用错的 `finishNotifyId` curl 一次回调，判据=只 `log.warn("…dropped…")`、单行状态不变（`JiangyiGatherService.java:151-156`）。

已知坑（跑前照抄）：`gatherCheck` 必须发 `productIds:null`，传 `[]` 被厂商 MyBatis 拼非法 SQL 回 555；`commitTrainingTenantOne` 的 `finishNotifyUrl` **不能带 token**（我方传 `publicBaseUrl + /jiangyi/api/gather-finish-notify`）⇒ 公网面须可达且免鉴权，本地靠 vicp 隧道演，生产要核 `JIANGYI_PUBLIC_BASE_URL`；🔴 **§4.3.3 `productInfo` 与 §4.3.11 `uploadInfoNew` 在文档里被 OCR 成 `productlnfo`/`uploadlnfoNew`（小写 L），照抄必 404**——序列跑之前先按 H-24 的探针做一次拼写对撞；🔴 我方后台**没有采集向导**（G-5 本轮只做只读），④ 这步得用脚本按 §4.3.3→4.3.14 的手动序列打。

### 9B.8 R6｜只读对照与商品维护面（我方，30 分钟，不开门）

| 步 | 接口 | 判据 / 口径 |
|---|---|---|
| **判「某商品在不在柜机模型里」的确定判据（10-23 14:2x 新拿到）** | V1.13 §4.1.2 `stdSku/getList` 的**原始行**里有 `textName` 字段（我方 `JiangyiGatherClient.VendorSkuView` **没透出这一列**，所以要直连厂商看 raw） | 🔴 **`textName` 就是模型类别串** ⇒ 「这个商品这台柜认不认得」从此有确定算法：**取该 stdSku 的 `textName`，去柜机现用模型的 classes.txt 里搜，命中即在**（行号−1＝classId），不必再靠品名/条码/图片猜。实测两例：我方挂的加多宝 310 罐＝stdSku **1480**，`textName="jdb_jdblc_310hs"`，在本柜现用 69 行 classes 里 **0 命中**（jdb 只有 `jdb_JDBHerbalTea...500hs` 一行＝stdSku **3388**、条码 4891599366808、品牌繁体「加多寳」），在旧模型 `ZQ_hysxr20260918155237..._3.txt` 里同样只有 500hs 那行 ⇒ **挂接正确但模型没学过，识别必然不命中，与条码是否一致无关**。⚠️ 同一条 raw 还坐实两件旧结论：1480 是 `specs=310hs` 的罐装却写 `category=bottle`/`categoryCn=瓶装`、`packageType=Q-其他` ⇒ **厂商 `category` 不能判罐/瓶**（H 段旧论再验一次）；其 `mainImg` 指向 `api.jisuapi.com/barcode2/...`（第三方条码网抓图）⇒ 后台看到的「罐装图」不是将邑自己拍的。附带可复用探针：`.tmp/jy-classdecode.mjs <关键字>`（走我方只读 `GET …/jiangyi/class-decode?textUrl=`，全 69 行回包，含 `stdSkuId/vendorBarcode/localCandidates/mappedSkuId`）。 |
| **69 类不是截断、且类表会自己长**（10-23 14:4x 回应「不可能这么少」） | 直连 OSS 拉两份 classes.txt 比字节/行数/末字节 | 🔴 现用类表 **69 行 / 1456 字节 / 末字节 `0a`**，与我方本地副本**逐字一致**⇒ 不是下载截断。同一商户**上一版（`ZQ_hysxr20260918155237…_3.txt`）＝65 行**⇒ 09-18→09-30 十二天里从 65 涨到 69，**印证将邑机制是「按商户采集→学习→重训专属分类模型+类表」，每学一个商品类表多一行**，不是「一个通用大模型认识所有商品」；厂商标准库 ≥4000 条（H-13）是**目录**不是识别能力，雪碧能认只是因为那个商户恰好学过它（classId 29）。设备侧并存多个模型文件（logcat 枚举）：检测骨干 5 个（`best320_seg64_nocls3`/`best320m_seg64_nocls3`/`best640s_box`/`best_800rk3576_0310_800senpt`/`spk640_916c_c2`）+ 商品分类 2 版（`ZQ_hysxr…`→`hysxr…`，均 `_50_3576`）+ 复核类表 3 张（`classes_auto_6`=6 行 / `classes_auto7`=7 / `classes_15`=15）⇒ **「69」只是商品分类这一张表的宽度**。⚠️ 但我方**没有判据**证明这台柜只加载了这一颗商品分类头：`jiangyi_device.classes_version` 为空（我方从未下发过模型，#4 仍 pending）、设备也不上报当前加载哪张类表 ⇒ 别把 69 当能力上限写进结论；要证实只能问厂商（该柜现在用哪颗分类模型+类表 URL）或现场 adb 拉模型目录。`_50_` 的含义**未知**（≠ 类数，类表实测 69 行），别猜。 |
| **厂商标准库自身的脏度：同名多款、name 可能是机器串、形态看 `packageType` 不看 `category`**（10-23 15:0x raw 对撞） | §4.1.2 `stdSku/getList` 的 **raw 行**（我方 `VendorSkuView` 只透出 8 列，`packageType`/`packageTypeId`/`stdSkuCode`/`textName` 都被吃掉 ⇒ 要看全字段必须直连厂商） | 实测「加多宝 500ml」在库里是**两条不同条目**：**434** `name=加多宝凉茶植物饮料500ml`、`packageType=P-瓶装`、`specs=500ml`、`textName=jdb_lczwyl_500ml`、**`barCode=null`**、`barCodeSource=""`；**3388** `name=JDBHerbalTeaJDBHerbalTeaPETbottle500mL500Millilitreml`（**厂商库里逐字就是这个英文串，不是我截断/翻译**）、`packageType=null`、`specs=500hs`、条码 4891599366808、品牌写成繁体「加多寳」、`barCodeSource=app`，且它的 `textName` ＝ `jdb_` + 该英文串 + `_500hs` ⇒ 该条目是**由模型/条码侧自动灌进标准库的**（推断，未见厂商文档说明生成路径）。🔴 三条可复用口径：① **`name` 不能当人话用**，机器生成条目直接把模型 textName 的英文段当名字；② **判形态看 `packageType`（434=`P-瓶装`）而不是 `category`**——1480（310 罐，图是罐）的 `category`/`categoryCn` 竟写 `bottle`/「瓶装」、`packageType=Q-其他` ⇒ 罐装在厂商侧归「其他」，`category` 已第三次证伪（H 段旧论）；③ **同一实物可能有多条**，挂接前必须用 `barCode` 精确查并核对 `textName` 是否＝柜机类表里那一行，否则会出现「挂上了 A 条、模型学的是 B 条」。 |
| 厂商对照只读 | `GET /devices/{id}/jiangyi/class-decode`（V16 §5.5.3 `stdSku/getStdSkuQueryByTextNames`；UI「查厂商商品对照（只读）」） | 真回 **67/69 行**、`vendorClassId` 原样回显（`classIdBase` 拨到 1 时错行必须肉眼可见）；未解析行显式「厂商库无对应」（回包稀疏，H-15）。🔴 **前后各 `SELECT` 一次 `sku_jiangyi_link`，必须逐字一致**——拍板口径：只读接口绝不写 `sku_id`（自动挂=错扣款） |
| **🔴 「classId 是柜里哪款商品」有官方口径，别数行号**（10-23 15:0x，订正我自己 14:5x 的两句话） | V16 §5.5.3 表格 166 + `.tmp/jy-classdecode.mjs`（69 行）+ `.tmp/jy-stdsku-byname.mjs`（§4.1.2 名称模糊） | 文档回包字段就是 `Skuld`(标准商品id) / **`stdSkuName`(标准商品名称)** / `textName`(商户商品简称) / `barCode` / `class`(「模型排序」，示例首行 `classId:0`) ⇒ **0-based 是文档写的**，数行号只是我方交叉验证，不是判据来源。实测关键四行：**20=3388**「JDBHerbalTea…500hs」/`4891599366808`/品牌写「加多寳」（§4.1.2 里 `name` 就是这串英文机器名，脏行属实）；**29=1018**「雪碧清爽柠檬味汽水普通听装330ml」/`6954767430386` ⇒ 我方 100013 `ACTIVE` **对得上**；**30=1627**「康师傅低糖绿茶蜂蜜茉莉味调味茶饮品」/`6922456896508`；**31=1621**「康师傅绿茶调味茶饮品蜂蜜茉莉味」/`6922456805166`。🔴 **新暴露的活缺陷：classId 31 我方挂的是 100015「加多宝凉茶植物饮料」（link=厂商 1480）且 `ACTIVE`**，而 31 号在模型里是**康师傅绿茶**；1480 自己的 `textName=jdb_jdblc_310hs` 在 69 类里 **0 命中** ⇒ 柜里放 310 罐加多宝测不出（模型唯一的加多宝是 20 号 3388=500ml PET 瓶装，条码 `…66808` ≠ 我方 `…38393`），而真出现康师傅绿茶时会被**按加多宝 ¥4.00 结算并扣加多宝账面**——与刚停掉的 30 号同一类资损，只是这条还 ACTIVE。下行现值（15:0x `floor-goods`）＝2 行：`[{29,1018,雪碧,350,stock2},{31,1480,加多宝,400,stock1}]` ⇒ **错配已经发给柜机**。**处置（用户 10-23 15:2x 拍板「停 31，今天只测雪碧」）**：`POST …/jiangyi/class-mappings/31/status {active:false}` ⇒ `data=1`、id=74 **ACTIVE→DISABLED**（行与 `sku_id=100015` 都留着；该行 `text_name` **本来就是空** ⇒ 将来要复原必须先补 §5.5.3 的名称证据，别无脑 `{active:true}`），下行实测回到 **1 行**（只剩 29/雪碧/stock2）。现库内映射 3 行：`72/29→100013 ACTIVE（带 text_name+model_name，唯一有据的一行）`、`73/30→100014 DISABLED`、`74/31→100015 DISABLED`。⚠️ 同时订正：我 14:5x 说「商品库 name 是机器串所以没法按名找」是以偏概全——`name=加多宝` 回 3 条（434/1480/3409 全中文）、`冰红茶` 17 条、`雪碧` 11 条，**3388 只是其中一条脏行** |
| 挂接与 textName 回填 | `GET/PUT /skus/{skuId}/jiangyi-link`、`POST …/create`、`POST …/pull-text-name` | `pull-text-name` 判据（10-22 收紧后）：**条码精确相等且 textName 唯一**才回填；多条不同 ⇒ **409 列候选**；空 ⇒ 404 且**不清值**（`.tmp/probe-stdsku.mjs` 可复跑对照） |
| 商品/类目字典 | `GET /jiangyi/std-skus`、`/jiangyi/product-categories` | 🔴 **「查商品库」的文档主路是 `name` 模糊**（V1.13 §4.1.2 第 12 页：`name` O 模糊、`barCode` **也是 O 模糊**、`applyMachineId` M=21；V16 §5.5.1 第 90 页则写 `name` M 模糊、`barCode` O 精确「正在实现中」⇒ 两版口径分歧，**以实测为准**：整码打回 1 条=精确，V16 那句已过时）。⚠️ 我 10-23 14:5x 对用户说的「判在不在库**只能**带 `barCode` 精确查」是**过度收紧**：那是 `pullTextName` 自动回填的判据（H-14：名称模糊必然命中 `test…` 仿行），不是「商品库没有名称 / 只能按条码查」——商品库有 `name` 且 15:0x 实测 `name=加多宝` 回 3 条、`冰红茶` 17 条、`雪碧` 11 条。列表长度仍不可当结论（H-13 截断）。 |
| 将邑**待采集**只读 | `GET /api/v2/ops/admin/jiangyi/pending-gather?name=`（V1.13 §4.2.2 `waitGatherProduct`；后台挂接弹窗底部「将邑待采集」区） | 🔴 这批条目**在商品库搜索里搜不到**（`stdSku/getList` 只回标准库，不含待采集），所以它是唯一能看见「我方 `product/create` 建出来、还没采集学习」的那批的窗口。41159 删除前的现值（2026-10-23 11:28，全量只回**这 1 条**）：`specs="330"`（**光秃秃的数字，无单位**）、`category=bottle`、`barCodeSource=""`、`productEn="ksf_ksfbhcnmkwcyp_330"`（厂商按名称+规格自造的拼音串）、`mainImg` = **带完整路径的隧道地址**（`https://<vicp-host>/api/v2/media/sku-images/2`）。⚠️ 本行上午 10:5x 记的「裸根、不带路径」**已被推翻**：`PendingGatherView` 是原样透传 `p.mainImg()`（不做补全），11:28 两次独立读取（我方端点 + 直打厂商 `waitGatherProduct`）都带完整路径 ⇒ 「裸根」是**我打印时把 host 之后整段脱敏吃掉了**的伪象（→ §11）。实测回包**没有 `barCode` 字段**，故列表不显条码；`name` 是真模糊过滤，**传条码空转**（文档 §4.2.2「barCode/name 至少一项」不成立） |
| **§4.1.6 已接并真跑通 / §4.1.7 仍未接** | V1.13 §4.1.6 `merchant/product/delete`（2026-10-23 已接：`DELETE …/skus/{skuId}/jiangyi-link/{linkId}/jiangyi-product`，同日 11:28 **真删真验**）；§4.1.7 `product/updateMainImg` **仍 0 实现** | delete 我方侧只放开 **`CREATED`**（我方自己建的商品）；`BOUND` 是厂商标准库**共享条目**⇒ 409 要求改解挂，别顺手放开。🔴 **厂商侧后果已实测**（一次性 worktree 实例 18099，跑我这颗 tip `e2bb471a`）：删 `41159` 后 `waitGatherProduct` 由 1 条→**0 条**＝**真从厂商库里消失**，不是只改我方状态；我方 link 行 `id=2` 转 **`RETIRED` 保留**，而 `GET /skus/{skuId}/jiangyi-link` **过滤 RETIRED ⇒ 回 `[]`**（弹窗里那条挂接随之消失，符合预期）；全库扫 `*jiangyi*`/`*product_id*` 列 + 全部 jsonb/text 列，除这条 RETIRED 历史外**无 41159 残留**。**重建口径与结果**（用户 2026-10-23 拍板「删了重建 §4.1.6 + §4.1.5，specs 改 310ml」「售 3.50 / 进 0」）：`specs=310ml`、`name=康师傅冰红茶柠檬口味茶饮品`、`brandName=康师傅`、`category=bottle`、`catId=4465`（饮料，§4.1.4 字典）、`barCode=6921294307016`、`salePrice="3.50"`（取 `sku_catalog.price_cents=350`）、`purchasePrice="0"`（100014 的 `purchase_cost_cents` 是 NULL，按本仓「0/NULL=不收」口径填 0，**非编造成本**）⇒ 厂商回**新 `productId=41175`**、link `id=3 CREATED`，厂商侧现值 `specs="310ml"`、`productEn="ksf_ksfbhcnmkwcyp_310ml"`（印证「规格写错会连带把拼音串也写错」）。🔴 **新发现：厂商在 `product/create` 时抓图并转存自家 OSS**——41175 的 `mainImg` 已是 `jianyidev.oss-cn-chengdu.aliyuncs.com/mainImg/1792726112008_new.png`（实测 200 image/png 213 KB；我方源 `…/api/v2/media/sku-images/2` 是 200 image/webp 32.5 KB）⇒ 送进去的绝对地址必须**建品那一刻公网可抓**；41159 当年留着我方隧道原址，最可能是**那次抓取失败**（推断，未证实）。🔴 **进/售价目前无法从任何只读端点回查**：`waitGatherProduct` 原始行只有 8 个键（无价格）、§4.3.3 `productInfo` 响应只回 `productId`+`gatherTimes`、§4.4.2 `getPageData` 只列**已学习**商品 ⇒ 价格只能等采集学习后验，本轮记**未知**。updateMainImg 先别写代码——既然厂商自己转存图，「改主图能不能不删重建」的价值等 R5 采完再拍板；🔴 真要接，路径是**大写 I 的 `updateMainImg`**，文档写的 `updateMainlmg` 会 404（H-24） |
| **在售面四源 + classId 30 已证伪** | `JiangyiFloorGoodsService.listFloorGoods`（内部只读端点 `GET /internal/v1/jiangyi/devices/{id}/floor-goods`，头 `X-Internal-Api-Key`） | 🔴 **将邑柜的在售面不读 `device_slot`**（CB-051 已拍板「开门柜无货道概念」，`device_slot` 只剩补货/盘点记账语义）⇒ 谁拿 `device_slot.assigned_sku_id` 判「某商品没上架」都是**用错轴**（2026-10-23 我本人犯过一次并当场更正）。四源＝`jiangyi_class_mapping`(ACTIVE + classId + skuId) → `sku_catalog`(ACTIVE) → `device_sku_inventory.quantity` → 商户价。🔴 **classId 是 0-based，且对齐被雪碧那行自证**：模型类别表 `.tmp/hysxr20260930120329732_20260930120413844_3.txt` 共 69 行，`kkkl_xb_qsnmw_pttz_330ml` 在**第 30 行**＝index 29＝映射行 id=72 的 classId ⇒ 数法成立。据此 **index 30 = 第 31 行 = `ksf_ksfdtlcfmmlwtwcyp_1gs`**，而我方冰红茶在厂商侧的串是 `ksf_ksfbhcnmkwcyp_310ml`（41175 实测）⇒ **旧记「class 30 可能就是冰红茶」证伪**，30 号是借来模型里的另一款康师傅。⚠️ **本行原写「`1gs`…具体哪款无据可查，别猜」——这句是错的，而且文档 + 我自己建的只读端点就能查**：§5.5.3 回包字段含 `stdSkuName`（V16 第 96 页 表格 166：`Skuld`/`stdSkuName`/`textName`/`barCode`/`class`「模型排序」），10-23 15:0x 实测 **30 = stdSkuId 1627「康师傅低糖绿茶蜂蜜茉莉味调味茶饮品」/ 6922456896508**（→ 下一行）。**处置（用户 2026-10-23 拍板「停」，口径＝测试按生产标准）**：`POST …/jiangyi/class-mappings/30/status {active:false}` ⇒ id=73 `ACTIVE→DISABLED`（**行与 `sku_id` 都留着，同端点 `{active:true}` 即复原**），下行实测由 2 行→**1 行**（只剩雪碧）。理由：留着＝「认出 B 商品却按 A 结算 ¥3.5、扣 A 的账面」⇒ 事后对账**无法解释**，属资损/对账缺陷而非显示问题；且借来的别商户模型按口径只用于验链路，不该承载业务映射。同轮把 100014 账面按用户给的数走**同一个 PUT 端点**改成 `quantity=1`（`capacity=4`/`lowThreshold=2` 原样回传未动）⇒ 下行 `stock:1`；⚠️ 「缺货」口径是**当前库存 ≤ 低库存阈值**，所以 1≤2 页面仍显缺货、仍可能推缺货通知——要显正常得 数量≥3 或阈值≤1。🔴 冰红茶要真正可识别，只有 R5 采集学习出**自己的** classId/textName（或 #4 下发含它的模型），届时新增一行**带 text_name 证据**的映射。🔴 **这次「停」买到了什么、没买到什么（两条解析通路，源码核过）**：识别归一**先按 `thirdPid` 反查 `sku_jiangyi_link`**（`SkuJiangyiLinkService.java:103`——只排除 `RETIRED`，**既不看映射状态也不校验上下架**，其 javadoc 写明这是刻意设计、设备级门槛交给 classId 兜底路径），命中就直接结算；只有 `thirdPid` 为空/查不到才回落 `resolveActive`（`JiangyiInternalController.java:212` ACTIVE 点查）⇒ 停用 id=73 **确实关掉了 classId 30 这条路**（未命中即 `items` 空 → fail-closed 转人工，`DeviceReportController.java:276-288`），但**关不掉 `thirdPid=41175` 那条**。它目前也发不出来：41175 无采集无学习、设备现用模型里没有它，且设备不拉 `getFloorByDevice`（真机段已证），`thirdPid` 由厂商侧决定 ⇒ 残余风险只剩「厂商把那款 `1gs` 的 pid 恰好也报成 41175」，**10-23 15:0x 这条也已可判**：§5.5.3 回包给了 30 号自己的 pid=**1627**，而我方 100014 只挂过 41159(`RETIRED`)/41175 ⇒ 只要设备按厂商真实 pid 上报，`by-product(1627)` 不命中任何 link、走不到结算。⚠️ 但这仍**不是「结构上封死」**：前提「设备按厂商 pid 上报」对本柜已被实测证伪过（真机 `thirdPid=0`），残余风险换了形态而没消失——别把两种口径混着写。要彻底封只能把这条 link 解挂成 `RETIRED`（`by-product` 随即不认），但那是我方唯一的 100014↔厂商商品挂接，R5 采集回来后仍要靠它把 `thirdPid` 认回我方 SKU ⇒ 本轮不做。 |

### 9B.9 R7｜刻意 404 的行为对照（需拍板或厂商，🔴 不排进本轮）

对象：`pokerOrder/getChessRoomOpenStatus`（真机 **98 次 okhttp 命中、0 次成功：70×401 + 28×404**，形态=事件触发的 3–6 条 burst，非固定轮询——详见 §3 A′ 同路径行）、`feature/getFeatureByDevice`、`deviceApp/updateAppVersion*`（G-7 + 拍板 B）。

为什么**不能顺手补 200**（逐字实证）：回调成功判据是字面 `"status":200` → `data` **`equals("open")`** → `OpenLockStateRecord.writeLockState`，而 🔴 **真/假两条分支随后都调 `DealStringMsg->openLock()`**（`:goto_35`/`:cond_3b`）⇒ 我方回 `open` 还是 `closed` **都会触发一次开锁**，差别只在要不要写那条本地记录。
🔴 **第一道闸不是路由，是凭据**：这条路径真机 98 次里 **401=70**，且同一次 burst 内 401/404 并存（§3 A′ 同路径行）⇒ 设备有两个带不同凭据的调用点，**补了 200 也只对那 28 次有效**。R7 步骤 0=先定「401 那条带的是什么」：抓一次 burst 的 `Authorization` 原文（nginx 不记头，须在网关侧临时开 `logging.level.org.springframework.web=DEBUG` 或对 `/jiangyi/api/**` 前加一个只记头指纹的 filter——**属代码改动，需拍板**），与我方 `DeviceTokenService` 签发值比对。
⇒ R7 的形态=**可控实测 + 人工盯门锁**：同一次只切 404→200 一个变量，观察柜是否多开一次锁；先取证「404 时 okhttp 走 `onError` 还是 `onFailed`」（这条决定补 200 到底改变了什么，仍未取证）。这是七轮里唯一**测它本身有物理副作用**的，绝不与别的轮次混跑；厂商一句话可替代实测。

### 9B.10 排期、互斥与耗时

| 轮 | 时长 | 需现场 | 需厂商 | 冻结购物 | 风险 |
|---|---|---|---|---|---|
| R0 | 10min | — | — | 否 | 无 |
| R2 | 5min | 听声音 | — | 否 | 无（唯一人耳判据） |
| R1 | 20min | 拔/合闸 | — | 否 | 🔴 **可能改 `M_NAME` 档位** ⇒ 必须紧跟一次结算复跑 |
| R6 | 30min | — | （delete 后果要账号） | 否 | 无 |
| R4 | 20min | 拉门/放货 | — | 🔴 是 | 低 |
| R5 | 半天 | 🔴 放样本、拉门（出图=柜机自身摄像头，**无需厂商 App**） | （云侧审核结果） | 🔴 是 | 中（G-6 可能打不开门 + G-5 无采集向导⇒手动脚本） |
| R3 | 15min + 复跑 | 取一瓶 | — | 否 | 🔴 覆盖柜上模型 ⇒ **排在 R1/R4/R5/R6 之后** |
| R7 | 待拍板 | 🔴 盯锁 | 建议要厂商 | 否 | 🔴 会开锁，单跑 |

**建议顺序**：`R0 → R2 → R1 →（结算复跑）→ R6 → R4 → R5 → R3 →（结算复跑）→ R7 另议`。
依据：把「可能改变设备档位」的 R1 放前面，让后面每轮都在**已知的 M_NAME** 下跑；把唯一有覆盖副作用的 R3 放最后，前面各轮跑完才看得出它改变了什么。

### 9B.11 每轮结果怎么落（不留聊天里）

跑完把 §9 对应行的「待真机」改成「实测」，并在此表追加一行：`轮次 | 时间 | PASS/PART/FAIL | 证据（nginx 行原文 / SQL 结果 / 日志事件名）| 新发现的判据`。🔴 0 命中的路径**保留在 `.tmp/never-hit.txt` 口径**（该文件是 gitignore 的本机证据，把结论抄进本表才是入库），别因为「这轮没打到」就把接口从表里删行——删行=下一个人重新猜一遍。

### 9B.12 R8｜多商品识别（2026-10-23 16:0x 我方侧建档完成，🔴 只差现场放货开门）

背景：昨天那次 PASS 只证明了**单款**（雪碧 classId 29 → 100013 → PAID 350）。本轮要证的是**一次开门拿多款、逐款归一/计价/扣库存**，所以先把在售面从 1 行扩到 4 行。

🔴 **2026-10-23 V400 新增前置（H-28）：开门前必须先做一次映射复核**。本柜 `classes_version` 目前为**空**（从没下发/确认过模型），按新口径 `verified=false` ⇒ 在售面直接返空、识别一律 `MAPPING_UNVERIFIED` 转争议、**不扣款**。步骤：后台「识别映射 → 映射复核（结算闸口）」→ 模型选当前 69 类（`modelName` 用借来的 `hysxr20260930120329732_20260930120413844_50`，`textUrl` 取同名 `…_3.txt`，`quantity=69`，起始值 0）→「拉取差异」→ 核对 26/27/28/29 四行仍指向 100016/100017/100018/100013（其余行按 text_name 自动 KEEP/MOVE）→「确认复核并应用」（对从未确认过模型的柜子，这一步会把当前 `classes_version` 采纳为复核版本）。复核后 `mapping-status.verified=true`；若厂商在那之前又重训过，行号会整体平移（H-27），先按 `.tmp/jy-decode-url.mjs` 重新核这 4 个位置。

建档走的是后台同一批端点、没有裸改 SQL（脚本 `.tmp/jy-onboard-multi.mjs`，重跑会撞名称/条码唯一约束，不会静默叠行）：`POST /skus` 建档 → `PUT /skus/{skuId}/jiangyi-link` 挂厂商现成条目（用户 10-23 拍板「挂厂商现成条目」，不走 §4.1.5 新增）→ `PUT /devices/{id}/jiangyi/class-mappings/{classId}` ACTIVE（带 `textName`+`modelName`）→ `PUT /devices/{id}/sku-inventory`。

| classId | 我方 SKU | 挂的厂商 pid | textName（§5.5.3 逐字） | 条码（两处一致） | 价 | 映射行 |
|---|---|---|---|---|---|---|
| 26 | 100016 可口可乐经典美味汽水330ml | 1033 | `kkkl_jdmw_pttz_330ml` | 6954767410388 | 350 | id=75 ACTIVE |
| 27 | 100017 可口可乐经典美味汽水500ml | 127 | `kkkl_qs_jdmw_500ml` | 6954767412573 | 400 | id=76 ACTIVE |
| 28 | 100018 雪碧清爽柠檬味汽水500ml | 2014 | `kkkl_xb_qsnmw_500ml` | 6928804010220 | 400 | id=77 ACTIVE |
| 29 | 100013 雪碧柠檬味汽水330ml（昨日 PASS 那行） | 1018 | `kkkl_xb_qsnmw_pttz_330ml` | 6954767430386 | 350 | id=72 ACTIVE |

⚠️ 这 4 个 classId **只对当前这颗 69 类成立**：类表按 `textName` 排序、厂商每次重训会整体平移行号（H-27；同一批商品在 65 类版本里雪碧是 **25**、可乐330 是 22）⇒ 开门前若厂商侧有过重训/下发，先按 `.tmp/jy-decode-url.mjs` 复核这 4 个位置再测。

`floor-goods` 实测回包（4 行）：`[{26,1033,可口可乐经典美味汽水330ml,350,stock4},{27,127,…500ml,400,4},{28,2014,雪碧清爽柠檬味汽水500ml,400,4},{29,1018,雪碧柠檬味汽水330ml,350,stock2}]`。每行的**归属证据是两处逐字对齐**：V16 §5.5.3 解码行的 `textName/stdSkuName/vendorBarcode` ↔ V1.13 §4.1.2 按 `barCode` 精确查回来的 `name/textName/barCode`（`.tmp/jy-stdsku-byname.mjs` 与条码轮询都跑过）。

⚠️ **28 号有一处对不上，已按「活行优先」处置**：§5.5.3 该类记的是 `stdSkuId=55`、`stdSkuName=可口可乐雪碧柠檬味汽水`，但本租户 §4.1.2 用条码 `6928804010220` 查到的活行是 **`2014` / `可口可乐雪碧清爽柠檬味瓶装500ml` / `textName=kkkl_kkklxbqsnmwpz500ml_500ml`**（55 在 `雪碧`/`柠檬味汽水`/`瓶装500ml` 三次查询里都不出现）。挂 2014，映射行的 `text_name` 仍填**模型侧**那个 token（`kkkl_xb_qsnmw_500ml`，它才是类表里的行名）。判据上不影响结算：现网设备上报 `thirdPid=0`，解析走 classId 兜底（`JiangyiInternalController.java:212`），pid 差异只影响「别去厂商库里按 55 找这款」。

🔴 **本轮的失败面是我方刻意设计，别当新缺陷**：识别归一 fail-closed——报文里**任一行**解析不到 SKU 就整单转争议（`RecognitionNormalizer.normalize` + `DeviceReportController.java:276-288` `JIANGYI_CLASS_MAPPING_MISS`）。所以现场**只放这 4 款**；放 20 号加多宝 500ml、或 30/31 号康师傅（现 DISABLED）都会把整单打废。库存 4 行 qty=4/cap=4 也是同一原因：无 lot 柜扣成负数即 409，账面必须 ≥ 设备可能上报的件数。

**判据（跑完在此追加一行）**：一次开门取 2 款以上 ⇒ `jiangyi_recognition_report` 新行 `outcome=FORWARDED` 且 `recognition_result` 含 **≥2 个 classId**、订单金额=对应单价之和、库存逐款递减、会话 COMPLETED。若只出 1 款 ⇒ 先分「设备只报了一件」还是「第二件解析失败被丢」，看同一 report 行的原始 JSON 再归因。

---

## 10. H｜文档笔误与不一致（照抄会错的一律钉在这）

| # | 位置 | 文档写的 | 实际 | 我方处理 |
|---|---|---|---|---|
| H-1 | §4.2.4/§4.2.5/§4.2.13/§4.2.15–18 全部 | `devicelnfo`（小写 L）、`getArtificialCheckByldentifier`、`getDeviceSimlnfo`、`Deviceld` | `deviceInfo`、`ByIdentifier`、`ByDeviceId`。**🔴 这不是全部**：商户面 V1.13 同样中招，且**访问路径整条被改**（→ H-24） | 我方按**正确拼写**建映射（Grep 文档 OCR 时注意） |
| H-2 | §4.2.13「设置视频上传模式」 | 路径写 `deviceInfo/getArtificialCheckByIdentifier` | 与 §4.2.11/12 同路径复用不是笔误，但**函数名与标题语义相反**（`ArtificialCheck`=人工复核，字段义=是否上传所有视频） | 回 `false`（只传异常视频），`isAutoUploadVideo` 同口径 |
| H-3 | §4.2.9「上报货柜正在使用中」 | 路径 `order/openDoubleDoorError`，描述行是从 §4.2.10 复制的「将识别结果上报」 | 真机实际走 `deviceInfo/uploadDeviceOpenStatus` | 两条都实现；`openDoubleDoorError` 仅审计 |
| H-4 | §4.2.16「上报门磁状态」 | 描述整段是广告接口的文字 | 路径 `getDoorInfo` + 参数 `doorState/lockState` 与标题一致 | 按标题+参数实现 |
| H-5 | §4.3.1 心跳 | 「机器端发送 `{msgType:heartBeat, msgContent:PONG}`」 | 固件发 **PING**，服务器回 PONG（log3 实测） | 不校验入值、恒回 PONG ⇒ 两形皆绿 |
| H-18 | 本表 10-22 早班批次的「`COMMON_URL` 比 `URL` 多一段 `/device`」 | 固件 `Constans.<clinit>` 把 `URL` 与 `COMMON_URL` 赋成**同一个串**（`Constans.smali:2396/2403` 与 `:2405/2408` 两次 `const-string` 完全相同 = `https://lingshouapi.hunanjysmart.com/device`），我方自钉 base 是 `…/jiangyi/api`；`InitAppServerInfoControl$19:144` 把回值**原样**写入 `URL`，`:146-156` 只做 `equals` 比较，**全程没有任何地方给 COMMON_URL 追加 `/device`** | nginx 里 `/jiangyi/api/device/…` 那四条（`uploadDoorState`/`uploadLockState`/`pokerOrderVideo/uploadVideoUrl`/`pokerOrder/uploadOrderError`）全部来自**字面自带 `/device/`** 的调用点（`UpDoorState.smali:1927`、`OrderVedioInfo2Server.smali:1576`、`CameraPreviewNew.smali:7184`），不是 base 带的；而 `tenant/getOrderRecheckProduct` 那 22 次裸命中出自 `BaseActivity:428` 的 **`URL`**，**不能**反过来当 COMMON_URL 的证据 ⇒ **COMMON_URL 现值在 nginx 里无从判明** | 设备面同一尾串的两种拼写一律都注册；只信「日志里见过哪种」会把没跑到的路径判成已验证 |
| H-19 | 早班「20 次 `/device/pokerOrder/uploadOrderError` ⇒ `M_NAME=YP`（唯一解）」 | 该 `/device` 路径有**六处**站点会发（`UploadInfo2Server.uploadErrorOther:1247`、`CameraPreviewNew:7184`、`OrderRknnResultDeal:2999`、`UploadIdentifyResult:57095`、`UploadLocalOrder:759`、`UploadInfo2ServerStock:1316`），六处逐个读了门都是 `equals(NORMAL)` 为真即 `return-void` ⇒ 只证到 **`≠NORMAL`**；`OkhttpUtils.postRequest`/`postRequest_json` **只对 `NO_NET` 短路**（`:759-774` 等四处），不拦 XY，而 XY 下这些站点照样发 | 真机那 20 次的 body 无法二分：`uploadErrorOther` 的 HashMap 键就叫 `errorMsg`/`errorDetailMessage`，与 `ErrorOrderEntity` 同名，我方日志只落这两个字段、没存原文 | 定档结论一律写 **`M_NAME ∉ {NORMAL, NO_NET}`，XY/YP 未分**；下次开机的判别探针＝**裸 `/pokerOrder/uploadOrderError` 若与 `/device` 那条成对出现 ⇒ 定 YP 且证明 COMMON_URL 落我方域**（`uploadError` 在 YP 腿会连发 JY+Other 两条，XY 在 `:cond_85` 就跳走了）。对 #90 无影响：`uploadBuyGoodsInfo2Server:645`（XY→`:cond_3f`、YP→`:cond_3f`、NO_NET→`:goto_3f`）三档同处置＝`dealNormalOrder` 只发 AIDL、零 HTTP |
| H-20 | 10-22 14:41:51 与 14:42:36 两次真机开门＝判据 **C 命中，A/B/D 全未检验** | 设备侧（UA `okhttp`）14:41–14:43 窗口只打了 8 条：`tenant/getOrderRecheckProduct`×2、`floor/getDeviceSellProductClassIds`×2（我方回 `classIds=[29]`）、`device/uploadLockState`×2、`device/uploadDoorState`×2、`device/pokerOrder/uploadOrderError`×2、`device/pokerOrderVideo/uploadVideoUrl`×1、`aliYunOss/getTempUploadToken`×1 ⇒ **`orderProduct/addRecognitionGoodsToOrder`（A）与 `pokerOrderProduct/addProductsToOrder`（B）两条腿 0 命中**，`jiangyi_recognition_report` 仍 **0 行**，`device_sku_inventory` 仍 1（未扣款，符合 fail-closed） | 🔴 **本轮没有「识别成功」样本**：两次都是设备主动转人工（14:42:09 `errorMsg=再次确认`，成因 **`幻影放回抑制链冲突转人工:T2 kkkl_xb_qsnmw_pttz_330ml(OUT计费与被抑制IN同链不可分)`**＋cam=1 调光未确认；14:42:54 `errorMsg=人工复核`，成因两摄 `开门后调光未确认:reason=settle_budget`）⇒ 既没证成 AIDL 档（D），也没暴露「第四档 ⇒ 我方缺 `addProductsToOrder` 路由」（B）。我在 14:37 把 D 写成「最可能」属**无成功样本时的外推**，本轮不改变「XY / YP / 第四档」三态未分 | ①潜伏缺口如实登记、**不提前实现**：`addProductsToOrder` 固件有两处字面——`UploadInfo2Server:349`（`COMMON_URL`+裸串，JY 腿在 `M_NAME≠NORMAL` 时改走它）与 `:625`（`URL`+`/device` 串，且受 `IS_OPEN_DOOR_ALAWYS` 开关），我方**均未注册**，真打到就是 401/404，责任在我方缺路由（payload 未文档化，等实路径出现再补）；②下轮要拿的判别样本＝**一次干净的取货**（只拿一瓶、手不碰其它货道、不放回、取完即关门），若出现 `addRecognitionGoodsToOrder` ⇒ 第四档、补路由即可通；若仍零上行 ⇒ 才轮到 AIDL 档成立 |
| H-21 | 10-22 14:53:05 一次「干净取货」＝**又落 `uploadOrderError`（判据 C），但设备自报成因被现场协议消掉两条** | 会话 `1792651983860343089366` DISPUTED、不扣款，`fail_reason` 尾部原文 `人工复核｜开门后调光未确认:cam=1,reason=settle_budget；原图证据不足，保留复核` ⇒ **14:42 的 `幻影放回抑制链冲突` 与 11:43 的 `副键弱证据换类（魔爪/雪碧）` 本轮均未复现**（膜已撕、柜内 3 瓶、另开一次门只取一瓶、不举到镜头前、取完即关门）；`addRecognitionGoodsToOrder`/`addProductsToOrder` 仍 **0 命中**、`jiangyi_recognition_report` 仍 **0 行**、`device_sku_inventory` 仍 3、视频 14:53:47 迟到 31 秒才落 `jiangyi_order_video` | 🔴 **#90 剩下的唯一设备侧闸＝`cam=1 reason=settle_budget`「开门后调光未确认」**，与商品/保护膜/开门时长/动作序列全无关（#92 已证 39.2s 也不消），我方无旋钮 ⇒ 只能厂商解释或修。另：网关日志 `addRecognitionGoodsToOrder` 那 4 条命中全是本人 14:26 的 `curl` 探针（`outcome=missing_token remote=172.18.0.12`＋401） | ①现场协议**已验证有效**，后续取证一律按「放/取分两次开门、只取一瓶、不放回、取完即关门」；②判「设备打到过某端点」**只认 nginx 台账 `UA=okhttp/4.9.3`**，应用日志同字面须先看 remote；③判「视频缺失」要等 ≥40 秒再查库 |
| H-22 | 本表 H-20/H-21 的「识别成功零上行 / 唯一闸＝`cam=1 reason=settle_budget` 只能厂商」 | H-21 判「取货轮剩下的唯一设备侧闸是 cam=1 调光未确认，我方零旋钮」 | 🔴🔴 **10-22 17:02:59 全链真绿，两条定性一并作废**：会话 `1792659768034787655997` 17:02:48 开门→17:02:59 关门，nginx（UA `okhttp/4.9.3`，全 200）出现 `orderProduct/addRecognitionGoodsToOrder` 与 `aliYunOss/getTempUploadToken`，**整轮 `uploadOrderError` 0 条**；网关 `recognition forwarded … items=1 outcome=PROCESSED`、`jiangyi_recognition_report` report_id=2 **`FORWARDED`**、`recognition_result` = `[{100013×1,conf 1.0}]` `model_version=jiangyi:CQYB10720`、订单 `1792659779535535992400` **PAID 350 分**、会话 **COMPLETED**、该会话 `ops_exception` **0 行**、trade-service 自 17:02 起 **0 ERROR**。「调光未确认」这轮**一次都没出现**——用户在 16:2x 现场调整了摄像头与门锁，并说明**门锁此前只锁了一半** ⇒ 那道闸是**门未合拢**（采集窗口状态机不闭），不是厂商旋钮。附带：`/jiangyi/api/order/uploadOrderCloseStatus` 被真机打到 **404**（body `doorCloseStatus=…`，已知未注册路由，非设备故障） | ①**再遇「调光未确认/证据不足」类成因，先查门锁到底、镜头对齐，再谈厂商**；②`M_NAME` 档位判废重核：成功腿走的是 `orderProduct/addRecognitionGoodsToOrder` **裸拼写**（按固件 `:349` 那是 NORMAL 档特征），与 H-19 降级后的 `YP` 结论冲突 ⇒ 要定档**先问厂商他们云上当前 M_NAME**，别再从 nginx 反推；③`recognition_result.fusion_mode` 恒 `VISION` 是 `RecognitionResultWriter.java:135` 给所有入口写死的标签，**不代表我方视觉链跑过**，别误读成造假；④16:36 那单丢单是我方 **412 不足额毒化共享事务**（F1-B 的 `noRollbackFor` 只盖 `BalanceInsufficientException`），已按争议结案 CONFIRM 收口，代码修复待拍板 |
| H-23 | 「关门反馈」三条通道分开判（真机 2026-10-22 + 固件 `control/lock/tool/UpDoorState`） | §4.2.8 只写「上报用户**拉门**状态」（`doorStatus=success/fail`），第四章**没有**关门回执端点；§4.2.16 列了可选门磁 `deviceInfo/getDoorInfo` | ①`uploadDoorState` 真机**每轮只发 1 次＝OPEN**（nginx 全史 32 条，无一轮有第二条）；网关日志里的 `door-event CLOSED` 是**我方在识别上报到达时合成的**（`DeviceReportController:508-512`），不是设备反馈；②设备**确实有关门上行** `order/uploadOrderCloseStatus`（全史 3 次：16:23/16:36/17:02，body `doorCloseStatus=yes, lockStatus=success`）⇒ 此前我方 **404 未注册**；③门磁 `getDoorInfo` 全史 **0 命中**（设备从不发） | 🔴 该回执**不能当门锁到位判据**：16:19 那轮设备自报「开门时间过长」，4 分钟后仍回 `doorCloseStatus=yes`；固件 `UpDoorState.persistPhysicallyClosedReceipts` 更把 `doorCloseStatus` 写死 `"yes"`，且 `uploadCloseDoorState` 那段在 `M_NAME ∈ {XY,YP,NO_NET}` 时整段跳过（`:395-430`），活体字段（`lockStatus`）与 563 smali（`doorStatus`）已不一致 ⇒ 语义只能问厂商。10-22 17:3x 已把该端点注册为**只审计 200**（`order`/`pokerOrder` × 裸/`/device` 四拼写，收原始 Map 全量入日志，**不接状态机**），单测 137/0、IT 23/0，容器 17:38 重建已生效；另：`shopping_session.close_time` **不可当关门证据**（9 轮全有值，被 6 处非门径写） |
| H-24 | V1.13 **访问路径与字段名整篇 OCR**：大写 `I` 被识别成小写 `l`（H-1 只记了 `devicelnfo` 一族，范围远不止） | `doc_v113.txt` 逐字：`/merchant/devicelnfo/getldentifier`(:397 §3.6.1)、`/merchant/product/updateMainlmg`(:914 §4.1.7)、`/merchant/selfhelpGatherProduct/productlnfo`(:1403 §4.3.3)、`/merchant/selfhelpGatherProduct/uploadlnfoNew`(:1890 §4.3.11)、`/merchant/devicelnfo/modifyAffiliationTenant`(:2900 §4.6.1)；字段名 `productld`(:922/:1039/:1305/:1411/:2535…)、`mainlmg`(:542/:749/:927/:2442/:2530)、`productlds`(:2502) | 🔴 **照抄 = 打到一条厂商侧根本不存在的 URL**。实测对撞 `.tmp/jy-ocr-path-probe.mjs`（2026-10-23 10:39，同一个 Bearer）：控制组 `stdSku/getList` → `http=200 status=200 data=array(11)`；`uploadInfoNew`（正确拼写）→ `http=200 status=200 data=null`（本柜当前无采集批次，正常）；`uploadlnfoNew`（**文档原文**）→ `http=404` + Spring 默认错误页 `{"timestamp":"2026-10-23 10:39:36","status":404,…}`；尾串 `uploadInfoNewZZZ` → **与文档原文逐字同形的 404** ⇒ 服务器眼里两者都是「无此路由」 | ①我方 client 里凡从文档抄来的路径/字段，**落码前先跑一次 404/200 对撞**（探针已现成）；本轮要接的 §4.1.6 `product/delete` 与 §4.1.7 必须写成 **`updateMainImg`**，别把 `updateMainlmg` 抄进去；②🔴 **判别路径存在性必须在鉴权有效时做**：同一条不存在的 URL，Bearer 坏时回 `http=200 status=503 msg=请先登录`，Bearer 对时才回 `http=404` ⇒ 厂商的鉴权在路由之前，**503 会把 404 完全掩盖**，拿 503 判「路径不存在」是错的；③`data:null` 不等于失败，判据只有 envelope `status==200` |
| H-7 | V1.13 §4.4.4.4 `industrialControlModel` | 中文说明也叫「模型类型」 | 厂商后台弹窗里对得上商户 API 的是**「工控型号」**那栏；「模型类型」是另一档（大模型/基础模型/商品模型，**商户 API 无对应字段**） | 校验只做字符串相等、不解释语义；默认登记 `88`（2026-10-10 拍板） |
| H-8 | V16 第四章 vs 第五章 | 两章各有一套 §x.2 设备面 | **入向面逐条完全一致**，第五章只多 3 条（§G-12）；`domainConfig/*` 两条都**没有**（固件自用未文档化面） | 以第四章为契约，`domainConfig` 走自钉 |
| H-9 | 文件名判模型类型 | — | 🔴 **不可靠**（实证推翻）：厂商「大模型」列表里的 `best_800rk3588_0310_800senpt.rknn` 载荷 `modelType:"product"` | 只信 `modelType` |
| H-10 | 工控机型线索 | 文档全文 grep「工控」**0 命中**（V16） | 真机下载的是 **rk3576** 系列（log4：`best640s_box_rk3576_0708enpt.rknn`、`ZQ_..._3_50_3576.rknn`、`best_800rk3576_0310_800senpt.rknn`），而 10-21 厂商后台推给我方的是 **rk3588** + `industrialControlModel:"88"` | ⚠️ **今晚测试前必读**：若 `updateModel` 真到设备而模型加载失败，**先怀疑 76/88 与 rknn 编译目标不匹配**，别判「通道不通」。76/88 语义=厂商问题清单在案项 |
| H-12 | V1.13 §4.3.6.4 `:1689` + §4.3.11.4 `:1932` | 「图片不足2000张」出现在**错误查询响应的括注**和**上传状态枚举的标签**里 | 两处都**不是门槛**：前者逐字「也是返回 no，**不算错误**」，后者只是 `success/fail/wait/weak` 四档标签。真数在 V16 §5.6 引言「**提交合格图片 200 张就可以了**」 | ⛔ 别再引用"自采要 2000 张"（我方 2026-10-21 一度据此判"自采不现实"，已更正） |
| H-13 | V1.13 §4.1.2 `/merchant/stdSku/getList` | 请求参数只有 `name`(O) / `barCode`(O) / `applyMachineId`(M 固定 21)，**无 page/pageSize**；响应参数也无 `total` | 🔴 **空查询回 20 条是服务端截断，不是库总量**。实测：`name=雪碧` 回 11 条（id 最大 **4083**）、`name=可口可乐` 回 20 条且 id 从 127 散到 3061 ⇒ 库里**至少 4000+ 条**标准商品 | 判"某商品在不在厂商库"**只能带 `barCode` 精确查**，不能拿列表长度或首条当结论（`.tmp/probe-stdsku.mjs`）。雪碧 `6954767430386` 精确命中 1 条 = `id 1018 / JY-001018`，与我方 `sku_jiangyi_link` 逐字对上 |
| H-14 | 同库脏行 | 「雪碧」11 条里 `id=3563 test雪碧听装330ml`、`id=4083 雪碧330ml` 两条 `barCode=null`，另有 4 条同 330ml 规格不同 69 码 | 名称模糊搜索**必然**命中测试/仿行 | 已按 H-13 收紧：`SkuJiangyiLinkService.pullTextName` 2026-10-22 起「条码精确相等且 textName 唯一」才回填，多条不同 textName ⇒ 409 列候选要求人工（此前是循环里最后一条静默覆盖） |
| H-15 | §5.5.3 的用法（2026-10-22 实测确立） | 接口中文名「根据 txt 文件地址查询商品信息」+ §4.3.2.9 把 `quantity` 说明写成「模型**商品**数量」、`textUrl`「模型对应 class 下载地址」 | ⇒ **classes 的每一行就是一个商品名**，所以「某个模型能不能用于结算」**不必问厂商**，直接拿它的 `textUrl` 调 §5.5.3 就自证 | 🔴 判据：返回行的 `barCode` 必须能对上我方在售 69 码。实测 10-21 那份 `_auto_6` 载荷（`classes_auto_6.txt`）只回 **2 行** `egg→stdSkuId 1911「测试egg」`、`toy→2423「测试toy」`，`barCode` 均 `null`，`bottle/bowl/bag/box` 四行整条消失 ⇒ **通用包装形态模型 = 结算不可用**，只能用于下行通道验证（任务 #75）。`textName` 与 `stdSkuName` 是模糊匹配（`egg`→`测试egg`），别指望它精确回原名。探针 `.tmp/probe-553.mjs`（改 URL 即可复查任何模型） |
| H-16 | 真机 logcat（`.tmp/log2/3/4.txt`、`device-log.txt`，本柜自报 `identifier=CQYB10720`） | 模型下行的 msgType **不止文档 §4.3.2.9 的 `updateModel`** | 实测四档**下行 16/16/8/8 + 同名回执 8/8/4/4**：`updateModel`、`updateThirdModel`、`updateBaseModel`、`updateBigModel`。🔴 **各档用的 classes 文件不同**：`updateModel`→`…_3.txt`（quantity 65/69，真商品数）；`updateBaseModel`/`updateThirdModel`→`classes_auto7.txt`（hand/bottle/bowl/bag/box/egg/toy）；`updateBigModel`→**`classes_auto_6.txt`** ⇒ **10-21 厂商后台点的那次下发（quantity 6 + classes_auto_6）用的是「大模型」档同一个 classes**，它从来不是商品模型，这解释了为什么它不可能识别 SKU。⚠️ 但厂商后台 HTTP 载荷写的是 `modelType:"product"` ⇒ `modelType`→WS msgType 的映射**是我方按 classes 文件名反推的，未实测**，别当定论；我方网关目前只发 `updateModel`（商品模型档），与此一致 | 若柜在线后推 65/69 类模型仍不加载，按档位（而非通道）排查；台账里 `update*ModelSure` 四档我方均已合并到同一 handler（G-3） |
| H-6 | §4.3.2.11 拉取视频示例 | `msgContent:"24005355433545_MSG"` | 固件 `DealSocketMsg.smali:186-200` 按 `/sdcard/{orderNo}-left.mp4` 找文件 ⇒ **不带 `_MSG`** | 我方透传会话 id，不加后缀 |
| H-11 | §4.2.5 返回说明 vs 示例 | 说明栏 `data` =「数据 / 对象 / 无用，固定格式」（v16:783-786），**json 示例却是 `"data":"0"`**（v16:789） | 同一节自相矛盾 | **照抄示例**（我方 `ok()` 回 `data:"0"`）——说明栏那三个词不构成字段规格；我 10-21 起草本表时曾把它判成「我方偏离云侧」，被 v16:789 推翻，已订正 |
| H-17 | §5.5.3 返回参数表 + json 示例（v16:6008-6042） | 参数表写 `Skuld`/`stdSkuName`/`class`，json 示例写 `"stdSkuld"`/`"name"`/`"classId"`——**同一节两套键名** | 🔴 真机回包两套都不是：`.tmp/probe-553-real.mjs` 2026-10-22 逐字打印 = `stdSkuId` / `stdSkuName` / `textName` / `barCode` / `classId` | 编码**只按真机回包**（`JiangyiGatherClient.stdSkuByTextNames:106`）。教训：文档示例连键名都不可信，凡是「照抄示例字段名」的接入点都要先打一次真回包 |
| H-25 | 我方 `jiangyi_device.model_name` **一列两义**（2026-10-23 现场撞出） | 登记弹窗那栏写的是「设备型号（可选）」（`DeviceJiangyiCard.vue:756` → `JiangyiOnboardingService.register:77`），下发回执 `confirmModel` 又用它存「当前生效模型名」（`JiangyiModelSyncService.java:326`） | 本柜现值 `model_name=CQYB10720`＝**登记时填的型号/identifier，不是模型**（`classes_version` 空、`jiangyi_model_deployment` 无行 ⇒ 从没下发过）。而识别映射页顶部「当前生效模型」读的就是这一列（`VisionMappingView.vue:60`）⇒ 界面在把「型号」当「模型」展示 | 🔴 **R3 的判据别用 `model_name` 非空**：只认 `jiangyi_model_deployment.confirmed_at` + `classes_version`。列拆分/改标签属口径变更，等下发真跑通那轮一起拍板，别顺手改（改了会把已有登记值语义搅浑） |
| H-26 | 厂商 `POST /merchant/modelFile/getModelFile`（用户 2026-10-23 15:5x 贴出，需自填 `commonTenantId`） | 我方**没有**这个接口，只有 §4.4.4.1 `getModelFileByApi`（**无参数**、租户由 token 定，`JiangyiGatherClient.java:250`）；两份厂商文档全文 `getModelFile` 唯一出现＝`getModelFileByApi` 那一行，`commonTenantId` **0 命中** | 四档实测（`.tmp/jy-modelfile-probe.mjs`，同一 Bearer）：`getModelFileByApi{}`→**0**、`getModelFile{76,product,commonTenantId:413}`→**5**、`{…426}`→**0**、**不带 `commonTenantId`→1544 条**（textUrl 覆盖 418/411/374/393… 多个租户）⇒ 未文档化且**无租户隔离**。413 那 5 颗（id 5282/5068/5066/5063/5061，quantity 13/12/11/3/3，5063/5061 共用同一 classes）经 §5.5.3 解出：`bj_phone_10g`/`bj_watch_8g` 干扰类 + 百事可乐330罐/脉动600/东鹏补水啦555/东鹏特饮/红牛250/康师傅冰红茶**500ml**(6921311105014)/丽芝士威化/东方树叶茉莉花+乌龙/农夫山泉550/维他柠檬茶——**无雪碧、无可口可乐**，冰红茶 500ml ≠ 我方 310ml（6921294307016） | 🔴 **不接**：跨租户数据面 + 对 R8 不解渴（下发任何一颗会把柜上 26/27/28/29 四类整片换掉）。R8 继续用借来的 **id 5247/5242**（`hysxr20260930120329732_76_20260930120413844_50`/`_10`，quantity 69，`…_3.txt`）；要**新**商品可识别只有 R5 自采（**200 张/商品**）或厂商给租户 426 训练。⑥（16:2x 补）回包还带 `modelUrl`：id 5282 → `…_413_80_3576.rknn`，OSS HEAD **6,603,840 B**、ETag MD5 `d4b7de4d4bbbb2a4c654fa4d463cec9e`，与用户下载到桌面的同名文件 **MD5 逐字相同** ⇒ 文件是真的、也能下（**OSS 无鉴权**），但「能下」≠「厂商给我们下发」：租户归属只看 `commonTenantId`，426 仍 0 条；`encryptionFlag=2` ⇒ 文件头没有 `RKNN` 魔数是**加密**所致，别当坏文件 |
| H-27 | **classId 是排序后的行号，跨模型版本会整体平移**（2026-10-23 16:3x 拿两版 hysxr 类表对撞） | 旧结论「classId 只是 classes.txt 的行号」只对一半 | 65 类（厂商 id **5102**，09-18，`ZQ_hysxr20260918155237…_3.txt`）vs 69 类（id **5247**，09-30）：新表在 `adym` 前插入 `OKPERCENT_xdltzwyl_360ml`、`bskl` 前插入 `bq_bqbhcklqs_600ml`、`bx` 后插入 `bzxt_bhsz_450hs`/`d5j_ynxqngzqps_scan320ml` 共 4 行，整表按 `textName` **ASCII 升序**（大写 `OKPERCENT` 排第 0）⇒ 雪碧 330 由 **25 → 29**、可口可乐330 由 22 → 26、其后每行整体 +N。另：hysxr 历史 65 类维持近两个月 → 67（09-29）→ 69（09-30），**每次学习新商品就重训并重排行号** | 🔴 **映射的 class_id 不是稳定主键**：厂商任何一次重训（哪怕只加一个商品）都会让「在我之前」的商品行号平移 ⇒ 若不重算，我方 `jiangyi_class_mapping` 会**静默错位**（认 B 扣 A）。R8 的 26/27/28/29 只对**当前这颗 69 类**成立，跑测试前先核这 4 个位置；生产必须做「模型版本 ↔ 映射版本」联动：模型更新后按 `text_name` 重算全部映射并强制复核，未复核的映射不可结算（已有 `writePregenerated/activatePregenerated` 但会打 DISABLED，见 G-16） |
| H-28 | **重训后映射会静默错钱**——2026-10-23 已做成**结算闸口**（H-27 的后果，CB-065） | 旧行为：映射只按 classId 存，模型重训/更新后既不重算也不失效；柜机按新行号上报 ⇒ 认 B 扣 A。`classes_version` 只在模型回执里回来，系统里**没有「映射已验证」这个概念** | 新增 V400：`jiangyi_device.mapping_verified_version / mapping_verified_at`；**verified ⇔ `classes_version` 非空且 == verified_version**。复核页「拉取差异」按 `text_name` 重算 KEEP/MOVE/NEW/ORPHAN/CONFLICT（两阶段 `-rowId` 哨兵绕开 `UNIQUE(device_id,class_id)`），人工确认后**同一事务**写戳；三条自动失效：`confirmModel` 换版本 / 未知模型回执（无 SENT 且从未确认）/ 下发超时（真改到行才撤）。未复核 **fail-closed 三层**：在售面返回空 / `resolveMapping` 返 null / gateway 在归一化前整单 `MAPPING_UNVERIFIED` → 订单 DISPUTED | 操作与判据：① 识别映射页顶部必须显示「已复核 classesVersion=…」；② 任何模型下发/确认后都要重新「拉取差异 → 确认复核并应用」，否则该柜识别**全部转争议**（**包括只带 thirdPid 的单**，刻意收紧）；③ CONFLICT（同柜同 text_name 多行）一律拒绝应用；④ `classId 0` 是合法类，过滤用 `>=0` 不是 `>0`；⑤ 本柜 `classes_version` 目前为空 ⇒ **部署 V400 后先跑一次复核**（adopt 当前 69 类）再开门；⑥ 别把 `model_name` 当模型（H-25），复核页的「柜机版本」只认 `classes_version`。⑦ 真机验证：未复核时下单 → 网关 `outcome=MAPPING_UNVERIFIED`、`recognize-timeout` 成因 `MAPPING_UNVERIFIED`、订单 DISPUTED 且不扣款；复核后同一单可正常结算 |

## 11. I｜本表怎么复核出来的（含被抓到的错）

- **2026-10-23 15:0x 用户一句「不是，你再看看文档呀？」抓到两处「把没读过的字段说成没有」**：① 我在 §9B.8 写 classId 30「具体哪款无据可查，别猜」——**V16 §5.5.3 表格 166 的回包字段里就有 `stdSkuName`（标准商品名称）**，我方 `…/jiangyi/class-decode` 就是它的只读封装、`stdSkuName` 一直在返回里，而我 **15 分钟前刚用它数过行号**，却把「我选了数行号这条笨路」写成「无从查起」（实测 30=1627 康师傅低糖绿茶、31=1621 康师傅绿茶、20=3388 加多宝 500ml PET）。② 我对用户说「判在不在厂商库**只能**带 `barCode` 精确查」，而 **V1.13 §4.1.2 写的是 `name` O 模糊、`barCode` 也 O 模糊**（V16 §5.5.1 另写 `name` M 模糊、`barCode` O 精确「正在实现中」⇒ 两版分歧，实测以精确为准）；「只认条码」只是我方 `pullTextName` **自动化回填**为躲 H-14 仿行而收紧的判据，不是商品库的查询能力——按名查现在就跑得通（`加多宝`3 条 / `冰红茶`17 条 / `雪碧`11 条）。**教训＝「查不到」有三种，必须分清：对面没这个字段 / 我方端点没透出 / 我方读了但没往那儿想。落『无据可查』之前，先把已有只读端点回包的全部字段名打一遍（本次就是一行 `Object.keys` 的活），并回文档核对入参清单；把自己为自动化加的闸当成对面接口的边界讲给用户，是把同一类错从内部搬到对外。** 附带后果：这次重读直接挣到一条**活的资损缺陷**——classId 31 = 康师傅绿茶，我方却 ACTIVE 挂着 100015 加多宝（→ §9B.8 新行）。

- **2026-10-23 12:2x 抓到我自己「用错轴」把已完成态说成缺口**：我拿 `device_slot.assigned_sku_id`（真柜 8 格里 100014 确实 0 格）断言「冰红茶没排货道 ⇒ 挂不上」，被用户一句「货道 有呀」+ 上架商品页截图推翻。真因：**将邑柜的在售面根本不读 `device_slot`**（`JiangyiFloorGoodsService.listFloorGoods` 只读 class_mapping/sku_catalog/`device_sku_inventory`/价；CB-051 早就拍板「开门柜无货道概念」，前端注释 `DeviceDetailView.vue:438` 就写着数据源是 `device_sku_inventory`），而 100014 在 `device_sku_inventory` 里是上架态（容量 4）。实测 `floor-goods` 里冰红茶**本来就在下行**（`classId 30 thirdPid 41175 ¥350 stock 0`）。**教训＝一个项目里常有两套并行的"上架"模型（格子维度 vs 商品维度），下结论前先回代码确认「这条链路读哪张表」；用错表的"证据"比没证据更贵——它听起来已经验过了。** 顺带这次对撞还挣到一条硬结论：classId 30 **不是**冰红茶（见 §9B.8 新行），旧记「仍未证实」可以定档为**证伪**。
- **2026-10-23 11:2x 抓到我自己上午写进 §9B.8 的一条假象（41159 的 `mainImg`「裸根、不带路径」）**：真跑 §4.1.6 删除 + §4.1.5 重建时同一条 `waitGatherProduct` 读回来是 `https://<vicp-host>/api/v2/media/sku-images/2`——**带完整路径**；而我方 `SkuJiangyiLinkService.PendingGatherView` 原样透传 `p.mainImg()`（不做补全）⇒ 不是代码在中间改写，是**我 10:5x 打印时把隧道域名之后整段一起吃掉了**（脱敏用了会吞路径的贪婪式）。**教训＝脱敏只能按「替换固定前缀」写（`replaceAll(PUB,'<tunnel>')`），不能用 `s#https://[^/].*#…#`；凡「对面存了什么」这类领域事实，落表前要有两条独立读取对撞（我方端点 + 直打厂商），单次打印不足以定性。** 顺带这次真跑把两条原先只是推断的 Facts 变成实测：§4.1.6 删除在厂商侧**真消失**（待采集 1 条→0 条）、厂商在 `product/create` 时**抓图并转存自家 OSS**（→ §9B.8）。
- **2026-10-23 12:4x 停完 classId 30 映射后回源码核「停用到底关的是哪条路」，差点把「关不掉的那条」说成封死**：识别归一是 **`thirdPid` 优先、classId 兜底**两条通路（`RecognitionNormalizer.resolveSkuId:76-97`），而 `thirdPid → sku_id` 的反查**只看 link 是否 `RETIRED`，不看映射状态**（`SkuJiangyiLinkService.java:103`，javadoc 自己写了「刻意不在这里再校验上下架」）⇒ 「映射行停掉了＝这个 SKU 不可能被结算」是**错的**；我这次是靠「41175 没采集没学习、设备模型里没它」才成立，不是靠停映射这个动作成立。**教训＝一个结论有两个以上通路时，要逐通路说「哪条被我关掉了、哪条我只是没证据走通」，别把「现在发不出来」写成「结构上封死」。**
- **2026-10-23 10:3x 抓到自己在写 H-24 探针时造出的一次假象**：我把探针的 `raw()` 从「返回原始 json」改成「返回摘要 `{http,status,msg,data:类型}`」之后**没改消费点**，于是 `login.data` 变成了字符串 `"string"`，四条调用全带 `Authorization: Bearer string` ⇒ 全部回 `http=200 status=503 msg=请先登录`，连控制组 `stdSku/getList` 也一样。我当场判成「厂商侧鉴权/会话今天坏了」，并用它否掉了 §3.6.1 那组拼写对撞。真因是**我的脚本**：同一条命令改用另一份现成探针（凭据同样从容器 env 取）打 `stdSku/getList` → `status=200 rows=1`，厂商面一切正常。**教训＝探针改返回结构必须同步改所有消费点；且任何「对面坏了」的判断，先看有没有一条已知可用的控制组——控制组跟着一起坏＝是我的脚本，控制组独活＝是对面。** 顺带这次踩实了一条方法学（已进 H-24②）：**厂商鉴权在路由之前**，Bearer 坏时对不存在的 URL 也回 `200/503 请先登录`，所以「503」既不能证明路径存在、也不能证明不存在，**路径判别只在鉴权有效时成立**。
- **2026-10-22 18:0x 为设计 §9B 测试流程而做的一次重算**（口径：`grep -rh '"/jiangyi/api/…"'` 注册字面 59 → nginx `UA=okhttp/4.9.3` 命中 16 → `comm -23` 得 0 命中 44；WS 下行按 `send(` 调用点得 7 个 msgType，按 `.tmp/round-20261022/gateway.log` 的 `ws downstream` payload  tally 得实际只发过 3 种）。**抓到我自己昨日落表时的两处错**：① §4.3.2.13 `getDeviceInfo` 记成「❌ 未接」，实际 `DeviceCommandController.java:76` 自 2026-10-15 `167f51d7` 就实现了（同文件上下两行的音量两条我都引对了，唯独漏了它）⇒ 总账 B「6 个 msgType / 10 未发」订正为 **7 / 9**，G-10 同步改；② 我把「已实现未实测」和「未实现」混在同一列里，导致 §9 看上去缺口比实际少——**真正的空白是没人点过**， hence §9B。**教训＝盘点表按「代码有没有」打勾，会系统性掩盖「链路跑没跑过」；缺口表必须显式分「未实现 / 实现未测 / 测过但判据不全」三档。**
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
- **仍未实锤、不许当结论用**的四条：G-4 里 `[{barCode,textName,bgType}]` 的归属、G-5 的「**§4.3 那 12 条由谁发**」（出图=柜机摄像头已由 §4.3.4 标题证死，但**驱动者是柜机 APK 自走、还是等一个商户端客户端**未证——本柜在线后看 nginx/网关有没有这批路径即可判）、H-10 的 76/88↔rknn 目标对应、`floor/getFloorByDevice` 真机是否会请求（~~真机 0 命中只说明没跑过补货开门~~ **10-22 二次已判**：本表 0 命中与「对接版本非 NORMAL」一致，但**不能**定档到 YP ⇒ 见 H-19（`M_NAME ∉ {NORMAL, NO_NET}`，XY/YP 未分））。
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
  **15:5x 订正**：该修复的「未提交」半句已作废——源码已随 `b734741d` 落 dev 分支工作树；「容器内是否已含」也已实测闭合：**16:1x 用 `docker cp /app/app.jar` + 宿主 Node 解 zip/`inflateRaw` 直接读 class 常量池**，`DeviceReportController.class` 四条字面（裸/`/device` × `orderProduct/addRecognitionGoodsToOrder`、`device/pokerOrder/uploadOrderError`+`device/order/uploadErrorOrder`）全 HIT，`DeviceExtensionController.class` 两条 `floor/getFloorByDevice` 拼写全 HIT ⇒ 现役容器（镜像建于 **14:25:11**、14:25:34 起，即 #99 那次重建）**已含双前缀**，**上面「容器=10-21 版本」的判定作废**，柜测前无需再重建 gateway。判据姿势留档：容器镜像无 unzip/python，`grep` 原始 jar 恒 MISS（deflate 压缩），必须解包后在 class 字节里查。
  ④ 本表 §3 顶部「DeviceUnknownPathController 注释仍列 domainConfig 不接管」的 ⚠️ 本身已过时（注释 10-20 起已改「已从本清单移出」，工作区实证）；但该注释同段「一切资金前缀（orderProduct/**、device/pokerOrder/**）刻意失败」与 10-22 双前缀显式注册形成新的表述矛盾（显式映射优先、行为正确，注释待同步）。
  ⑤ 工作区实跑：`mvn -o -B -pl services/jiangyi-gateway verify` **137 单测 + 22 IT 全绿 BUILD SUCCESS**（DeviceCompatRoutingIT 9 条含 10-22 新增 2 条双前缀用例）。

## 12. 相关文档

- `docs/JIANGYI_GATEWAY_DESIGN.md` §2 端点契约 / §5 已知边界与待办（本表是它的**逐条展开**，冲突处以本表的行号证据为准，并回去订正对方）
- `docs/archive/audit/AUDIT_TRACK_E_JIANGYI_INTERFACE_GAP_2026-10-13.md`（E-2 选型结论仍有效；R2 与 confirmActivate 已实现）
- `将邑对接资料/将邑socketUrl缓存卡点_分析与人厂沟通清单_2026-10-19.md`（域名/WS 目标切换机制，最新修订 10）
- `docs/JIANGYI_LOCAL_TUNNEL_FALLBACK.md`（断点调试备用入口）

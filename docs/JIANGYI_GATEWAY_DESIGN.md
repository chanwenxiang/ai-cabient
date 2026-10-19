# 将邑开门柜接入网关（jiangyi-gateway）设计 — CB-022

> 2026-10-09 实施。权威方案：WorkBuddy 计划 `electric-forging-tesla-n6eohtlS.md`（已批准）。
> 本文是落码后的**现状速查**，与方案冲突时以方案 §10 待确认项为准。

## 0. 核心判断（复述）

- **枢纽**：将邑 `orderNo` = 我方 `sessionId`（V16 §4.3.2.7「商户服务器生成、需唯一」）——免映射。
- **金额不采信设备侧**：识别上报只带 classId+quantity（无金额字段）⇒ 结算金额 = Σ(映射 SKU 现价 × qty)（分）；映射未命中 fail-closed 转 DISPUTED。
- **模式一无关门上报**：识别上报到达时 gateway 先合成 door-event CLOSED（SHOPPING→RECOGNIZING）再转发 edge-results；`uploadDoorState`（拉门成功）→ door-event OPEN（OPENING→SHOPPING）。
- **零侵入**：device-info / trade 业务服务 / common-core / 前端不动；唯一现有 Java 改动 = `DeviceServiceClient`（路由分流）。
- **名词：本文「云侧／云端」专指将邑智能的 SaaS 后端** `https://lingshouapi.hunanjysmart.com/device`（厂商云，实测证据：设备面 43 条路径全打它、JWT 由它签发 `{"exp":2188291197,"deviceId":10720}` HS512、桶 `jiangyi-logs.oss-cn-shanghai.aliyuncs.com`、`domainConfig/*` 由它下发域名、OTA/特征包/模型/调光表/商户后台都在它那边）。我方 `services/jiangyi-gateway` 是它在**设备固件眼里**的顶替者（只顶 device-facing API，不搬它的数据）；我方 `services/*`（trade/edge/admin）是业务后端，本文不称「云端」。

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
- **设备 token 一槽通用（2026-10-19 log3 实锤，撤掉「双 token 墙」旧判断）**：真机开机序列是 `token/openDoorDeviceStatus`（log3 行 90）→ 响应 `data.token`（行 95，云侧那枚 HS512）→ **同一个串**既当后续所有 HTTP 的 `Authorization: Bearer`，又当 WS URL 的 `?token=`（行 235 `初始启动socket地址 …?token=eyJhbGciOiJIUzUxMiJ9…`，与行 95 逐字相同）。固件另有 `Constans.TOKEN` 槽，全 log 27 次恒为空串——不存在「设备拿云侧长期凭据连商户 WS」这回事。⇒ **域名切到我方后，设备领的是我方 HS256/HS384 token，握手与 `DeviceAuthInterceptor` 原样可用**，无需云侧密钥/公钥，也无需 `advertising` 双 token 兼容（该两项待办作废）。仍待厂商确认的只剩云侧长期 token 的**过期行为**（我方 exp 7d，设备每日定时重启重领，正常）。
- **激活制**（审计 A1/P0-3，2026-10-15 拍板）：SN 为出厂可枚举标识，仅凭「已登记+BOUND」无法防仿冒——`jiangyi_device.auth_enabled`（V366，默认 FALSE）经运营台 `POST /api/v2/ops/admin/devices/{id}/jiangyi/activate|deactivate` 逐台激活后方可领 token；**deactivate 同步 bump token_version**（存量 token 即刻失效）；未激活请求 403 + HIGH 告警（10 分钟/SN 限频）；同 SN 10 分钟 ≥3 次签发 → JIANGYI_TOKEN_ISSUE_BURST 告警（不拒发，防误伤营业）。

## 4. 部署要点

- compose：`docker compose -f infra/docker-compose.full.yml build jiangyi-gateway && docker compose -f infra/docker-compose.full.yml up -d --no-deps --force-recreate jiangyi-gateway`（拆两步，铁律 #41）。
- 内网穿透（联调）：frp/cpolar → 本机 nginx 80/443 → gateway `127.0.0.1:18084`；setDomain 的 domain=`https://<穿透域名>/jiangyi/api`、socketUrl=`wss://<穿透域名>/websocket/device/`（`/device/` 尾段+尾斜杠必需——handler 注册在 `ws/WebSocketConfig` 的 `/websocket/device/{identifier}`，固件按 `socketUrl+identifier+?token=` 直拼）。
- 验证顺序（联调日 1）：入驻 register→bindDomain（setDomain）→ 设备通电 → token 签发 → WS 在线（`/internal/v1/jiangyi/devices/{id}/routable`）→ 扫码开门 → 模拟器/真机上报 → 结算 COMPLETED。

## 5. 已知边界与待办

- 一期只开放购物开门；强制开门空 msgContent 结构性拒绝。~~operatorMode 501~~ →
  **2026-10-11 收尾E：补货开门真路由上线**——trade 补货开门走
  `/internal/v1/jiangyi/devices/{id}/open-door`（stock=true）→ WS
  `sendOpenStockDoor`（msgContent=补货会话 id ≡ 补货单号）；柜机回执
  uploadLockState/uploadDoorState 与补货视频 §4.2.14（orderNo=补货单号）落
  `jiangyi_order_video` 台账；关门回执缺失由 300s CLOSE watchdog → trade
  recognizeTimeout（补货会话→COMPLETED 快照关闭）+ 补货任务完成清场双兜底。
  运维开门（ADM 会话）仍 501（协议无此概念）。
  未接 UI：补货任务详情的「补货视频查看」入口——补货视频已按 §4.2.14 落
  `jiangyi_order_video`（by-order=补货会话 id 可查），入口需要时另开。
- **`getDetail` 拓扑值不必等厂商**（2026-10-19 log2/log3 实锤，含 10-19 18:39 最新一次开机）：云侧真实响应 `data` = `{id:10720, identifier, huodaoNum:null, isBind:"yes", recognitionType:"end", doorNum:1, industrialControlType:null, locks:[1], usbNumLu:1, usbNumLb:3, usbNumRu:0, usbNumRb:0, modelNum:2, mqttClientId/TopicPrefix/lastOnlineTime/mqttStatus/lastHeartbeatTime/mqttQos:null}`。两条硬结论：
  ① **云侧的「空」分两种**——不存在的硬件通道回 **0**（`usbNumRu/Rb`），未维护的元数据才回 **null**（`huodaoNum`/`industrialControlType`/`mqtt*`）。⇒ null 容忍度对 `huodaoNum` 这类**有实证**（同一份日志 68+32 次开机照常跑），但对 `doorNum/locks/usbNum*` **云侧从不回 null**，我方桩回 null 属**偏离云侧**，不是中性。
  ② 拓扑是**将邑后台可改的每机配置**，本台 SN 在同一天日志里出现过 3 种形态：`doorNum:1,locks:[1],Ru:0,Rb:0`（68 次）、`doorNum:1,locks:[1],Ru:5,Rb:6`（32 次）、`doorNum:2,locks:[1,2],Ru:5,Rb:6`（4 次，09-28，同期 logcat 出现 `打开摄像头2`）。⇒ 我方回什么值 = 声称这台柜子的物理拓扑是什么，**必须逐台建档**，`jiangyi_device` 现有列里没有拓扑（V332/V336/V366/V369 全无 `door/lock/usb/model_num`），要落库+运营可编辑才谈得上正确回答。
  厂商问题 8 由此从「usbNum 语义未知」降级为两条确认：0/null 约定是否如上、拓扑由谁在何时维护（换摄像头/换锁后我方怎么知道）。
  **已修（2026-10-19 晚，`DeviceInfoController.getDetail`）**：拓扑字段不再回 null，改按上表实测元组回数字（`doorNum=1`/`locks=[1]`/`Lu=1`/`Lb=3`/`Ru=0`/`Rb=0`/`modelNum=2`），null 只留给云侧自己也回 null 的 `huodaoNum`/`industrialControlType`/`mqtt*`；单测 7 条锁死该语义（`DeviceInfoControllerTest#getDetailReturnsMeasuredHardwareTopologyNotNulls`）。同批遗留一处未证字段：`id` 云侧回数字 10720、我方回 deviceId 字符串，类型差异固件容忍度未实证，真机连入后先看是否解析异常。
- specialId 不采信（V16 §4.2.10 唯一出口、来源未定义）。
- **§5.2 独有的 3 条入向端点不接（有拍板，但出处已被删）**：V15 全文 OCR（107 页）与 V16 逐条对账，云/设备→商户服务器的入向面在第五章 §5.2 比第四章 §4.2 多三条——
  `pokerOrderProduct/addProductToOrder`（§5.2.11 二次甄别扣款通知，「商户端收到后需要进行扣款处理」）、
  `thirdRecheckOrder/recheckOrder`（§5.2.12 重审退款/补扣，`orderNo`+`productForms`+`amount`(分)）、
  `pokerOrder/sendTimeOutError`（§5.2.14 订单超时，文档明文**「不用 token 验证」**）。
  三条均系 2026-10-09 模式一选型时**明确裁掉**（原文「二次甄别/重审/超时异常上报均不接」+「第四章没有订单超时异常接口，模式一下超时兜底靠我方自身逻辑，需在 trade 侧设计」），出处 `docs/JIANGYI_SAAS_INTEGRATION_V16.md` §7 —— **该文件已在 `177ec8c4`「文档治理」删除**，`AUDIT_TRACK_E…:3-4` 仍把它当活文档引用；原文现只可读 `git show 84c2bee8:docs/JIANGYI_SAAS_INTEGRATION_V16.md`。
  落地核对：超时兜底已实现（OPEN 15s / CLOSE 300s / recognize-timeout 10min → DISPUTED），争议单在我方结算页可人工确认收款（`SettlementConfirmDisputeService`）⇒ 拍板自洽，**不是漏实现**。
  **维持不接**，且理由比选型记录写得更硬：模式一下设备识别/异常上报直发我方 domain，**订单数据根本不落将邑云**，云侧「二次甄别订单列表」无数据来源 ⇒ 不会向商户推这 3 条。（除非日后改用将邑后台结算——那属改选型，须重开此项。）
  两点留档：① 这三条**不是 V15 遗留**——V16 里各存 1 处（`doc_full_text.txt` 行 1318/1361/1446），V15↔V16 入向面逐条完全一致；裁它们的依据是「第四章 vs 第五章」的模式选择，不是版本更替；② 若日后接 §5.2.14，它是**免鉴权的入向写路径**，须 `GatewayWebConfig` 显式豁免 + 只允许「置失败」方向 + 按 orderNo 幂等，禁止任何放款/发货语义。
- **真机固件调用面 ⊃ 文档第四章（这才是「两套接口」的真实形态）**：log2/log3 实证设备实际会请求 43 条 `…/device/**` 路径，文档第四章只覆盖其中 17 条，我方另补 7 条真机路径（`isAutoUploadVideo`/`receiveCurrentTxtUrl`/`operationMode/sync`/`floor/getDeviceSellProductClassIds`/`uploadDeviceOpenStatus`/`order/uploadOrderLockStatus`/`aliYunOss/getTempToken`），**剩余 32 条我方无端点**。其中确定与我方业务相关的样本：`tenantOrder/needCheck`（29 次，`{}`→`data:false`，租户级「订单是否需人工复核」开关）、`tenant/getDeviceBasicConfig`（29 次，上报 `doorNum/huodaoNum/modelNum` 换租户配置）、`feature/getRecognitionPolicy`（`{"effectiveMode":"DUAL","diagnostic":"requested=DUAL effective=DUAL reason=customer_release_dual"}`——**双摄识别策略由云侧 feature flag 控制**）、`order/pValueByTransactionId` + `deviceInfo/getPValue`（摄像头调光「光值」遥测，非识别结果，勿误当结算入口）、`order/uploadAddGoodsCloseStatus`、`reportCameraRealInfo`、`selfCheckReport`、`dictionary/getPicSwitch`。
- **域名机制实测订正（2026-10-19 晚，log2/log3 `OffNetDomainTool` + `domainConfig/*` + WS 地址三向对齐）**：上面那句「按租户 domain 拼接 vs 硬编码」的疑问，log3 已能答一半，且答案改变了整个卡点：
  - 固件运行期 HTTP base 存在 `COMMON_URL` 槽，实测恒为 `https://lingshouapi.hunanjysmart.com/device`——**与 `domainConfig/getConfigDomainByIdentifier` 的响应值逐字相同**（`{"status":null,"msg":null,"data":"https://lingshouapi.hunanjysmart.com/device"}`）。⇒ 域名是**设备主动轮询得来的**，不是只能靠后台推送；厂商在他们后台把这台机器的 domain 配成我方值，设备下一次轮询即拿到。
    - 🔴 **设备流量确实到过我方（2026-10-19 16:18 起），但只到「启动序列」那几条**：`.workbuddy/memory/2026-10-19.md` 行 94（16:18:00 签发 token、16:18:01 `getDetail`×2 全 200，nginx UA=`okhttp/4.9.3` 确认为真机流量）、行 124（我方网关 logged `floor/getDeviceSellProductClassIds` 404、`receiveCurrentTxtUrl` 404、`advertisingDevice/*` bad_token）、行 185（`token→getDetail→getArtificialCheck×2→receiveCurrentTxtUrl→classIds` 每 ~2 分钟整循环）。⇒ 上面「我方无端点」的风险**已在现网部分兑现**（404 台账是正在跑的数据，不是理论清单）。此前「我方从未被真机打过一次」的说法作废（错因：只翻了 `.tmp` 厂商侧 logcat，未先读每会话必读的活文档）。
    - ⚠️ **但「切了域名就全量过来」不成立**：同一时段（17:47→18:39）logcat 的 `OffNetDomainTool` 行仍逐字打印 `COMMON_URL=https://lingshouapi…/device`，且我方域名在三份 logcat 里 **0 命中** ⇒ 我方目前只接住启动序列，其余路径的流量仍在将邑云侧。两种解释（固件有≥2 个地址槽 / 该日志打的是内置默认常量而非运行期值）都**未被排除**，取证以我方 access log 为准。详见 `将邑对接资料/将邑socketUrl缓存卡点_分析与人厂沟通清单_2026-10-19.md` 修订 5 第二节。
    - 但 **WS 至今没过来**：行 186 nginx 全史只有一条 websocket 记录且 UA=curl（我方自己的隧道探针），真机 12 个请求全 POST、零 GET ⇒ `socketUrl` 槽恒空、回落固件内置 `wss://lingshouapi…`（详见 `将邑对接资料/将邑socketUrl缓存卡点_分析与人厂沟通清单_2026-10-19.md` 修订 3 E1-E4）。**HTTP 与 WS 的域名是两条独立通路，切了 HTTP ≠ 切了 WS**：WS 地址只能由云侧下发载荷带过来，而我方网关收到的 WS 握手次数 = 0。
    - 🔴 **第二堵墙（比"地址没过来"更硬，需第三个拍板）**：即便 WS 地址下发修好，设备 WS URL 的 `?token=` 带的是**将邑云 HS512 设备 token**（payload `{"exp":2188291197,"deviceId":10720}` = **2039-05-06 到期，我方无法吊销**），而我方 `DeviceHandshakeInterceptor` 只验自家 HS256/HS384 ⇒ **握手必 401**。三个候选：(a) 请厂商把商户 token 灌进 WS URL；(b) 我方对白名单做「结构解析 + trade 登记表交叉核对（BOUND + authEnabled）」的宽松校验（= 容忍一把不可吊销的长期凭据进设备面）；(c) 现场 `pm clear`/解绑重激活。**待用户拍板，不可自选。**
  - `OffNetDomainTool` 打的 `offnetDomain=, offnetSocketUrl=, 已应用=false` 是**无网络兜底缓存**（两条槽全 log 恒空），不是主域名槽——此前把 `offnetSocketUrl` 当 socketUrl 下发位的推断作废。
  - WS 地址实测 `wss://lingshouapi.hunanjysmart.com/websocket/device/CQYB10720?token=…`：HTTP base 尾段是 `/device`，WS 路径却是固定的 `/websocket/device/{identifier}` ⇒ **WS 只取 domain 的 host（scheme 换 wss），path 不参与拼接**（推断，待厂商一句确认，汇报文件问题 9）。
  - `sendDeviceDomain` 私有消息确实在 WS 上（log2/log3 各 1 次实测 msgContent=`https://lingshouapi.hunanjysmart.com/device`，设备回 `sendDeviceDomainSure` 后 **`SocketMsgDeal.sendDeviceDomain` 直接触发整机重启**，10-19 16:02:01 又一例）。⇒ 重推值必须填**含尾段的完整 base**（我方 = `https://<网关域名>/jiangyi/api`），填裸域名会少前缀 404；且无需人工干预——推送自带重启。
  - 🔴 **切域名的代价（原「只读桩即可」的判断太轻）**：43 条路径全部跟着 `COMMON_URL` 走我方，包括 `deviceApp/*`（App/系统 OTA）、`feature/*`（特征包与双摄识别策略下发）、`token/getAliyunAkToken`（将邑自有日志桶凭证）、`product/tenantPidAndClassId`、`replenishmentHistory*`、`deviceInfo/getBaiduAudioCode`（百度离线 TTS 激活）。切过来即**断厂商侧这些能力**——我方要么代理转发、要么永久桩掉。这是拍板项，不是实现细节。
    - **拍板 B 判定（2026-10-19 晚）：现在不反代，维持 404 + 台账**。硬障碍是我方自己复核出来的、对方清单没提的一条：**反代不是配置活**。设备切到我方域名后带的是**我方签发的 token**（`DeviceTokenService.java:20/49` 自签密钥，HS256/HS384），将邑云只认自家 **HS512**（v1.13.0 §3.4.5 示例头 `eyJhbGciOiJIUzUxMiJ9`）——`Authorization` 原样转发**必 401**。要反代得先有「商户凭证换票」或厂商 M2M 通道，属改造不属转发。
    - 反代的**收益也低于设想**（回源实测）：`deviceApp/updateAppVersion` 云侧响应实测 **4 次可归属采样全为 `{"status":200,"msg":"success","data":10720}`**（log3 行 265/266/615/619，同一行带 url 尾可绑定）——**只回一个设备 id，没有包地址**，OTA 实际走 WS 私有指令 `softwareUpdate`（log3 计数 5 次），反代这个 HTTP 端点拿不到升级包；`token/getAliyunAkToken` **可完整归属 URL 的采样只有 2 次**（log3 行 237/587），两次同值 `c95bc4e765c842e09c278b5f561968c1` ⇒ 只能说「采样一致」，**不能断言它是长期常量**（全 log 另有 3 个 32-hex `data` 因 logcat 交错无法绑定 URL）。用途是给将邑自有日志桶的凭证，与我方无关。⇒ 这两条桩掉的代价：这批柜子停在 V9.10.806、少一路厂商侧日志；**不是**「零风险无代价」，是真机 day-1 若厂商侧功能缺失要回头补的项。
    - **能力分档（进厂商清单，按优先级）**：① `feature/*`（`getFeatureByDevice` 特征包轮询 + `getRecognitionPolicy` 双摄识别策略，固件 `FeaturePackPuller` 5min 一次）——**最重**，404 后固件回落行为未知，且云侧实测诊断串 `reason=customer_release_dual` 说明策略是**厂商按客户放行**的 flag ⇒ 厂商第一优先；② `deviceApp/*`（OTA）中期再定；③ `token/getAliyunAkToken`、`replenishmentHistory*` 桩掉**可接受**（少一路厂商侧日志/补货历史），但按上一条口径**不写成「零风险」**。
    - 新增厂商问题 13、14：见 `将邑对接资料/将邑socketUrl缓存卡点_分析与人厂沟通清单_2026-10-19.md` 修订 5。
- **32 条缺口的落地处置（2026-10-19 晚，`DeviceCompatController` + `DeviceUnknownPathController`）**：逐条从 log3 抠出云侧真实请求/响应（能完整解析出 url 尾的样本数写在代码注释里），分三类：
  - **照抄实测值（16 条，`DeviceCompatController`）**：`tenantOrder/needCheck`→`false`(×2)、`product/tenantPidAndClassId`→`{}`(×2)、`deviceInfo/getOtgStatus`→`"open"`(×13)、`dictionary/getPicSwitch`→`"on"`(×2)、`tenant/getDeviceBasicConfig`→15 字段整组(×27，取 10-19 18:33 起的众数组)、`getTenantPvalueConfig`/`Close`→`null`（云侧自己对同一台设备回过 8/6 次 null ⇒ null 有实证容忍度，且调光表属厂商租户级标定数据，不复制进我方库）、`getBaiduAudioCode`→`status:666` 原文案、`getPValue`/`pValueByTransactionId`/`uploadAddGoodsCloseStatus`/`selfCheckReport`/`reportDeviceStatus`/`reportCameraRealInfo`/`updateTrouble`/`uploadDeviceDataCardNo`→`data:null` 只留原始日志。
    - ⚠️ **证据强度自审（2026-10-19 晚，写进代码注释同一口径）**：`tenantPidAndClassId` 只有 **2 次**能完整绑定 URL 且响应为 `{}`；logcat 里另有 11 次「获取商户特殊商品ID」标签下的 `{"0":"",…}` 形态响应，因日志交错**无法证明属于该端点** ⇒ 我方仍取 `{}` 作为**保守答复**（宁可让设备读到空映射，不伪造一份商户商品 ID 表），不是「实测众数」。**取证铁律（本轮两次踩错后定死）**：logcat 的 tag 与 URL 分属两行、进程交错，用「最后一次 url 归属前一 tag」这类配对法会产出明显荒谬的结果；**只有同一行内出现 `请求数据 … url<尾>` 的响应才算实锤**，其余形态一律降级为「未归属」。
  - **刻意失败（未实测的一律 404）**：`deviceApp/*`(OTA 3)、`feature/*`(3)、`token/getAliyunAkToken`、`deviceInfo/getFaceModelConfig`、`getDeivceFrameRate`、`replenishmentHistory*`(2) + 一切资金前缀。**不回 200 空值**：缺口多为读取类，`status:200,data:null` 等于告诉设备「配置就是空」，会被掩盖成一次成功；404 走设备失败分支 + 我方 `JIANGYI_PATH_UNIMPLEMENTED` WARN 台账，真机第一天就靠这份清单定优先级。此前「非资金一律 200 空态」的口径作废。
    - 订正（2026-10-19 晚）：`domainConfig/*`(2) **已从这份 404 清单移出**，改为我方自钉（见下条）。上面「剩余 32 条我方无端点」的口径同时作废：拍板 A 后为 **16 条照抄 + 2 条自钉 = 18 条已实装，其余走 404 台账**。
  - **等厂商/等真机**：调光表由谁维护（问题 11）、`getDeviceBasicConfig` 15 字段语义（问题 10）、`addGoodsId` 我方是否消费（问题 12）。
- **域名自钉已实装（拍板 A，2026-10-19 晚，`DomainConfigController` + `JIANGYI_PUBLIC_BASE_URL`）**：设备既然会主动轮询 `domainConfig/getConfigDomainByIdentifier` 取 HTTP base，我方就把这两条**接成"把自己钉回去"**，而不是继续 404 让固件走未实测的回落分支：
  - `getConfigDomainByIdentifier` → **照抄云侧的怪形态** `{"status":null,"msg":null,"data":"<base>"}`（log3 实测响应体就是 status/msg 双 null，**不是** 200 信封；固件按 `data` 取值，套 200 反而偏离实测）。`data` = `JIANGYI_PUBLIC_BASE_URL` 归一化后 + `/jiangyi/api`（已含尾段则幂等不重复追加）。
  - `getConfigStatusByIdentifier` → **恒回 `data:1`**。云侧对同一台机器在开机序列里回过 1 也回过 2（10-19 12:16:20.962 那次为 1），**不是严格状态位**；恒 1 的语义是「配置待拉，来拉吧」，最坏代价是每次开机多一次幂等轮询，换来掉域名后的自愈。若回 2 = 「已是最新」，等于诱导固件跳过拉取，反而把我方钉死在它缓存的旧值上。
  - **失败安全**：env 未配 / 空白 / 含空格 / 非 http(s) ⇒ 两个端点**一起** 404（`JIANGYI_DOMAIN_PIN_DISABLED` WARN），不猜值。两端口共用一个开关是刻意的——绝不能出现「状态说配置待拉，拉取却 404」的自相矛盾。配错域名比不配更危险（会把整机 fleet 钉到不可达地址），所以宁可 404 走厂商原路径。
  - 🔴 **开机顺序约束**：设备拉 `domainConfig/*` 是在**已持有合法 token 之后**（实测顺序 token → status → domain，同 base 同 token）。⇒ 运维顺序**登记 → 激活 → setDomain** 不可颠倒；先把域名钉到我方、设备却还没有我方 token，会从厂商云切到我方再吃 401，比不切更难查。
  - 现网影响：`JIANGYI_PUBLIC_BASE_URL` 已在 `infra/docker-compose.{apps:92/217, full:165/328, production:102}` 三处网关 env 块透传；**换域名 = 改这一行**，无需代码。生产入口已正式化：`cabinet.easygovm.com`（A→120.25.246.179 + certbot 证书，`.workbuddy/memory/2026-10-19.md` 行 190-196），服务器 `infra/.env.production` 的四行域名换值里**已含该键**（仓库只有 `.example`，本机 `infra/.env` 仍是穿透域名——那是 dev 文件，prod 由 `scripts/deploy-prod.sh:111` 的 `--env-file infra/.env.production` 加载）。🔴 自钉生效还有一条硬前提：`/jiangyi/` 与 `/websocket/` 两条 location 必须在**入口那层 nginx** 通到网关——仓库侧三份 `infra/gateway/nginx*.conf` 均已配，宿主机 vhost 是仓库外文件，须 `nginx -T` 核一次。冒烟口径见 `docs/DEPLOYMENT_CHECKLIST.md` §2.1 步 4。
  - **入口路由已于 2026-10-19 晚从公网证实**（`node scripts/jiangyi-day1-check.mjs`，只读探测）：`https://cabinet.easygovm.com` 上 `POST /jiangyi/api/domainConfig/*`、`POST /jiangyi/api/feature/*`（catch-all）、`GET /websocket/device/CQYB10720`（带 Upgrade 头）三条**全 401** ⇒ 都穿到了网关鉴权层，宿主机 vhost 两条 location 通。**但 401 不等于自钉已上线**：代码目前仍是未提交的工作区文件，prod 跑的是旧 jar，真机若此刻来拉 `domainConfig` 只会吃 catch-all 的 404（不钉、无害、也无效）⇒ **day-1 第一件事是部署含 `DomainConfigController` 的新 jar**——而 `scripts/deploy-prod.sh:43-49` 的干净性判定用 `grep -v '^??'` **不拦未跟踪文件**，`git archive HEAD` 又只含已提交内容 ⇒ 新 Java 文件忘 `git add` 会「部署成功但跑的是没有自钉的旧类」，现网表现与"配置没生效"几乎同形（细则见 `docs/DEPLOYMENT_CHECKLIST.md` §2.1 步 4）。
  - 临时把真机引到开发机的备用入口（反向隧道，仅断点调试期用）：`docs/JIANGYI_LOCAL_TUNNEL_FALLBACK.md`。
- **设备面错误出口 HTTP 码回归（2026-10-19 晚修）**：`dad701b4` 引入的 `JiangyiApiExceptionHandler` 把设备面异常渲染成 **HTTP 恒 200 + 体内 status**，实测吞掉 `DeviceAuthIT` 8/13 断言（401/403/404 全变 200）。之所以入库时无声：该类命名 `*IT` ⇒ surefire 默认不跑，而 `pre-push-ci-preflight` 第 101 行明写「不跑 `mvn verify`」，只有 CI `integration` job（`mvn verify -pl …,services/jiangyi-gateway -am`）会抓——当时 dev 领先 origin 4 个提交未推，CI 尚未见过它。现改为 **HTTP 码与体内 status 同值**（文档字段表只规定 `status` 是 int、200 成功、其他皆失败，未规定 HTTP 码）：设备按体内码判成败，nginx/监控/我方 IT 按 HTTP 码判，两套口径互不掩盖。修后 `mvn verify -pl services/jiangyi-gateway` = surefire 91/91 + failsafe 18/18 绿。**现状（2026-10-19 晚，拍板 A 后）**：`mvn -o -B verify -pl services/jiangyi-gateway` = surefire **97/97** + failsafe **19/19**，BUILD SUCCESS（新增 `DomainConfigControllerTest` 6 例、`DeviceCompatRoutingIT.domainConfigSelfPinsOurOwnBaseInsteadOfLettingFirmwareGuess` 1 例）。
- **与云侧不一致的两处，已逐条拍板（2026-10-19 用户决定）**：
  - `deviceInfo/isAutoUploadVideo`：**保持我方 `false`**。云侧对同一台 CQYB10720 恒回 `data:true`（log3 行 300/312/4545/10674…实测×15 无一次 false ⇒ 厂商环境是**每单全量上传视频**）。不对齐的理由是成本：true 会让每单一段视频经 `/aliYunOss/getTempToken` 的 STS 凭证直传我方桶，物联卡上行流量 + OSS 存储随单量线性增长。代价是复现厂商行为时少一路视频证据。日后要放开按**设备/租户粒度配置项**做，不开全局开关。
  - `deviceInfo/receiveCurrentTxtUrl`：**照抄云侧 `{"status":666,"msg":"当前模型text文件地址不存在","data":null}`**（log3 行 291/640 两次全为该值）。此前我方回 `data:"0"` 属"成功"语义，固件会以为有 txt 地址可取；我方 classes txt 随 `/modelFile/notify` 的 modelUrl 下发、走 `downloadModelNotify` 通道，不经此回执，所以 666 才是与实现自洽的答复。协议允许 `data:null`，`Map.of` 会 NPE ⇒ 该文件另立 `envelope(status,msg,data)` 构造，测试已锁。
- 二期剩余：模型同步预生成映射（已上线，保留条目供核对）；补货开门真路由已上线（见上收尾E）。
- 待办：真机联调回填识别率。（~~`pnpm gen:api-types` 重跑~~ → **已完成** 2026-10-12 `f5c94d8e`，含收尾E by-task 新端点类型入库）（~~商户端月度结算单 UI~~ → **早已上线**：后端 V330 `a82fb0f9`（`GET /merchant/settlements/monthly-bills` + `MerchantSettlementBill` 调度/重建），端侧 `27dd0899`——`settlements.vue` 月度结算单 section + 「结算对账」导航入口，2026-10-12 代码核实）

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

- **（审计 A1 顺带）控件固件是否支持配置设备级 secret**：现激活制=SN 仿冒的运营面防线；若将邑 V16 协议外固件可注入 per-device secret，token 端点升级「SN+secret 双因素」即真一机一密（二期，依赖厂商确认）。

- classId 行号方向（0/1-based）→ **已实锤 0 起**（V16 第 98 页 §5.5.3 响应示例首商品 classId=0；1 起体系不会出现 0）。classIdBase 默认 0 与代码一致（AdminJiangyiController defaultValue="0"），预览人工确认兜底保留。
- "76"/"88" 与主板对应关系 → **用户拍板默认登记 "88"**（2026-10-10：采集文档第 44 页 §4.4.4.5 示例 rk3588/rk3576 两型号均为 "88"，是当前证据下最合理取值）。校验机制不变：设备登记值与模型值字符串相等，不解释语义；若真机首推校验失败，改登记值即可，无需改代码。
- ~~downloadModelNotify msgContent 真实结构~~ → **已由 PDF 原件核实**（§4.2.5：HTTP POST identifier+modelName），WS 分支仅兜底。
- 采集期 WS 共存行为 → **降级（2026-10-11）**：采集/学习为运营**现场**操作（人站柜机前），与顾客扫码撞车概率极低 ⇒ 不专门安排，**真机进场当天顺手测一次**即可（关注点：采集开门时营业 WS 会话是否被将邑侧复用）。
- finishNotifyUrl 公网可达性 → **已完成**（2026-10-10 阶段 0 穿透联调，提交 `fcac1942`）：花生壳内网穿透 `130954bm9ka83.vicp.fun` → `192.168.0.26:80`（gateway）；经穿透 `POST /jiangyi/api/gather-finish-notify` 返回 **200**（body `{"msg":"accepted","status":202}` 静默语义），WS 升级亦到达应用层。`JIANGYI_PUBLIC_BASE_URL` 已入 `infra/.env`（gitignore）并经 `docker-compose.full.yml` 透传给 trade（重建后 printenv 确认）；`jiangyi-gateway` 重建后公开面白名单生效（此前旧镜像对回调 401）。⚠️ 依赖穿透客户端在线（花生壳 502 = 客户端离线）；正式上线建议换固定公网域名/专线。
- ~~§4.4.3 学习是否真的集合粒度（无 productId）~~ → **已实锤：集合粒度**（2026-10-11 依据采集文档 p42-43 接口签名：`commitTrainingTenantOne` 请求体仅 modelName+finishNotifyUrl+finishNotifyId，**无任何商品参数**；产物 §4.4.4 = modelUrl + modelTextUrl 模型商品映射文件 + quantity 模型商品数量 ⇒ 触发学习即「整柜样本训练一个模型」，不存在单品下发选项）。
- ~~设备端视频保留策略/容量上限~~ → **已实锤：视频保留 1 个月 / 设备本地存储 64GB**（2026-10-12 用户转述将邑确认）。三点结论：①64GB 对「默认只传异常单」（§4.2.13）绰绰有余，本地缓冲不构成瓶颈；②**保留期 1 个月 ⇒ 超过 1 个月的正常单视频柜机已清理且从未上 OSS，不可回拉**——若运营抽检窗口超 1 个月且需覆盖正常单，须改 §4.2.13 全量上传（当前未触发，维持默认）；③OSS 生命周期天数 N 与设备保留期**解耦**（OSS 只存已上传的异常单副本），已在 `docs/DEPLOYMENT_CHECKLIST.md §3.1.1` 定稿 90 天。「拉取指令能否指定超过保留期的单」仍未确认（预计不可：超期单无副本可拉）。**OSS 侧清理方案已落** `docs/DEPLOYMENT_CHECKLIST.md §3.1.1`（`jiangyi-video/` 前缀**桶级生命周期规则**，由 OSS 服务执行、**不依赖**角色 `DeleteObject`）。角色 `jiangyivideoupload` 已于 2026-10-11 补 `oss:DeleteObject`，但那只服务「运营手动删单条」的**服务端**调用，**当前未开放**（无业务需求，且删对象会让 `jiangyi_order_video` 台账变死链）；保留期清理**一律走生命周期规则**，见铁律 #68。
- ~~视频链路是否纳入范围~~ → **CB-024 已实现**（2026-10-10）：§4.2.6 getTempUploadToken = gateway OSS STS AssumeRole（aliyun-java-sdk-core CommonRequest，inline Policy 收窄到 PutObject 桶/dirName* 前缀，15 分钟会话）+ §4.2.14 uploadVideoUrl = gateway 转发 trade jiangyi_order_video 落库（V338，uk(order_no,serial_num) 幂等）。bucket=ai-cabinet-by（oss-cn-shenzhen，用户已建）。**部署配置已完成（2026-10-10 实测 ALL GREEN）**：RAM 子账号（只授 AliyunSTSAssumeRoleAccess）+ 直传角色 jiangyivideoupload（信任=本账号 root sts:AssumeRole，权限策略 jiangyi-video-put=oss:PutObject 仅限 ai-cabinet-by/jiangyi-video/*）；JiangyiVideoUpload ARN 已填 infra/.env JIANGYI_OSS_*，gateway 容器重建后 env 注入确认。实测：AssumeRole 真实签发 ✓ / jiangyi-video/ 内 PutObject 200 ✓ / 目录外 PutObject 403（收窄生效）✓。MinIO 转存归档属二期可选。
- ~~视频 object key 规范~~ → **已定稿（2026-10-14）：`jiangyi-video/{deviceId}/{yyyyMMdd}/{orderNo}_{serial}.mp4`**。①按柜分目录：生命周期规则、按天排障、台账对账都以前缀定位；②订单号唯一但**一个订单可多分片**（uk(order_no,serial_num)、videoQuantity/serialNum 上下文），序号必带；③**设备端要求**：上传必须落在凭证下发 dirName（`jiangyi-video/{deviceId}/`）之下——STS inline Policy 已收窄到设备级子目录（`uploadPolicyJson(bucket, dirName+deviceId+"/")`），越权 key 云端直接 403，规范由机制强制而非约定；④deviceId 非纯数字（6~20 位）fail-closed 不发凭证；⑤读路径（运营侧预签名 GET）保持 `jiangyi-video/*` 全前缀不变（服务端会话，与设备写权隔离）。**全链路实测（2026-10-14）**：云侧角色补 `AliyunOSSFullAccess` 后，设备 JWT → STS → PutObject 200 → 台账落库 → 商户端预签名 GET **206 video/mp4**（此前写路径被 RAM 资源组级 ImplicitDeny 拒，设备直传从未成功、台账全空）。

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

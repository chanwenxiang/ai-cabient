# 将邑科技 SAAS 对接解读（V16.0.0）

> 源文档：`将邑对接资料/将邑科技对接SAAS所需接口及流程V16.0.0.docx`（110 页，2026-09-01 发布，将邑智能科技）。
> 提取全文：`将邑对接资料/doc_full_text.txt`。
> **提取完整性已核查（2026-10-09）**：表格 XML 187=提取 187；文本框 81 个全是页码/版权；页眉页脚 128 个全是版权；内嵌图 151 张抽查全为 Logo/背景/小程序码，无业务截图。正文与表格即全部业务内容。
> 本文档为阅读解读 + 对接落地分析，接口细节以原文档为准。

## 1. 文档定位

将邑科技是**动态视觉识别开门柜设备方案商**（摄像头端侧识别、RKNN 模型、阿里云 OSS 传视频）。
本档描述：将邑设备固件 ↔ 商户服务器（即我方 trade 服务）↔ 将邑 SAAS 服务器 三方之间的接口。

**对接我方时的角色**：我方平台扮演「商户服务器」，需要：
1. 实现约 20 个设备上报的 HTTP 接口（设备主动调我方）；
2. 提供一台 WebSocket 服务（设备长连接，我方下发开门/补货/模型等指令）；
3. 提供阿里云 OSS STS 临时凭证接口（设备上传购买视频）；
4. 若采用「将邑后台」模式，再对接将邑侧 ~15 个回调/查询接口。

## 2. 两种对接模式

| | 模式一：普通对接（第四章） | 模式二：用将邑后台（第五章） |
|---|---|---|
| 商品/补货/订单管理 | 商户自建 | 用将邑管理后台 + 运维小程序 |
| 正常订单识别结果 | 设备→商户服务器 | 设备→商户服务器，商户扣费后回调将邑 |
| 异常订单 | 设备→商户服务器 | 设备→将邑服务器，运维小程序「二次甄别」审核后→商户，商户扣费后回调将邑 |
| 重审（退款/补扣） | — | 将邑→商户（5.2.12），商户处理后回调将邑（5.4.3） |
| 设备侧差异 | 设备全量上报商户 | 同左 + 扣费结果需上报将邑 |

## 3. 通用约定（坑点标注）

- **将邑 API base**：`https://lingshouapi.hunanjysmart.com`；管理后台 `https://lingshou.hunanjysmart.com/`
- **测试账号**（运维小程序）：`15736292199 / 123456`；正式账号需向将邑运营申请
- **tenantId + secret**：联系将邑售后获取（setDomain、设备取 token 都要用）
- **鉴权**：设备↔商户用设备 SN 换发的 token（`Authorization` 头）；商户→将邑用运维小程序账号登录换 Bearer token（`/merchant/authentication/login2`）
- **必填标记**：M=必填，C=条件必填，O=非必填
- 🔴 **金额单位不统一**：绝大多数接口「单位为分」，但 **5.4.7 补货结果回调 `floorPokerCreate` 的 `salePrice` 是「元」** —— 对接时必须逐接口核对，资金字段单独封装转换。
- 🟡 **msgType 复用**：强制开门（4.3.2.2）与购物开门（4.3.2.7）的 `msgType` 都是 `openDoor`，靠 `msgContent` 是否带订单号区分。
- 🟡 文档存在复制粘贴错误：4.2.16（门磁状态）描述写成了「广告展示」；4.2.19（广告）请求参数却写 `lockStatus`。实联时要按 URL 与上下文理解，勿按描述。

## 4. 核心业务流程

### 4.1 设备初始化（两种模式相同）
1. 我方调将邑 `POST /platform/tenant/setDomain`（tenantId、secret、domain=我方 http 地址、socketUrl=我方 ws 地址）——**先配好这个，设备才知道往哪上报**。
2. 设备启动 → 调我方 `POST token/openDoorDeviceStatus`（deviceSn）→ 我方返回 token（未绑定设备=未激活）。
3. 设备调我方 `POST deviceInfo/getDetail` → 我方返回设备编码 identifier（唯一别名，ws 连接用）。

### 4.2 模型下发
- 我方经 ws 下发 `updateModel`：`{quantity, modelUrl(.rknn), textUrl(classes txt), modelName}`。
- 设备下载成功后**重启**，重启后调我方 `deviceInfo/downloadModelNotify` 上报当前模型名（未上报=下发失败）。
- 模型 class 与商品映射：`POST /merchant/stdSku/getStdSkuQueryByTextNames`（传 modelTextUrl，返回 stdSkuId/textName/barCode/class）。

### 4.3 购买主流程（资金闭环在模式二）
```
用户扫码 → 我方生成订单号 → ws 下发 openDoor(msgContent=订单号)
  → 设备开锁 → 上报门锁状态 device/uploadLockState
  → 用户拉门 → 上报门磁 device/uploadDoorState（开门失败则不会有订单结果）
  → 购物+端侧识别 → 用户关门
  → 设备上报（三选一）：
     a) 正常订单: orderProduct/addRecognitionGoodsToOrder（forms: classId/quantity[/thirdPid/price/productName]）
     b) 进入大模型复核: device/pokerOrder/uploadOrderError (bigModel=doing) → 完成后再上报 a 或 c
     c) 异常订单: device/pokerOrder/uploadOrderError（errorMsg）
  → 模式二：我方扣费 → 回调将邑 sendOrderAmt；异常单等将邑二次甄别结果(5.2.11)后扣费再回调
```
- 柜机占用中：`order/openDoubleDoorError`（上一单未结算时，双门一笔订单只能开一个门）。
- 超时异常：`pokerOrder/sendTimeOutError`（**免 token 校验**）。
- 故障：`order/uploadFaultOrder`（如摄像头打开失败）。

### 4.4 视频
- 设备用我方 STS 凭证（`/aliYunOss/getTempUploadToken` → accessKeyId/Secret/securityToken/endpoint/bucketName/dirName）直传阿里云 OSS。
- 默认只传**异常订单**视频；`deviceInfo/getArtificialCheckByIdentifier` 返回 boolean 控制是否全量上传。
- 上报 `device/pokerOrderVideo/uploadVideoUrl`：`orderNo, serialNum, videoQuantity, videoUrls[]`——**超约 1.5 分钟自动分片**，前后摄像头地址逗号分隔，上传失败传空数组，需按序号聚合。
- 可 ws 指令 `sendUploadErrorOrderVideos` 按订单号主动拉取视频。

### 4.5 补货（模式一商户自管 / 模式二走将邑）
- ws 指令 `sendOpenStockDoor`（msgContent=补货单号）/ `sendOpenStockDoubleDoor`（{doorPosition, historyNo}）；补货视频同购买视频上报（订单号字段填补货单号）。
- 模式二：补货在运维小程序操作 → 我方 `POST /merchant/floor/getFloorBySn` 拉补货商品（thirdPid/salePrice(分)/stock/classId/productName）→ 补完回调将邑 `floorPokerCreate`（**salePrice 单位元**）。

### 4.6 设备激活（V16 新增 5.4.10）
`POST /merchant/deviceInfo/confirmActivate`：{identifier, deviceSn, deviceName, deviceAddress, screen(has/no)} —— 新设备上线流程。

## 5. 接口清单

### 5.1 设备 → 商户服务器（我方需实现，前缀=我方 domain）
| 接口路径 | 用途 | 备注 |
|---|---|---|
| token/openDoorDeviceStatus | 设备换 token | body: deviceSn |
| deviceInfo/getDetail | 取设备编码 identifier | ws 连接标识 |
| deviceInfo/downloadModelNotify | 上报当前模型名 | 未上报=模型下发失败 |
| /aliYunOss/getTempUploadToken | OSS STS 凭证 | 视频直传 |
| device/uploadLockState | 门锁状态 | success/fail |
| device/uploadDoorState | 拉门(门磁)状态 | 失败则无订单结果 |
| order/openDoubleDoorError | 柜机占用中 | 可选 |
| orderProduct/addRecognitionGoodsToOrder | 订单结算(识别结果) | 资金入口 |
| device/pokerOrder/uploadOrderError | 异常/进大模型上报 | bigModel=doing |
| deviceInfo/getArtificialCheckByIdentifier | 视频上传模式查询 | 返回 boolean |
| device/pokerOrderVideo/uploadVideoUrl | 视频地址上报 | 分片 serialNum/videoQuantity |
| deviceInfo/getDeviceSimInfo | 设备信息 | 可选（信号 0-4） |
| deviceInfo/getDoorInfo | 门磁实时状态 | 可选 |
| deviceInfo/changeWifi | wifi 状态 | 可选 |
| deviceInfo/queryNetwork | 在线状态 | 可选 |
| advertisingDevice/getAdvertisingByDeviceId | 屏幕广告 | 有屏机型 |
| order/uploadFaultOrder | 故障上报 | 如摄像头失败 |
| pokerOrder/sendTimeOutError | 订单超时异常 | 模式二；免 token |

### 5.2 商户服务器 → 将邑（Bearer token，login2 换取）
| 接口 | 用途 | 关键点 |
|---|---|---|
| /merchant/authentication/login2 | 换 token | mobile/password=运维小程序账号 |
| /platform/tenant/setDomain | 配置我方 http+ws 地址 | tenantId+secret |
| /merchant/pokerOrderNotify/sendOrderAmt | 回调结算结果 | amount 分；forms{thirdPid,quantity,salePrice 分} |
| /merchant/pokerOrderNotify/sendOrderRecheck | 回调重审结果 | handleWay=refund/supplementary_pay；amount=重审后总额；initiationMethod 0将邑/1商户 |
| /merchant/pokerOrderNotify/uploadOrderPayError | 回调支付失败 | orderNo, errorMessage |
| /merchant/pokerOrderNotify/uploadRecheckOrderPayError | 回调重审失败 | 同上 |
| /merchant/deviceInfo/updateDeviceModelByPoker | 回调模型下发结果 | deviceSN, textUrl, modelUrl |
| /merchant/floor/floorPokerCreate | 回调补货结果 | 🔴 salePrice 单位=元 |
| /merchant/device/getOnlineStatusAndVersion | 查设备在线+版本 | currentStatus: online/offline/fault/unbind |
| /merchant/selfhelpGatherProduct/receiveGatherProductZipData | 采集 zip 地址（海外） | productList{productID, ossUrl} |
| /merchant/deviceInfo/confirmActivate | 设备确认激活 | V16 新增 |
| /merchant/stdSku/getList | 查将邑标准商品库 | 🔴 applyMachineId 固定 21；name 模糊 |
| /merchant/floor/getFloorBySn | 拉设备补货信息 | 先在运维小程序补货 |
| /merchant/stdSku/getStdSkuQueryByTextNames | 按模型 txt 查商品 | classId↔商品映射 |
| /merchant/tenantGather/searchGatherProductPageData 等 4 个 | 采集图片审核（查列表/查图/删/提交） | 合格图 ≥200 张，将邑复审 |

### 5.3 WebSocket（我方提供 ws 服务，设备连 `{socketUrl}/{identifier}?token={token}`）
心跳：设备发 `{msgType:"heartBeat",msgContent:"PONG"}`，回 `{status:200,msgType:"heartBeat",msgContent:"PONG"}`。

| msgType | 用途 | msgContent |
|---|---|---|
| openDoor | 强制开门(可选)/购物开门 | 空 / 订单号（靠内容区分） |
| sendForceOpenDoubleDoor | 强制双门(可选) | "L"/"R" |
| sendOpenStockDoor | 补货开门 | 补货单号 |
| sendOpenStockDoubleDoor | 补货双门 | {"doorPosition":"L","historyNo":"补货单号"} |
| closeDoor | 关门 | 可选 |
| openDoubleDoor | 双门购物开门 | {"doorPosition":"L","orderNo":"订单号"}；一单只开一门 |
| updateModel | 模型下发 | {quantity,modelUrl,textUrl,modelName} |
| sendDeviceLightStripFlag | 灯带 | 1 开 0 关（可选） |
| sendUploadErrorOrderVideos | 拉取视频 | 订单号 |
| getDoorState / getDeviceInfo | 门磁/设备信息 | 可选 |
| sendQueryNetwork | 检查在线 | 可选；不在线走 AP 配网 |
| sendChangeWifiCommand | 连 wifi | {acount,password}；超时 120s（可选） |
| sendDeviceSoundSwitch / sendDeviceSoundVolume | 声音开关/音量 | 0关1开 / 0大1中2小（可选） |
| reboot | 重启 | — |

## 6. 落到本项目的对接要点

1. **协议适配层**：本项目 edge 目前只支持 chzh8 协议。将邑设备接入 = 新增一套设备协议适配（设备上报 HTTP 接口组 + ws 指令下发 + OSS STS），对应旧系统「三套协议不同构」之外的第四套，建议独立 adapter，不复用 chzh8 语义。
2. **订单状态机对齐**：将邑订单生命周期 = 开锁上报 → 拉门上报 → 识别结果三态（正常/大模型中/异常）→（模式二）二次甄别 → 重审。我方订单模型需能表达「大模型复核中」「二次甄别扣费」「重审退款/补扣」。
3. **资金字段**：统一内部用分；对将邑出参入参按接口核对单位（仅 floorPokerCreate 为元）。
4. **视频存储**：设备要求直传阿里云 OSS（STS），我方现有对象存储两层（storage_uri + 对象存储）需提供阿里云 OSS 通道或适配层。
5. **商品映射**：thirdPid（将邑标准商品库 id）/ classId（模型 class）/ textName（字母简称，对应 classes.txt）三者映射表是识别结算正确性的核心。
6. **文档缺失/需向将邑确认**：
   - tenantId、secret、正式账号（运维 + 管理后台）
   - 《将邑科技商品采集接口文档 v1.2.0.pdf》《易邻购动态视觉智能售货机产品使用手册.pdf》（正文引用但未提供）
   - setDomain 的 domain/socketUrl 是否要求 https/wss、是否支持内网穿透测试
   - 模型文件（rknn）由谁制作、我方新增商品如何触发模型更新
   - 4.2.19 广告接口请求参数疑似文档错误（写了 lockStatus）

## 7. 模式一选型结论（2026-10-09 用户选定：模式一·普通对接）

| 资源/文档 | 模式一是否需要 | 依据 |
|---|---|---|
| tenantId + secret | **必需** | 4.2.2 setDomain 是两模式共同第一步（登记我方 http+ws 地址，设备据此发现上报目标）；设备绑定到 tenant 也靠将邑侧操作 |
| 生产开通（找将邑建 tenant、绑设备 SN） | **必需** | 设备未绑定=拿不到 token=未激活；测试账号只够联调 |
| 运维小程序正式账号 | 非必需 | login2（5.4.1）与 5.4.x 回调、二次甄别、补货、商品库均为模式二功能；模式一用不到 |
| 《商品采集接口文档 v1.2.0》 | **需要（上新商品时）** | 第二章明确把「对接商品采集流程」列在**普通对接方式**下（思路 1(2)）；端侧模型仍是将邑制作，新品识别必须走采集→训练→updateModel |
| 《易邻购产品使用手册》 | 非必需，建议备用 | 引用处（运维小程序教程 12-18 页、补货流程 13 页）均为模式二场景；现场部署/AP 配网可能用得上 |

模式一对接范围裁剪：**只接第四章**（4.2 设备上报 17 个 URL + 4.3 ws 指令全集）；第五章 5.4.x 回调、5.5 商品库查询、5.6 采集审核（除采集流程本身）、二次甄别/重审/超时异常上报**均不接**。
🟡 注意：第四章没有「订单超时异常上报」接口（5.2.14 是模式二专属）——模式一下订单超时兜底完全靠我方自身的订单超时逻辑，需在 trade 侧设计。

## 8. 版本演进速览（V1→V16，2025-04 ~ 2026-09）

关键节点：V2 二次甄别、V3 双门购物、V7 故障上报+声音控制、V8 模型/补货结果回调、V10 超时异常+txt 查商品、V12 设备在线版本查询、V13 采集 zip、V14 采集图片审核、V15 SN 查商户、V16 强制/补货开门 + 设备确认激活。

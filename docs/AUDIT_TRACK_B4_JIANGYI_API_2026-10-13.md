# Track B-4 — 将邑对接面接口契约审计（2026-10-13）

> 范围：jiangyi-gateway 全部 Controller + WS、trade-service JiangyiInternal/AdminJiangyi* 端点、商户采集三端点。
> 子代理产出 + 主会话对 P1 逐行复核（DeviceReportController:58-112、SessionInternalController:56-63、SessionOpenService:92-113、JiangyiInternalController:228-231 实证）。
> 勿重复已定案：AU-003（§5.4 回调判非缺陷）、AU-009（confirmActivate）、AU-040（order-video 归属，本报告补证据）。

## ① 端点分区与鉴权形态

- **设备/公开面** `/jiangyi/api/**`：DeviceAuthInterceptor Bearer 设备 JWT；豁免恰为 token 别名两条 + gather 回调（GatewayWebConfig.java:34-40）。
- **gateway 内部面** `/internal/**`：X-Internal-Api-Key（constant-time、未配置 503 fail-closed，InternalApiAuthInterceptor.java:28-42）。
- **trade 内部面** `/internal/v1/jiangyi/**` 18 端点：internal-key（WebConfig.java:33-34）。
- **运营面** `/api/v2/ops/admin/**` 21 端点：@RequiresPermissions 全挂（PermissionAspect.java:25-50 实证 AOP 执行点）。
- **商户面** gather 三端点+视频回放：permission+guard+requireDevicePack 三件套（MerchantDevicePortalService.java:127-151）；回放先 assertOrderBelongsToMerchant（MerchantFinanceService.java:151）✔。

## ② 发现表

| ID | 级 | 位置 | 发现 | 证据 | 处置建议 |
|---|---|---|---|---|---|
| **AU-060** | **P1** | DeviceReportController.java:70,94,134,187 | 设备上报面「open-failed / recognize-timeout」转发支路**不带 deviceId、无归属校验**：任一持合法设备 JWT 者可用**他人 orderNo** 把受害人 OPENING 会话置 FAILED+释预授权（门实开后取货无结算链路），或把他人 RECOGNIZING 会话转 DISPUTED。对比受保护面 door-event/edge-results 均有 DEVICE_MISMATCH 兜底 | 主会话复核：DeviceReportController:70/:94/:134 `postOpenFailed(body.orderNo())`/`recognizeTimeout(sessionId)` 均不比对 token deviceId；SessionInternalController:56-63 仅 sessionId；SessionOpenService:92-113 CAS 只判状态不判设备；JiangyiInternalController:228-231 无 deviceId 形参。对照 SessionDoorService.java:104-106、VisionResultIngestService.java:78-80 | 接真前必修：trade open-failed/recognize-timeout 增 deviceId 形参并做归属比对（对齐 P1-5 口径），或 gateway 用 token deviceId 反查会话 |
| AU-061 | P2 | DeviceReportController.java:64,109 | 同根因 tracker 面：complete(OPEN)/register(CLOSE) 不校验 orderNo 归属→恶意设备可撤销他人 15s 开门兜底登记/注入垃圾 CLOSE；Redis key 由 body.orderNo 直接 format 无长度字符集限制 | CommandTracker.java:103-105 | 与 AU-060 同批修 |
| AU-062 | P2 | AdminJiangyiController.java:71-75,86-93 | 视频台账列表返回原始实体（含 videoUrls 对象键）给 `ops:device:list` 持有者，绕开 CB-029 特意分离的 `ops:device:video` 授权点；三处读权限口径不一致（list/list/video） | V339__jiangyi_video_play_permission.sql；JiangyiOrderVideo.java:39 | 列表改脱敏 DTO 或统一升权限（定性见 DC-11） |
| AU-063 | P2 | DeviceReportController.java:152-161 | 识别转发 trade 失败被 catch 仍回 200 且 finally 无条件 complete(CLOSE)：设备不再重发+兜底同时清除→只能落 10min 扫描转 DISPUTED，结算降级人工 | :156-157 catch 不上抛、:159-161 | 失败不 complete(CLOSE)（保留 300s 重推）或回非 200；加告警 |
| AU-064~073 | P3 | 见下 | ①DeviceTokenController:75 免鉴权端点透 IllegalStateException message（暴露密钥配置状态）②同文件 envelope/默认体契约不一致③OrderVideoReportController:47-51 serialNum/videoQuantity 无范围校验④JiangyiOrderVideoMapper:20-31 upsert 非原子竞态丢报⑤RecognitionNormalizer:44-49 forms 无上限放大内部调用⑥JiangyiInternalController:200-211 内部 upsertMapping 无最小校验⑦SessionWatchdog:53,64 锁无 owner token 误删他实例锁⑧GatherNotifyController:37-49 无限速+msg 非 success 即否决 PENDING ticket⑨token-revoke 内部端点全仓无调用方（吊销能力只在 curl）⑩merchant 面干净 | 各 file:line 引 B-4 子代理全表 | 登记延后/随批 |

## ③ 存疑（需拍板）

1. **STS 上传凭证按前缀共享**：uploadPolicyJson 资源 `{bucket}/{dirName}*`（OssStsService.java:297-302），任一设备 15 分钟内可覆写其他设备待传路径 → 是否改 `{dirName}/{deviceId}*` 设备级隔离（DC-12）。
2. **modelTextUrl 直连 GET**（JiangyiModelSyncService.java:234-238）URL 由将邑数据决定 → 是否限定 aliyuncs 域（SSRF 面，DC-13）。
3. 视频台账列表对 `ops:device:list` 脱敏定性（并入 DC-11）。
4. WS token 走 query 落 nginx access log（V16 协议约束）→ 日志侧脱敏或接受为协议债。
5. open-failed reason 渠道文案直接透给消费者/商户侧的文案分层。

## ④ 干净面声明（整域通过，实证 file:line）

- **⑤状态机单点**：将邑链全部迁移经 SessionService.transition（JiangyiRecognitionTimeoutService:87,95；SessionOpenService:113；SessionDoorService:125-165），无 controller 直写 state 旁路。
- **①鉴权拓扑**：两面互斥注册无意外裸奔面；trade 默认拒绝+显式豁免不含 ops/merchant 将邑端点（WebConfig:38-58）。
- **②资金核心绑定**：door-event/edge-results DEVICE_MISMATCH 在码（SessionDoorService:104-106；VisionResultIngestService:78-80）；强制开门结构上不可发出（OpenDoorController:49-52）；WS 握手 identifier==token claim（DeviceHandshakeInterceptor:67-73）。
- **③设备 JWT 链**：密钥未配置/短 32 字节签发即 503（DeviceTokenService:82-88）；每请求 BOUND+token_version 吊销比对（DeviceAuthInterceptor:68-78）；负向 IT 实证（DeviceAuthIT:109-163 伪造/吊销/退役均 401）。
- **④幂等主干**：open-failed CAS+锁、recognize-timeout CAS、ticket 一次性 CAS、模型 confirm/fail 条件更新、重复下发 SENT-pending 闸门。
- **金额自持**：识别上报不带金额、映射未命中整单 fail-closed（RecognitionNormalizer:40-56）——与「金额我方算」铁律一致。

## AU-040 补充证据

OrderVideoReportController.java:54 转发 body.orderNo 与 token deviceId 从不比对；JiangyiInternalController:161-166 仅按 (orderNo, serialNum) upsert，deviceId 存 token 解出值（JiangyiOrderVideoMapper:33）→ 可覆写他单行的 deviceId 字段。

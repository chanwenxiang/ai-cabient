# Track B-2 — 会话/开门/设备域接口契约审计（2026-10-13）

> 范围：Session*、CabinetOpenLanding、Device*、DeviceAvailabilityKpi、device-service 开门/ops-command、MQTT 凭据面。
> 方法：只读子代理逐端点七项矩阵 + 状态机/超时兜底穷举。

## ① 端点 × 七项矩阵（摘要）

| 端点 | ①鉴权 | ②BOLA | ③校验 | ④幂等 | ⑤状态机 | ⑥错误码 | ⑦DTO |
|---|---|---|---|---|---|---|---|
| POST /api/v2/sessions（开门） | ✅ | ✅ 仅本人创建 | ✅ | ✅ 幂等键+device 锁 | ✅ 初态 CREATED | ⚠️Q7/Q10 | ✅ |
| GET /{id}、/order、/live-cart | ✅ | ✅ owner | ✅ | ✅ 只读 | — | ⚠️Q6(403 可枚举) | ✅ |
| POST cancel | ✅ | ✅ | ✅ | ✅ CANCELLED 重放=ok | ✅ transition | ⚠️Q1 | ✅ |
| demo-close | ✅ mock 双闸 | ✅ 404 | ✅ | ✅ CAS | ✅ | ✅ | ✅ |
| internal door-event | 内网 key | ✅ deviceId↔session 匹配(SessionDoorService:104) | ✅ @Valid | ✅ 重复 OPEN/CLOSED 静默 no-op | ✅ | ✅ | ✅ |
| internal gravity-deltas | 同上 | ✅ 匹配校验 | ⚠️嵌套不级联(Q8) | ✅ merge | — | ✅ | ⚠️ |
| internal live-cart | 同上 | ⚠️无 device 绑定（仅展示链，存疑） | @Valid | ✅ 锁+CAS 态门 | — | ✅ | ⚠️价格客户端供给 |
| internal open-failed | 同上 | 无 deviceId 校验（CAS 限 CREATED/OPENING） | ✅ | ✅ 三重保险 | ✅ | ✅ | ✅ |
| /o/{deviceId} 落地页 | 公开+IP 限流 20/min | ✅ canonical id | ✅ 收敛 | ✅ 只读 | — | ✅ HTML 全转义 | ✅ |
| DeviceController 各端点 | ✅ | ✅ | ⚠️nearby lat/lng 无域校验(Q8) | ✅ | — | ✅ | ✅ |
| device internal heartbeat/ota/ad-play | 内网 | ✅ | ⚠️ad-play 缺 @Valid(Q8) | ✅ tracker 去重 | — | ✅ | ✅ |
| MQTT 凭据 issue/revoke/status | ✅ | ✅ | ✅ | ✅ | — | ✅ secret 仅一次 | ✅ |
| KPI / slots | ✅ view/edit | ✅ requireDeviceAccess | ✅ | — | — | ✅ | ✅ |
| device-service open-door/ops-command | 内网 | ✅ B-22 注册校验 | ✅ OPS 白名单 | ⚠️HTTP 重试重复发令(Q2) | — | ✅ | ✅ |

## ② 状态机穷举结论

- 存量实体状态变更 **100% 经 `SessionService.transition`**（:499-517 同态 no-op、非法迁移 409）；旁路清单=0；仅 4 处新实体赋初态（符合 SessionState javadoc 豁免）。
- 迁移表自洽；**WAITING_UPLOAD 为死态**（Q5）。
- 超时兜底**真实接线**：trade 4 个 `expireStale*` @Scheduled + `@EnableScheduling`(TradeServiceApplication:29)；将邑 SessionWatchdog + gateway @EnableScheduling(:20)；识别永不到账→300s/10min→DISPUTED+争议单，链路完整。

## ③ 问题清单

| ID | 级 | 位置与复现 | 台账 |
|---|---|---|---|
| Q1 | P2 | 会话失败/取消后**门仍物理打开=免单窗口**：15s/90s 超时置 FAILED/CANCELLED 并释预授权，无撤销/锁门指令；迟到 OPEN/CLOSED 事件静默 no-op、无告警（SessionExpireService:383、SessionService:373-379、SessionDoorService:122-131/142-144）。复现：慢响应柜 16s 开锁→watchdog open-failed→用户取货→识别上报被终态拒收 | AU-028 |
| Q2 | P2 | legacy 开门链 HTTP 重试→重复 MQTT OPEN（每次新 commandId，edge 无法去重）；「送达成功但响应丢失」时可二次开锁；jiangyi 链已决策不重试，本链未对齐（DeviceServiceClient:111-116 + DeviceCommandService:24-29） | AU-029 |
| Q3 | P3 | markOpsRemoteSessionFailed/markRestockOpenFailed 未走 session:life 锁+FOR UPDATE CAS，与 markOpenDoorFailed 语义漂移（AdminDeviceOpsService:119-132、OpsService:116-129） | AU-030 |
| Q4 | P3 | 事务内抢锁族残留：SessionDoorService:95-97、SessionService:275-277、SessionOpenService:92-94、SessionExpireService:71-72（靠 FOR UPDATE 行锁兜底；expireStale 批量 500 持行锁放大等待） | AU-031 |
| Q5 | P3 | WAITING_UPLOAD 死态（common-core SessionState:35、SessionDoorService:72） | AU-032 |
| Q6 | P3 | 越权语义不一致：getSession/cancel/order 非本人 403（存在性 oracle）vs demo 链 404（SessionService:482-486 vs :231-233） | AU-033 |
| Q7 | P3 | 锁包装器 500 携带 `e.getMessage()` 透出；IllegalState 原 message 回传（SessionService:570/585、AdminDeviceOpsService:144）——与 B-1 F-05 同族 | AU-034 |
| Q8 | P3 | 校验缺口：internal ad-play 无 @Valid；GravityDelta/LiveCart/AdPlay 嵌套 record 无级联 @Valid；nearby lat/lng 无范围校验（DeviceInternalController:61-67、DeviceController:52-58） | AU-035 |
| Q9 | P3 | 契约漂移：requireInflightCapacity 的 INFLIGHT 缺 SETTLING；maxInflightOrders 一字段双语义、钳制 0-2 未在配置面说明（MerchantOpsPolicyService:20-26、UserValidationService:137-156） | AU-036 |
| Q10 | P3 | 幂等重放竞态：并发同 key 都 miss 预检→后进锁者撞 DB 唯一键→500「系统繁忙」而非重放/409（SessionService:122-129） | AU-037 |

## ④ 存疑

1. live-cart internal 推送不校验 sessionId↔设备绑定，DELTA 单价由客户端供给——若前端当"实扣预览"展示有误导风险（结算证据为重力 deltas，仅展示面）。
2. open-failed 端点无 deviceId 佐证：内网持 key 者可批量把他人 OPENING 会话打 FAILED（依赖 CAS+内网信任边界，是否按设备 token 限域**待业务拍板**）。
3. `retryForOperations` DISPUTED→RECOGNIZING 再结算是否重复扣款，依赖 SettlementService 订单幂等（B-3 已证：order 存在即返回，双扣不可达 ⇒ 此存疑由 B-3 结论关闭）。
4. gateway 合成 CLOSED 与 trade expiry 赛跑边界——CB-022 已验 fail-closed，剩余窗口同 Q1。

## ⑤ 审过且干净

CabinetOpenLandingController、DeviceMqttCredentialController、DeviceAvailabilityKpiController、device-service ops-command/白名单/B-22、slots 归属链、Dev* mock 三控制器（@ConditionalOnProperty + ProductionStartupValidator:120 硬拒）、开门主链双限流与黑名单/欠款阶梯/余额-only 预授权路径（RiskControlService:68-100、UserValidationService:67-117）。

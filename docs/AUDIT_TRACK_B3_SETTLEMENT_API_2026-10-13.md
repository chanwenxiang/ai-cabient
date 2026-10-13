# Track B-3 — 结算/争议/识别上报面接口契约审计（2026-10-13）

> 范围：Dispute*、VisionInternal、DataConsistency、OpsException*、OpsAlertInternal、JiangyiInternal（结算相关面）、gateway 设备上报链。
> 方法：只读子代理追链 edge-results→completeAsyncRecognition→SettleOrchestrator→RecognitionService→Finalize→争议→裁决/退款，逐端点七项矩阵 + 幂等专项穷举。

## ① 端点 × 七项矩阵（摘要）

| 端点 | ①鉴权 | ②越权 | ③校验 | ④幂等 | ⑤状态机 | ⑥泄漏 | ⑦DTO |
|---|---|---|---|---|---|---|---|
| POST /internal/v1/vision/edge-results | 内部key+CIDR(可选)+**deviceId=会话柜机强绑定**(VisionInternalController:78) | n/a | ⚠️quantity>0 但**无上界/条数/clamp** | ✅ | 仅 RECOGNIZING 采纳✅ | ✅ | ✅ |
| POST /internal/v1/vision/anomaly-events | 同上 | **无 deviceId 归属校验** | 无条数/字段上界 | dedupKey 可被抢占 | n/a | CRLF 日志注入 | ✅ |
| POST /internal/v1/ops-alerts | 同上 | 不校验 deviceId | **message 无长度上界** | 无（每次即发 webhook） | n/a | log 注入面 | ✅ |
| POST /internal/v1/jiangyi/order-video-report | 同上+gateway JWT | **不校验 orderNo 归属 deviceId** | serialNum/videoQuantity 无界 | uk(order_no,serial_num) upsert ✅ | n/a | 转发失败仍回 200（设计） | ✅ |
| POST /internal/v1/jiangyi/sessions/{id}/recognize-timeout | 同上 | — | — | lifeLock+RECOGNIZING 复核 ✅ | ✅ | ✅ | ✅ |
| /api/v2/ops/disputes/* | ops:dispute[:resolve] ✅ | 均 requireDeviceAccess ✅ | 结案行仅 quantity>0 | ticket 锁+行锁+canActWhileOpen ✅ | Transitions 单点 ✅ | ✅ | ✅ |
| /api/v2/disputes/*（消费者） | 登录默认拒 ✅ | fileByConsumer 验 owner✅；非本人→404 防枚举✅ | reason≤256 ✅ | 重复申诉 409✅ | COMPLETED/DISPUTED 才可申诉✅ | ✅ | ⚠️detail 透 previewUrl+裸 videoUri |
| consistency/exceptions 各面 | 权限+设备域 ✅ | ✅ | ✅ | 锁+幂等 ✅（retry 例外见 F） | ✅ | ✅ | ✅ |
| gateway /jiangyi/api/device/* | 设备 JWT 逐请求验吊销 fail-closed ✅ | deviceId 取 JWT subject ✅ | forms quantity>0 无上界 | 依赖 trade 侧 ✅ | ✅ | ✅ | ✅ |

## ② 幂等专项结论（核心）

- **重复识别上报**（4 层）：a) VisionResultIngestService:83 非 RECOGNIZING 回执 ALREADY_HANDLED；b) SessionSettleService:168 lifeLock 内二次复核+SETTLING 先行落库；c) SettleOrchestrator:101-109 settle 锁+findByIdForUpdate+**order 存在即返回**（双扣终极闸）；d) RecognitionResultWriter:121-128 task_id+会话唯一双闸。⇒ **同一/异 taskId 重投不会双重扣款/双重扣库存**。
- **重复裁决**：ticket Redis 锁+FOR UPDATE+canActWhileOpen（ops/merchant/autoWaive 共用 key 互斥）；重复 WAIVE→null/netCompleted=0；重开已退单被 DisputeService:752-757 拦。
- **资金幂等键**：CHARGE/REFUND/ADJUST 三族齐全；退款上限=min(请求,netCharged)；`deterministicRefundNo` 渠道防二退。⇒ **钱账幂等闭环成立（balance-only 档全额本地事务，无账实窗口）**。
- **扣款失败/余额不足**：SettlementOrderFinalizeService:162-237 三档均转 PENDING、清券、预授权冲抵保留、记风控+ops 告警——钱账一致 ✅。

## ③ 问题清单

| # | 级 | 位置与复现 | 台账 |
|---|---|---|---|
| A | P2→**建议接真前必修** | SettlementOrderSupport.java:89 `int lineAmount = unitPrice * quantity` —— **quantity 无上界→int 溢出造负金额单**。持内部 key 对自家绑定柜 RECOGNIZING 会话上报 quantity≈2^30（先补库存使扣减通过）→行金额负→chargeOrder:139 负额跳过扣款标 PAID。修法：入口 quantity/条数上限（≤999/行、≤50 行）+金额 long | AU-038 |
| B | P2→建议接真前必修 | VisionResultIngestService.java:129-155 置信度未 clamp：`overallConfidence=99.0` 直过双门槛→绕过"低置信转人工" | AU-039 |
| C | P2→建议接真前必修 | JiangyiInternalController.java:161 + JiangyiOrderVideoMapper:17-33 order-video-report **不校验 orderNo 归属上报 deviceId**：任一合法设备可覆写他人 orderNo 分片行，污染争议录像取证源。trade 侧一行 session↔deviceId 比对即可修 | AU-040 |
| D | P3 | VisionAnomalyIngestService:34-72：批量无上限、deviceId 不校验存在性；dedupKey 可被伪造抢占压制真异常建单；webhook 在 @Transactional 内发（回滚后已发/重复触警无节流） | AU-041 |
| E | P3 | OpsAlertInternalController:32-39 message 无长度上限→webhook 刷屏；alertType/deviceId 未消毒→CRLF 注入 | AU-042 |
| F | P3 | OpsExceptionController:98-102 vs OpsExceptionService:410-411 —— /retry 幂等键**永不命中**（controller 查 `idempotencyKey=`，落库是 `幂等键：`）；实际双扣被 order 闸兜住，但幂等承诺为假 | AU-043 |
| G | P3 | 事务内抢锁残留：DisputeService:536/577/590/1043、SettlementService:127-137（claim/resolve/autoWaive/settleManual/demoZeroSettle 外层 @Transactional 内 tryLock） | AU-044 |
| I | P3 | DisputeService:483 getMyTicket detail 透 previewUrl+裸 videoUri，与 :459 列表 CB-030 口径不一致（DTO 越面） | AU-045 |

## ④ 存疑

1. **H（P2 待确认）**：`resolveTicket`→`confirmDisputedItems` 的渠道 HTTP 嵌在外层长事务+行锁内，与 SettlementService:140"无外层长事务"注释矛盾；balance-only 无资损，**接渠道前须用 mock=false 复核**（与 refundByOperator :246 刻意不开外层事务的设计自相矛盾）。→ 接真前 checklist
2. SessionSettleService:114-127 FAILED 分支 `failReason=e.getReason()` 入 SessionDto——"库存不足 sku=…"内部文案可透出消费者（泄漏面小，是否刻意待确认）。
3. RecognitionResultWriter fail-hard：重报闭环成立（事务回滚含状态迁移）；仅外 skuId 超长走 500 而非 400（无入参长度校验）。

## ⑤ 审过且干净

DisputeController 全部 6 端点；ConsumerDispute file/mine/uploadEvidence；edge-results deviceId 绑定与 modelVersion fail-closed（P1-5 正确落地）；recognize-timeout、gather-finish-notify（一次性凭据）；gateway 设备面 JWT 链（验签→BOUND→token_version 实时吊销）；DataConsistency；OpsException claim/transfer/note/cancel/manual-resolve；余额不足三档钱账一致；chargeOrder 净额冲抵防双扣（P0-4 钉住）。

**总结**：核心资金幂等与状态机单点成立，双重扣款/退款/扣库存未发现可达路径；接真前建议必修 A/B/C 三条（均为内部 key+deviceId 绑定防护面的纵深补强）。

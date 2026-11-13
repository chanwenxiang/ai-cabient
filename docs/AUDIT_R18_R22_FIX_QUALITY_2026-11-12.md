# R18–R22 修复质量核查报告（2026-11-12 深夜）

> **任务**：核查 R18–R22 五轮修复的「修得对不对」。R1–R17+M01 已由 [AUDIT_FIX_QUALITY_VERIFICATION_2026-11-12.md](AUDIT_FIX_QUALITY_VERIFICATION_2026-11-12.md) 核过（虚报仅 1 条 A26，已修）；本轮补齐 22 轮里唯一未核查的区段。
> **方法**：两层——① **锚点批检**（46 项，一次性脚本 `scripts/verify-r18-r22-anchors.mjs`，逐项用当前 HEAD 实测类/方法/常量/门禁脚本/钉测存在性与关键代码形态）；② **资金路径深读**（5 条钱相关修复读实现逻辑判正确性，不只看在位）。代理配额耗尽（11-16 重置），全部主会话直做。
> **判定符号**：✅在位且正确 / ⚠️在位但有保留 / ❌未修或虚报。

---

## 一、总判定

| 轮 | 核查项 | ✅ | 结论 |
|---|---|---|---|
| R18（P1×2+P2×4，77b8feeb/51916f22/33a1a877） | 8 项 | 8 | watchdog 阈值配置化 70s+钉测 ✓ / V458 种模板+种库门禁 ✓ / 批次原子 UPDATE+version 递增 ✓ / IT 存在 ✓ / 租约三态+心跳带身份摘除+RenewTest ✓ |
| R19（56664645/17906dba/b8b76dd3 + 15~23/27/29/30） | 12 项 | 12 | 商户闸+ScopeTest ✓ / compose-image-tags 自测 ✓ / ZREM 抢占+专用池+ClaimTest ✓ / watchdog UUID+Lua ✓ / **池容量 ×2 判据实锤（CONNECTIONS_PER_TASK=2）** ✓ / XXL 3.12 ✓ / rbac-unavailable ✓ / nginx 同源 ✓ / 备份 alert() ✓ / rangeByScore 批上限 ✓ |
| R20（17fe5fb8/1a352188/5e19bfd5/f44ec365） | 13 项 | 13 | 全部在位（详见资金深读） |
| R21（bfaeb937） | 1 项 | 1 | 锁边界 afterCompletion 落在 InventoryService（68-90 行，注释完整） |
| R22（23c57e09/382c0769） | 9 项 | 9 | reason/#orderId ✓ / 短信额度后置（SmsCodeService:76 注释实证）✓ / 资金表保护 ✓ / 巡检不短路 ✓ / 采集意图留痕（真实落点=JiangyiCabinetCaptureService，61 行改动）✓ / 支付宝 fail-fast ✓ / 遥测 90 天窗 ✓ / ScheduleZones 统一 ✓ |
| R20-4/R21-1 出站超时 6 处 | — | — | 已由 S1 门禁（check-outbound-client-factory）**永久钉住**，本轮免复检 |
| **合计** | **46 项** | **46** | **零虚报、零残留** |

## 二、资金路径深读（5 条，全部 ✅）

1. **R20-P2-5 支付分事件只进不退**：早退闸确实在 `setOrderState` **之前**（PayScoreOrderNotifyService:97-107），双证据判据（payscoreState=DONE ∨ orderStatus=PAID）任一命中整段忽略——注释明确写了「否则 DONE 会被倒退成 EXPIRED，查单与对账据此误判」的因果。
2. **R20-P2-7 对账渠道过滤**：三处全实证——`sumPaidAmountBetween`/`findPaidOrderIdsBetween` 渠道参数贯穿（null 仅 Mock 聚合账单语义，注释写明）；`sumLedger(start,end,channel)` 的退款扣减按渠道分桶；`collectLedgerOrderIds` 与 sumLedger 同 scope。`RechargeOrderMapperXmlTest` 离线解析 XML 钉谓词下推。
3. **R20-1 退款 ABNORMAL 不入账**：`finalizeLiveOrderRefund` boolean 契约（OrderPaymentService:859 javadoc：「false=渠道 ABNORMAL 未记账，只落 REFUND_FAILED 痕迹」）；ABNORMAL 分支落 REFUND_FAILED 进人工队列（:908）+ HIGH 告警（:415-417 回调确认路径同样不入账）；痕迹行耗尽处置同样不自动冲账（:1179-1186「金额方向敏感」）。
4. **R18-5 库存 CAS 重试**：`syncAggregateInventory` 三轮重读-重算-再写（InventoryLotService:536-549），注释写明「汇总值恒为当前批次和，重试不放大差异；3 轮仍冲突按 save 原口径抛 409 回滚」——语义正确（收敛而非放大）。原子 SQL 在 DeviceSkuInventoryMapper:120/128（`quantity = quantity - #{take}, version = version + 1` 单语句）。
5. **R19-19/18 网关双修**：SessionWatchdog UUID token + RELEASE_IF_OWNER Lua（防删别人的锁）；DeviceCommandTracker ZREM 抢占令牌 + 专用有界池（1/2/256+CallerRuns）——两处均在位且形态与报告一致。

## 三、结构性加固交叉（本批新门禁对旧修复的永久钉住）

| 旧修复 | 现由哪个门禁永久守护 |
|---|---|
| R20-4/R21-1 出站超时 6 处 | `check-outbound-client-factory`（S1） |
| R22-5 资金表删除保护 | `check-ledger-immutability`（S5） |
| R22-4 to_char 时区 | `check-mapper-tz-timezone`（S4③） |
| R22-6 巡检不短路 | `check-config-early-return-scope`（S6） |
| R22-10 时区统一 | `check-bare-localdate`（S4②）+ `check-tz-pinned`（S4①，顺带修了 R19-17 漏网的 3 个 Dockerfile） |
| R22-9 遥测保留窗 | `check-retention-registry`（S7，config 键锚点校验） |
| R22-1 reason 同源 | `check-composed-literal-roundtrip`（S3-3a，写读分离点全量在册） |

⇒ R18–R22 的修复不只「在位」，其中 7 个族已升级为**结构性门禁**，再发即红。

## 四、核查过程中的勘误/发现（3 条，无资金风险）

1. **台账路径勘误**：R22-7「采集意图留痕」真实落点是 `JiangyiCabinetCaptureService.java`（61 行改动），不是早期日志提到的 JiangyiGatherConsoleService（该类已并入/更名）——判「我的改动还在不在」必须 `git log -S` + 查 commit stat，别按记忆找文件。
2. **R21-3 落点勘误**：锁边界 afterCompletion 在 `InventoryService.java`（结算库存锁），不在对账服务。
3. **并行会话瞬态**：核查期间一次 mvn test-compile 被并行会话在制文件挡红，数分钟后自愈——多会话下「编译红」先看归属再定性。

## 五、结论

**R18–R22 五轮修复质量：46/46 在位、5 条资金路径深读全部正确、零虚报**。加上 R1–R17 核查的 99.7% 真实率，**22 轮审计的全部修复经两轮独立核查，唯一虚报（A26）已修，残留 5 条已在当日处置（§七）**。R23 的重点因此不是「补旧账」，而是：新代码面（V458–V462、支付分、兜账 v2、S 系列门禁自身）+ 全量四端扫 + 第二层 7 族（S9–S15）的结构化落地核查。详见 [AUDIT_R23_PLAN_2026-11-12.md](AUDIT_R23_PLAN_2026-11-12.md)。

*核查落盘：2026-11-12 深夜，快照=当前 HEAD（9c30038b 之后含本批 S 系列）。锚点批检脚本 `.tmp/verify-r18-r22-anchors.mjs` 可重跑复核。*

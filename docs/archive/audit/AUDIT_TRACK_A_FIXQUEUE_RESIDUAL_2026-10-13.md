# Track A-1/A-2/A-3 — FIX_QUEUE_2026-10-05 批次 1–4 存量复扫（2026-10-13）

> 基线：worktree `audit-pre-launch`（9911a2ee + 台账提交 4ef59d29）。
> 方法：只读子代理逐条 grep+读码核三态（已修+证据 / 未修 / 形态变化·判非缺陷+证据），主会话抽查 P0 级证据。
> 底账：`docs/FIX_QUEUE_2026-10-05.md`。

## ⚠️ 基线限定

主目录存在**未提交**的 `MerchantWithdrawService.java` 等 13 个文件改动（其他会话遗留，含 settle_unlock_hour 相关）。本表结论一律以 worktree 入库基线为准；**提现/解冻口径须待该批改动落库后复核**（与 Track B-1 F-01/F-02 直接相关）。

## 复扫总表

| 编号 | 三态 | 证据 | 现判级 | 备注 |
|---|---|---|---|---|
| P0-4 | 已修 | OrderPaymentService.java:374-404 | — | 三场景断言测试已补 |
| P0-1 | 读取侧已修/写入侧形态变化 | 读:MinioVideoService.java:303(streamTo 拒 file://)、:94(presign 拒 file/http)；写:主代码 grep `isPlatformObjectUri` 仅定义(:120)零调用、`videoUri` 出参仅 server 生成(:236)；旧录像链退役 MqttEventListener.java:299 | — | 已无接受设备自报 URI 的入口 |
| P0-6/6b | 已修 | check-compose-ports.mjs:8-10,39-46,66-74（四形态）+负例:162-165；full.yml:376/387 未再裸绑 | — | |
| P1-15 | 已修 | SubmitReplenishmentLinesRequest.java:17 @NotEmpty@Valid；ReplenishmentTaskLineDto.java:15 @Min(0) | — | |
| P1-12 | 已修 | check-admin-endpoints.mjs:13,16 fileURLToPath 自锚定 | — | |
| P1-10 | 形态变化/判非缺陷 | DeviceSimulator.java:406-419：绑 0.0.0.0 为容器端口转发所需，宿主面由 compose `127.0.0.1` 前缀+门禁把守 | — | |
| P1-11 | 形态变化/判非缺陷 | docker-compose.devops.yml:81-83：`:ro` 断 socket 写权限，缓解=仅受信分支，根治=docker-socket-proxy（已文档化） | P3 | 风险接受有记录 |
| P1-9 | 形态变化/判非缺陷 | OfflineUploadQueue 随旧录像链退役（check-edge-queue-threading.mjs:149,214）；在役 OutboundMqttQueue 已有上限淘汰 | — | 类已删除 |
| P0-2 | **部分修** | 缓解：EdgeRuntimeConfig.kt:55-56 逐设备 prefs+KeystoreCipher 加密；服务端拒默认键 ProductionStartupValidator.java:83。**未修**：build.gradle.kts:32 fleet 默认 key 仍在，flavors:39-48 不覆盖；逐设备内部凭据未落地（device_mqtt_credential 仅 MQTT） | **P1** | 自 P0 降 P1（解包 APK 不再直接得生产 key） |
| P0-3 | 已修(主入口)/形态变化 | deviceId 绑定 VisionResultIngestService.java:78-79 DEVICE_MISMATCH；VisionRecognitionListener 链路默认关（application.yml:165-166）；Redpanda 仍 PLAINTEXT（发布绑 127.0.0.1 兜底） | P3(SASL) | |
| P1-6 | **部分修** | signingConfigs 已加 build.gradle.kts:54-70+无凭据即失败:96-105；OTA 验签 ApkSignatureVerifier.kt:41-70←OtaInstaller.kt:66；**未修**：versionCode 防回滚零命中；AndroidManifest.xml:19 `usesCleartextTraffic="true"`、无 networkSecurityConfig | **P2×2** | |
| P1-6批3(bootstrap) | 已修 | check-env.ps1:134-145；verify-production-readiness.ps1:243-246 | — | |
| P2-8 | 已修 | WarehouseService.java:204-206、:244、:285 | — | |
| P2-14 | 已修 | check-admin-endpoints.mjs:61-71 模板串形态拒绝 | — | |
| P2-15 | 已修 | money-ui-contracts.test.ts:122-149 线长 4 例 | — | |
| P1-3 | 已修 | WarehouseService.java:343-346 死参已清 | — | |
| cancelShippedDeviceLines 锁排序 | 已修 | WarehouseService.java:862-866 | — | |
| RevenueSplitService 费率归零 | 已修 | :250/:294/:488 留痕待人工 | — | |
| SiteRentBill fixedCents | 判非缺陷 | 队列 14b 已定案关闭 | — | |
| WarehouseMonthlyCloseMath | 已修 | :55-62 钳制移至全量应用后 | — | |
| InTransitService 幂等 | 已修 | :64-68 exists 守卫 | — | |
| OpsSessionOrderQueryService 吞异常 | 部分修 | :559-560 补 log.error；paidAt 仍回退 createdAt | P3 | |
| max(priceCents,1) | 已修 | ProcurementService.java:739-742,756-762 缺失采购价阻断 | — | |
| 分页 size 上限 | 已修 | OpsRiskAdminService.java:40、OpsCommercialFacade.java:89 钳 100 | — | |
| LineCommissionJob 长事务 | 已修 | :116-131 锁内短事务 | — | |
| 事务内抢锁 | 已修 | SettlementSettleOrchestrator.java:98-102 先锁后 tx | — | |
| recordSplit/markUsed | markUsed 已修/recordSplit 判非缺陷 | SettlementOrderFinalizeService.java:113-128 / :134-136（刻意随整体回滚，理由成文） | — | |
| yuan() 收敛 | **未修** | admin-vue grep `function yuan(` **13 处** | P3 | |
| ps1 BOM 门禁 | 已修 | check-ps1-bom.mjs+ci.yml:510 | — | |
| 镜像钉 digest | 已修 | 无 `:latest` | — | |
| check-mybatis-null-clear 假绿 | 已修 | :74-87 窗口 4→12、break 语义修正 | — | |
| check-*-wired 基线 | 已修 | android 基线 14；mp consumer19/merchant25/admin13 | — | |
| e2e-lib.ps1 锁 TOCTOU | 已修 | :13 CreateNew+FileShare.None | — | |
| OrgSitesView/ExceptionListView `|| 0` | 已修 | `?? 0` | — | |
| CrudTable 主键顺序 | 判非缺陷 | CrudTable.vue:619-627 类型语义使然 | — | |

## 未修项汇总（入台账口径）

- **P0：无**（原 P0 全部修复或经接线/退役消解）
- **P1**：P0-2 残余 —— release/device flavor 仍内嵌 fleet 共享默认 `INTERNAL_API_KEY`（build.gradle.kts:32），逐设备内部凭据未实施；现靠服务端拒默认键+逐机 prefs 覆盖缓解 ⇒ **AU-010**
- **P2**：① OTA 无 versionCode 防回滚（**AU-011**）；② `usesCleartextTraffic="true"` 且无 networkSecurityConfig（**AU-012**）
- **P3**：① admin 13 处自建 `yuan()`（AU-013）；② paidAt 回退下单时间（AU-014）；③ Redpanda 无 SASL（AU-015）；④ devops runner docker.sock 读写挂载（AU-016）

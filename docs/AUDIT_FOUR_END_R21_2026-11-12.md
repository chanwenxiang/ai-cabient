# 四端审计 R21 报告 · 配置/锁/时间/文案/软删除/出站调用（2026-11-12）

> **性质**：换轴全量（不再看 R19 的授权/持久层/校验，也不看 R20 的状态机/资金链/营销）。
> **执行方式**：**不开子代理**，主审独自用 grep + 定向读码完成；全部结论均为本人亲验。
> **六轴**：①配置开关矩阵 ②分布式锁全景 ③时区与日期边界 ④错误码与用户文案 ⑤软删除/数据保留 ⑥出站 HTTP 客户端。
> **总账**：**新发现 3 条 = P1×1 + P3×2**；另有 **3 条排除性复核**（含 1 条我自己提出的误报，已撤回）与 1 条方法论订正。
> **收敛信号**：本轮六轴里 4 轴零命中——与 R17 同型，说明这些面此前已被压实。

---

## 一、发现

### R21-1（P1·可用性）出站 HTTP 客户端缺超时，共 6 处，其中一处是登录主链

R20-4 只报了支付的两个客户端；本轮把 `services/*` 下所有 `RestClient/RestTemplate/HttpClient` 构建点扫了一遍：

| 客户端 | 位置 | 超时配置 | 影响 |
|---|---|---|---|
| **WeChatMiniAppClient** | `wechat/WeChatMiniAppClient.java:56-58` | **0**（`SimpleClientHttpRequestFactory` 未设 connect/read） | `code2Session` 是**微信登录主链**；微信侧卡住 ⇒ 请求线程无限期阻塞，Tomcat 线程被逐个吃掉 ⇒ 全站不可用 |
| WeChatPayV3Client | `payment/WeChatPayV3Client.java:42` | 0 | 支付/退款（R20-4 已报） |
| AlipayOpenApiClient | `payment/AlipayOpenApiClient.java:33` | 0 | 支付/退款（R20-4 已报） |
| TradeServiceClient | `device/client/TradeServiceClient.java:28` | 0 | 设备门磁转发（R20-10 已报 MQTT 侧） |
| IdentityVerifyClient | `identity/IdentityVerifyClient.java` | 0 | 实名核验 |
| JiangyiModelSyncService | `service/JiangyiModelSyncService.java` | 0 | 将邑模型同步 |

已设超时的对照：`InternalRestClientFactory`（10s/30s）、`BarcodeLookupClient`（3s/8s）、`AgreementChargeClient`、`AmapGeocodeService`、`MerchantAiInsightService`、`DevOpsHubService`、`TradeInternalClient`。

- **修法**：统一走 `InternalRestClientFactory` 同款的 requestFactory（connect 3s / read 10s），把 6 处收口成一个工厂；`WeChatMiniAppClient` 优先（保留它现有的 `BufferingClientHttpRequestFactory`，只叠加超时）。
- **判据**：`grep -rn "RestClient.create()\|new SimpleClientHttpRequestFactory()" services/*/src/main/java` 结果全部带 `setConnectTimeout/setReadTimeout`；压测：把微信域名指向黑洞 IP，登录接口应在 10s 内 504 而非挂死。

### R21-2（P3）16 个文件用无时区的 `LocalDate.now()`，靠容器 `TZ=Asia/Shanghai` 兜底

- 命中文件（全量）：`AdminDashboardService / AdRevenueSyncService / CommercialFlowService / DemoDataService / DeviceSlotService / InventoryLotService / LineManagerKpiService / MerchantFinanceService / MerchantSkuPricingService / ProcurementService / ReplenishmentService / RevenueSplitService / SiteContractService / SlaMetricsService / WarehouseService / WarehouseInventoryMapper`。
- 其中语义敏感的点：`InventoryLotService:155/290/491`（临期/冻结天数/货架期）、`:377`（批次号 `B-<today>`）、`DeviceSlotService:582/587`（7 天临期窗口）、`AdminDashboardService:489/515`（保质期推算）、`MerchantFinanceService`/`RevenueSplitService`（账期归属）。
- 现状：Docker 镜像统一 `ENV TZ=Asia/Shanghai`，生产无影响；但 CI 容器、开发者本机、将来多区部署时，**跨天前后 8 小时内会出现「临期差一天」「批次号跨日」**。
- **修法**：统一改 `LocalDate.now(ScheduleZones.ZONE)`（仓里已有该常量，`OpsMerchantFeeLedgerController:72` 是正确范例）；加一条 grep 门禁禁止裸 `LocalDate.now()`。
- **判据**：`TZ=UTC` 启动后跑临期用例，结果与 `TZ=Asia/Shanghai` 一致。

### R21-3（P3）分布式锁在事务内释放：解锁早于提交

- `InventoryService.deductForOrder`（`:58-73`）：方法 `@Transactional`，锁在 `try/finally` 里释放 ⇒ **finally 执行时事务尚未提交**，其他实例可在「已解锁、未提交」窗口内读到旧账面并再次扣减。
- 缓解：本轮之前已把汇总扣减改成条件 UPDATE（`quantity >= take` + 影响行数判定）⇒ 不会超卖；但批次账（R18-2 未修）仍可能在窗口内被覆盖。
- **修法**：锁改由外层（非事务方法）持有，或扣减走 `TransactionTemplate` 在锁内显式提交；批次账接入原子 UPDATE 后再把本条降为「已缓解」。
- **判据**：并发 IT——A 持锁扣减、B 在解锁后立刻读，B 必须看到 A 的结果（提交后）。

---

## 二、排除性复核（本轮提出但**不成立**，勿据此修）

1. **「23 个配置常量是死旋钮」——撤回（我的误报）**。
   我最初按「除声明外无 `SystemConfigService.<NAME>` 引用」筛出 23 个，随后按字面值与类内调用复核：
   `CONSUMER_ORDER_SEARCH_ENABLED` 等在 `SystemConfigService:664-666` 被 **self 调用**读取，并在 `:1029-1033` 用 `upsertIfAbsent` 种默认值；`consumer.service_phone`（2 处引用 + 2 处迁移种子）、`device.rental.overdue_lock_days`（2 引用）均有真实消费方。**无死键**。
2. **软删除无泄漏**：全仓仅 4 个迁移含 `is_deleted`、6 个实体挂 `@TableLogic`，且带 `isDeleted` 字段的实体全部有 `@TableLogic`（抽查 20 个域对象，零遗漏）。手写 SQL 的 `is_deleted=0` 过滤只出现在 2 处（其余靠框架），未发现「已删行被读出」的路径。
3. **935 处硬编码中文异常不构成「错误码缺陷」**：前端不按业务文案做分支（全仓只有 `consumer-api.ts:336` 按 `(401)` 模式判断，属传输层），`ApiMessages` 的 46 条别名映射只服务于少量历史码。⇒ 不派修；若要统一错误码，是体验工程而不是缺陷修复。

---

## 三、方法论订正（写给下一轮的我）

- **配置键的种子不在 Flyway**，而在 `SystemConfigService` 的 `upsertIfAbsent(...)`（约 `:1000-1060`）。下轮判断「配置是否生效/是否有默认值」，要查这段代码，不能只看 `db/migration` 与 `application.yml`（我本轮第一版就是这么误判的）。
- **锁的覆盖面**应用「构建点清单」而不是「计数」（`tryLock` 153 处、`unlock` 与之一一对应不代表正确）；真正的判据是「锁的持有区间 ⊇ 事务区间」。

---

## 四、建议

1. R21-1 优先修（登录链无超时 = 单点可用性炸弹），一次把 6 处收口到统一工厂。
2. R21-2 与 R21-3 都很便宜：一条 grep 门禁 + 一处锁边界调整；可与 R18-2（批次账原子化）合并到同一批，因为 R21-3 的缓解正依赖它。
3. 本轮无 P2，六轴中四轴零命中——若要继续挖，建议换「外部系统契约（将邑/微信/支付宝报文级）」与「数据生命周期（归档/清理/恢复演练）」，其余轴已接近收敛。

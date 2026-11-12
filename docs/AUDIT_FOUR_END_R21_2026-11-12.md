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

---

## 五、后续回执（2026-11-12 夜补记，来自 R22）

> 本节不新增 R21 条目，只把「R21 三条现在的真实状态」和「R22 待定项回执」同步过来，
> 让只读 R21 的人不必再翻 R22 就能知道哪些已修、哪些没修、哪些根本不是 R21 的遗留项。
> 证据正文见 [R22 报告 §四 / §八](file:///D:/ai-generated%20code/ai-cabinet/docs/AUDIT_FOUR_END_R22_2026-11-12.md)。

### 5.1 R21-1（P1 出站无超时 6 处）现状

R21 报的是「未显式 setConnectTimeout/setReadTimeout」这一事实；R22 对每一处在 **HEAD 与工作树** 分别复核：

| 站点 | HEAD（`4e230651`） | 工作树 | 结论 |
|------|------|--------|------|
| `WeChatPayV3Client` | 无 | 3s/10s（下载 60s） | peer 在制修复（未提交） |
| `AlipayOpenApiClient` | 无 | 3s/10s | peer 在制修复（未提交） |
| device `TradeServiceClient` | 无 | 已有 Timeout 配置 | peer 在制修复（未提交） |
| `WeChatMiniAppClient`（登录 code2session） | 无 | 无 | 🔴 **仍未修** |
| `IdentityVerifyClient` | 无 | 无 | 🔴 **仍未修** |
| `JiangyiModelSyncService` | 无 | `RestClient.create()`（[:59](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/java/com/aicabinet/trade/service/JiangyiModelSyncService.java#L59)） | 🔴 **仍未修** |

⇒ R21-1 **未收口**；派修时请按「统一客户端工厂」一次收全，别逐处补（R22-1/R22-2 与本族同链路，见 5.3）。

### 5.2 R21-2 / R21-3 现状

- **R21-2（无时区 `LocalDate.now()` 16 文件）**：R22 把它**收窄并加严**为 R22-10——真正会错的是 4 处业务日窗口用 `ZoneId.systemDefault()`（`MerchantFinanceService:266-267`、`DeviceSlotService:575-577`、`DeviceRentalService:319/348`、`InventoryLotService:861`）+ 2 处批次号 `LocalDate.now()`。**实测**：trade-service 容器 `TZ=Asia/Shanghai`、应用日志偏移 `+08:00`，故当前与 `ScheduleZones.ZONE` 等价、生产不发作；兜底来源已定位＝[Dockerfile.java-service:12-13](file:///D:/ai-generated%20code/ai-cabinet/Dockerfile.java-service#L12) 的 `ENV TZ` + `/etc/localtime` 符号链接（compose 里**没有** `TZ:` 字面，只有 `docker-compose.devops.yml` 命中过该串）。
  - 🔴 **跨轮对账**：peer 在制改动（工作树，HEAD 尚无）已给 ENTRYPOINT 补 `-Duser.timezone=Asia/Shanghai`（R19-17），理由写明「ENV TZ + 符号链接依赖 tzdata 与链接同时正确，换/精简 base image 会**静默退回 UTC**」⇒ 这正好消掉本条「部署侧漏配就平移 8 小时」的一半风险。**但代码侧的 `systemDefault()` 仍应统一改 `ScheduleZones.ZONE`**：镜像参数属于部署前提，不该成为业务日窗口的判据来源。
  - 修法不变：统一 `ScheduleZones.ZONE` + 一条 grep 门禁禁止业务日窗口用裸 `LocalDate.now()`/`ZoneId.systemDefault()`。
- **R21-2 的同族新发现（本轮补，属 SQL 侧）**：R22-4 —— 商户账单按日聚合用 `to_char(created_at,'YYYY-MM-DD')`，PostgreSQL **会话时区实测为 UTC** ⇒ 桶标签是 UTC 日。实库已命中 4 笔（上海 11-03 早间被记成 11-02）；每月 1 日 00:00–07:59 的成交会落到上月标签、不在日骨架内 ⇒ 日行与**月合计一起少计**。**这一条不在 R21 的 16 个文件里**（R21 只查了 Java 侧 `now()`，没查 SQL 侧 `to_char`）——时区判据必须同时看 JVM 默认区 **和** PG 会话时区。
- **R21-3（锁早于事务提交）**：状态未变。[InventoryService.java:59-73](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/java/com/aicabinet/trade/service/InventoryService.java#L59) 仍是 `@Transactional` 方法内 `runWithDeviceLock` 在 `finally` 里 `unlock`。R22 未重报（不重复计数），但它**仍在待修清单里**，且按 R21 原文依赖 R18-2（批次账原子化）先落。

### 5.3 R22 待定项回执（同步摘要，**不属于 R21 任何条目**）

R22 §八 的四项收口结论，读 R21 时一并知道即可，别把它们当成 R21 的遗留：

| R22 项 | 回执结论（一句话） | 与 R21 的关系 |
|---|---|---|
| 8.1 厂商 §7.15 重复 start | 已证「同柜单会话 + 未结束占用约 15 分钟自释放」，真机过期会话回 `503 采集会话不存在或已过期`；缺「进行中会话再 start」的返回（现场点两次即可定档） | 无关（R22-7 的定档项） |
| 8.2 jiangyi-gateway 单/多实例 | **结案**：4 份 compose + 3 份 nginx 穷举均为单实例，HA 只 scale trade-service ⇒ 不升 P1；转成部署约束（禁止 scale gateway / 按 identifier 粘连） | 无关；但属「出站路由」同族，R21-1 修客户端时别把网关路由一起动 |
| 8.3 多层柜 | **钉到结构层**：全库 179 表无 `floor/layer` 列、无货架表、`device_info` 只有 `cabinet_type`（现网 5 台全 `VISION`）⇒ 我方模型无层维度，多层柜**必然错** | 无关（R22-3 的定档项） |
| 8.4 「两侧皆 0 记 MATCHED」 | **不是待定、是拍板**：现状 A（无交易即平）vs 新增 `NO_DATA` 状态 B；建议启用支付宝前先落 B | 无关（R22-8 的口径项） |

### 5.4 对 R21 第四节「建议」的更新

- 建议 1（R21-1 优先修）：**仍成立**，且 6 处里仍有 3 处完全未动（见 5.1）。
- 建议 3（下轮换「外部系统契约」「数据生命周期」两条轴）：**已由 R22 执行**，结果见 R22 §一（出站失败语义 6 条）与 §二（生命周期 2 条）；两条轴都**不是零命中**，说明 R21 当时的「接近收敛」判断对这两轴偏乐观。

---

## 六、修复回执（2026-11-12 当日，P1 余量 + P3 全清）

| ID | 处置 | 提交 |
|---|---|---|
| R21-1 ✅（收口） | 三处未修站点（WeChatMiniAppClient / IdentityVerifyClient / JiangyiModelSyncService）按 R20-4 同范式补 3s 连接 / 10s 响应；支付两处已在 `1a352188` 修掉 ⇒ **6 处全收口** | `bfaeb937` |
| R21-3 ✅ | `runWithDeviceLock` 活跃事务时把 unlock 挂 `TransactionSynchronization.afterCompletion`（提交/回滚后释放）⇒ 锁持有区间 ⊇ 事务区间；deduct/restore/recordRefundKeptGoods/adjustForOrder 四入口共用一处生效。账面并发已由 R18-2/4/5 条件 UPDATE 谓词兜底，本修为双保险 | `bfaeb937` |
| R21-2 ✅（点名面） | R22-10 点名的 4 处业务日窗口 + 2 处批次号已统一 `ScheduleZones.ZONE`（见 R22 §九）；SQL 侧同族由 R22-4 一并修（账单 to_char 加 AT TIME ZONE）。16 文件的**全量**裸 `LocalDate.now()` 清扫与 grep 门禁未做（并入常规批） | `23c57e09` / `382c0769` |

**R21-1 判据复核**：`grep -rn "RestClient.create()\|new SimpleClientHttpRequestFactory()" services/*/src/main/java` 现在全部站点带超时或走内部工厂；「微信域名黑洞 → 登录 10s 内 504」的真机压测未跑（需外网控电环境），建议下次弱网联调时补一次。

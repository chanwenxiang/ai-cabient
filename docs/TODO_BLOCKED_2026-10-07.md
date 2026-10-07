# 待完成清单（2026-10-07）

> **为什么有这个文件**：不是「我不想做」，而是**客观条件不具备**（缺外部依赖 / 需业务决策 /
> 环境停机）。每次条都写明**阻塞原因**与**解锁条件**，避免下次重新评估一遍。
>
> ⚠️ **纪律**：本清单里的项**不得被算作「已完成」**，也不得在汇报里被跳过。
> 解锁后要做的事写清楚，解锁前的动作不要凭印象重排。

## 📌 最终状态总表（2026-10-07 23:0x 实测复扫后生成）

> **这一节是唯一权威口径**：下面的分类由 `grep -nE "^\| \*{0,2}(A|B|C|D|E|F|G)[0-9]"` +
> `grep -nE "⬜|仍未做|未排期|未装机|未生成"` **全文复扫**得出，不采信任何历史清单。
> 复扫抓到并已修正的文档矛盾：**F3**（活文档已校准但本清单 22:4x 段仍写「未校准」）、
> **D2**（标「未接入」实为刻意的诚实实现）。
>
> 图例：✅ 已完成 ｜ 🔴 阻塞于外部 ｜ 🟡 我能做但未排期 ｜ 🚫 有意不做

| 分类 | 剩余项 | 数量 |
|------|--------|------|
| 🔴 需**你/外部方**给信息或决策 | B1、B2、B3、B4、B5、B6′、**G7**、C1、C2、C3、C4、C5 | 13 |
| 🟡 **我能做**但未排期 | **F4**、E7、G6、F1（并入你的存量扫描） | 4 |
| 🚫 有意不做 | E9、D3 | 2 |

### 🔴 一、需你 / 外部方提供信息或决策（我做不了）

| 项 | 缺什么 | 解锁后要做什么 |
|----|--------|----------------|
| **B3** | APK 签名证书：**自建 / 第三方托管**？provisioning 谁负责 | OTA 验签代码已落地（`ApkSignatureVerifier` + V317），剩证书链决策 |
| **B4** | 6 个功能开关**开哪些** | 全已实现且可配置，默认关是纪律。建议开 `adBannerEnabled`/`wxAdEnabled`（V316 已开）、`payChannelSelectEnabled`、`orderSearchEnabled` |
| **B5** | 流量主 **adunit- 广告位 ID** | 需先在腾讯流量主后台真实开通并建广告位（外部动作） |
| **B6′** | 三套柜机**先上哪套**？（已取证：三套协议不同构） | 🔴 另发现 **yichu2 有现金/投币路径**（`Yichu2CashPayService`），我们没有 ⇒ 若上 yichu2 需你决策是否补现金 |
| **G7** | 正式发布密钥生成 | 🔴 **安全敏感**：建议独立会话 + 离线操作 + 加密备份，**不入仓库** |
| **B1 / B2** | 采购决策（同 SKU 多供应商 ⇒ A 方案不可行，已定走 C） | 阻塞 E3（供应商对账）、E4（盘亏联动应付） |
| **C1** | 将邑中间件对接文档 | 对接面已备好（`SkuDeltaCalc`），等对方文档 |
| **C2** | 微信/支付宝**商户号资质** | 阻塞支付渠道接入；CB-010 已证「智慧零售」间连不含无人柜 |
| **C3** | 硬件协议文档 | 阻塞货道电机自检、主板温度读取、压缩机控温（D2 真控温也压在这里） |
| **C4** | 将邑是否提供**开门/关门两帧识别** | 只给最终 SKU 列表则两帧差分用不上 |
| **C5** | 第三方实名核验接口文档 | 🟡 **优先级已降**：V326 已移除消费者实名入口，仅 B 端提现仍用该通道 |

### 🟡 二、我能做但未排期（不需任何外部输入）

| 项 | 现状 | 建议 |
|----|------|------|
| **F4** `PROJECT-REFERENCE.md` | 🔴 仍未做（随 2026-10-04 搬家丢失）。**不需外部输入，纯粹是没做** | 内容量大（环境/构建/OpenAPI/取证），**建议排在你的存量全量扫描之后** —— 那时材料最全 |
| **E7** 仓库月结接财务 | 已取证：`WarehouseMonthlyCloseService` 逐 SKU 算出期初/进/出/应盘/实盘/差异，但**不落库**（无月结单实体）、`gap` **不生成应付**、无审批锁账 | ⚠️ 动资金。按「只补一半最危险」，要做必须**一次做全**：单据表 + 应付生成 + 审批锁账 + admin 入口 + 反向冲销。否则会造出「出表不入账」的半成品 |
| **G6** OTA 真机验证 | 编译已通（`compileMockDebugKotlin` + 单测绿），**未装机** | 需真机：验「旧包可升级」+「自签包被拒」两条 |
| **F1** 缺口逐条核实 | 已核 12 项，**命中率 30%**（4 项真缺口） | 建议并入你说的存量全量扫描（台账 S1–S12 清单） |

### 🚫 三、有意不做

| 项 | 理由 |
|----|------|
| **E9** 分享/邀请裂变 | 用户 2026-10-07 决定不做。取证：`Member.inviteCode` 等字段完整但无入口 |
| **D3** 抽奖/互动游戏 | 优先级低 |

### ✅ 四、本轮已完成（2026-10-07）

**竞品对照三件套**（新增铁律 35/36）：规则 `.cursor/rules/competitor-benchmark-before-code.mdc` +
台账 `docs/COMPETITOR_BENCHMARK.md`（14 条 / 71 URL / 55 家主体 / 每条 ≥3 家且 ≥2 家开门柜同行）+
门禁 `check:competitor-benchmark`（负向验证 台账侧 13/13 + 迁移侧 8/8）

**V325**：撤销 V323 实名通道（CB-002 定案，forward-only 不改历史迁移）｜自动短信催缴（CB-003）｜
欠款惩罚阶梯 + 还清自愈（CB-011）｜阶梯接入开门拦截

**V326**：消费者去实名（CB-002/CB-014）—— 删 `UserValidationService` 硬拦开门 +
小程序两步改单步。**商家侧实名一分未动**（V321 提现实名校验保留且有真实来源）

**F3** 活文档 §2 校准 ｜ **B6** 三套柜机协议取证 ｜ **B8** 实名口径关闭

### 🔴 五、上线前必须有人显式做的三件事（不算欠账，但会静默失效）

1. **发短信需三门同时开**：`NOTIFY_SMS_ENABLED=true` ＋ 模板 `channels` 含 `SMS`（V325 已 seed）
   ＋ `aicabinet.auth.sms.provider` 已配置。⚠️ 运营台**暂无**该开关图形入口。
2. **MOCK 通道不可上生产**（`recognition` 默认 mock、`支付分/实名` 未配置）。
3. **在途订单上限是按商户配置项，不是硬编码**：`UserValidationService:145` 读 `MerchantOpsConfig.maxInflightOrders`，且 `CompetitiveGapService:227` 会把它钳制在 **0–2** ⇒ **实际最多 2 笔在途**，勿当配额调大。

---
---

## ✅ A. 环境阻塞 —— **已于 2026-10-07 14:51 全部解除**

容器与镜像被清空后重建了 Postgres，并借机做了一件**更强**的事：
**让 Flyway 从空库全量重放全部 311 个迁移**（不是只跑 V312）。

| # | 项 | 结论 |
|---|---|---|
| A1 | **V312 迁移实跑** | ✅ `Successfully applied 311 migrations to schema "public", now at version v312`；158 张表、**0 失败** |
| A2 | **V312 行为验证** | ✅ 三层全过：新列默认 NULL（＝未分类，**非 OTHER**）、`NORMAL_SHRINKAGE` 可写入、`VARCHAR(24)` 超长被拒 |
| A3 | 后续迁移实跑 | ✅ 环境已恢复 |

### 顺带得到的两个更强结论
1. **311 个迁移从零全量重放成功** ⇒ 迁移链**无断裂、无相互依赖缺失**。
   这比单独跑 V312 强得多 —— V312 依赖的 `warehouse_stocktake_line` 是 V3x 建的，
   只跑 V312 证明不了它。
2. 🔴 **V310/V311/V312 的列全部实测存在**（查 `information_schema.columns`，
   **不采信日志**）：`ad_revenue_daily.income_cents` ✓、
   `inventory_write_off` 的 4 个新列 ✓、`warehouse_stocktake_line.diff_reason` ✓。

⚠️ 代价：本地旧种子数据随 `pgdata` 卷删除而丢失，需重跑种子。
不影响代码结论（迁移链已全量重放证明）。

### 重启 PostgreSQL 的配方（下次直接复用）
```powershell
docker compose -f infra/docker-compose.yml up -d postgres
# 等 ready
for i in $(seq 1 20); do docker exec ai-cabinet-postgres-1 pg_isready -U aicabinet -d aicabinet; done
# 触发 Flyway 全量重放（**先 install common-core**）
mvn -pl services/common/common-core install -DskipTests -o
mvn -pl services/trade-service spring-boot:run
```
配环境变量：`SPRING_DATASOURCE_URL/ USERNAME / PASSWORD` + `SEED_ENV=local`，
并加 `-Dspring-boot.run.arguments=--spring.main.web-application-type=none`。

⚠️ 迁移完成后应用会因 `RedisConnectionException` 失败 —— **这是预期的**（Redis 未起），
**不影响迁移结论**：Flyway 在 Redis 依赖之前已完成。

**三个踩过的坑**：
1. PowerShell 5.1 的 `Out-File -Encoding` **只认 `Unicode`**，不认 `utf16`。
2. `mvn -pl services/trade-service **-am** spring-boot:run` 会跑在**父 pom** 上 →
   报「找不到 mainClass」。`-am` 只能用于 `test`/`install`，**不能用于 `spring-boot:run`**。
3. 造 fixture 前**必须先 `\d 表名` 看真实 schema** —— 我凭记忆写
   `stocktake_name` 报「column does not exist」，实际是 `stocktake_no`。
   又写 `sku_catalog(sku_id, sku_name)` 报 `price_cents` 非空。
   ⇒ 与 MEMORY 里那条「包名/字段要从 jar 或实际数据读」同源。

---

## 🔴 B. 需业务/产品决策（我无法从代码推断）

| # | 项 | 需要你回答 | 影响 |
|---|---|---|---|
| **B1** | ~~缺口 #4 走 C 还是 A~~ | ✅ **已定：走 C**（用户答「同 SKU 多供应商」⇒ A 不成立）。台账端点已落地 `f70e3e8d`；UI 视图未做 | UI 可后补 |
| **B2** | ~~批次→采购单追溯是否可行~~ | ✅ **已答：同 SKU 多供应商** ⇒ A 不成立。老系统取证也证实它没这个概念 | 已永久走 C |
| **B3** | **APK 凭据与证书体系** | 正式签名证书怎么走（自建 / 第三方托管）？provisioning 流程谁负责？OTA 验签要不要强制？ | 🔴 **唯一「不做就会上线暴雷」的项**：debug key 签名无法上架、OTA 不验签等于允许任意 APK 覆盖 |
| **B4** | **6 个功能开关是否打开** | `orderSearchEnabled` / `couponEntryEnabled` / `productDetailEnabled` / `chartsEnabled` / `payChannelSelectEnabled` / `adBannerEnabled`+`wxAdEnabled` | 已实现，默认关是纪律。其中 2 项因流量主路线变成「要上线就得开」 |
| **B5** | **流量主广告位 ID** | 需先开通流量主并创建广告位，`adunit-` 开头的 ID 填到运营台「系统配置 → 扩展功能」 | 不填则广告位不渲染；**必须真实开通后实测** |
| **B6** | **柜机型号确认** |新柜是 `chzh8` / `jinyu2` / `yichu2` 哪个厂商的？ | ✅ **已取证（2026-10-07 22:4x）**：三套**协议不同构**（ASCII 文本帧 / BCD+CMD / 二进制+校验和），**无法靠配置通吃** ⇒ 需 `CabinetProtocol` 抽象 + 三实现。详见下方 §22:4x |

---

## 🔴 C. 阻塞于外部（等对方）

| # | 项 | 阻塞原因 | 备注 |
|---|---|---|---|
| C1 | **将邑中间件对接** | 等对方文档 | 对接面已备好：`SkuDeltaCalculator`（已解耦）+ 云端 `/internal/v1/vision/edge-results`；采购三问见 `docs/..._2026-10-06.md` §12.5 |
| C2 | **支付认证**（微信/支付宝商户号） | 缺商户资质 | 阻塞：渠道账单对账、查单补偿、提现实名/KYB |
| C3 | **硬件协议文档** | 缺 | 阻塞：货道电机自检、主板温度读取、压缩机控温 |
| C4 | **将邑：是否提供开门/关门两帧识别** | 采购问题 | 只给最终 SKU 列表则两帧差分用不上 |

---

## ⚪ D. 明确不做（有理由，不是欠债）

| # | 项 | 理由 |
|---|---|---|
| D1 | 仓库侧「核销/报废」独立入口 | `InventoryOpsService.writeOff` 实现完整，只是入口 `requireDevice` 只支持设备侧。**可低成本扩展**（应做，但归入 E 类待排期而非「不做」） |
| D2 | `SET_TARGET_TEMP` 真控温 | 🟡 **软件侧已实现且为刻意设计**（2026-10-07 取证）：`MqttCommandPublisher:88` 下发 → `OpsCommandExecutor.handleSetTargetTemp:128` 校验(-40~40)+落盘 SharedPreferences，**回执诚实写明「温控执行器未接入，尚未实际控温」**（不 falsely 回 success）。🔴 真控温仍缺硬件（压缩机/继电器），阻塞于 **C3 硬件协议文档** —— 这是硬件依赖，非软件欠债 |
| D3 | 抽奖/互动游戏 | 优先级低（E 类） |

---

## 📋 E. 可做但未排期（按依赖顺序）

| # | 项 | 前置 | 成本 |
|---|---|---|---|
| E1 | 缺口 #3 跨仓调拨在途损耗 | ✅ **已完成（含 UI）**：V314 后端 `a2a5f4ce` + admin 登记 `39fe2e6c` | — |
| E2 | 缺口 #5 仓库侧核销 | ✅ **已完成（含 UI）**：V313 后端 `0380661c` + admin 入口 `6199ca1e` | — |
| E3 | 缺口 #8 供应商对账单 | **依赖 B1/B2** | 中 |
| E4 | 缺口 #4 盘亏联动应付 | **依赖 B1/B2** | 中 |
| E4a | 缺口 #4 的 C 方案：待索赔台账 | ✅ **已完成（后端 + UI）**：端点 `f70e3e8d` + 台账页 `WriteOffClaimLedgerView.vue`（含按责任方汇总） | — |
| E5 | 缺口 #6 近效期预警（仓库侧） | ✅ **已完成**：V320 `findExpiringPage` + 端点 `GET /api/v2/ops/admin/warehouse/expiry-alerts` + 独立页 `WarehouseExpiryAlertsView.vue`（含剩余天数/紧急度）。**刻意不物化预警任务表**（仓库侧无动作要驱动） | — |
| E6 | 缺口 #7 采购退货原因分类 / 残次品处置 | ✅ **已完成（后端+UI）**：V318 三列（`reason_category`/`responsible_party`/`defective_flag`）+ 退货弹窗录入 | — |
| E7 | 缺口 #9 仓库月结接财务结算 | 无 | 高 |
| E8 | 缺口 #4 广告收入入账（切片 4，**动资金**） | 需先开通流量主（B5） | 中 |
| E9 | 分享/邀请裂变 | 🚫 **用户决定不做**（2026-10-07）。取证：`Member.inviteCode`/`invitedBy` **零消费者**（仅域类自身）⇒ 是死字段，**没有运行中的功能需要「关闭」**。若将来要做，从零设计（邀请关系表 + 奖励规则） | — |
| E10 | 券可用范围 | ✅ **已完成**（V319）：`scope_type`三型 + 商户/柜机集合 + **接入唯一拦截点** `CouponScopeValidator` + admin 建券表单 | — |

---

## 🔴 F. 清单本身的欠账（最容易被忽略）

| # | 项 | 说明 |
|---|---|---|
| **F1** | 剩余缺口未逐条核实 |🟡 **进行中（2026-10-07 19:10）** —— 已核实 **12 项**，**3 项文档不成立/判断有误**（30% 命中率，见下方 §I） |
| F2 | 缺口 #11「无提现实名/KYB 校验」 | ✅ **已完成**（V321）：`WithdrawEligibilityService` 卡在**打款之前**（商户主体资质 + 申请人实名 + 线长实名）。**不做**「实名落库」—— 本地不存完整证件号是**有意的隐私设计** | — |
| F3 | `docs/PROJECT_KNOWLEDGE.md` §2「仓库现状速览」已过期 | ✅ **已完成（2026-10-07 22:4x）**：当日实测回填（迁移 321/最新 V325、Controller 85、单测 333/全量 1626 tests、admin 视图 77、lessons L-223），并与 `.workbuddy/memory/MEMORY.md` 硬事实表**同步**；表内加了「怎么测的」命令 |
| F4 | `PROJECT-REFERENCE.md` 外置手册未重建 | 🔴 **仍未做**：随 2026-10-04 搬家丢失。**未重建 = 大量环境/构建/取证配方只存在于 MEMORY 与本文件**，换会话/换人成本高。属「可随时做」但**内容量大**，建议排在存量扫描之后 |
| F5 | `EDGE-ANDROID-GRADLE.md` 未建 | ✅ **实际已做（2026-10-06）**：配方落在 [`edge/android-app/docs/ANDROID_BUILD.md`](../edge/android-app/docs/ANDROID_BUILD.md)（+ `EDGE_VISION_INFERENCE.md`）。**本条状态标错，本次更正** —— 不必再建 |

---

## 🟡 G. 本轮进行中（2026-10-07 14:55 V313 遗留）

| # | 项 | 状态 | 解锁动作 |
|---|---|---|---|
| **G4** | ~~admin 收货弹窗未接损耗登记~~ | ✅ **已完成**（`39fe2e6c`）：损耗列**只读自动推导**，运营只填实收 | — |
| **G1** | **V313 全量测试** | ✅ 已过：clean 全量 **1534/0**（提交 `0380661c` 后补跑并推送） | — |
| **G2** | **`WarehouseService` 新增注入的循环依赖** | ✅ **已实证排除**：全量 1534/0，日志中 `BeanCurrentlyInCreation`/`circular reference` **零命中**，且有 **7 个 `@SpringBootTest` 真建过完整上下文** | — |
| **G3** | 仓库侧报损的 admin UI 入口 | ✅ **已完成**（`6199ca1e`）：独立异步组件 `WarehouseWriteOffDialog.vue` + `WarehouseView` 勾选触发；chunk 126.4KB ≤ 150KB | — |

⚠️ **G2 是本轮最需要盯的一项**：`InventoryOpsService` 现在依赖 `WarehouseService`，
若后者（直接或间接）依赖前者 ⇒ 启动即失败。
**未验证「能启动」就等于没验证**（编译绿 ≠ 上下文能建起来）。

---


---

## 🔴 G. Android 侧遗留（G5-G7，2026-10-07 17:15 排查后更新）

| # | 项 | 状态 | 解锁动作 |
|---|---|---|---|
| **G5** | `edge/android-app` Android 本地编译 | ✅ **2026-10-07 17:20 已打通**（SDK 装好，V317 编译+单测通过）。配方见 `edge/android-app/docs/ANDROID_BUILD.md` | — |
| **G6** | OTA 签名校验**未装机验证** | 🔴 **仍未做**（编译已通，但没真机） | ① 旧 APK 能正常升级 ② **自签包被拒绝** |
| **G7** | 正式**发布密钥**未生成 | ⬜ 待做（B3a） | 离线生成 + 加密备份，**不入仓库**；每次发布记录证书指纹 |

### G5 排查结论（2026-10-07 17:10-17:20）—— ✅ 已打通
**排障三层，一层一层排**：
1. 无 gradle wrapper/ 系统无 gradle ⇒ **本地已有缓存 Gradle 8.9**
   （证据：项目 `.gradle/8.9` 存在 ⇒ 之前构建过）
2. 试下载 gradle 8.7 失败（`curl: (7) CONNECT tunnel failed, response 502`）
   ⇒ 放弃下载，用缓存
3. `SDK location not found` ⇒ **`dl.google.com` 直连可达（200）**
   ⇒ 装 commandlinetools + platform 34 / build-tools 34.0.0

**结果**：`:app:compileMockDebugKotlin` BUILD SUCCESSFUL；
`:app:testMockDebugUnitTest` BUILD SUCCESSFUL（surefire XML 48+ testcase）。

**完整配方已写进 `edge/android-app/docs/ANDROID_BUILD.md`**（含 SDK 首次安装步骤）。

### 🔴 这次排障的真正收获：人工核对 Android 代码 = 没有证据
V317 写完我只做了人工核对（import 齐、枚举存在、`@Suppress` 位置对、同 package 无需 import），
**自认为没问题**。编译一跑就抓到真bug：
```
// ❌ 我写的
installed.any { it.contentEquals(cand) }
// 🔴 installed: List<Signature>，而 contentEquals 是 Array<Byte> 的扩展函数
```

🔴 **更危险的连带问题**：`Signature` 是 Java 类、**不实现 `equals`** ——
若图省事写 `it == cand` 会**永远为 false** ⇒ **所有 OTA 都会被拒**。
这不是「编译不过」的小瑕疵，而是**能静默把功能打死**的坑。

⇒ Android 侧代码**没有编译证据就不算完成**；「未验证」要显式记进待完成。

## 🔴 H. 迁移可重入性（2026-10-07 18:41发现并已修）

**症状**：为抓 OpenAPI spec 起后端 → Flyway 报
`Script V313__write_off_warehouse_scope.sql failed`
→ `constraint "ck_write_off_location" ... already exists`，**整个上下文起不来**。

**根因**：`ADD CONSTRAINT` 前没有 `DROP CONSTRAINT IF EXISTS`。
🔴 PG **不支持** `ADD CONSTRAINT IF NOT EXISTS` ⇒ 手动跑过 psql（Flyway 无记录）
后再从零重放，**必然**撞车。
⚠️ **这不是本地环境问题** —— 任何走 Flyway 的干净库部署都会炸。

**已修**：
- `V313`：`ck_write_off_location` 前置 DROP
- `V314`：三个 CHECK（`chk_wh_transfer_loss` / `_nonneg` / `_reason`）各前置 DROP
- `V319`：本来就有 `DROP CONSTRAINT IF EXISTS`（写的时候就对了）

**验证**：V313 / V314 / V319 **各连续跑两次**，均无 error ⇒幂等成立。
（判据是「连跑两次」而不是「跑一次成功」—— 后者证明不了可重入。）

⇒ 已写进**跨项目** `MEMORY.md`（不限本项目）。

### 抓 OpenAPI spec 的完整配方（下次直接用）

1. `docker compose -f infra/docker-compose.yml up -d postgres redis`
   🔴 Redis 密码是 **`devredis`**（`infra/docker-compose.yml:32` `${REDIS_PASSWORD:-devredis}`），
   设空串会报 `RedisConnectionException`
2. `mvn -pl services/common/common-core install -DskipTests`（**必须**）
   ⚠️ 不装则 trade-service 从本地仓库吃**旧 jar** ⇒ spec 里**缺新字段**，
   而生成脚本照样「成功」
3. `mvn -pl services/trade-service spring-boot:run "-Dspring-boot.run.arguments=--server.port=8080"`
4. `OPENAPI_IGNORE_CACHE=1 OPENAPI_URL=http://127.0.0.1:8080/v3/api-docs node scripts/gen-openapi-types.mjs`
   加 `NO_PROXY=127.0.0.1,localhost`，否则本地请求被代理截走返回 **502**
5. **判据**：`node -e` 读 `.tmp/live-openapi.json` 确认新字段在

🔴 **本轮踩的两个坑**（都写进跨项目记忆）：
- `gen-openapi-types.mjs` **没有 `--fresh` 参数**（我凭记忆用的）⇒ 静默吃10/6 的旧缓存。
  真名是**环境变量 `OPENAPI_IGNORE_CACHE=1`**（`:35` 注释里写着，参数名要读源码）
- 🔴「脚本 exit 0」**不能证明产物更新**。症状是「新字段不在产物里」。
  判据必须落在产物内容上。

---

## I. F1 逐条核实（2026-10-07 19:00-19:10）

### 命中率仍约 30%：又核实 2 项，1 项与文档不符
已核实 **12 项**（仓储 9 + 资金 1 + E5 + F2），其中**3 项文档不成立/判断有误**：
⑥ 近效期端点「不成立」、`reason` 「自由文本」判断有误、E9 分享裂变**本来就没开**（死字段）。

### ① 实名链路的真相：**「委托第三方校验，本地不留证件号」是设计，不是漏了**

`AccountService:166-168` 只 `user.setVerified(true)` 不写`user_realname_auth` 表
—— 我原以为这是「双重缺口」。核实后发现**这是有意设计**：

```
VerifyIdentityRequest(realName, idCardLast4)   ← 接口只收「姓名 + 证件后 4 位」
IdentityVerifyClient.verify()
  → POST 第三方 baseUrl { realName, idCardLast4 }   ← 委托第三方做实名校验
  → 本地只留 user.verified 布尔位
```

⇒ **本地从不采集完整证件号**（`user_realname_auth.id_card_number` 永远不会有值）。
所以「实名信息落库」**不是**待补的缺口，而是**要维持的隐私设计**。
🔴 **更正**：我原先写的「补实名落库」会造成**倒退**——为做风控而把完整证件号
存进自己库，反而扩大了泄露面。**保持现状是对的。**

真正缺的只有一条：**提现链路不看 `verified`**。

### ② 🔴 唯一的真缺口：提现不校验实名

| 提现入口 | 校验了什么 | 实名 |
|---|---|---|
| `MerchantWithdrawService.merchantApply` | 商户存在、钱包锁、限额、收款账户 | 🔴 无 |
| `LineWithdrawService`（线长） | 同上 | 🔴 无 |

`user.verified` 全仓只在 `UserValidationService:81` 用作**开门门禁**。

⚠️ **风险不是「冒名提现」**（提现是打到商户自己的结算账户，冒名者拿不到钱），
而是**「未完成实名的商户主体能签发提现」** ⇒ 合规上缺少 KYC 留痕。

⇒ 落法只需**一段**：提现申请时校验「申请人已实名」+「商户有法人与营业执照」，
缺失时**给可读原因**（缺哪一项）。**不需要**落库、**不需要**存量迁移。

**⚠️ 存量问题不成立**（2026-10-07 用户澄清）：
系统**尚未上线**，不存在「没实名的老用户」。⇒ 直接硬拦即可，
**不要设计宽限期**—— 那是为一个不存在的问题写代码。
（我原先把「0 行 `user_realname_auth`」当成「存量未实名」是**误读**：
那张表本来就永远是 0 行。）

### I.3 E5「仓库侧近效期预警」—— **真缺**（不是文档说的「有端点」）
- 系统唯一的 `expiryAlerts` 走 `PullOffTask`，而 `PullOffTask` 字段是
  `deviceId`/`lotId` ⇒ **只覆盖设备侧**
- `WarehouseService` 里`expiryDate` **只用于存取**，**没有任何筛选/预警查询**
- ✅ 数据基础齐备：`warehouse_inventory.expiry_date` 有值 +索引
  `idx_wh_inv_expiry (warehouse_id, expiry_date)` 已存在（等值列在前，顺序正确）

⇒ **成本比预想低**：不需要新表（仓库侧只是「提醒哪些批次快到期」，
**没有动作要驱动**，物化任务表反而会引入「过期了但任务还开着」的清理问题）。

### I.4 消费者小程序的实名逻辑核实（用户问「对吗」）

**结论：整体正确，2 个要点已处理/已确认**（与 V321 提现门禁**方向一致、不冲突**：
一个管「消费开门」、一个管「资金出账」）。

**✅ 对的部分（有据可查）**
| 项 | 证据 |
|---|---|
| **实名在开门前完成** | `index.vue:1355 ensureCanOpenDoor()` 先查账户，未达标就弹 `OpenPrepDrawer` |
| **抽屉是真阻塞**（非可选提示） | `prepResolve` + `new Promise<boolean>`（`:1361`）⇒ `onPrepDone(true)`/`onPrepCancel(false)` 决定后续 |
| **两步引导清晰** | 抽屉有 step 视觉：`① 实名 → ② 免密支付`（`open-prep-drawer.vue:9-18`） |
| **格式校验前端也做** | `verify.vue:214-222`：姓名 ≥2 字、证件后 4 位须 `/^\d{4}$/` |
| 🔴 **前后端判定一致** | 前端 `isPayReady(...)`；后端 `UserValidationService:86-99` **同逻辑**（免密优先、余额兜底）⇒ 不会「前端放行后端拒绝」或反之 |
| **兜底在后端** | `UserValidationService:81` 硬拦未实名 ⇒ 前端被绕过（改包/直接调 API）仍拦得住 |
| 隐私说明存在 | 两处都有「信息仅用于本柜购物核验」 |

**① 🔴 实名无过期/复核机制 —— 确认为「有意取舍」，不改**
后端只存 `user.verified` 布尔位，无「谁何时实名」「证件是否变更」
⇒ **用户改了身份证/姓名后系统无从感知**。
这是与 V321「不存完整证件号」一致的**隐私优先取舍**。
🔴 **不建议**改成存证件号（**会扩大泄露面**）。
真要复核只能靠**第三方自己留档**，本地不留。

**② 🔴 提现门禁提示语指错地方（已修）**
V321 上线后发现：提现是**商户侧**操作，而原提示写「请先在『我的』完成实名」
——「我的」是**C 端消费者小程序**页面，**商户管理员未必有** ⇒ 指到一个去不了的页面
（最坏情况：反复点、反复看到同一句错）。

已改为双通道指路：
> 申请人尚未完成实名，无法发起提现。**请改用已完成实名的账号发起**，
> 或由本人先在消费者小程序「我的」完成实名认证

**负向对照**：把文案改回旧版 ⇒ `unverifiedMessage_pointsSomewhereReachable` 恰好红，
错误信息里能看到旧的错误文案。判据有效。

### I.5 核实：「小程序的实名和后端的实名怎么确认是真的」

**答案：我们不自己确认，委托第三方** —— 但核实查出一个**真缺陷并已修**。

#### 完整链路
```
小程序 verify.vue / open-prep-drawer.vue
  → POST /verify { realName, idCardLast4 }      ← 🔴 只传「姓名 + 证件后 4 位」
  → AccountService.verifyIdentity
  → IdentityVerifyClient.verify(...)
      ├─ if (securityProperties.mockEnabled()) return;    ← 跳过校验
      ├─ if (!isConfigured()) 503                          ← baseUrl 空则拒（fail-loud）
      └─ POST 第三方 baseUrl + X-Api-Key
           └─ IdentityVerifyResponse{ matched, success, passed } → isOk()
  → user.setVerified(true)                    ← 🔴 只留布尔位，无留痕
```

#### 🔴 已修的真缺陷：`isOk()` 判据过宽（且此前**零测试覆盖**）
旧代码：`passed || matched || **success**`
🔴 多数第三方 API 里 `success` 语义是「**请求处理成功**」而非「核验通过」。
若第三方返回 `{success:true, passed:false}`（请求成功但核验不通过）
⇒ **未实名用户被标记为已实名** ⇒ 可开门、可提现。

改为**保守判定（宁可拒不可放）**：
1. 只认 `passed` / `matched` 为 true；
2. 🔴 `success` **单独为 true 一律不算通过**；
3. 🔴 `passed` 显式 false ⇒ **一律拒**（即使 `matched=true`）——
   测试抓到这一条，是写完实现后跑测试才发现的；
4. 全部缺失 ⇒ 拒（不猜「没报错就是过」）。

**负向对照**：恢复旧判据 ⇒ **恰好 2 例红**
（`successAlone_isRejected` / `successTrueMatchedFalse_isRejected`）。

同时加 `describe()` 字段级日志（区分「明确不通过」与「字段全缺」——
排查方向完全不同），且**不打姓名/证件号**（PII 禁止进日志，有测试钉住）。

#### ✅ 核实后确认：防线是真的（我一度误判）
`AICABINET_MOCK_ENABLED` 默认**true**（`application.yml:82`、
`docker-compose.apps.yml:119` 也是 `:-true`）⇒ 第一反应是「生产会跳过实名校验」。

**核实后不成立**：`ProductionStartupValidator:118-121`
`rejectMockFlagsInStrictProfile()` 第一条就是
`if (securityProperties.mockEnabled()) throw IllegalStateException(...)`
⇒ **production/staging 启动即失败**。它同时还拦两套提现 mock 与对账 mock。
⚠️ 教训：默认值看起来危险，**要查到「谁在拦它」再下结论**。

#### ⚠️ 三个仍然存在的弱项（**取舍，不是 bug**）
| 弱项 | 说明 |
|---|---|
| 🔴 **只传证件后 4 位** | 同名 + 末 4 位碰撞概率不低 ⇒ 第三方也只能做**弱核验**。这是**隐私 ↔ 核验强度**的取舍 |
| 🔴 **无核验留痕** | 只存 `verified` 布尔位，无「谁何时通过哪渠道核验」⇒ 改包/重放可为任意姓名+后4位拿到 `verified=true`。缓解：接口需登录态；但**无「同一用户只能实名一次/变更需重验」限制** |
| 🔴 **第三方字段契约未取证** | 改判据的前提是「第三方的核验通过落在 `passed`/`matched` 上」⇒ **必须拿到第三方接口文档确认**（见 C5） |

#### 🔴 新增待办
| # | 项 | 状态 |
|---|---|---|
| **C5** | **第三方实名核验接口文档**（字段语义 / SLA / 核验强度） | 🔴 **未取**。拿到后需复核 `isOk()` 判据是否符合契约 |

### I.6 🔴 用户追问「我们没对接第三方实名校验吧？微信授权不就能实名？」

####核实结论 1：第三方实名字段**从未配置**（是占位）
- `application.yml:96-98`：`base-url: ${IDENTITY_VERIFY_BASE_URL:}`（默认**空**）
- `infra/.env.example:19-20`：两项**都是注释掉的**
- `isConfigured()` = `baseUrl != null && !baseUrl.isBlank()`
⇒ **第三方实名字段是占位，从未接入。**

#### 🔴🔴 核实结论 2：已修的缺陷 —— mock 环境「随便填就实名成功」
`AccountService.doVerifyIdentity` 原代码：
```java
identityVerifyClient.verify(name, idCardLast4);   // 内部若 mock → 直接 return
user.setVerified(true);                            // 🔴 无条件置真
```
而 `AICABINET_MOCK_ENABLED` 默认 **true** ⇒ 开发/staging 里
**输入任意姓名 + 4 位数字即`verified=true`** ⇒ 可开通免密支付 ⇒ 🔴 **可白嫖**。
⚠️ staging 恰恰是做 soak 测试的环境，风险不是假想。
（生产被 `ProductionStartupValidator:119` 拦下，不会带 mock 上线。）

**V322 修法**：`verify` 改返回 `VerifyOutcome{verified, mock}`，
`trusted() = verified && !mock`；调用方**只在 `trusted()` 时**才 `setVerified(true)`，
mock 时只记姓名 + `warn` 日志（不打PII）。
**负向对照**：`trusted()` 不再区分 mock ⇒ `mockPass_isNotTrusted` 恰好红。

#### ✅ 核实结论 3：用户提的「微信授权即实名」——**方向对，但有前置**
| 方案 | 机制 | 成本 | 前置 |
|---|---|---|---|
| **A 微信官方「实名信息校验」** | 用户填姓名 + **完整身份证号** → 跳「微信城市服务」授权页（固定 appid `wx308bd2aeb83d3345`）→ 回跳带 `code` → 后端调 `intp/realname/checkrealnameinfo` | **免费** | 🔴 **须用户已在微信支付实名**；否则 `verify_openid=V_OP_NA` 直接终止 |
| **B 第三方人脸核身** | `wx.startFacialRecognitionVerify` 活体 + 第三方比对 | 0.2~0.5 元/次 | 无前置，适用强监管 |

🔴 **关键差异**：A 是**「核对」微信支付已有的实名，不能「获取」** ——
微信官方明确「**实名信息是获取不到的，只能获取昵称与绑定手机号**」。
⚠️ 当前设计「只传后 4 位」**连 A 都走不通**（微信要完整 `cred_id`）。

⇒ **需你拍板（B8）**：
| 选项 | 成本 | 覆盖未实名用户 | 我们要改的 |
|---|---|---|---|
| 维持现状 | 0 | ✅ | 🔴 等于没实名 |
| **A 微信官方** | 免费 | ❌ 仅已实名微信支付用户 | 收完整证件号（**转发不存**）+ 跳授权页 + 调微信接口 |
| B 第三方人脸 | 按次 | ✅ | 接 SDK |
| **A + B**（行业标准：轻用 A、重用 B） | 按次 | ✅ | A 为主 + B 兜底 |

### I.7 老系统（easygo）取证：走的是**第四条路（法大大 CA）**，不能照抄

用户定了「A+B 组合」后，问「我们的老代码呢」。取证结果：**老系统不用 A/B**，
它用**法大大电子合同** —— 实名与签约一体化。

| 仓库 | 命中 | 结论 |
|---|---|---|
| `ego-automat` | `EgoOptUserAccountDetail.java:13-37` | 🔴 存了**完整身份证号 + 三张证件照 + 银行卡号** |
| `ego-automat` | `EContractService.java:95getCustomerCA(customerName, customerMobile, customerEmail, customerIdCard, customerIdentType)` | 法大大做 CA 实名 → 拿 `customerId` → 签合同（:138/:174/:241） |
| `ego-automat` | `com.fadada:sdk:20210901`（`pom.xml:252`） | SDK 依赖 |
| `ego-automat-android` | 🔴 **零实名实现**；grep 命中全是噪音（`com.facebook.rebound.Spring` 动画库、`ShoppingListener` 接口名含 "ping"） | 老安卓端面向**已签约的运营商**，无 C 端实名 |

#### 🔴 为什么不照抄（**必读，避免下一个人照搬**）
1. **合规姿态相反**：我们 V321 定的「**不存完整证件号**」是硬约束（不落地= 缩小泄露面）。
   老系统存 `idNumber` + `idCardFrontIcon`/`idCardReverseIcon`/`idCardHandheldIcon` + `bankCardNumber`
   ⇒ 今天是**风险资产**（泄露即完整身份+银行卡）。
2. **目标不同**：老系统是「**运营商签约 + 保证金**」（`clientMachinesGuaranteeFeeYuan`）业务，
   签约是刚需 ⇒ 值得上法大大。我们是「C 端买饮料」⇒ 🔴 签约对 C 端无价值，
   上法大大是**为不存在的问题付钱**。
3. **成本不匹配**：法大大按签约计费，对每个 C 端用户走一次 CA 认证不合理。

#### 四条路线全景
| 路线 | 覆盖未实名用户 | 我们存什么 | 成本 | 老系统用过 |
|---|---|---|---|---|
| 现状（自填后4位） | ✅ | 姓名 | 0 | — |
| **A 微信官方校验** | ❌ 仅已实名微信支付用户 | 转发不存 | 免费 | ❌ |
| **B 第三方人脸** | ✅ | **可完全不存**（交第三方） | 0.2~0.5/次 | ❌ |
| C 法大大 CA | ✅ | 🔴 完整证件号+证件照+卡号 | 按签约计 | ✅ |
| **A+B（已定）** | ✅ | 转发不存 | 按次 | — |

⚠️ **B 的输入正是那三张证件照 + 证件号** ⇒ 我们**不需要自己存**，
交给第三方核验、本地只存它返回的 `verified` 即可。

### ✅ B8 已定（2026-10-07 用户）：走 **A + B 组合**

| # | 问 |
|---|---|
| **B8** | ~~实名走哪条？A（微信官方）/ B（第三方人脸）/ A+B 组合~~ → ✅ **已关闭（2026-10-07）**：用户拍板 **C 端不做实名**（竞品五家 + 微信支付分协议 2.3 双重取证，见 `docs/COMPETITOR_BENCHMARK.md` CB-014），V326 已移除消费者实名；**商家侧 B 端实名保留**（提现合规）。第三方人脸通道**不接入**，`IdentityVerifyClient` 保持未配置 |

---

## 维护纪律

1. **解锁一条就划掉一条**，并在 `docs/BUSINESS_GAP_ANALYSIS_2026-10-06.md` §11 的对照表更新状态。
2. **新发现的阻塞项加进来**，注明是「谁说不能做」还是「环境不具备」——
   前者可追溯，后者会随环境恢复而消失。
3. **未实跑的迁移不算完成**（A1/A2）。
4. **未启动验证的 Spring Bean 改动不算完成**（G2）—— 编译绿不等于上下文能建起来。
5. **F1 是元问题**：如果 F1 做完发现清单大面积过期，本文件需整体重写。
---

## 📌 2026-10-07 16:10 追加：老系统取证 ⇒ B2 已答，A/C 方案定型

### B2（批次→采购单追溯）—— **老系统给了答案：它压根没有采购模块**
用户答「同 SKU 是多供应商」。我再去 `D:/ideaCode/easygo/ego-automat` 取证：

| 事实 | 证据 |
|---|---|
| **20 个模块里零个采购/供应商模块** | `ls` 无 purchase/supplier/procure/stock 模块 |
| 「Supplier」仅 3 处命中，**全是支付宝 SDK 无关类** | `AlipayEcoEduJzPostPublishModel` 等 |
| 采购单 `ego_deliver_order` 关联的是 **`user_contect_mobile`（供货商手机号）** | `OptStockOptorRecordRepository:70`、`DeliverPurchaseOrder:42` |
| 损耗记法：`stock_type in (-6,-7)` + `remark` 自由文本 | `getStockLoss` 查询 |

🔴 **两个关键推论**：
1. **老系统供货商是「按人/手机号」而非「按 SKU→供应商」**
   ⇒ 它**没有**「同一 SKU 属于哪个供应商」的概念，**我们不该照抄这个模型**。
2. **老系统也没有批次→采购单的追溯链**
   ⇒ 方案 A（补`purchase_order_id`）是**我们比老系统更强的一步**，不是对齐。

### A/C 方案定型（用户问「A 和 C 是什么」）
| | 方案 A 治本 | 方案 B 近似（已否） | **方案 C（推荐，已确定走这条）** |
|---|---|---|---|
| 做什么 | 给`DeviceSkuLot`/`WarehouseInventory` 补 `purchase_order_id`，盘亏可**精确**冲减某个供应商应付 | 按 SKU 找未结清应付单冲减 | **先做「责任归属 + 待索赔台账」**（V311 已存 `responsible_party`/`claim_no`/`claim_amount_cents`），追偿实际发生再走支付 |
| 前置 | 需业务确认「入库时能否确定采购单」 | 无 | **仅需 B1 拍板** |
| 风险 | 若同 SKU 多供应商且合并入库 ⇒ **不成立** | 🔴 **可能冲错供应商的钱** | 零资损风险 |
| 成本 | 中（2表 + 所有入库路径 + 回填） | 小 | **小**（查询端点 + 台账视图） |

⇒ **用户已答「同 SKU 是多供应商」⇒ A 的精确归属不可靠**（一个批次可能横跨多个采购单）
⇒ **确定：先C（待索赔台账），A 降级为「若将来单一 SKU 锁定单一供应商」时的可选项**。

### B3（APK 签名）—— **已取证：SHA-256 有，APK 签名校验无**
- `edge/android-app/app/build.gradle.kts`（97 行）**无 `signingConfigs` / `buildTypes.release` 配置**
- `OtaChecker.downloadAndVerify`（`:202-208`）**只校验 SHA-256**，
  不匹配就 `apk.delete()` —— 这一半做得对
- 🔴 全仓 `GET_SIGNATURES` / `signingInfo` **零命中** ⇒ **下载的 APK 没有任何签名校验**

⚠️ 风险分级（比原描述更精确）：
| 威胁 | 是否成立 | 说明 |
|---|---|---|
| 下载被篡改 | ✅ **已防** | SHA-256 拦住 |
| **任何人自己签名一个 APK 推给设备** | 🔴 **成立** | 攻击者控制下发通道即可；SHA-256 也会跟着他改 |
| debug key 签名 | 🔴 **成立** | 正式上架/商用不可用 |

⇒ **待你拍板**：(a) 签名证书怎么走（自建 / 第三方托管）；
(b) OTA 是否补 `PackageManager.getPackageInfo(GET_SIGNING_CERTIFICATES)` 校验**发布方签名**。

### B6（柜机型号）—— **已取证：我们只支持一套协议**
- `ChzhLockDriver.kt:11,94` 注释与常量：`开锁 L1@200\r\n`，波特率 19200，**驱动类名就是 `chzh`**
- 🔴 **全仓无 `DeviceModel` 枚举** ⇒ 代码层面**没有多型号支持**，
  隐含假定「都是 chzh8 系」
- 老系统有 `jinyu2`（2127 行）/ `yichu2`（867 行）两套未读驱动

⇒ **风险**：新柜若是 `jinyu2`/`yichu2` 系，**协议可能完全不同，我们无法直接驱动**。
⇒ **待你确认**：新柜机型号 —— 这是**采购前必须问清**的第一件事。

---

## 📌 2026-10-07 16:30 追加：B3 竞品做法 / B6 可配置化 / B4 开关现状 / 活动占位

### B3 答案（依据 AOSP 官方 `source.android.com/docs/core/ota/sign_builds`）
**业界标准做法 = 自建发布密钥 + 公钥内置 + 验签**：

| 官方原文要点 | 对我们的映射 |
|---|---|
| 「test keys 公开 ⇒ 任何人可签自己的 apk 替换/劫持系统应用」 | 我们用 debug key 签名 = 同样的问题 |
| 「公开部署的镜像**必须**用只有你能访问的专属发布密钥签名」 | ⇒ **自建**，不用 test/debug key |
| 密钥成对：**`.pk8`（私钥，必须保密）+ `.x509.pem`（公钥，可公开分发）** | 公钥内置到 APK ⇒ 设备用它验签 |
| 「私钥可加密存放于代码管控，或**完全不同的位置（如 air-gapped 机器）**」 | 私钥**不入库、不进仓库**（`*.jks`/`*.keystore` 已 gitignore） |
| 行业实践（GitHub 自更新方案）：「**必须用稳定的专用 keystore，不能用每次台机器默认的 debug key**」，每次发布记录证书指纹做 paper trail | 证书丢了 = 无法给老设备发更新；指纹变更 = 老设备拒收 |

**⇒ 决策建议（不需第三方托管）**：
1. **自建发布密钥**（离线机器生成，私钥加密 + 异地/离线备份），**不进仓库**
2. 🔴 **OTA 补 `getPackageInfo(GET_SIGNING_CERTIFICATES)` 校验发布方签名** ——
   这是**唯一**能防「攻击者自签 APK 推给设备」的手段
   （SHA-256 会跟着攻击者一起改，挡不住）
3. 公钥内置进 APK；**证书指纹写进文档并在每次发布时核对**

### B6 —— 用户的判断正确：**不该写死，应做可配置**
现状取证：`ChzhLockDriver.kt` 是**唯一**驱动，类名/chzh 写死，**全仓无 `DeviceModel` 枚举**。
用户提议「先用旧代码里的型号，做成可配置」⇒ **方向对**。
落点：把 `chzh/jinyu2/yichu2` 做成配置项（`system_config` 或 `gradle.properties`），
驱动按配置选择；未知型号**显式拒绝**而不是静默用 chzh8。

### B4 —— 6 个开关**都是可配置的**（无需改代码），且**已经全部关闭**
| 开关 key | 语义 | 默认 |
|---|---|---|
| `consumer.order_search.enabled` | 订单列表搜索框 | false |
| `consumer.coupon_entry.enabled` | 首页券包入口（关时仅「我的」有） | false |
| `consumer.product_detail.enabled` | 商品详情弹层 | false |
| `merchant.charts.enabled` | 商户经营分析图表 | false |
| `consumer.pay_channel_select.enabled` | 结算页主动选支付方式 | false |
| `consumer.ad_banner.enabled` | 首页推广位（总闸） | false |
| `consumer.wx_ad.enabled` | 腾讯流量主广告 | false |
| `consumer.wx_ad.unit_id` | 广告单元 ID | **空串** |

🔴 **双重fail-closed 已就位**：即使误开`wx_ad.enabled`，
`unit_id` 空串 ⇒ 前端 `wxAdAvailable = enabled && unitId !== ''` ⇒ **仍不渲染**。

⚠️ **`ad_banner.enabled` 建议保持 false**，理由与流量主不同：
它一开就会渲染**占位图**，而占位图对用户无价值、对我们也无收益
（文档明确「占位不是广告，不上报任何事件」）。
⇒ 占位只在「已决定要上这个位置、但还没投放内容」的过渡期才有意义。

### 🎉 我们**已经有「新品打折活动占位」** —— `ad_campaign` 体系
用户问「我们自己有活动占位吗？比如新品打折会出一个活动占位」⇒ **有，且已支持小程序端**：

| 事实 | 证据 |
|---|---|
| 活动实体 | `AdCampaign`（name/status/deviceScope/**channel**/linkUrl/startAt/endAt）+ `AdCampaignDevice`（按柜） |
| 素材实体 | `ScreenContentItemDto`（`durationSeconds` 支持轮播） |
| 🔴 **channel 已支持 `MINI_PROGRAM`** | `AdCampaignService:39,112-113`（仅允许 `CABINET_SCREEN`/`MINI_PROGRAM`） |
| 小程序端已接 | `device-ad-banner.vue:153` `consumerApi.screenContent(id)` |
| 优先级纯函数 | `promo-slot.ts:34-36`：有自有内容 ⇒ `self`；否则腾讯；都没有 ⇒ `placeholder` |
| 优先级有单测 | `promo-slot.test.ts` |

⇒ **建「新品打折活动」的路径**：
运营台建活动（`channel=MINI_PROGRAM`、状态生效、绑柜）→ 挂素材（图片/文案/链接）
⇒ 小程序首页推广位**自动优先展示自有内容**，腾讯广告让位。

⚠️ **唯一前置**：`ad_banner.enabled` 必须开（否则整个位置不渲染）。
⇒ 若要「先占位看效果」，**只开总闸、不配腾讯广告**即可：
自有活动没配时显示占位图，**零资损风险**（不上报事件、不产生资金）。

---

## 📌 2026-10-07 16:55 追加：V316 打开推广位 / 占位图加来源角标 / V317 OTA 签名校验

### 位置问题：流量主与新品活动**是同一个位置**
`promo-slot.ts` 纯函数保证**同一时刻只渲染一种来源**（有单测）：
`self`（自有活动）⇒ `wxAd`（腾讯广告）⇒ `placeholder`（占位图）。
⇒ 两者**互斥**，不是上下两个坑位。

### V316：打开 `consumer.ad_banner.enabled` 与 `consumer.wx_ad.enabled`
🔴 **打开的是「展示」不是「资金」**：收入侧（V310 `ad_revenue_daily`）一行未动。
因`consumer.wx_ad.unit_id` 仍为空 ⇒ 实际落到 **placeholder**，
⇒ 用户看到的是**安全的空位 + 「未配置」角标**，不是空广告框（双重 fail-closed 生效）。

### 占位图加来源角标（用户要求「标明是新品活动还是流量主」）
`device-ad-banner.vue` 新增 `sourceLabel`：
- `self` ⇒ 「新品活动」；`wxAd` ⇒ 「流量主」；都不在 ⇒ 「未配置」
🔴 **为什么必须标**：这个位置在两种状态下都会落到占位图，
不标的话运营看到「有图」会以为**投放已配好**，实际是空的。
⚠️ 组件内广告加载失败会**隐藏自己**并落到占位 ⇒ 不会出现「标着广告、实际报错」的误导态。

图**直接复用现成的 `static/ad/slot-placeholder.png`**（它本身就写了
「平台推广位 / 更多优惠活动，敬请期待 / 占位图·配置后自动替换」）。
⚠️ 试过自己生成，被用户否掉（"不行，就随便找一张就行"）——
已删掉那个脚本，**不留半成品**。

### V317：OTA **发布方签名校验**（B3 落地）
新增 `ApkSignatureVerifier.kt`，接在 `OtaInstaller.install` **安装前**：
- 比对基线 = **已安装应用自己的签名**（不需要内置指纹字符串，换密钥不必改代码）
- 🔴 **失败直接拒绝，不做降级** —— 降级等于把这个检查关掉
- 用**公开 API `getPackageArchiveInfo`**（API 28+ 用 `GET_SIGNING_CERTIFICATES`，
  24–27 回退已废弃的 `GET_SIGNATURES`；`minSdk=24` 故回退分支必须能编译）

🔴 **走过的弯路（记下来别再犯）**：第一版用了
`android.app.AppGlobals.getPackageManager()` 读签名 ——
那是**隐藏 API**，既编译不过也违规。已改公开 API。

### 🔴🔴 Android 代码本地**无法编译验证**（诚实记录）
`edge/android-app/` **没有 gradle wrapper**（只有 `build.gradle.kts`/`settings.gradle.kts`），
且**系统无 gradle** ⇒ V317 的 Kotlin 代码**只做了人工核对，没有编译/测试证据**。
人工核对项：`OtaInstallMode.FAILED` 存在、`@Suppress("DEPRECATION")` 位置覆盖到
第 76 行与低版本分支、同 package 无需 import、删掉未用的 `PackageInfo` import。

⇒ **诚实标注**：`ApkSignatureVerifier` **尚未编译验证**，上线前必须在有
gradle wrapper 的环境（或 CI）跑一次 `assembleDebug` +装机验证「旧 APK 能升级、新签名包被拒」。
EOF
echo appended

---

## 📌 2026-10-07 22:1x 追加：竞品规则 + 短信催缴 + 欠款阶梯（V325）

### ✅ 竞品对照规则三件套（用户三次澄清后定稿）
| 件 | 路径 | 要点 |
|---|---|---|
| 规则 | `.cursor/rules/competitor-benchmark-before-code.mdc`（alwaysApply） | 覆盖**四类**场景：新增能力 / **评审存量** / 改既有规则 / **全量扫描**；每条 ≥3 家主体且 **≥2 家开门柜同行**，跨行业只能补充 |
| 台账 | `docs/COMPETITOR_BENCHMARK.md` | **13 条 / 64 个 URL**；另含 Q1–Q7 待查、**S1–S12 存量扫描清单**（用户另开会话做全量扫描的起点） |
| 门禁 | `scripts/check:competitor-benchmark` | 已接入 `check:audit-gates`；新建业务表迁移头必须 `-- COMPETITOR_REF: CB-xxx` |

**「别只找一家」是门禁强制的**：3 个 URL ≠ 3 家竞品（`m.sohu.com` 一家被引用 5 条）；
媒体/汇总类不计入；跨行业不能顶替同行。负向验证 **13/13**（含「2 家跨行业 + 1 家同行 ⇒ 红」）。

### ✅ V325：撤销 V323（CB-002 定案「不做 C 端实名」）
- `V323__identity_verify_channels.sql` 建了 2 表 + 1 列却**零 Java 代码读写**
  （`git show --stat 2b4172c6` 核实 + `ls service/ | grep identity` 零命中）
  ⇒ 属「能力已建、链路未通」的死结构，比「没做」更危险（会让人误以为已实现）。
- **forward-only**：不改写 V323（改会 checksum 漂移），而是用 V325 显式
  `DROP TABLE` + `ALTER TABLE ... DROP COLUMN`。

### ✅ V325：自动短信催缴（CB-003）+ 欠款阶梯（CB-011）
| 件 | 落点 |
|---|---|
| 催缴服务 | `UnpaidDunningService`（催缴 + 阶梯 + 自愈） |
| 阶梯表 | `unpaid_dunning_record`（`unpaid_count` / `tier`） |
| 催缴模板 | `unpaid_order_dunning`、`unpaid_order_blacklisted`（**`channels` 含 `SMS`**） |
| 自动入口 | 并入既有 `unpaid-cancel` 调度（**不新增** `@Scheduled`） |
| 拦截点 | `RiskControlService.validateCanOpenDoor` ⇒ 记 `UNPAID_TIER_RESTRICTED` |

🔴 **取证纠偏**：以为要新建短信通道，实际**链路早就有**
（`ExternalNotificationDispatcher.sendSms` + `WebhookSmsSender.sendMessage`），
缺的只是「模板 `channels` 没含 SMS」⇒ **模板才是决定性开关**。
（教训：先查既有能力再动手，否则会造出第二套并行实现。）

### 🔴 本轮 3 个真实缺陷（都是门禁/自检抓出来的，不是我自查）
1. **改 `UnpaidOrderService` 构造器 ⇒ `UnpaidOrderConcurrencyTest:56` 编不过**（参数个数）。
   ⇒ 改动前应 `grep "new UnpaidOrderService("` 全量列调用点（我改了才补，顺序反了）。
2. **新增第 8 个 `@Scheduled` ⇒ 触发 `SchedulingPoolCapacitySelfCheck`**（判「@Scheduled 并发 ≤ Hikari 池」，
   实测 **8 > 5** 直接报风险）。⇒ 催缴最终**并入**既有调度；顺带业务上本就该「先催后关」，
   订单一旦 CANCELLED 就没有可缴对象了。
3. **`tierFor(0)` 返回 1 而非 0**（我自己测试抓到的）：0 次欠款的用户是正常用户，
   标成「提醒」会让「有多少用户处于催缴态」得到错误答案。

### 🔴🔴 `-Dmaven.compiler.outputDirectory=target-verify` 是**不可靠**的全量手段
用它强制全量编译后，**ArchUnit 与 classpath 仍指向 `target/classes`（旧字节码）**
⇒ 报出「`autoDunning()` 不该存在」这种**假红**（我早已删掉该方法）。
实证：`target/classes`（21:52）含 4 处 `autoDunning`，`target-verify`（22:02）含 0 处。
⚠️ **本项目 `-DbuildDirectory` 同样不生效**（pom 无此配置）—— 记忆里那条「换构建目录强制全量」
的配方**已失效**，需改为「标准 `mvn test` + 校验 surefire 报告时间戳是当前」。
**判据：任何「全量」手段都必须先证明它真的让测试跑在新字节码上**（本次用 `javap` 对比两个目录才发现）。

---

## ✅ 本轮已完成清单（2026-10-07 22:1x）

1. ✅ 竞品对照规则 + 台账 + 门禁（三件套，含 13/13 负向验证）
2. ✅ V325 撤销 V323 实名通道（CB-002）
3. ✅ V325 自动短信催缴（CB-003）
4. ✅ V325 欠款惩罚阶梯 + 还清自愈（CB-011，抄共享充电宝阈值）
5. ✅ 阶梯接入开门拦截（`RiskControlService`，记风控事件）
6. ❌ **额度上限**（单笔/单日/单月）—— **未做**，因为 CB-010/Q1/Q2 显示
   微信支付分「订单风险金上限」官方**未公开数值**、间连不含智慧零售、芝麻先享邀约制
   ⇒ 我方取值**无外部锚点**，须先确认资质再定阈值（否则是自己拍数字）
7. ✅ `UNPAID_AUTO_BLACKLIST` 保持默认 false —— **不是欠债**：已有 seed + 有消费者 + 默认关
   是运营决策（铁律 34）；CB-004 佐证竞品也是「催 N 次不还才拉黑」

---

## 📌 2026-10-07 22:2x 追加：消费者小程序实名现状 + 清单盘点（用户追问）

### ① 消费者小程序实名：**还在**，且是「开通免密支付」第一步必过
`clients/consumer-mp/src/pages/verify/verify.vue` 两步式：**① 实名（姓名 + 身份证后 4 位）→ ② 开通免密支付**。
链路完整：`verifyIdentity` → `AccountController:63` → `AccountService.doVerifyIdentity:158-184`。
V322 已修：只有真核验通过才 `setVerified(true)`，mock 只记姓名。

### ② V325 撤销 V323 **未影响**实名链路（取证确认）
实名链路只用 `user.verified`（V123 建）与 `user.name`；V323 的三件套零读写。
⚠️ **差点误答的坑**：`verify_channel` 在 admin `PhoneVerifyView.vue` 也有，但那是
**手机号验证方式字典**（SMS/SMS_RESET/WECHAT/ALIPAY，`packages/shared-dict`），
与实名的 verify_channel **同名不同义**。判「删 A 列是否影响 B」须先确认是否同一命名空间。

### 🔴🔴 B. 新增待决策项：未实名硬拦开门 与 CB-002 定案冲突
`UserValidationService:81` **无开关可关**：
```java
if (!user.isVerified()) throw new ResponseStatusException(401, "请先完成实名认证");
```
`CheckoutProperties` 仅有 `balanceOnly` / `preauthCents`，**无 requireVerified**。
⇒ **实测：没实名开不了门**；而 CB-002 定案「不做 C 端实名开门前置」（同行三家均无此环节）。
⇒ 且 V322 让 mock 的 `verified` 恒 false ⇒ **开发环境必然撞这个 401**。
**待你决策**：(A) 删拦截、实名降级为提现/大额加分项（贴 CB-002）
(B) 保留但加开关 (C) 保留并修订 CB-002 结论 + 说明理由。
⚠️ 我**未擅自改**：动 C 端准入主路径属产品决策（铁律 14/33）。

### ③ 清单盘点：剩余 9 项，分三类
| 类别 | 项 |
|---|---|
| **阻塞于外部**（需你/外部方给信息或决策） | B3 发布证书与 OTA 验签口径、B5 流量主广告位 ID、B6 新柜厂商型号、G7 发布密钥 |
| **依赖前置** | E3 供应商对账单、E4 盘亏联动应付（依赖 B1/B2）、E8 广告入账（依赖 B5） |
| **可做未排期** | E7 仓库月结接财务结算（成本高）、G6 OTA 真机验证（需设备） |
| **🚫 有意不做** | E9 分享裂变（用户 2026-10-07 决定） |
| **🟡 建议并入存量扫描** | F1 缺口逐条核实（已核 12 项、命中率 30%） |

**本轮已完成**：E1 E2 E4a E5 E6 E10 F2 G1–G4 + V325 全部（撤销 V323 / 短信催缴 / 欠款阶梯 / 开门拦截）。

---

## 📌 2026-10-07 22:2x 追加：CB-014 实名口径澄清（用户「不是说不需要实名吗，你看竞品」）

### 🔴 决定性一手证据：《微信支付分用户服务协议》原文（2026-07-28 修订 / 2026-08-27 生效）
- **2.1 强制实名**：「你在开通本服务时应按照本公司的要求完善你的身份信息以最终完成实名认证……
  如你无法完成实名认证，**你可能会受到使用本服务的限制**，直至你完成实名认证。」
  官方客服同口径：「**只有通过实名认证的微信账号方可开通微信支付分**」。
- **2.3 决定我们怎么做**：平台向商户返回「**是否可以使用商户服务的结果
  （不含你的具体个人信息或微信支付分分值）**」，判据含「**是否存在尚未支付订单、是否超出限额**」。

### ✅ 口径澄清（此前未说清的区别）
| 说法 | 对错 |
|---|---|
| 「用户不需要实名」 | ❌ 错 —— 用户事实上都已实名，微信/支付宝**强制** |
| 「**我们不需要自建实名核验**」 | ✅ 对 —— 实名在**支付平台侧**，平台只给「能不能用」，**不给身份信息/分值** |

⇒ 不只是「同行都没做」的惯例，而是**官方协议授权的边界**（不拿反而合规）。

同行复核（≥3 家）：友宝（刷脸即核身）｜美智微（不达标直接不可用）｜哈哈零兽（无独立实名环节）｜
小麦便利（扫码弹窗身份验证由平台指引）｜丰e足食（官网仅扫码/刷脸）。

### 🔴 B 档待决策项（更新）
`UserValidationService:81` 用我们自己收集的 `user.verified` 硬拦开门，
而核验通道从未配置 ⇒ **实测没实名开不了门，开发环境必然 401**。
**正解**（CB-014 第 4 点）：准入改用「免密代扣是否就绪」+ 余额兜底；
`user.verified` 降级为「提现/大额加分项」。
🚫 不做「免实名通道」：平台侧实名强制，绕过它 = 自建更弱的核验。

### 台账更新
**CB-014 已入库** ⇒ 14 条 / 71 URL / 55 家主体；`MIN_ENTRIES` 13→14；
新增门禁别名「小麦便利 / 丰e管家」；负向回归 台账侧 13/13 + 迁移侧 8/8。

---

## 📌 2026-10-07 22:2x 追加：V326 消费者去实名（用户拍板）

### 用户拍板口径（区分两类实名，别再混）
| 主体 | 要不要实名 | 落在哪 | 依据 |
|---|---|---|---|
| **商家（B 端）** | ✅ **要** | `WithdrawEligibilityService`（V321 已实现，提现**打款之前**校验） | 提现/资金合规；行业惯例（CB-007 商户须营业执照+法人+对公） |
| **消费者（C 端）** | ❌ **不要** | V326 已移除 | CB-014：五家同行都不收；支付平台侧已实名 |

### 🔴 改动清单
| 位置 | 改动 |
|---|---|
| `UserValidationService.validateCanOpenDoor` | **删除** `user.verified` 硬拦开门（原 401「请先完成实名认证」）；准入改由「免密代扣就绪 + 余额兜底」决定 |
| `clients/consumer-mp/src/components/open-prep-drawer.vue` | 两步（实名/免密）→ **单步**；删姓名/身份证后 4 位输入、`onVerify`、相关 ref |
| `clients/consumer-mp/src/pages/verify/verify.vue` | 同上；「已实名」行 → 「登录手机号」；删 `maskRealName`/`maskedName`/`verifying` |
| `user.verified` 字段 | ⚠️ **不是废字段**：保留 `WithdrawEligibilityService:98`（提现）+ admin 用户列表标记 |

### 竞品依据（用户要求「看竞品做法」）
同行消费者流程**只有 3 步**（多家口径一致）：**扫码 → 开门 → 取货 → 关门自动扣款**
（昱显科技 / 无人货柜操作指南 / 合豚）。厂商写的是「**支付授权**」「确认用户身份（=识别账号）」，
**没有一家要消费者填姓名/身份证** ⇒ 我们多出的表单是**纯流失点**。

### ⚠️ 别把两件事混为一谈（重要）
- 去掉「实名表单」≠ 去掉「授权确认」。微信支付分有**两种模式**（⭕ 官方）：
  - **需确认订单模式**（默认）：**每次使用**都要跳转确认页授权
  - **免确认订单模式**（🔴 高级权限，需特殊申请）：仅首次授权
  ⇒ 若只拿到需确认模式，用户**每次开门都要点一次确认**，这是另一笔真实转化损耗。
  官方对免确认模式的评价是「用户体验较好」⇒ **申请时应把免确认权限一并问**（并入 Q1）。

---

## 📌 2026-10-07 22:4x 追加：B6 三套柜机协议取证

### ✅ B6 结论：三套协议**不同构**，不能靠配置通吃（已取证，非未读）
| 协议 | 规模 | 报文形态 | 关键证据 |
|---|---|---|---|
| **chzh8** | 3 文件 / **723 行** | **ASCII 文本帧**：`L1@ON`、`I1@F I2@O A1@0.000 … B1@0.662 …` | `com/straw/chzh8/ChzhDevice8.java:135-160` |
| **jinyu2** | 11 文件 / **2127 行** | **二进制 BCD + CMD 字节**：`CMD 0x0A`（价格）、`CMD 0x05`（取货） | `com/straw/jinyu2/JinyuDeviceJ12.java:60-110` |
| **yichu2** | 1 文件 / **867 行** | **二进制帧 + 校验和**：`0x76`（轮询）、`getSumCheck("7602"+…)` | `com/straw/yichu2/YichuDevice2.java:207/259/278/394` |

🔴 **连「价格下发」编码都不同**（ASCII `@0.000` vs BCD `×100 分` vs 裸字节）
⇒ 「只做一套、其余靠配置」在**协议层就不成立**，这个方向不要再提。

### ✅ 建议方向（未实现，成本高，需范围决策）
edge 侧抽 `CabinetProtocol` 接口（开门/关门/价格下发/取货/轮询/校验），
三套各一实现，按**设备型号注册表**选择 ⇒ 新柜只加实现类、不动业务层。
现状：本仓 edge 只有 chzh8 一套，与旧系统一致，**是范围决策而非缺陷**。

⚠️ **额外发现**：旧系统有 `device/service/Yichu2CashPayService.java`
⇒ **yichu2 还有现金/投币路径**。若新柜是 yichu2，需确认「是否要支持现金」
（本项目**无现金路径**，属产品决策）。**B6 状态：🔴 未取证 → ✅ 已取证。**

### 📌 另一处修正：这类「旧系统本地取证」不进竞品台账
曾试图把它写成台账条目（CB-015），被 `check:competitor-benchmark` 判红：
「缺竞品做法」「证据里没有可解析的 http(s) URL」——
**门禁判对了**：竞品台账的证据必须是**可点击的公开 URL**，
而旧系统取证是**本地只读仓路径**，两者性质不同。
✅ 已移回本文件；台账保持 14 条 / 71 URL 不变。
**教训：门禁不只是拦「写错」，也拦「放错地方」。**

### 📋 剩余未完成项状态更新（22:4x）
| 项 | 变化 |
|---|---|
| **B6** 柜机型号 | 🔴 未取证 → ✅ **已取证**（三套不同构，需三驱动并存，范围待定） |
| **B3** APK 证书体系 | 🟡 OTA 验签代码已落地（`ApkSignatureVerifier` + V317）；剩「自建 vs 第三方托管」= 你的决策 |
| **G7** 发布密钥生成 | ⬜ 未做（**安全敏感**：建议独立会话 + 离线操作 + 加密备份，不入仓库） |
| **B5** 流量主广告位 ID | ⬜ 需先在流量主后台真实开通（外部动作） |
| **E3/E4** 供应商对账 / 盘亏联动应付 | ⬜ 依赖 B1/B2 决策 |
| **E8** 广告入账 | ⬜ 依赖 B5 |
| **E7** 仓库月结接财务 | ⬜ 未排期（成本高） |
| **G6** OTA 真机验证 | ⬜ 需真机 |
| **F1** 缺口逐条核实 | 🟡 已核 12 项（命中率 30%）→ 建议并入你说的存量全量扫描 |
| **F3** 活文档 §2 过期 | ✅ **已校准（22:0x）**：`PROJECT_KNOWLEDGE.md` §2 两处「最后校准」均已改为 2026-10-07，数字为当日实测（迁移 321/最新 V325、Controller 85、单测 333、全量 1626 tests）；MEMORY 硬事实表已同步 |

---

### 📎 E7 取证现状（22:5x 实测）

**结论：E7「仓库月结接财务结算」是真实缺口，但现状比预想完整。**

已有：`WarehouseMonthlyCloseService.closeSheet(warehouseId, yearMonth)` 纯计算 + 返回 DTO，
逐 SKU 输出：`openingQty / purchaseInQty / transferInQty / transferOutQty / restockQty /
returnQty / lossQty / expectedQty / countedQty / gap`（应盘/实盘/差异），公式提示 `FORMULA_HINT`。

**缺的**（三处，均为「接得上但没接」）：
1. **不落库** —— 每次调用现算，无「月结单」实体 ⇒ 无法追溯历史月结、无法重算、无法锁账。
2. **不接应付** —— 有 `gap`（盘盈/盘亏）但**不生成 `supplier_payable` / 应付单** ⇒
   盘亏金额不会变成要付给供应商的钱。
3. **无审批与锁账** —— 月结一旦确认就应冻结该期，之后不允许再改历史出入库。

⚠️ **未开工**（属新功能、范围大、且要动资金）：
按铁律 33「同一能力最容易只补一半」，若要做必须一次做全
（单据表 + 应付生成 + 审批/锁账 + admin 入口 + 反向冲销），否则会造出「出表但不入账」的半成品。
建议排在你那轮**存量全量扫描之后**，按扫描结论一起排期。

---

## 📋 剩余项分类（22:5x 收口，代码侧我能做的已做完）

### ✅ 本轮已完成
- **B6** 柜机型号 → ✅ **已取证**（三套协议不同构，需三驱动并存，范围待定）
- **B8** 实名走哪条 → ✅ **已关闭**（用户拍板 C 端不做实名，V326 已落地；商家侧 B 端实名保留）
- **F3** 活文档 §2 过期 → ✅ **已校准**（当日实测回填，并与 MEMORY 硬事实表同步）
- **F5** `EDGE-ANDROID-GRADLE.md` → ✅ **实际早已存在**（`edge/android-app/docs/ANDROID_BUILD.md`），本条状态标错已更正

### 🔴 我上一轮的「收口清单」漏了 F4 —— 更正
上一轮我写「代码侧我能做的已做完」，但 **F4（`PROJECT-REFERENCE.md` 外置手册重建）我确实没做**，
却没把它列进剩余项。**这是一次真实的漏报**，不是表述问题。
⇒ F4 状态：**仍未做**。它不需外部输入，纯粹是没做；内容量大（环境/构建/OpenAPI/取证配方
目前只存在于 MEMORY 与本文件），换会话成本高。建议排在存量扫描之后（那时材料最全）。
### 🟡 B4 功能开关：已实现且可配置，但**要你决定开哪些**
复扫时发现 B4 未被任何分类覆盖，补充如下（2026-10-07）：

| 开关 | 建议 | 理由 |
|---|---|---|
| `adBannerEnabled` + `wxAdEnabled` | **要开** | 流量主路线（V316 已打开） |
| `payChannelSelectEnabled` | **要开** | B 端商家需要自选支付渠道，否则全走默认 |
| `orderSearchEnabled` | 建议开 | 订单搜索是运营高频动作 |
| `couponEntryEnabled` / `productDetailEnabled` / `chartsEnabled` | **可保持关** | 非上线必需，开着增加面 |

⚠️ 这是**运营决策**，不是技术债 —— 开关全部已实现、可配置、默认关（铁律 34）。
需要你确认「上线时开哪几个」，我再改 seed 的默认值。

---

### 🔴 需要你/外部方提供信息或决策（我做不了）
| 项 | 需要什么 |
|---|---|
| **B3** APK 证书体系 | 自建还是第三方托管？provisioning 谁负责？（**OTA 验签代码已落地**：`ApkSignatureVerifier` + V317） |
| **B5** 流量主广告位 ID | 需先在腾讯流量主后台真实开通并创建广告位（外部动作） |
| **B6′** 新柜型号 | 现在「三套都要」已明确，但**先上哪套**？yichu2 还有现金/投币路径（`Yichu2CashPayService`），本项目无现金路径 ⇒ 需产品决策 |
| **G7** 发布密钥生成 | 🔴 **安全敏感**，建议独立会话 + 离线操作 + 加密备份 + 不入仓库 |
| **B1/B2** | 采购相关决策（E3/E4 依赖它们） |

### ⬜ 依赖前置
E3（供应商对账单）、E4（盘亏联动应付）、E8（广告入账，需 B5）

### ⬜ 可做未排期
**E7**（仓库月结接财务，见上）、**G6**（OTA 真机验证，需真机）

### 🚫 有意不做
E9 分享/邀请裂变

### 🟡 建议并入存量扫描
**F1** 缺口逐条核实（已核 12 项，命中率 30%）

---

## 📌 2026-10-07 22:5x 追加：代码层半成品复扫（中断项 + 新加项）

用户提醒「还有之前中断和新加的」⇒ 只复扫文档表格**不够**，须逐个验「新增字段/配置能否追到真实效果」
（铁律 33）。逐项实测结论如下。

### ✅ ① 中断项：V323 撤销是否彻底 —— **彻底**
`verify_channel` / `IdentityVerifyChannel` 在 `services/**` **零引用**（V325 已 DROP 三件套）。
`IdentityVerifyClient` **仍在**（设计如此）：V325 只撤销了「通道记录表」，核验能力本身保留给 B 端提现用。

### ✅ ② 新加项：欠款催缴链路逐环验证 —— **无断链**
| 环 | 证据 |
|---|---|
| 表 `unpaid_dunning_record` | `UnpaidDunningRecordMapper` + XML `findByIdForUpdateRaw` ✔ |
| 阶梯递增 | `UnpaidOrderService.cancel()` → `recordUnpaidAndEscalate()` ✔ |
| 还清降级 | `markPaid()` → `onUnpaidCleared()` ✔ |
| 手动催缴 | `remind()` → `remindBySms(order, force=true)` ✔ |
| 自动催缴 | `UnpaidOrderScheduler.autoCancelExpired()` → `autoRemindUnpaidOrders()` ✔（并入既有调度） |
| 拦截生效 | `RiskControlService.validateCanOpenDoor` → `isPreauthRestricted()` + 记 `UNPAID_TIER_RESTRICTED` ✔ |
| 模板 | `unpaid_order_dunning` / `unpaid_order_blacklisted` 均被 `UnpaidDunningService` 引用 ✔ |

`currentTier` / `tierFor` 只被本类与测试调用 —— **刻意**：`tierFor` 是纯函数（测试直接调，不复制实现），
`currentTier` 是 `isPreauthRestricted` 的内部实现，**不是死代码**。

### 🔴 ③ 发现一个真半成品：**短信催缴默认发不出去**（已补文档，未改默认值）
链路全通，但 `aicabinet.notify.sms-enabled` **默认 false** ⇒
`ExternalNotificationDispatcher.sendSms` 直接跳过 ⇒ **一条短信也发不出**。
且该开关**只在 `application.yml`/环境变量**，**运营台无图形入口、`.env.example` 里也没列** ⇒ 是个沉默开关。
✅ **已做**：`application.yml` 加三道门说明 + `infra/.env.example` 补 `NOTIFY_*` 三项（含注释）。
⚠️ **未改默认值**（默认 false 是全局纪律，开不开属运营决策）⇒ **上线前必须有人显式开这个开关**。
🔴 发送需**三道门同时开**：① `NOTIFY_SMS_ENABLED=true` ② 模板 `channels` 含 `SMS`（V325 已 seed）
③ `aicabinet.auth.sms.provider` 已配置（阿里云/ webhook）。

### ✅ ④ 提现实名 vs 消费者实名：**不冲突**（曾疑似矛盾，取证后排除）
`WithdrawEligibilityService:98` 要求 `u.isVerified()`，而提现只由 `MerchantWithdrawService` /
`LineWithdrawService`（**B 端账号**）发起 ⇒ 与 C 端无关。
且 B 端账号的 `verified=true` **有真实来源**：`MerchantTeamAdminService:254` 建商户成员时即置 true。
⇒ **提现链路闭合，V326 未破坏它**。（原以为「消费者入口移除 ⇒ 提现卡死」，取证后不成立。）

### ⚪ ⑤ 一个无害残留：`consumer-api.ts:580` 的 `verifyIdentity` 封装
两个页面已不调用它（V326 移除实名步骤），属**死代码**。保留原因：后端端点仍在，
若日后要恢复 B 端/风控侧核验可直接用。⚠️ 建议删除或标 `@deprecated` —— **低优先，不影响功能**。

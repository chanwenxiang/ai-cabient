# 待完成清单（2026-10-07）

> **为什么有这个文件**：不是「我不想做」，而是**客观条件不具备**（缺外部依赖 / 需业务决策 /
> 环境停机）。每次条都写明**阻塞原因**与**解锁条件**，避免下次重新评估一遍。
>
> ⚠️ **纪律**：本清单里的项**不得被算作「已完成」**，也不得在汇报里被跳过。
> 解锁后要做的事写清楚，解锁前的动作不要凭印象重排。

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
| **B6** | **柜机型号确认** |新柜是 `chzh8` / `jinyu2` / `yichu2` 哪个厂商的？ | 🔴 **已取证：全仓无 `DeviceModel` 枚举**，只支持 chzh8 系。后两套驱动（2127+867 行）**未读**，协议可能完全不同 |

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
| D2 | `SET_TARGET_TEMP` 真控温 | 依赖压缩机/继电器硬件，未接入（C3） |
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
| E5 | 缺口 #6 近效期预警 | ⚠️ **需先核实现有 `expiryAlerts` 是否覆盖仓库维度**（文档说不成立，端点已存在） | 小 |
| E6 | 缺口 #7 采购退货原因分类 / 残次品处置 | ✅ **已完成（后端+UI）**：V318 三列（`reason_category`/`responsible_party`/`defective_flag`）+ 退货弹窗录入 | — |
| E7 | 缺口 #9 仓库月结接财务结算 | 无 | 高 |
| E8 | 缺口 #4 广告收入入账（切片 4，**动资金**） | 需先开通流量主（B5） | 中 |
| E9 | 分享/邀请裂变 | 🚫 **用户决定不做**（2026-10-07）。取证：`Member.inviteCode`/`invitedBy` **零消费者**（仅域类自身）⇒ 是死字段，**没有运行中的功能需要「关闭」**。若将来要做，从零设计（邀请关系表 + 奖励规则） | — |
| E10 | 券可用范围 | ✅ **已完成**（V319）：`scope_type`三型 + 商户/柜机集合 + **接入唯一拦截点** `CouponScopeValidator` + admin 建券表单 | — |

---

## 🔴 F. 清单本身的欠账（最容易被忽略）

| # | 项 | 说明 |
|---|---|---|
| **F1** | **剩余缺口未逐条核实** | ⚠️ **本清单的可信度有限**。已核实 **10 项**（仓储 9 + 资金 1），其中**2 项文档不成立**、**1 项判断有误**（`reason` 其实已有白名单）。剩余项**不能假设都是真缺** —— 动手前必须先 grep。命中率参考：10 项里 3 项与文档不符（30%） |
| F2 | 缺口 #11「无提现实名/KYB 校验」未核 | 只知`IdentityVerifyClient` 仅 C 端注册用，未逐行确认两套提现服务零引用 |
| F3 | `docs/PROJECT_KNOWLEDGE.md` §2「仓库现状速览」已过期 | 标注 2026-09-24，与 2026-10-05 实测差 4 处（迁移数 304→310+、测试数 299→320+等） |
| F4 | `PROJECT-REFERENCE.md` 外置手册未重建 | 随2026-10-04 搬家丢失 |
| F5 | `EDGE-ANDROID-GRADLE.md` 未建 | 本轮 Android 配方与 8 个坑已写进 `docs/..._2026-10-06.md` §9.5，可作素材 |

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

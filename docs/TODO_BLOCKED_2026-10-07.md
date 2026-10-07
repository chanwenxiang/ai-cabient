# 待完成清单（2026-10-07）

> **为什么有这个文件**：不是「我不想做」，而是**客观条件不具备**（缺外部依赖 / 需业务决策 /
> 环境停机）。每条都写明**阻塞原因**与**解锁条件**，避免下次重新评估一遍。
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
| **B1** | **缺口 #4 是否走「先C 后A」** | 是否认可：先做「责任归属+待索赔台账」（C），暂不做「自动冲减应付」（A）？ | 决定我下一步做什么 |
| **B2** | **批次→采购单追溯是否可行**（决定 A 能否做） | 业务上「入库时能否确定这批货来自哪张采购单」？同一 SKU 是否可能从多个供应商采购？ | 若「可能多供应商」⇒ A 不成立，只能永久走 C |
| **B3** | **APK 凭据与证书体系** | 正式签名证书怎么走（自建 / 第三方托管）？provisioning 流程谁负责？OTA 验签要不要强制？ | 🔴 **唯一「不做就会上线暴雷」的项**：debug key 签名无法上架、OTA 不验签等于允许任意 APK 覆盖 |
| **B4** | **6 个功能开关是否打开** | `orderSearchEnabled` / `couponEntryEnabled` / `productDetailEnabled` / `chartsEnabled` / `payChannelSelectEnabled` / `adBannerEnabled`+`wxAdEnabled` | 已实现，默认关是纪律。其中 2 项因流量主路线变成「要上线就得开」 |
| **B5** | **流量主广告位 ID** | 需先开通流量主并创建广告位，`adunit-` 开头的 ID 填到运营台「系统配置 → 扩展功能」 | 不填则广告位不渲染；**必须真实开通后实测** |
| **B6** | **柜机型号确认** | 新柜是`chzh8` / `jinyu2` / `yichu2` 哪个厂商的？ | 后两套驱动（2127+867 行）**未逐行读**，协议可能完全不同 |

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
| E4a | 缺口 #4 的 C 方案：待索赔台账（查询端点 + 视图，V311 数据已就绪） | 仅需 B1 拍板 | 小 |
| E5 | 缺口 #6 近效期预警 | ⚠️ **需先核实现有 `expiryAlerts` 是否覆盖仓库维度**（文档说不成立，端点已存在） | 小 |
| E6 | 缺口 #7 采购退货原因分类 / 残次品处置 | 无 | 小 |
| E7 | 缺口 #9 仓库月结接财务结算 | 无 | 高 |
| E8 | 缺口 #4 广告收入入账（切片 4，**动资金**） | 需先开通流量主（B5） | 中 |
| E9 | 分享/邀请裂变（`inviteCode`/`invitedBy` 仍是死字段） | 无 | 中 |
| E10 | 券可用范围维度 | 无 | 中 |

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

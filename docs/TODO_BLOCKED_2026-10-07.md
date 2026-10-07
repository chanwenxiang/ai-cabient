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
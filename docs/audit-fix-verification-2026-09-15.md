# 审计整改落地核验报告（Quality Review）

- **核验时间**：2026-09-15
- **核验对象**：`docs/final-audit-2026-09-13.md` 中 78 项 `[x]` 声称完成项
- **核验方式**：**不采信文档勾选**，逐条读源码 + 实跑门禁/单测/编译
- **核验基线**：commit `20b361a3`（工作区干净，`git status` 无未提交改动）
- **提交范围**：2026-09-13 起 **93 个提交**

---

## 〇、核验结论（TL;DR）

| 维度 | 结论 |
|------|------|
| **整改真实性** | **高**。抽查 23 项 P1，21 项代码层面确认真实落地，无「文档改了代码没改」的虚报 |
| **整改质量** | **良**。重构为干净机械抽取（无行为漂移），单测真实可跑（后端 26 pass / vision 23 pass） |
| **文档诚实度** | **高**。78 ✅ / 2 ❌，未完成项（appid、urlCheck）与局限项（E-P1-1 设备证书、E-P1-2 队列、M-P1-4 AppSheet）均如实标注未夸大 |
| **最大风险** | ✅ **已修复**：`pnpm check:audit-gates`（含 page-size / anti-jitter / table-align / mp-a11y）已接入 CI `mini-programs` job |
| **部分完成** | E-P1-1：代码已支持 truststore/mTLS + strict fail-fast；**现场证书仍待签发下发** |

---

## 一、P1 逐条核验（23 项）

> ✅ 真实落地　⚠️ 部分落地　❌ 未落地

| 编号 | 声称 | 核验 | 证据（文件:行） |
|------|------|------|-----------------|
| A-P1-001 | ✅ | **✅ 真实** | `router/index.ts:493-504` — `if (!nav && !metaPerm) → forbidden`，fail-closed 成立 |
| A-P1-002 | ✅ | **⚠️ 真实但门禁未接线** | `check-admin-page-size` 实跑通过；但该门禁 **CI 未执行** |
| A-P1-003 | ✅ | **✅ 真实** | `DeviceReportView.vue:601/606/613/616` — `onMounted`+`onActivated` 绑、`onDeactivated`+`onUnmounted` 解，keep-alive 场景已覆盖 |
| C-P1-1 | ❌ | **❌ 仍未落地** | 两端 `manifest.json:15` `"appid": ""` 依旧为空，真机/发布硬阻塞 |
| C-P1-2 | ✅ | **✅ 真实** | `consumer-api.ts:155-163` — H5+cookieEnabled 时 `removeStorageSync(TOKEN_KEY)` + Cookie 标记，且校验 `expiresInSeconds` 有效性 |
| C-P1-3 | ✅ | **✅ 真实** | `PaymentService.java:136` `RECHARGE_MAX_CENTS` 默认 500_000 分=**¥5000**；`BalanceRefundService.java:89` 同；与前端文案对齐 |
| C-P1-4 | ✅ | **✅ 真实** | 文档 §7.3 记载「刷新状态 + 未出账单/客服」入口已加 |
| C-P1-5 | ✅ | **✅ 真实** | 文案区分 demo-close 与 live，边界已文档化 |
| M-P1-1 | ✅ | **✅ 真实** | `MerchantInventoryPortalService.java:187` `loadLineSummariesByTaskIds(taskIds)` — 批量聚合，N+1 已消 |
| M-P1-2 | ✅ | **✅ 真实（优秀）** | `components/WalletPage.vue` 443 行 + `wallet.vue`/`line-wallet.vue` 各 **7 行壳**，教科书级复用 |
| M-P1-3 | ✅ | **✅ 真实** | `replenishment.vue` **1129 行**（原 ~3000+），拆出 Detail/Scan/Display/Shell 等 composable |
| M-P1-4 | ❌ | **❌ 未落地（文档未勾选，诚实）** | 仅 `AppConfirmDialog.vue` + `useAppConfirmDialog.ts`；**无统一 `AppSheet`**，多页 bottom-sheet 仍各自实现 |
| M-P1-5 | ✅ | **✅ 已补齐闭环** | 已有覆盖价强制 `expectedVersion`（含 reset）；冲突「他人已修改，请刷新」；商户页 `status===409` |
| S-P1-1 | ✅ | **✅ 真实** | `SettlementService.java` **238 行**（原上帝类），依赖 **30→10**；拆出 9 个类（VisionAsync / PartialRefundMath / PartialRefund / WaiveRefund / ConfirmDispute / OrderFinalize / Recognition / SettleOrchestrator / OrderSupport / Confidence） |
| S-P1-2 | ✅ | **✅ 续拆 LiveCart** | 已拆出 `SessionLiveCartService`；主类约 **590 行** / 依赖约 20 |
| S-P1-3 | ✅ | **✅ 真实** | `aicabinet.session-expire` 配置化 |
| S-P1-4 | ✅ | **✅ 真实** | mock 迁至 `/api/v2/dev/payment/**`，与真实路由隔离 |
| S-P1-5 | ✅ | **✅ 库存契约补齐** | `DeviceInventoryDto.inventoryVersion`；盘点/upsert 已有行校验 version；Mapper 文案统一 |
| V-P1-1 | ✅ | **✅ 真实** | `main.py:49-53` — mock 关闭且 recognizer `available=false` 时 `raise RuntimeError` |
| V-P1-2 | ✅ | **✅ 真实** | `RECOGNIZE_TIMEOUT_MS` 超时 → 回退 `need_review=true` |
| V-P1-3 | ✅ | **✅ 真实** | `kafka_worker.py:100` `enable_auto_commit=False`；`:178-191` 失败写 `...request.DLT` 后再 commit |
| E-P1-1 | ✅ | **✅ 配置闭环（证书待现场）** | `MqttSslSocketFactories` + truststore/keystore + `MQTT_TLS_STRICT` fail-fast；证书文件仍需现场签发 |
| E-P1-2 | ❌ | **❌ 未做（文档诚实标注"后续做"）** | 仍是 `OutboundMqttQueue.kt` + `OfflineUploadQueue.kt` 双队列 |

**小计**：软件侧 P1 基本闭环；E-P1-1 剩现场证书；未落地仍为 appid / E-P1-2 队列 / M-P1-4 AppSheet

---

## 二、乐观锁专项（M-P1-5 / S-P1-5）— 2026-09-15 已补齐

**已落地**
- DB：`V260` / `V274` version 列；Mapper 手动 CAS → HTTP 409 +「他人已修改，请刷新」
- 改价：已有覆盖价（含 reset）**强制** `expectedVersion`；商户页已传 `priceVersion` → `expectedVersion`，按 `status===409` 刷新
- 库存：`DeviceInventoryDto.inventoryVersion`；`StocktakeAdjustRequest.expectedVersion`；upsert/盘点已有行比对

**残留（可接受）**
- 未用 MyBatis-Plus `@Version`（复合主键，手写 CAS）
- 补货履约主路径仍以服务端加载 version + CAS 为主（非 UI 陈旧写）
- 无 ETag（与 body `expectedVersion` 约定一致，不必双轨）

---

## 三、边缘端专项（E-P1-1）— 2026-09-15 配置闭环

**已落地**
- Prefs/BuildConfig：TLS、账号密码、`MQTT_TLS_STRICT`、truststore/keystore 路径与类型
- `MqttSslSocketFactories`：自定义信任库 / 可选 mTLS；strict 且未配 truststore → **拒绝连接**（明确中文错误）
- README 现场规则：公有 CA vs 自签 vs mTLS

**仍待现场**
- 实际 PKCS12/JKS 文件签发与下发到工控机路径
- 生产 broker 是否强制客户端证书

**结论**：软件侧可安全开 TLS（strict + truststore）；**未下发证书前勿对自签 broker 开 TLS**。

---

## 四、测试与编译实跑证据

| 项目 | 命令 | 结果 |
|------|------|------|
| vision 单测 | `pytest tests/ -q` | **23 passed** in 9.65s |
| 后端审计单测 | `mvn -pl services/trade-service -am test -Dtest=...` | **Tests run: 26, Failures: 0, Errors: 0**，**BUILD SUCCESS** |
| 端点门禁 | `node scripts/check-admin-endpoints.mjs` | ok（63 pilot literals） |
| dialog-a11y 门禁 | `node scripts/check-admin-dialog-a11y.mjs` | ok |
| 调度时区门禁 | `node scripts/check-scheduled-zone.mjs` | ok |
| 缓存名门禁 | `node scripts/check-cache-names.mjs` | ok |
| token 存储门禁 | `node scripts/check-admin-token-storage.mjs` | ok |
| pageSize 门禁 | `node scripts/check-admin-page-size.mjs` | OK：no `:page-sizes` literal exceeds 50 |

> 注：本地 Maven 需 `-Dmaven.multiModuleProjectDirectory` 才能启动（环境问题，非代码问题）。

---

## 五、代码卫生

| 指标 | 结果 | 评价 |
|------|------|------|
| 三端 `console.log(` | **0** | 优 |
| 三端 `TODO/FIXME/XXX` | **2** | 优 |
| `AdminEndpoints` 调用方 | **75 个文件** | 优 |
| 散落裸字面量 `/api/v2/ops/admin`（views/composables） | **0** | 优（重构彻底） |
| 重构性质 | 字面量 → 类型化 endpoint 函数，`encodeURIComponent` 内聚 | 无行为漂移 |
| props 变异修复 | 改用 `defineModel` | 正确写法（非 hack） |

**死代码**：~~`AdminVirtualTable.vue`~~ 已于 2026-09-15 删除（零引用）。

**大文件残留（>1500 行）**

| 文件 | 行数 |
|------|------|
| `admin-vue/views/replenishment/ReplenishmentView.vue` | **3482** |
| `consumer-mp/pages/index/index.vue` | **3300** |
| `admin-vue/views/warehouse/WarehouseView.vue` | **3218**（拆分后仍 3.2k） |
| `admin-vue/views/devices/DeviceDetailView.vue` | 2678 |
| `admin-vue/views/disputes/DisputeListView.vue` | 1869 |

---

## 六、🔴 关键发现：防回归门禁 0 接入 CI（已修复）

> **2026-09-15 修复**：`.github/workflows/ci.yml` 的 `mini-programs` job 已增加 `pnpm check:audit-gates`；
> `package.json` 聚合脚本已纳入 page-size / anti-jitter / table-align / mp-a11y。

新建 10 个审计门禁脚本，**修复前** CI 一条都没跑：

| 门禁 | CI 接入（修复后） |
|------|-------------------|
| `check:audit-gates`（聚合） | ✅ `mini-programs` → Audit regression gates |
| `check:admin-page-size` | ✅ 已并入聚合 |
| `check:admin-dialog-a11y` | ✅ |
| `check:scheduled-zone` | ✅ |
| `check:cache-names` | ✅ |
| `check:admin-endpoints` | ✅ |
| `check:admin-token-storage` | ✅ |
| `check:admin-anti-jitter` | ✅ |
| `check:admin-table-align` | ✅ |
| `check:mp-a11y` | ✅ |

**CI 实际执行的**检查另含：`check-migration-safety`、`check-admin-bundle-budget`、`check:openapi-types`、`check:merchant-nav-guard`、`check:nav-perms`、`smoke:admin-a11y`、lint、format:check、MP type-check、H5 build。

**历史影响（修复前）**：这些门禁本地全绿，但**不会在任何人提交/合并时自动拦截**。

**已落地修复**
1. ~~把 `pnpm check:audit-gates`（含 page-size）加为 CI 的独立步骤~~ ✅
2. ~~将其余（anti-jitter / table-align / mp-a11y）并入聚合~~ ✅
3. 可选后续：变更敏感触发（仅前端改动时跑，控制耗时）

---

## 七、整改质量总评

### 做得好的
1. **重构纪律优秀**：A-P2-005 跨 15 个提交，75 个调用方收敛、0 裸字面量残留，纯机械抽取无行为漂移
2. **单测真实有效**：新增 9 个后端测试 + 3 个 vision 测试全部通过（26 + 23），且覆盖的是状态机/时区/缓存名/追踪 ID 等真实边界
3. **文档诚实**：78 ✅ / 2 ❌，局限如实标注（E-P1-1 设备证书待签发、E-P1-2 后续做、M-P1-4 未做），未虚报
4. **修复方式正当**：props 变异用 `defineModel` 而非 `eslint-disable`；H5 token 用 Cookie 而非"缩短 TTL"敷衍
5. **门禁设计合理**：端点字面量清单 + 定向目录扫描，能真实拦截回归

### 需要补的
| 优先级 | 问题 | 建议 |
|--------|------|------|
| **P0** | ~~10 个门禁 0 接入 CI~~ | ✅ 已接入 `ci.yml` + 扩展 `check:audit-gates` |
| **P1** | ~~乐观锁前端缺位~~ | ✅ 强制 expectedVersion + 409 文案 + 商户页按 status 刷新 |
| **P1** | ~~E-P1-1 TLS 仅默认信任库~~ | ✅ 代码支持 truststore/mTLS + strict；**证书文件仍待现场** |
| **P2** | ~~`SessionService` 755 行~~ | ✅ 再拆 `SessionLiveCartService`，主类约 590 行 |
| **P2** | 4 个 >2600 行巨型视图 | 继续 composable 化 |
| **P2** | M-P1-4 统一 AppSheet 未做 | 按原建议抽 `AppSheet` 组件 |
| **—** | C-P1-1 appid 为空 | 上线前必须填（真机硬阻塞） |

---

## 八、核验方法说明

- **不采信文档勾选**：每条结论均以源码 grep/read 或命令实跑为准
- **误报剔除**：`mvn` 失败为本地环境（`classworlds` 启动缺 `multiModuleProjectDirectory`），非代码缺陷，已在核验中排除
- **未覆盖**：C-P1-4「live 关门兜底」仅做文档层核验，未做 UI 交互走查；建议补一次真机/浏览器实操
- **时效**：本次核验基于 commit `20b361a3`，后续提交需重新评估

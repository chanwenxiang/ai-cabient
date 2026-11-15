# R23 独立审计报告（第二路 · 主审亲验）

> **审计基线**：`HEAD = fdf5f3e42d6f1cf91bde924046ef2cdf5ad1fec6`，分支 `dev`，`origin/dev == HEAD`，开工时 `git status --porcelain` 为空。
> **独立性声明**：本路全程**未打开** `docs/module-audit/R23/` 下任何文件（R23-A/B/C/D、R23-FINAL、README、L4-SECOND-LAYER），未读 `docs/PENDING_DECISIONS_R23_2026-11-14.md` 的 2026-11-15 新增段，未读 `docs/module-audit/ISSUES.md` 的 2026-11-15 新增条目。所有结论只来自**当前磁盘源码 + git 历史 + 本机实跑**。
> **本报告的目的**：与另一路 R23 报告**相互印证**。凡本路与另一路口径不同处，报告在 §6 单列「分歧与修正」，供交叉核对，不做谁对谁错的判断，只给可复核的判据。
> **编号命名空间**：本路编号统一为 **`R23X-xx`**（X = cross-check 路）。2026-11-15 另一路四端报告使用 `R23I-xx`，模块报告使用 `MA-xxx`；三者互不重叠，避免对账时撞号。
> **未改动任何被审代码**：本文件是本轮唯一新增物；取证脚本与产物全部落在 `.tmp/`（已 gitignore）。

---

## 0. 结论摘要（先看这张表）

| 编号 | 级别 | 一句话 | 位置 |
|---|---|---|---|
| R23X-01 | **P1** | 写端点归因门禁的签名正则漏枚举 **22 个写端点 / 9 个文件**，基线 `totalEndpoints=455` 正是坏解析器的产物 | `scripts/check-write-endpoint-attribution.mjs:267` |
| R23X-02 | **P1** | 两个**资金类**托管任务没有 `@Scheduled` 回退，`XXL_JOB_ENABLED` 默认 false ⇒ 默认配置下平台资金日检 / 欠租锁柜零触发，且违反同文件 Javadoc 的明文承诺 | `PlatformFundService.java:276`、`DeviceRentalService.java:540`、`XxlJobManagedTasks.java:14` |
| R23X-03 | P3（充值）/ **P2（退款）** | 微信回调 nonce 验签后立即占位、失败不释放（支付宝同类已修到入账前）⇒ 入账异常后重投被判重放。充值有 5min 清扫兜底（自愈≈35min），退款同步无同频兜底 | `WeChatPayNotifyService.java:59,120-131`、`RechargeOrderScheduler.java:29` |
| R23X-04 | P2 | 看板 `LOW_STOCK` 行「查看」按钮的权限判据与实际跳转不同源（判 `/replenishment`、跳 `/stock-health`，两个不同权限码） | `DashboardView.vue:527-529` vs `:697` |
| R23X-05 | P2 | `check:mp-scoped-hash` 登记在聚合链里，但在 CI 里**恒 SKIP**（产物被 gitignore，门禁步骤早于任何 mp 构建）⇒ 零覆盖 | `scripts/check-mp-scoped-hash.mjs:83-86`、`ci.yml:750` |
| R23X-06 | P2 | 跨端开门超时不变式（网关 70s ≥ trade 60s）无代码强制，且超时落到 `FAILED`，而兜账只救 `CANCELLED` | `SessionWatchdog.java:75`、`application.yml:364`、`VisionResultIngestService.java:97-107` |
| R23X-07 | P2 | 门禁元判据只认 `--self-test`，5 个门禁用 `--selftest` 且仍留在 `exempt` ⇒ 「已获夹具的豁免必须移出」这条棘轮永不触发 | `check-audit-gates-wiring.mjs:59`、`gate-fixture-registry.json` |
| R23X-08 | P2 | 常驻横幅棘轮基线与实测差 3（`--print`=49、常量=52），注入 3 条仍绿 ⇒ 棘轮有余量、不会自动收紧 | `check-admin-banner-ratchet.mjs:21` |
| R23X-09 | P3 | 接线门禁的「传递闭包」把 `name` 加回自身（应为 `m[1]`）⇒ 规则 4 闭包是 no-op | `check-audit-gates-wiring.mjs:174-183` |
| R23X-10 | P3 | 4 个资金键天花板不在任何门禁判据内：改成 `999999999L` 全部门禁仍绿 | `SystemConfigService.java:485-489` |
| R23X-11 | P3 | admin 产物配对门禁两处 fail-open（取不到 merge-base 即 SKIP 绿；`mb==head` 即 OK），本地判据在基线上空转 | `check-admin-artifact-pairing.mjs:197-209` |
| R23X-12 | P2 | 设备押金是预授权第二来源：`Math.min(Integer.MAX_VALUE, …)` 无业务上界（不在 `CommissionParamBounds` 族）+ 分支先于 `fromDb==0` ⇒ 全局关闭失效（后者可辩，前者不可） | `ConsumerPreauthService.java:68-82`、`OpsDeviceAdminService.java:607-609` |
| R23X-13 | P3 | 台账规模数字漂移：迁移 379→**实测 474**、最新 V385→**V481**（另 controllers 实测 111、门禁 194、`check:*` 156） | `docs/PROJECT_KNOWLEDGE.md:37` |

**门禁负向注入实跑**：13 例三态验证 → **11 例真红 / 1 例锚点不唯一（已换锚点复现真红）/ 1 例为我的注入目标错（非门禁缺陷）/ 1 例基线余量（见 R23X-08）**，全程 `dirtyAfter=[]`（零残留）。明细见 §5。

---

## 1. 方法：我先证伪，再定级

1. **子代理自报一律不采信**。本路先起 7 条只读镜头（L1 新代码面 / L2 门禁健康 / L3 后端 / L3 前端边缘 / L4 结构化与数据 / L5 运行时 / 额外角度），回收后对每条 P1/P2 由主审**重新读码或实跑**，报告自报的绿/数字一律重测。
2. **判「不是缺陷」与判「是缺陷」同标准**：阴性结论也带路径:行号与实测值（§4）。
3. **像 bug 先假设刻意设计**：查邻近注释、commit message、UI 文案三处，再看同仓是否已有同名修复（同族对照）。
4. **实跑优先**：能跑的不看文档。本机实跑项包括 194 个门禁中的关键子集、隔离检出副本上的 13 例负向注入、Flyway 全量 checksum 对账、看板/权限/调度源码直读。
5. **不碰运行中的 dev 栈**：只读 `docker exec psql` 查 `flyway_schema_history`，未执行任何写操作、未重建容器。

---

## 2. P1 亲验结论

### R23X-01（P1）写端点归因门禁漏枚举 22 个写端点，且基线即由坏解析器种下

**位置**：`scripts/check-write-endpoint-attribution.mjs:267`（签名探测正则）

```js
if (/public\s+[\w<>,\s]+?\s+\w+\s*\(/.test(lines[j])) { ... }   // ← 字符类缺 `.`
```

**现象**：方法签名返回类型含 `.`（全限定名 / 泛型内的嵌套类，如 `ApiResponse<DictDtos.Item>`）时，正则匹配不上，`sig<0` 直接 `continue` —— 该端点**连 snapshot 都不进**，于是「新增写端点必须归因」与「总量双向对平」两条判据对它全部失效。

**我的独立量化**（脚本：`.tmp/r23-neg/.tmp/r23-quantify.mjs`，直接 import 门禁导出的 `controllerEndpoints` 并自行按注解计数）：

| 文件 | 注解数 | 门禁解析 | 漏 |
|---|---|---|---|
| `AdminJiangyiController.java` | 27 | 21 | 6 |
| `OpsWarehouseController.java` | 19 | 15 | 4 |
| `OpsExceptionController.java` | 10 | 7 | 3 |
| `SysDictController.java` | 5 | 2 | 3 |
| `AccountController.java` | 8 | 6 | 2 |
| `SystemConfigController.java` | 5 | 4 | 1 |
| `MerchantWalletController.java` | 3 | 2 | 1 |
| `DemoDataInternalController.java` | 1 | 0 | 1 |
| `MemberController.java` | 1 | 0 | 1 |
| **合计** | **477** | **455** | **22** |

**决定性证据**：`scripts/write-endpoint-attribution-baseline.json` 里 `totalEndpoints = 455` —— 与**坏解析器的输出**逐字相等（真实注解数 477）。这说明基线是照着缺陷解析器种的，因此该缺口永远对不平、也永远不会红。

**判据**：门禁自述「新写端点未归因即红 + 总量双向对平」。漏枚举 ⇒ 运营侧写端点（`AdminJiangyi`/`OpsWarehouse`/`OpsException`/`SysDict`/`SystemConfig` 共 15 个）处在「看不见」状态，MA-082 家族的「权限码 + 审计留痕 + 前后值」三必达防线在这些端点上没有判据。

**修复**：① 正则字符类补 `.`（`[\w<>,\s.]`）；② 重种基线（`totalEndpoints` 455→477），`maxUnattributed` 只许下调，不得借重种上调；③ 补一条**静态**夹具钉住「返回类型含点号的端点必须入 snapshot」（现在 53 个 `*.test.mjs` 里没有这一形态）。

**是否刻意**：否。注释 ④ 说的「未解析端点显式入基线」指**已枚举但递归不可达**（`attributed=false`），与「根本没枚举」是两件事。

---

### R23X-02（P1）两个资金类定时任务无 Spring 回退，默认配置下零触发

**位置**：`services/trade-service/.../service/PlatformFundService.java:276`、`DeviceRentalService.java:540`、`service/XxlJobManagedTasks.java:14,66-67`、`resources/application.yml:374`

**实测**：

```
grep -c '@Scheduled' PlatformFundService.java DeviceRentalService.java   → 0 / 0
XxlJobManagedTasks.KEYS                                                  → 含 "platform-fund-daily"(66) "device-rental-daily"(67)
application.yml:374                                                      → enabled: ${XXL_JOB_ENABLED:false}
```

**反证承诺**：`XxlJobManagedTasks` 类注释写得很明确 ——「关闭该开关则全部回退到 Spring 常驻调度 —— 这是调度中心不可用时的逃生门，故 **`@Scheduled` 注解一律保留**」。这两个类恰恰没有 `@Scheduled`，逃生门对它们不成立。

**后果**：`XXL_JOB_ENABLED=false`（本地/IDEA 默认，也是 compose 未显式开启时的默认）⇒ handler bean 不装配 + 无 Spring 调度 ⇒ 平台资金日检告警、欠租锁柜**静默停摆**，只剩运营台手动「立即执行」。`=true` 但调度中心故障时同样无兜底。

**门禁为何没拦**：`scripts/check-xxl-job-wiring.mjs` 只对平 `KEYS ↔ handler ↔ seed ↔ 控制台`（该文件第 15-19 行自述），**没有**「KEYS 成员必须保留 Spring 回退或显式豁免」这条判据。

**修复**：两方法补 `@Scheduled(cron=..., zone=ScheduleZones.ZONE_PROP)`（XXL 开启时经 `tryBegin` 自动让位，行为不变）；并给 `check-xxl-job-wiring` 加「托管 key 必须有 `@Scheduled` 或豁免登记（含理由）」规则，否则同一个洞还会再开一次。

**是否刻意**：否。同文件把「刻意排除的 2 个任务」逐条列了理由（stale-monitor / cache-purge），这两个不在其中，属遗漏。

---

## 3. P2 / P3 亲验结论

### R23X-03（P2）微信回调 nonce 占位过早且失败不释放

`WeChatPayNotifyService.parseAndVerify`：验签（`:55`）→ 时效（`:58`）→ **`assertNonceOnce`（`:59`）** → 解密/校验商户号（`:60-76`）。占位后若无业务异常，一切正常；但 `PaymentService.handleWeChatNotify` 里 `creditRechargeNotify(...)`（`:377`）一旦抛异常，异常传播到 `api/WeChatNotifyController.notify`（无 try/catch）→ 500 → 微信重投 → **同一 nonce 被 `WECHAT_NOTIFY_REPLAY` 拒（`:127-129`）**，10 分钟内该笔充值不会入账；超过 `TIMESTAMP_SKEW_SECONDS` 后又会被时效拒。

**同仓两处已修过同族形态**（这是判「非刻意」的关键证据）：
- `AlipayNotifyService.java:45-48` —— H13 注释明写「`notify_id` 去重占位**不在验签阶段执行**，移到金额校验通过之后、入账之前」；
- `NotificationDispatchListener` —— 明写「dispatch 失败必须释放键，否则 24h 内重投全被挡」并实现 `releaseDispatchMark`。

微信侧两者都没有。**注意收敛**：三条 `alertSilentCallback + return` 分支返回 204（微信不再重投，属刻意的「留痕转人工」），**不**是缺陷；缺陷只在「占位之后抛异常」这一条路径上。

**🔴 2026-11-15 交叉验证后按路径分裂定级（原判 P2 过重）**：
- **充值回调**（`/notify`）→ **P3**。`RechargeOrderScheduler:29` `@Scheduled(fixedDelayString ... :300000)` 每 5 分钟调 `autoCancelExpiredPending()`，后者逐单 `syncPendingOrder(order)` 先查单、已支付即补入账（`PaymentService:648-676`）；阈值 `RECHARGE_AUTO_CANCEL_MINUTES` 默认 30 分钟 ⇒ **最长约 35 分钟自愈**，前提是该清扫任务在跑。
- **退款同步回调**（`/refund-notify`）→ **仍 P2**。`handleWeChatRefundNotify` 之后没有同频清扫：`autoCancelExpiredPending` 只处理 `PENDING` 充值单，退款侧靠「每日对账查单」或人工（`WeChatNotifyController` 注释自述盲区靠日对账兜底）⇒ 幽灵余额窗口以「天」计。

**修复**：把占位移到「订单/金额校验通过后、入账前」（对齐 H13），或业务异常时按 payload 释放 nonce（对齐 listener）；业务幂等键保留作双保险。

### R23X-04（P2）看板「缺货」行按钮权限判据与跳转不同源

```
DashboardView.vue:527-529  actionTargetPath: case 'LOW_STOCK': case 'REPLENISHMENT' → '/replenishment'
DashboardView.vue:697      goAction:        case 'LOW_STOCK': router.push('/stock-health')
menu.ts:87                 '/stock-health'  perm: ops:stock-health:list
menu.ts:207                '/replenishment' perm: ops:replenishment:list
```

`canHandleAction(row)` = `canAccessPath(actionTargetPath(row))`（`:545-547`），而 `router` 守卫对 push 目标做 `findNavByPath` 精确匹配、查不到即 fail-closed。于是出现**两种错**：只有 `ops:stock-health:list` 的角色 → 该行**不出按钮**；只有 `ops:replenishment:list` 的角色 → **出按钮**、点击被守卫拦成「无权访问」。

同一文件里 `quickLinks` 的「缺货柜/SKU」以及 `goAction` 都指 `/stock-health`，**三处口径两处对一处错**，无注释辩护 ⇒ 判「改一半」遗留，非刻意。修复：`actionTargetPath` 拆出 `case 'LOW_STOCK': return '/stock-health'`。

### R23X-05（P2）`check:mp-scoped-hash` 在 CI 里恒 SKIP

- 该门禁在聚合链内：`package.json` → `check:audit-gates` 含 `pnpm check:mp-scoped-hash`；
- 它扫的是 `clients/*/dist/{dev,build}/mp-weixin`，而 `.gitignore` 忽略 `clients/*/dist/`，`git ls-files 'clients/*/dist/*'` = 0；
- CI 里 `pnpm check:audit-gates` 在 `ci.yml:750`（`mini-programs` 作业），该作业在它之前只跑 `build:packages`（只写 packages/dist），**mp-weixin 构建在 `ci.yml:847/858`，且在门禁之后、还需 secret 才执行**。

**实跑证据**（干净检出副本）：`node scripts/check-mp-scoped-hash.mjs` → `SKIP：未找到产物目录（先构建小程序）`，`EXIT=0`。即：CI 每次都是这条输出。判据本身（防共享组件 scoped 哈希落空）没有失效，但**在 CI 上从未执行过**，属「登记了但零覆盖」。

**修复**：CI 里该门禁加 `--require-artifacts`（无产物即红），或在 gate 之前先做一次 mp 构建；否则从聚合链里摘掉并如实标注「仅本机有产物时有效」。

### R23X-06（P2）跨端开门超时不变式无强制，且超时落 `FAILED`（兜账不救）

- 网关：`SessionWatchdog.java:75` `@Value("${jiangyi.watchdog.open-timeout-ms:70000}")`；
- trade：`application.yml:364` `opening-seconds: ${SESSION_OPENING_EXPIRE_SECONDS:60}`，消费于 `SessionExpireService:78`；
- 两端是**两个独立环境变量**，注释/测试反复声称「70s ≥ 60s + 调度余量」，但 `SessionWatchdogTest` 全 mock、阈值硬编码 `70_000L`，**没有任何一处读对端配置**。
- 后果链：把 `SESSION_OPENING_EXPIRE_SECONDS` 调到 120（该键在上轮刚被从 10 调到 60，属会被调整的键）→ 网关 70s 就把会话置 `FAILED`；而 `VisionResultIngestService:97-107` 的兜账 v2 **只把 `CANCELLED` 转 `DISPUTED`**，`FAILED` 仅留 HIGH 异常留痕 ⇒ 回到「货已取走、无单可结、只能人工」。

**修复**：两端同源（同一配置中心键），或启动期加硬校验 `网关阈值 ≥ trade opening-seconds + margin`；测试改为读两端配置断言，而不是断言常量。

### R23X-07（P2）元判据只认 `--self-test`，5 个 `--selftest` 门禁卡在 exempt

`check-audit-gates-wiring.mjs:59` 与 `check-gate-test-coupling.mjs:136` 同款：

```js
const selfTest = /\bselfTest\s*\(/.test(src) && src.includes('--self-test');
```

实测有 5 个门禁自带可执行负向夹具但用的是 `--selftest`：`check-admin-artifact-pairing`、`check-door-cabinet-slot-copy`、`check-mp-emoji-icon`、`check-mp-touch-target-ratchet`、`check-stocktake-chain`。它们**全部**仍在 `gate-fixture-registry.json` 的 `exempt`（67/69）里。于是规则「已获得覆盖的豁免条目 = 陈旧必须移出（棘轮只减不增）」对这 5 条**永不触发**，豁免池只增不减。

另：`check-admin-artifact-pairing.mjs:192` 自报「8 例：4 主判据 + 4 豁免约束」，判据本体是有夹具的 —— 更加说明它们不该留在 exempt。

**修复**：识别口径改为 `/(^|\s)--self-?test/`（或同时接受 `--selftest`），并把 5 条移出 `exempt`、同步下调 `maxExempt`。

### R23X-08（P2）横幅棘轮基线与实测差 3

```
node scripts/check-admin-banner-ratchet.mjs --print   → { "files": 85, "total": 49 }
BASELINE（:21）                                        → 52
```

我向 `DashboardView.vue` 的 `<template>` 注入 3 条 `<el-alert>` → 门禁输出 `el-alert=52≤52`，**仍绿**。即：棘轮有 3 个免费名额，且计数下降后基线不会自动收紧（`--print` 与常量之间没有任何对账）。这与我先前 C13 的「未变红」是同一件事，不是门禁坏，是基线与实测脱钩。

**修复**：基线降到 49，或让门禁在 `total < BASELINE` 时也红（要求同步下调），避免「先减后加」的回旋空间。

### R23X-09（P3）接线门禁「传递闭包」写错变量 ⇒ 规则 4 是 no-op

```js
// check-audit-gates-wiring.mjs:174-183
for (const name of [...reached]) {
  for (const m of (scripts[name] || '').matchAll(/pnpm\s+(?:run\s+)?(check:[A-Za-z0-9:_-]+)/g)) {
    if (checkScriptNames.includes(m[1])) reached.add(name);   // ← 把 name 加回自身
  }
}
```

应 `reached.add(m[1])`。现状：循环第一轮 `reached.size` 不变 → 第二轮 break ⇒ 传递闭包恒等于初始集。当前门禁仍绿是因为规则 2 另有「CI 直接调用 / 可达脚本文本提及」的兜底路径；但「A 脚本内部 `pnpm B`、B 未被任何地方直接引用」这种真实接线形态，本门禁**判不出来**。

### R23X-10（P3）资金键天花板不在任何门禁判据内

`SystemConfigService.java:485-489` 的 `FUND_KEY_CEILINGS`（提现手续费 bps ×2、余额退款上限、开门预授权上限）是资金读侧回落的最后一道闸。我把它改成 `999999999L` 后实跑：`check-commission-param-bounds`（只扫 `set*()` 调用点，此处在 `Map.of` 里）、`check-feature-flags`（只查键登记与读取点）**全绿**。即：这 4 个上限值可以被静默改大，无任何判据。

不主张加「禁止改常量」的门禁（常量本来就该能改），但建议：写侧 `validateFundKeyValue` 与这张表**同源**（现在是一处 Map + 一处前缀分支，两处口径要人工对齐），并加一条纯函数用例钉住「4 个键的 ceiling 与校验分支取值相同」。

### R23X-11（P3）admin 产物配对门禁两处 fail-open

`check-admin-artifact-pairing.mjs:199-209`：`merge-base` 取不到 → `SKIP` + `exit 0`；`mb==head` → 「无待推提交」+ `exit 0`。在基线快照上（`origin/dev==HEAD`）我实跑得到的就是后者 —— 本地判据在基线上**结构性空转**。这是有注释、有 `--selftest` 8 例、且 CI 有字节级重建兜底的**刻意取舍**，所以只列 P3 备忘：文档应写明「本地这条门禁不构成正确性证明」，别让台账把它当绿灯依据。

### R23X-12（P2，2026-11-15 交叉验证后由 P3 上调）设备押金：无业务上界 + 穿透「0=关闭」

`ConsumerPreauthService.resolvePreauthCents`：

```java
if (device != null && device.getDepositCents() != null && device.getDepositCents() > 0) {
    return (int) Math.min(Integer.MAX_VALUE, device.getDepositCents());   // :70-72
}
int fromDb = systemConfigService.getInt(SystemConfigService.CHECKOUT_PREAUTH_CENTS, -1);
if (fromDb == 0) return 0;                                               // :74-82 关闭分支
```

两条并存的事实：
1. **分支序**：设备押金先于全局键返回 ⇒ 「全局配 0 关闭预授权」对配了押金的柜机**不生效**；
2. **无业务上界**（本节原判漏掉，由模块轮 MA-178 补足、我已复核）：这里是 `Math.min(Integer.MAX_VALUE, …)`，**不是** `CommissionParamBounds.PREAUTH_CENTS_CEILING`；全局键在 `:83-90` 才有天花板闸，写侧 `OpsDeviceAdminService:607-609` 也无上界。⇒ 配一个荒谬押金就能冻结消费者对应余额，绕过 S12「资金乘数必须过闸」的整族防线。

「具体配置压全局」本身可以是合理优先级（设备押金是业务配置、不是风控开关，UI 文案 `DeviceAssetDeploymentCard.vue:295`「开门押金（元）」），但**无上界**这一点没有语义辩解的余地。故上调 P2：至少补天花板 + 补配置页互斥说明；「0 是否该压倒设备押金」仍需产品拍板。

### R23X-13（P3）台账规模数字漂移

| 项 | 文档 §2 | 本机实测 |
|---|---|---|
| Flyway 迁移 | 379 个 / 最新 V385（`:37`） | **474** / **V481**（`ls V*.sql`=474，数据库 `flyway_schema_history`=474，全部 success） |
| controllers | 未列（旧台账 93） | **111**（`git ls-files '*Controller.java'`） |
| 门禁脚本 | 未列 | **194** 个 `scripts/check-*.mjs`、**53** 个 `*.test.mjs`、**156** 个 `check:*` 脚本 |
| 审计门禁链 | `pnpm check:audit-gates` | 实跑「145 个引用全部有定义、194 个门禁全部接线」 |

漂移方向是「增长未回填」，不是断链；但任何拿 `379/385` 做基数验收的检查（UAT 手册、迁移对账脚本）都会误判。

---

## 4. 我核过的阴性结论（判「不是缺陷」，附证据）

| # | 结论 | 证据 |
|---|---|---|
| N-01 | **Flyway 一致性无问题**：474 个已应用迁移的当前文件 checksum 与 dev 库**逐条相等**（474/474，0 不符） | 自写 CRC32 比对（Flyway 算法：去 BOM、逐行去换行后连续 CRC32、转 int32）+ `docker exec psql` 读 `flyway_schema_history`，见 `.tmp/r23-flyway-checksum.mjs`。**这条推翻了「已应用迁移被改写 ⇒ validate 拒服」的推断**（详见 §6-1） |
| N-02 | 分账 `rate=0` 不发微信请求、无 receiver 走 LEDGER_ONLY、按腿立账 —— 口径自洽 | `RevenueSplitService.java:144-167,485-504`、`WeChatProfitSharingService.java:99-144`（前轮已核，本轮复读未变） |
| N-03 | 状态机迁移是**白名单**（`canTransitionTo` switch 枚举），终态不会被黑名单式放行 | `common-core/.../enums/SessionState.java:24-40` |
| N-04 | 兜账 v2 的 `CANCELLED→DISPUTED` 是**刻意**只覆盖 CANCELLED（`FAILED` 留 HIGH 异常留痕，注释写明「真实事故路径只有 CANCELLED」） | `VisionResultIngestService.java:97-107`。缺陷不在这个选择，而在 R23X-06 的「网关会把会话打成 FAILED」 |
| N-05 | `alertSilentCallback + return`（204）不是静默丢弃，是「渠道已收款但无主/金额异常 ⇒ 留痕转人工」的刻意分支 | `PaymentService.java:358-376,461-467`（注释 P3-4 明写旧写法 `.ifPresent()` 完全静默是错的） |
| N-06 | admin 产物配对在 CI 有字节级重建证明，本地两处 fail-open 有注释自证取舍 | `check-admin-artifact-pairing.mjs:6-30,112-194`（`--selftest` 8 例，含真事故复现） |
| N-07 | `check-fix-batch-coverage` 与 `check-ledger-evidence` 都能真红 | §5 的 C09 / C10 三态验证 |

---

## 5. 门禁负向注入三态验证（隔离检出副本，零残留）

**做法**：`git clone --no-hardlinks` 到 `.tmp/r23-neg`（后 `fetch --unshallow` 补齐 1695 个 commit，与 CI `fetch-depth: 0` 同口径），在副本内「记录基线 → 注入 → 跑门禁 → 还原 → 再跑」，最后校验 `git status --porcelain` 为空。**主工作区一行代码未动**。

| 例 | 门禁 | 注入 | 基线 | 注入后 | 还原后 | 判定 |
|---|---|---|---|---|---|---|
| C01 | check-yuan-to-cents | 裸 `*100` | 0 | 1 | 0 | 真红 ✔ |
| C02 | check-status-enum-parity | 状态串漂移 | 0 | 1 | 0 | 真红 ✔ |
| C03 | check-permission-matrix | 权限码不对齐 | 0 | 1 | 0 | 真红 ✔ |
| C04 | check-outbound-client-factory | 裸 `HttpClient` | 0 | 1 | 0 | 真红 ✔ |
| C05 | check-openapi-types | 产物与 DTO 漂移 | 0 | 1 | 0 | 真红 ✔ |
| C06 | check-window-limit-honesty | 窗口截断无回显 | 0 | 1 | 0 | 真红 ✔ |
| C07 | check-ps1-bom | 加 BOM | 0 | 1 | 0 | 真红 ✔ |
| C08 | check-script-table-set | 表名未登记 | 0 | 1 | 0 | 真红 ✔ |
| C09 | check-fix-batch-coverage | `totalItems` 1→2（`items=1`） | 0 | 1 | 0 | 真红 ✔（首轮锚点 `"totalItems": 1,` 出现 5 次未能定位，改用 JSON 定点改写后复现） |
| C10 | check-ledger-evidence | 台账塞入不存在的 `check:` / `V999__` / 路径 | 0 | 1 | 0 | 真红 ✔ |
| C11 | check-feature-flags | 读取点未登记的键 | 0 | 1 | 0 | 真红 ✔ |
| C12 | check-commission-param-bounds | `FUND_KEY_CEILINGS` 改 `999999999L` | 0 | **0** | 0 | **我的注入目标错**：该门禁只扫 `set*()` 调用点，此处是 `Map.of` 常量 ⇒ 不构成门禁缺陷，但暴露 R23X-10 的空白 |
| C13 | check-admin-banner-ratchet | 注入 3 条 `<el-alert>` | 0 | **0** | 0 | **基线余量**：49+3=52=BASELINE ⇒ R23X-08 |

**结论**：`11/11` 有效注入全部真红，还原后 `git diff` 为空、`dirtyAfter=[]`。门禁主体判据是活的；坏的是判据**覆盖面**（R23X-01/05/07/10）和**基线纪律**（R23X-08）。

> 附带发现（非缺陷，但会误导取证）：`check-fix-batch-coverage` 在**浅克隆**下会红 27 条「登记的 commit 不存在」。CI 的相关作业已带 `fetch-depth: 0`（`ci.yml:716-717` 注释还专门写了 2026-11-13 的实测事故），所以这是**取证姿势**问题不是门禁问题 —— 但任何人用 `--depth 1` 复现 CI 都会看到假红。

---

## 6. 分歧与修正（供交叉核对）

以下是本路**实测后**与「其他镜头自报」不一致的三处。列出来是为了让两路报告能逐条对账，不是否定对方 —— 差异点本身都值得复核。

### 6-1 「已应用迁移被事后改写 ⇒ 目标库 validate 拒服」——**后果不成立（dev 库）**

自报口径：`git log --diff-filter=M` 得 35 个 `V*.sql` 被改（19 个 ≤400 老版本）⇒ 判定这是 P1「已应用脚本改内容，目标库 validate 会拒服」。

本路实测：把 474 个迁移按 **Flyway 算法**（去 BOM → 逐行去换行 → 连续 CRC32 → 转 int32）算 checksum，与 dev 库 `flyway_schema_history.installed_checksum` 逐条比对 → **474/474 完全一致，0 条不符**；且 trade-service 容器正跑在 V481 上（库内 496 条历史 = 474 条版本化 + 22 条 repeatable，全部 `success`）。

所以：
- 「历史上有文件被改过」成立（git 事实）；
- 「现在会导致 validate 失败」**不成立**（dev 库已 repair 或改写发生在应用之前，最终一致）；
- 残留风险只在 **UAT/生产**是否 repair 过 —— 本机无 UAT 库可查，属「未验证」而非「已证明有问题」。

**建议改判**：P1 → P2/P3「历史改写缺少跨环境审计闭环」，修复动作不变（`MIGRATION_BASE_REF` 固定到发布 tag、`base==HEAD` 时提示），但不要写成「现在就会拒服」。

### 6-2 「0=关闭预授权对设备押金失效」——**分支序可辩，但押金无上界是真缺陷（我原先只看到前一半）**

自报口径（子代理）：P2，建议把 `fromDb==0` 提到方法最前。

本路初次核查：设备押金是独立业务配置，不是风控开关；「具体配置压全局」是常规优先级，于是**降 P3**、只提文案。

**2026-11-15 与模块轮 MA-178 交叉验证后修正**：MA-178 指出押金走的是 `Math.min(Integer.MAX_VALUE, …)`，**不在** `CommissionParamBounds` 天花板族内、写侧也无上界。我复核 `ConsumerPreauthService:71` 与 `OpsDeviceAdminService:607-609`，确认属实 —— 这一半没有语义辩解的余地（可写出荒谬押金并冻结消费者余额，绕过 S12 整族防线）。⇒ **合并结论：R23X-12 上调 P2**；「0 是否该压倒设备押金」保留待拍板，但「押金必须有上界」不需要拍板，直接修。

### 6-3 「admin 产物配对门禁三处恒放行」——**是真的，但属刻意取舍，P3 备忘**

`SKIP` / `无待推提交` / 「有任意 `static/admin` 文件即放行」三处确实存在，但文件头注释自证「刻意窄，宁可漏报不误伤」、`--selftest` 8 例、CI 另有字节级重建。判**刻意**，只建议文档别把它当正确性证明。

---

## 7. 本路未亲验（诚实声明）

以下来自镜头自报、本路**未逐行复核**，不作为本报告结论，仅供交叉核对时定位：

1. 运营侧「215 条未归因中 134 条属两级判据口径差」的逐端点分解（依赖 S9 扫描器口径，我只复核了 R23X-01 的漏枚举）；
2. `check-competitor-benchmark` 的 R6 存量盲区（`V358` 声明 `N/A` 但含业务词）——只看了门禁结构，未跑全量 N/A 复查；
3. 9 处 `ADD COLUMN` 无 `IF NOT EXISTS`（V331/V336/V342/V348/V349/V403）——未逐条打开确认；
4. `z-index` 裸值 / theme.css 令牌「值不一致」两族 UI 存量项 —— 未逐值比对；
5. UAT/生产库的 Flyway checksum 一致性（无库可连）；
6. `ScheduledTaskRegistry` 44 / KEYS 38 / seed 38 三方对照的逐 key 差集（本路只核了 R23X-02 两个 key）。

---

## 8. 给下一批的建议排序（按「修一个能关一类」排）

1. **R23X-01 + R23X-10**：一次修「判据覆盖面」两个洞，顺带把基线重种流程写成脚本，避免下次又照着坏解析器种数。
2. **R23X-02 + R23X-06**：都是「跨端/跨机制不变式没有代码强制」，可合并成一个「启动期不变式自检」小批次（`ProductionStartupValidator` 加两条断言：网关阈值 ≥ trade opening-seconds + margin；每个托管 key 必须有 `@Scheduled` 或豁免）。
3. **R23X-03**：单点修复，照抄支付宝 H13 的形态，风险最低、价值最高（资金回调）。
4. **R23X-04**：一行修复 + 一条静态判据（`actionTargetPath` 与 `goAction` 的 type→path 映射必须同源，可用 AST 或双表比对）。
5. **R23X-05 + R23X-07 + R23X-08**：把「登记在链里但不执行的」「豁免了却已有夹具的」「基线与实测脱钩的」清一遍，让门禁面板数字重新可信。
6. 其余 P3 随修复批次带走，不必单开。

---

## 9. 复现命令清单

```bash
# 基线
git rev-parse HEAD && git status --porcelain

# R23X-01 端点漏枚举（在隔离副本内跑，import 门禁自身实现）
node .tmp/r23-neg/.tmp/r23-quantify.mjs

# R23X-02 资金任务无 Spring 回退
grep -c '@Scheduled' services/trade-service/src/main/java/com/aicabinet/trade/service/{PlatformFundService,DeviceRentalService}.java
grep -n 'platform-fund-daily\|device-rental-daily' services/trade-service/src/main/java/com/aicabinet/trade/service/XxlJobManagedTasks.java
grep -n 'XXL_JOB_ENABLED' services/trade-service/src/main/resources/application.yml

# N-01 Flyway 全量 checksum 对账（只读 dev 库）
docker exec -i ai-cabinet-postgres-1 psql -U aicabinet -tA -F$'\t' \
  -c "select version,checksum,script from flyway_schema_history where version is not null order by installed_rank;" > .tmp/r23-flyway-db.tsv
node .tmp/r23-flyway-checksum.mjs     # → same 474 / mismatch 0

# R23X-05 干净检出下门禁恒 SKIP
cd .tmp/r23-neg && node scripts/check-mp-scoped-hash.mjs   # → SKIP, EXIT=0

# R23X-07 / R23X-08
node -e "const r=require('./scripts/gate-fixture-registry.json');console.log(r.exempt.length,r.maxExempt)"
node scripts/check-admin-banner-ratchet.mjs --print         # → total 49
```

---

**审计人**：主审（第二路独立）
**完成时间**：2026-11-15
**状态**：只读审计，未修改任何被审文件；本文件为新增报告。

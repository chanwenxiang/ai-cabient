# VERIFY-EXT2 — 外部报告《AUDIT_R23_INDEPENDENT_2026-11-15》对抗性验证

> **验证人**：第三路对抗验证（证伪者），2026-11-15。
> **验证对象**：`docs/AUDIT_R23_INDEPENDENT_2026-11-15.md`（外部 AI 第二路"主审亲验"报告，13 条 R23I-01..13，自述 P1×2 + P2×7 + P3×4）。
> **验证基线**：`HEAD = fdf5f3e42d6f1cf91bde924046ef2cdf5ad1fec6`（`git log --oneline -3` 确认，≥ 报告自称基线）。一切判定以当前 HEAD 实跑/读码为准。
> **纪律自证**：工作区真文件零改动（全程 `git status --porcelain` 前后比对一致，唯一新增物=本文件 + `.tmp/verify-ext2/` 下取证脚本，后者 gitignore）。负向注入全部在 `.tmp/verify-ext2/clone`（`git clone --no-hardlinks`，HEAD 同 fdf5f3e4）内做，每次注入后 `git checkout --` 还原并 diff 自证 CLEAN（见 §3 各条"残留"行）。
> **取证脚本**：`.tmp/verify-ext2/quantify-endpoints.mjs`、`diagnose-misses.mjs`、`closure-buggy-vs-fixed.mjs`、`flyway-checksum-verify.mjs`、`patch-gate-in-clone.mjs`（注入 patcher）。

---

## 1. 总表

| R23I | 报告 P 级 | 我的判定 | 我的 P 级 | 一句证据 |
|---|---|---|---|---|
| 01 写端点归因漏枚举 22 | P1 | **成立**（根因修正：19 点号 + 3 同行签名） | P1 | 独立复算 477 注解 vs 门禁 455，逐文件漏数与报告表格全同；基线 `totalEndpoints=455` 恰等于坏解析器输出 |
| 02 两资金任务无 Spring 回退 | P1 | **成立，与我方 MA-260 同源重叠**（口径差见 §4-①） | P1 | 两文件 `grep -c '@Scheduled'`=0；KEYS:66-67 在册；yml:374 默认 false；handler bean `@ConditionalOnProperty` ⇒ 关闭即零触发；`.env.staging:70` 实为 false（比报告更实的部署面证据） |
| 03 微信回调 nonce 占位过早 | P2 | **成立** | P2 | `assertNonceOnce` 在解密/业务前（:59），失败不释放；重投 10min 内被 `WECHAT_NOTIFY_REPLAY` 拒；支付宝 H13 注释 + `NotificationDispatchListener.releaseDispatchMark` 两处同族已修实证"非刻意" |
| 04 看板缺货行按钮判据/跳转不同源 | P2 | **成立** | P2 | `actionTargetPath` LOW_STOCK→`/replenishment`、`goAction` LOW_STOCK→`/stock-health`、`canHandleAction=canAccessPath(actionTargetPath)`，两路径 perm 码不同（menu.ts:87 vs :207） |
| 05 check:mp-scoped-hash CI 恒 SKIP | P2 | **成立** | P2 | 干净 clone 实跑 `SKIP…EXIT=0`；ci.yml:750 门禁先于 :847/:858 的 mp 构建；`clients/*/dist/` gitignore（`git ls-files`=0） |
| 06 跨端开门超时无强制 + FAILED 兜账不救 | P2 | **成立** | P2 | 网关 `JIANGYI_WATCHDOG_OPEN_TIMEOUT_MS:70000` 与 trade `SESSION_OPENING_EXPIRE_SECONDS:60` 两独立键，全仓无比对逻辑；兜账 v2 只救 CANCELLED（:97-107 注释自证）；`check:open-timeout-contract` 只对 trade↔C端，不覆盖网关↔trade |
| 07 元判据只认 --self-test，5 门禁卡 exempt | P2 | **成立**（修正：拼接修复只救 3/5，见 §3-07） | P3 | 判据 `src.includes('--self-test')`（wiring:59、coupling:136）；5 门禁全用 `--selftest` 且全在 exempt（67/69）；注入实证拼写修复后仅 3 条报"豁免陈旧" |
| 08 横幅棘轮基线差 3 | P2 | **成立**（P 级降） | P3 | `--print`=49、`BASELINE=52`；clone 注入 3 条仍绿（52≤52）、第 4 条才红（53>52）——复算与报告一致 |
| 09 接线门禁传递闭包写错变量 | P3 | **成立（当前 latent 零判定影响）** | P3 | `reached.add(name)`（:179）在 `for (name of [...reached])` 内=只能加回自身；用真实 package.json+workflows 仿真 buggy vs fixed，reached 均为 154、unreachable 差集为空 ⇒ 缺陷真、现行无后果 |
| 10 资金键天花板不在门禁判据内 | P3 | **部分成立**（事实真、风险定性弱，见 §3-10 反证） | P3 | 无门禁引用 `FUND_KEY_CEILINGS`（grep 空）；但 Map 值本就引用 `CommissionParamBounds` 常量（同源已在）+ 写侧 400 运行时防线在（S12 拍板⑧设计如此） |
| 11 admin 产物配对两处 fail-open | P3 | **成立（报告已自认刻意取舍）** | P3 | `merge-base` 失败→SKIP exit 0、`mb==head`→"无待推提交" exit 0（:199-209 逐行确认）；文件头注释+8 例 selftest+CI 字节级重建自证取舍 |
| 12 「0=关闭预授权」对设备押金不生效 | P3 | **成立，与我方 MA-178 + 拍板⑩ 重叠** | P3 | `resolvePreauthCents` :68-72 设备分支先于 :74-81 全局 0 分支；外部"倾向语义分层、P3 待拍板"与我方拍板⑩口径一致 |
| 13 台账规模数字漂移 | P3 | **成立，与我方 MA-261 同族（不同台账）** | P3 | PROJECT_KNOWLEDGE.md:37 写 379/V385，实测 474 个 V*.sql / 最新 V481；controllers 111、check-*.mjs 194、*.test.mjs 53 全部与报告实测一致 |

**计数**：成立 10（01/03/04/05/06/07/08/09/11/13）＋ 与我方重叠 2（02≈MA-260、12≈MA-178+拍板⑩）＋ 部分成立 1（10）＋ 不成立 0。

---

## 2. 成立条目的复算/复现细节 + 建议新 MA 号

### 2.1 R23I-01（建议 **MA-280**，P1）

**独立复算**（`.tmp/verify-ext2/quantify-endpoints.mjs`，直接 import 门禁导出的 `controllerEndpoints`，注解按 masked 源逐行自数）：

- 全部 4 个扫描根（trade/device/common-core/jiangyi-gateway）下带写注解的文件：注解行合计 **477**，门禁 `controllerEndpoints` 解析合计 **455**，差 **22**。
- 9 个漏文件逐文件数（annLines→gate）：AdminJiangyi 27→21、OpsWarehouse 19→15、OpsException 10→7、SysDict 5→2、Account 8→6、SystemConfig 5→4、MerchantWallet 3→2、DemoDataInternal 1→0、Member 1→0 —— **与报告表格逐格相等**。
- 基线 `scripts/write-endpoint-attribution-baseline.json`：`totalEndpoints=455`、`totalAttributed=240`、`maxUnattributed=215`，`455` 与坏解析器输出逐字相等 ⇒ "基线由坏解析器种下"成立；总量对平判据（evaluate 的 totals 分支）对这 22 个端点结构性失效。

**根因修正（报告只说对了一半）**（`diagnose-misses.mjs` 逐条诊断）：

- **19/22** 是报告说的点号缺陷：返回类型含 `.`（`ApiResponse<DictDtos.Item>` / 全限定 `com.aicabinet.common.dto.X`）匹配不上 `[\w<>,\s]`。逐一列出如下（19 条，含 SysDict 三条、OpsWarehouse 四条全限定、AdminJiangyi 五条等）。
- **3/22 是第二个缺陷**：`OpsExceptionController.java:55/69/105` 三处注解与签名**同行**（`@PostMapping(...) public ApiResponse<OpsExceptionDto> note(...` 跨行参数），枚举窗口 `for (let j = i + 1; ...)` 从 `i+1` 起扫，永远看不到同行签名。这三条返回类型**无点号**，点号修复救不回。
- ⇒ 修复必须两刀：① 字符类补 `.`（救 19）；② 窗口起点改 `j = i`（救 3）。只修报告说的那一刀会残留 3 条盲区。
- **隔离副本实证**：clone 内先只补点号 → 门禁红 `端点总量 455 → 474：快照已变，必须重种基线`（恰好 +19）；再补窗口 → `455 → 477`（全 22 复活）。同时证明总量对平判据本身是活的——修好解析器即红，重种基线是必经动作。（残留：CLEAN-AFTER-C3，clone 已还原）

**附带勘误**：报告 §R23I-01 判据段写"运营侧写端点…共 15 个"，按其自己的表格 6+4+3+3+1=**17** 个，笔误。

### 2.2 R23I-02 → 并入 **MA-260**（同源，不另立号）

复算全中：两文件 `grep -c '@Scheduled'` 均 0（exit 1）；`XxlJobManagedTasks.KEYS` :66/:67 在册；`application.yml:374` `enabled: ${XXL_JOB_ENABLED:false}`；`ScheduledTaskXxlJobHandler` `@ConditionalOnProperty(...havingValue="true")` ⇒ XXL 关闭时 handler bean 不装配、两方法仅被 `ScheduledTaskRegistry`(:150/:153) 引用，除运营台手动外零触发。Javadoc 逃生门承诺（XxlJobManagedTasks:14）+ 两个显式排除项（stale-monitor/cache-purge）核对无误——两任务不在豁免清单，属遗漏。同批"日结/月度三任务"（merchant-daily-income-notify 等）对照文件全有 `@Scheduled`，反衬遗漏。

**部署面加重证据（报告没抓到）**：`infra/.env.staging:70` 显式 `XXL_JOB_ENABLED=false` —— staging 环境这两个资金任务**现在就**处于零触发状态，不只是"本地 IDEA 默认"的理论风险。（compose `docker-compose.apps.yml:207` 默认 `-true`，报告"也是 compose 未显式开启时的默认"一句对 compose 不成立，见 §4-②。）

### 2.3 R23I-03（建议 **MA-281**，P2）

读码全链确认：`parseAndVerify` :55 验签 → :58 时效 → **:59 `assertNonceOnce` 占位**（TTL 10min）→ :61-71 解密/商户号 → 返回后 `PaymentService.handleWeChatNotify` :377 `creditRechargeNotify` 抛异常 → `WeChatNotifyController.notify` :26 无 try/catch → 500 → 微信重投 → `:127-129` `WECHAT_NOTIFY_REPLAY` 拒（≤10min）/ `WECHAT_NOTIFY_EXPIRED` 拒（>5min skew）。同族两处已修形态核对属实：`AlipayNotifyService` H13 注释原文"notify_id 去重占位不在验签阶段执行——移到金额校验通过之后、入账之前"；`NotificationDispatchListener:58/:76` `releaseDispatchMark` 存在。报告的收敛说明（三条 `alertSilentCallback+return` 204 属刻意、`syncPendingOrder/syncPendingFromWeChat` :606/:637/:658/:716/:1243 兜底在）逐条核对无误——定级 P2（丢失窗口存在、查单兜底收窄）恰当。

### 2.4 R23I-04（建议 **MA-282**，P2）

`DashboardView.vue`：`actionTargetPath` LOW_STOCK/REPLENISHMENT→`/replenishment`（:527-529）、`goAction` `case 'LOW_STOCK': router.push('/stock-health')`（:697）、`canHandleAction = canAccessPath(actionTargetPath(row))`（:545-547）；`config/menu.ts` `/stock-health` perm `ops:stock-health:list`（:87-92）、`/replenishment` perm `ops:replenishment:list`（:207-210）。两种错（只有 stock-health 权限→按钮不出现；只有 replenishment 权限→按钮出现但守卫拦）逻辑成立。小勘误：报告写 `menu.ts` 未给目录，实际在 `config/` 非 `router/`，行号本身对。

### 2.5 R23I-05（建议 **MA-283**，P2）

静态链 + 实跑双证：`package.json` `check:audit-gates` 链含 `check:mp-scoped-hash`；`check-mp-scoped-hash.mjs:83-86` `existing.length===0 → SKIP`（return，exit 0）；`.gitignore:16` `clients/*/dist/`，`git ls-files 'clients/*/dist/*'`=0；ci.yml mini-programs 作业 `check:audit-gates` 在 :750，此前仅 `build:packages`（只写 packages dist），mp 构建 :847/:858 在门禁后。干净 clone 实跑 `SKIP：未找到产物目录（先构建小程序）` EXIT=0。（残留：无写操作）⇒ CI 每次必为这条 SKIP 输出，"登记在链但零覆盖"成立。R23-B 的 13 注入未测过此门禁，与本结论不冲突（B 测判据咬合、I 报告测执行路径，正交）。

### 2.6 R23I-06（建议 **MA-284**，P2）

- 网关：`SessionWatchdog.java:75` `@Value("${jiangyi.watchdog.open-timeout-ms:70000}")` + gateway yml:46 `JIANGYI_WATCHDOG_OPEN_TIMEOUT_MS:70000`；trade：`application.yml:364` `opening-seconds: ${SESSION_OPENING_EXPIRE_SECONDS:60}`。两个独立环境变量，grep 全仓无任何"读对端比对"逻辑；`SessionWatchdogTest` 三处硬编码 `70_000L` 全 mock。
- 后果链：`SessionWatchdog:41` 注释自证"回执 15~60s 才到时，会话已被本类 open-failed 置 FAILED"；`VisionResultIngestService:97-107` 兜账 v2 只把 CANCELLED 转 DISPUTED，FAILED 仅 `reportLateRecognition` HIGH 留痕（注释"真实事故路径只有 CANCELLED"）。
- **补一个报告没提的旁证**：仓里已有 `check:open-timeout-contract` 门禁，但它钉的是 trade yml ↔ 消费端 index.vue 的 60s 契约，**不覆盖网关↔trade** 这一对——"看似有契约门禁实则没盖住这条边"，比报告的"无任何强制"更准确一层的描述。

### 2.7 R23I-07（建议 **MA-285**，P3——P 级降）

判据与登记全核实：`check-audit-gates-wiring.mjs:59` 与 `check-gate-test-coupling.mjs:136` 同款 `/\bselfTest\s*\(/.test(src) && src.includes('--self-test')`；5 门禁（admin-artifact-pairing / door-cabinet-slot-copy / mp-emoji-icon / mp-touch-target-ratchet / stocktake-chain）grep 确认**只有** `--selftest` 无 `--self-test`；5 条全在 `gate-fixture-registry.json` exempt（67/69）；admin-artifact-pairing:192 自报"8 例：4 主判据 + 4 豁免约束"属实。

**注入实证与修正**：clone 内把判据改为 `src.includes('--self-test') || src.includes('--selftest')` → 门禁报"豁免陈旧"**仅 3 条**（door-cabinet-slot-copy / mp-emoji-icon / mp-touch-target-ratchet），非 5 条。原因：admin-artifact-pairing 与 stocktake-chain 的自检**不是** `selfTest(` 函数形态（grep `selfTest(` =0，是 `process.argv.includes('--selftest')` + 内联 cases），第一个条件 `/\bselfTest\s*\(/` 也不满足 ⇒ 报告的修复方案（只改拼接识别）救不回这 2 条，需扩展检测（认 `--selftest` 直跑形态或落 fixtureMap）。**缺陷本身（5 条带可执行负向夹具的豁免永不触发棘轮）成立且比报告说的更顽固**；P 级我降为 P3（豁免池卫生问题，无直接业务风险）。（残留：CLEAN-AFTER-D）

### 2.8 R23I-08（建议 **MA-286**，P3——P 级降）

`BASELINE = 52`（:21）vs `--print` `{files:85, total:49}` 复算一致。clone 注入复现：+3 条 `<el-alert>` → `OK：el-alert=52≤52` 仍绿；+4 → `FAIL: 53 > 基线 52`。基线是硬编码常量、计数下降无自动收紧判据——"棘轮有 3 个免费名额、不会自动收紧"成立。P3：影响只是横幅回弹空间，非判据失效。（残留：CLEAN-AFTER-A）

### 2.9 R23I-09（建议 **MA-287**，P3）

读码确认 `check-audit-gates-wiring.mjs:173-183`：

```js
for (const name of [...reached]) {
  for (const m of (scripts[name] || '').matchAll(/pnpm\s+(?:run\s+)?(check:[A-Za-z0-9:_-]+)/g)) {
    if (checkScriptNames.includes(m[1])) reached.add(name);   // ← name 已在集合里
  }
}
```

`reached.add(name)` 恒为已存在成员 ⇒ 第一轮 `before===after` 即 break，"传递闭包"特性数学上死代码（应为 `reached.add(m[1])`）。**影响裁决（报告未做定量）**：`.tmp/verify-ext2/closure-buggy-vs-fixed.mjs` 用真实 package.json + workflows + preflight 完整仿真 buggy vs fixed 两种闭包 → reached 均 154、unreachable 差集双向为空 ⇒ 现行仓库无任何接线形态依赖此闭包，缺陷 latent；check:* 脚本文本里唯一内链 `check:shared→check:nav-perms` 两条都另有 CI 锚点直连。门禁当前实跑绿（145 引用全定义/194 门禁全接线/154+2 可达）。"A 内部 pnpm B 且 B 无直连"形态出现之日即漏判/误红之日——P3 恰当。

### 2.10 R23I-11（建议 **MA-288**，P3 备忘——或随批次带走的文档行）

`:199-209` 逐行确认两处 fail-open：merge-base 失败→`SKIP…` exit 0；`mb==head`→`OK：无待推提交` exit 0。在 `origin/dev==HEAD` 基线上本地结构性空转属实。报告自身已按"刻意取舍、P3 备忘"定级并核对文件头注释 + `--selftest` 8 例 + CI 字节级重建兜底——判定与其证据一致，无夸大。

### 2.11 R23I-13（建议 **MA-289**，P3；与我方 MA-261 同族不同台账）

实测：`ls services/*/src/main/resources/db/migration/V*.sql` = **474**、最新 **V481__retire_device_temp_alert_knob.sql**（对 dev 库只读查询 `flyway_schema_history` versioned 行亦 474）；`git ls-files '*Controller.java'` = **111**；`ls scripts/check-*.mjs` = **194**；`*.test.mjs` = **53**。PROJECT_KNOWLEDGE.md:37 写 379/V385（表头自注"2026-09-24 校准"）——漂移方向"增长未回填"属实。与我方 MA-261（SCHEDULED_TASK_MANAGEMENT.md §五）同族但**不同台账文件**，两处都该修，可并批次。

---

## 3. 不成立/部分成立条目的反证

### 3.1 R23I-10：部分成立（事实真、风险定性弱 → 不立 MA，留 P3 备忘即可）

**事实面全部复核为真**：`grep -rln FUND_KEY_CEILINGS scripts/*.mjs` 空——确实无门禁判据覆盖这 4 个天花板值；`check-commission-param-bounds` 的 `SETTER_RE` 只认 6 族 setter 调用点（:30-31），`Map.of` 常量不在其扫描语义内（C12 注入目标错的自评正确）；把 Map 值硬编码成 `999999999L` 无门禁红。

**但反证有三**：

1. **"两处口径要人工对齐"不成立**：`FUND_KEY_CEILINGS` 的 4 个值本身就是 `(long) CommissionParamBounds.WITHDRAW_FEE_BPS_MAX / REFUND_LIMIT_CENTS_CEILING / PREAUTH_CENTS_CEILING` 引用（SystemConfigService:485-489），前缀分支 :524-525 亦引用同一常量——**同源已经成立**（报告建议的"写侧与表同源"现状即满足）。真要把上限改大，改动点是 `CommissionParamBounds` 常量或 Map 字面量，都是**有意源码编辑**，过 review/测试的常态路径。
2. **与我方 S12 设计意图不冲突**：天花板是写侧 400 + 读侧回落双保险（validateFundKeyValue :523-529 / getPositiveLimit），S12 拍板⑧的防线定义就是运行时校验；"门禁判据是否覆盖常量值"从来不在 S12 交付面里。报告自己也承认"不主张加禁止改常量的门禁"。
3. **威胁模型弱**：注入形态=开发者故意把单源引用改写成硬编码字面量。这类"故意绕开单源"的编辑按仓内既有哲学归 commit review + 纯函数用例（报告建议的"钉 ceiling 与校验分支同值"用例是合理的轻加固），不构成门禁缺陷条目。

**结论**：事实陈述真、定性"无任何判据=可被静默改大"偏夸张（改常量不是静默动作），降为 P3 备忘、不立新 MA；其建议的纯函数钉值用例可随 S12 后续批次带走。

### 3.2 无其余"不成立"条目——但三处**修正**（报告内部不一致/口径偏差，不影响成立性）

- **① R23I-02 vs MA-260 口径差（对 ISSUES/PENDING 的反向订正）**：R23I-02 与我方 MA-260 核心同源（同两任务、同证据、同修法），外部只挑两个资金任务**口径正确**；我方 ISSUES.md MA-260 行与 PENDING_DECISIONS ⑪ 写"**38 个 XXL 托管任务全无 @Scheduled fallback**"——经抽样实测为**过度概括**：DevicePresenceService:134（device-presence）、OpsFeeBillJob:38（ops-fee-bill-monthly）、MerchantDailyIncomeNotifyScheduler:100、DepositMonthlyRefundJob:23、MachinePayeeBillJob:25、CouponService、DataConsistencyService、DeviceTempPlanService、ProfitSharingRetryScheduler、LineCommissionJob、FinanceMarginLockScheduler 等大量托管任务**都有** `@Scheduled`（trade-service 主源码 43 文件带注解，R23-D.md:16 自记 44），XXL 关闭时 `shouldYieldToXxlJob`（ScheduledTaskService:422-429，`!xxlJobEnabled → false`）放行 Spring 路径，逃生门对它们**是通的**。R23-D.md 原文（:127-147）本就正确落在"2 任务"，注册进 ISSUES/PENDING 时被放大成"38 全无"。**建议主审订正 MA-260 行与拍板⑪文案**："2 个资金任务无 @Scheduled 回退（其余 36 任务有关闭态回退，但 XXL 开启中调度中心故障时无自动兜底——yield 是配置静态判定）"。这不改变 ⑪ 的三个选项本身。
- **② R23I-01 根因只对 19/22**（详见 §2.1，3 条同行签名是第二缺陷）+ 判据段"共 15 个"算术笔误（应为 17）。
- **③ R23I-07 修复方案只救 3/5**（详见 §2.7，2 条还需认 `--selftest` 直跑形态）。

---

## 4. 交叉核对记录

| 对象 | 结果 |
|---|---|
| `ISSUES.md` MA-176..261 | R23I-02≈MA-260（同源，外部未读我方文件，独立撞题）；R23I-12≈MA-178（设备押金分支先于全局 0 分支，MA-178 已含"穿透 0 语义+无上界"，外部口径是其子集且倾向一致）；R23I-13≈MA-261 同族（不同台账文件，双立）；其余 10 条 ISSUES 无同题——外部报告有净新增价值 |
| `PENDING_DECISIONS_R23_2026-11-14.md` | R23I-12 落拍板⑩（优先级/上界/0 语义三问已列，外部"待拍板"标注正确）；R23I-02 落拍板⑪（兜底调度/停摆告警/接受现状，⑪ 的文案受 §3.2-① 口径订正影响但三选项不变）；R23I-01/03/04/05/06/07/08/09/10/11/13 **不在任何拍板题内**，属直接修复项（10 不立 MA 除外） |
| `docs/module-audit/R23/R23-B.md`（13 注入全红） | B 路 13 条（yuan-to-cents/money-prefix/inline-math/preauth-zero/ledger-immutability/pending-state/mp-emoji/menu-perm/frontend-perm/xxl-cron/feature-flags/env-placeholders）与 R23I-05/07/09 声称的门禁问题**零同款**——B 测"判据对真违规咬合"，I 报告测"判据覆盖面/执行路径/登记纪律"，正交不冲突，无需裁决。I11（xxl-job-wiring cron 漂移红）反证该门禁判据活但确无"Spring 回退存在性"判据，与 R23I-02 互证 |
| R23-FINAL.md | 未单列 R23I 条目（外部报告晚于终报产出），重叠归并以上表为准 |

## 5. 对报告 §4/§5 自证部分的抽检（3+ 条反证尝试）

| 抽检 | 结果 |
|---|---|
| N-01 Flyway 474/474 checksum 一致 | **独立复现成立**：自写 Flyway CRC32 算法脚本（`.tmp/verify-ext2/flyway-checksum-verify.mjs`）+ `docker exec psql` 只读导出 `flyway_schema_history` → same=474 / mismatch=0。"推翻另一路 P1『改写⇒validate 拒服』"的阴性结论扎实 |
| N-03 状态机白名单 | **成立**：`SessionState.canTransitionTo` 为 switch 枚举白名单（common-core），终态无出边 |
| N-04/N-05 兜账只救 CANCELLED / alertSilentCallback 204 刻意 | **成立**：两处注释原文与报告引用一致（VisionResultIngestService:97-107"真实事故路径只有 CANCELLED"；PaymentService P3-4"旧写法 .ifPresent 完全静默是错的"） |
| §5 C13（banner 注入 3 条不红） | **独立复现成立**（+3 绿 / +4 红，见 §2.8） |
| §5 C12（commission-param-bounds 对 Map 常量不红） | **代码级复核成立**：SETTER_RE 六族 setter 调用点语义确不含 `Map.of` 常量 |
| 附带发现（fix-batch-coverage 浅克隆假红） | 未复跑（CI 已带 fetch-depth:0 佐证其"取证姿势"定性，风险自洽） |

抽检的阴性结论全部站得住，未发现编造迹象（详见 §6）。

## 6. 报告整体质量定性

- **"先证伪再定级"名实相符度：高**。抽 5 条（01 逐文件数、05 CI 路径、08 注入、N-01 全量 checksum、C12 注入自评）全部可独立复现，且 13 条正文引用的 路径:行号 与当前 HEAD 逐一核对基本全中（仅 menu.ts 目录与个别行号 ±1 的瑕疵）。阴性结论也带可复核证据（N-01..N-07），不是装饰。
- **发现的价值**：R23I-01（22 端点漏枚举+基线被坏解析器种下）与 R23I-07（拼接盲区卡豁免棘轮）是我方四路报告均未抓到的净新增；R23I-02/12/13 与我方独立撞题互证。
- **发现的瑕疵（不影响成立性）**：① R23I-01 根因只覆盖 19/22（漏了同行签名窗缺陷）+ "15 个"算术笔误；② R23I-07 修复方案低估（只救 3/5，2 条需更深检测）；③ R23I-02 "compose 未显式开启时的默认"一句对 compose 不成立（compose 默认 `-true`），且漏报了更硬的 `.env.staging=false` 实证；④ R23I-09 未做定量（现行零判定影响，latent）；⑤ R23I-10 风险定性偏夸张（同源已在、有意编辑非静默）。**无编造、无残段假绿**——其 §5"13 例三态+零残留"与我的复算口径一致。
- **P 级修正汇总**：07 P2→P3、08 P2→P3（其余维持）。

## 7. 收尾

- **成立 10 / 不成立 0 / 与我方重叠 2 / 部分成立 1**。
- 建议新 MA（仅本报告登记，未写 ISSUES.md）：**MA-280**（R23I-01，含双根因修正）、**MA-281**（03）、**MA-282**（04）、**MA-283**（05）、**MA-284**（06）、**MA-285**（07，含 3/5 修正）、**MA-286**（08）、**MA-287**（09）、**MA-288**（11，P3 备忘）、**MA-289**（13，可与 MA-261 并批）。R23I-02 归 MA-260、R23I-12 归 MA-178/拍板⑩；R23I-10 不立 MA（P3 备忘随 S12 批次带走）。
- 另建议主审订正 **ISSUES.md MA-260 行与拍板⑪的"38 全无 @Scheduled fallback"文案**（见 §3.2-①，不影响 ⑪ 三选项）。

—— 验证人：VERIFY-EXT2（第三路对抗验证），2026-11-15。工作区零改动自证：全程仅新增本文件与 `.tmp/verify-ext2/`（gitignore）下脚本；所有注入在 `.tmp/verify-ext2/clone` 还原后 `git status --porcelain` 与基线逐字节一致（CLEAN-AFTER-A/C3/D 等标记见 §2）。

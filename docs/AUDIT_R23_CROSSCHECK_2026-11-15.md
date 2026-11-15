# R23 两轮审计交叉复核（Cross-check）— 2026-11-15

> **复核对象**
> 1. 四路报告：`docs/module-audit/R23/{R23-A,R23-B,R23-C,R23-D,R23-FINAL,L4-SECOND-LAYER}.md`
> 2. 独立报告：`docs/AUDIT_R23_INDEPENDENT_2026-11-15.md`
>
> **复核基线**：`HEAD=fdf5f3e4`（两份报告锚 `a95a2fa3`，其后仅 `fdf5f3e4` 一笔 docs 提交，业务代码零差异）。
> **方法**：逐条回源码/实跑，**不采信报告自报**；对每条只给四种结论：`对` / `部分对（需改表述或调级）` / `复核不到（缺证据）` / `错`。
> **本轮唯一新增物**：本文件。未改任何被审代码；只读 SQL/门禁实跑。

---

## 0. 结论摘要

| 报告 | 条目 | 对 | 部分对 | 复核不到 | 错 |
|---|---|---|---|---|---|
| 四路（MA-*） | 11 缺陷 + 4 观察/拍板 | 10 | 1（MA-260 定级） | 2（B 注入全量、L4 S9 分解） | 0 |
| 独立（R23X-*） | 13 缺陷 + 7 阴性 | 11 | 2（R23X-02 定级、R23X-03 表述） | 2（N-02/N-07 未复跑） | 0 |

**三句话**
1. 两份报告**没有事实性硬伤**：抽查到的每个行号、正则、配置、计数都能复现；最可量化的两条（门禁漏 22 端点、Flyway 474/474 checksum）我都独立复算通过。
2. **两份报告会互相打架的地方只有一处**：四路 B 路称「13 注入全红、零假绿门禁」，独立报告却挖出 5 条门禁缺陷。复核裁决：**独立报告对**，B 路的「零假绿」只能读作「抽样的 12 个门禁零假绿」，不能读作「全部 194 个」。
3. **两份报告共同漏掉了同一类 P1**（运营端数据范围越权 5 条 + 提现双重解冻等），原因不是谁不认真，而是四路的九类镜头与独立路的门禁/调度镜头**都没有「功能权限过了、数据范围没过」这一轴**。详见 §4。

---

## 1. 四路报告逐条复核

### 1.1 缺陷 11 条

| 编号 | 其结论 | 复核 | 我读到的事实 |
|---|---|---|---|
| MA-176 (P2) | `InventoryLotService.doAddRestock` 既有批次仍「读→算→绝对 save」 | **对** | `:383-398` 确为 `find→setQuantity(get+delta)→save`；同文件 `:347-349` 的 restore 走 `increaseQuantityAtomic`；`:591` 为新建行（安全）。锁失效窗口的威胁模型成立 |
| MA-177 (P3) | `device_sku_lot` 无 (device,sku,batch) 唯一键 | **对** | `V24__inventory_lots_phase_a.sql` 全文无 UNIQUE；`grep UNIQUE` 空；建档为 find→INSERT |
| MA-178 (P3→**P2**) | `deposit_cents` 预授权分支不在天花板内、穿透 0 语义 | **对；按 2026-11-15 二次复核拆分** | `ConsumerPreauthService.java:71` 只夹 `Integer.MAX_VALUE`；`OpsDeviceAdminService.java:607-609` 仅钳负数 ⇒ **「无业务上界」P2**（可写荒谬押金并冻结消费者余额）；「0=关闭 vs 设备押金优先」保留 **P3 待拍板**。与独立报告 R23X-12（已由 P3 上调 P2）一致 |
| MA-179 (P3) | `buffer_yuan/tolerance_yuan` 不在守卫族；YuanFen 超两位精度抛错 | **对** | `FUND_GUARDED_KEYS`（`:456-468`）与 `FUND_KEY_CEILINGS`（`:485-489`）均无这两键；`YuanFen.toCents` 对 `scale>2` 抛 `IllegalArgumentException` |
| MA-200 (P3) | `ops:recognition-demo:view` 空转豁免 + R8 拍板项漂移 | **对** | `check-menu-permissions.mjs:25`、`check-frontend-perm-codes.mjs:33` 豁免在位；`clients/admin-vue/src` 全域 0 引用；`V421:24` 明文「去留属 R8 待拍板」；两份 PENDING_DECISIONS 均 0 命中 recognition-demo |
| MA-201 (P3) | `check-feature-flags` R2 用未剥注释文本匹配 | **对** | `check-feature-flags.mjs:365-371` 的 `otherJava.some(t=>t.includes(...))` / `scsConsumerText` 均未剥 `//`、`/* */`；只剥了常量声明与 seed 调用 |
| MA-224 (P2) | `SiteContract` 清空日期不落库 + status 分叉 | **对** | `SiteContractService.java:112-129`：加载旧行→`setStartDate(null)/setEndDate(null)`→`updateById`（MP 默认 NOT_NULL 跳 null）→`statusFor(null)` 重算；`SiteContract` 无 `@TableField(updateStrategy)`；`V176:27-28` 两列可空；前端 `|| null`（`OrgSitesView.vue:1163-1164`） |
| MA-225 (P3) | 两个 admin 新面板轮询无代际守卫 | **对** | `DeviceJiangyiCapturePanel.vue:89-105`：await 后无条件 `session.value = 响应`；`startCapture` 只 `stopPoll()` 不清在途；RestockPanel `:71-85` 同构 |
| MA-226 (P3) | MA-175 台账状态漂移（代码已修台账 open） | **对（已收口）** | `MachinePayeeBillService.java:170-171/206-214` 已有 `@Transactional`+`findByIdForUpdateRaw`；ISSUES.md 现存行已改 `fixed（51a75d3e…）` 并注明「R23-C/MA-226 抓出」 |
| MA-260 (P2) | 两个资金任务无 `@Scheduled` 回退 | **对，但定级需统一** | `grep -c @Scheduled` 两文件均 0；`XxlJobManagedTasks.java:14` 明写「@Scheduled 注解一律保留」；`application.yml:374` 默认 false。**但** `docker-compose.{apps,full,ha,prod-managed}.yml` 均 `XXL_JOB_ENABLED:-true`，只有 `infra/.env.staging:70=false` ⇒ 默认部署有调度；P2 比 P1 更准（独立报告 R23X-02 定 P1 偏高） |
| MA-261 (P3) | `SCHEDULED_TASK_MANAGEMENT.md` §五 32/31/1 漂移 | **对** | `:267` 仍写「32 行 = 31 托管 + 1 看护」；实测 `scheduled_task=44`、`xxl_job_info(job_group=10)=38/38`、注册表 44 |

### 1.2 B 路门禁健康

| 其结论 | 复核 | 说明 |
|---|---|---|
| 13 条负向注入 / 12 门禁，13/13 全红 | **复核不到（未全量复跑）** | 我读了其中两类判据代码（yuan-to-cents、feature-flags）与注入方法，形态成立；重跑了 `check-xxl-job-wiring`/`check-scheduled-task-seed`/`check-audit-gates-wiring`，输出与报告逐字一致。**但** B 的 13 条**没有覆盖** `check-write-endpoint-attribution`，而它恰恰是坏的（见 §2 R23X-01） |
| 「零假绿门禁」 | **部分对（表述过宽）** | 应改为「抽样的 12 个门禁零假绿」。同一批门禁里 `check-admin-banner-ratchet` 基线 52 vs 实测 49（R23X-08）、`check-audit-gates-wiring` 传递闭包 no-op（R23X-09）、5 个 `--selftest` 门禁卡 exempt（R23X-07）、`check-mp-scoped-hash` CI 恒 SKIP（R23X-05）——都是 B 未覆盖的判据面 |
| 四口径 49 / 194 / 78-145 / 豁免 67-69 | **对** | `ls scripts/check-*.mjs=194`、`check-*.test.mjs=49`；wiring 实跑输出「78/145 带夹具，豁免 67/69」；`find scripts -name '*.test.mjs'=53`（独立报告引用的 53 也对，口径不同：含非 check- 前缀的 4 个） |
| 三个轻项修复复核属实 | **对（抽核）** | `feature-flags-read.mjs` 的 `getPositiveLimit` + `POSITIVE_LIMIT_MIN_SITES`；`check-preauth-zero-semantics.mjs` 三锚点；`check-commission-param-bounds.mjs:34,199,230` 的 `MIN_WRITERS=7`+`evaluateAnchor` 均在 |

### 1.3 D 路运行时

| 其结论 | 复核 | 说明 |
|---|---|---|
| 空库 Flyway V1→V481：474 迁移全 success | **对** | 仓库 `V*.sql=474`；dev 库 `flyway_schema_history` 474 行 `success`、`max(version::int)=481` |
| 任务三源 44/44/38 + 12 cron 时区全带 zone | **对** | `scheduled_task=44`（2 disabled）；XXL 38/38；`@Scheduled(cron=...)` 恰好 12 个且每个都含 `zone=`（逐个打印核对） |
| 生产 ECS 22/80 超时、未巡检 | **部分对（口径）** | 连接超时这一事实无法由我复核（我不重试外部主机）；但**「生产」一词与项目口径不符**：按 `PROJECT_KNOWLEDGE` 与用户 2026-11-10 重申，`120.25.246.179` 是**测试/部署服务器**，尚无生产环境。应写「部署服务器不可达」 |
| job134 数据断代判非缺陷 | **复核不到** | 依赖 `xxl_job_log` 历史与容器地址变迁，我只核了当前 38/38 与今日 last_run，未复读 11-07~11-08 全段 |

### 1.4 L4 / FINAL

| 其结论 | 复核 | 说明 |
|---|---|---|
| 15 族结构化落地、S12 为资金乘数最完整形态 | **复核不到** | 依赖 S9/S10 扫描器口径与 134 区分解，本轮未逐端点重跑 |
| **P1×0（首次全量轮零 P1）** | **部分对（范围限定）** | 在四路声明的镜头内成立；但**不能当全局结论**：独立路 2 条 P1、本仓另有 R23Q 7 条 P1（数据范围/资金半收尾），均不在四路镜头内。建议改写为「四路镜头内零 P1」 |
| 修复批派工单/明确不做 | 对（流程项） | 与报告正文一致 |

---

## 2. 独立报告逐条复核

### 2.1 缺陷 13 条

| 编号 | 其结论 | 复核 | 我读到的事实 |
|---|---|---|---|
| R23X-01 (P1) | 写端点归因门禁正则漏 22 端点；基线 455 是坏解析器产物 | **对（端点分布有一处算术错）** | 正则 `[\w<>,\s]` 确缺 `.`（`check-write-endpoint-attribution.mjs:267`）。我独立计数：四服务 98 个写控制器 / **477** 个写注解；门禁实跑 `455`；`controllerEndpoints(AdminJiangyiController)` 实跑=21 vs 注解 27。**漏枚举 22 个/9 文件：Admin* 6 个、非 Admin 16 个；按运营台口径 17 个（AdminJiangyi 6 + OpsWarehouse 4 + OpsException 3 + SysDict 3 + SystemConfig 1）**——独立报告原文第 72 行写「共 15 个」，少算 2，需订正。基线 `totalEndpoints=455` 与门禁输出逐字相等 ⇒ 「基线来自坏解析器」是强推断（生成脚本已不在仓，未取到运行记录），但缺口本身**已实证** |
| R23X-02 (P1) | 两资金任务无 Spring 回退，默认配置零触发 | **部分对** | 缺陷成立（见 MA-260）。但 `infra/docker-compose.*.yml` 默认 `XXL_JOB_ENABLED:-true`，`application.yml` 的 false 只影响裸 JVM/IDEA；P2 更准。另：`.env.staging=false` ⇒ **staging 确实永不跑这两个月任务**，这点值得写进修复批 |
| R23X-03 (P2) | 微信回调 nonce 占位过早，失败不释放 | **部分对（表述）** | 代码属实：`WeChatPayNotifyService.java:55-60` 验签→时效→`assertNonceOnce`→解密；`WeChatNotifyController` 无 try/catch。但「该笔充值/退款同步丢失」应改为「**最长约 10–35 分钟延迟**」：nonce TTL 10 分钟（`:24`），之后微信重投可通过；且 `autoCancelExpiredPending`（`PaymentService.java:648-677`）≥30 分钟的单会先查单同步。无永久丢失 |
| R23X-04 (P2) | 看板 LOW_STOCK 按钮判据与跳转不同源 | **对** | `DashboardView.vue:522-529` → `/replenishment`，`:697` → `/stock-health`；`menu.ts:87`（`ops:stock-health:list`）vs `:207`（`ops:replenishment:list`） |
| R23X-05 (P2) | `check:mp-scoped-hash` CI 恒 SKIP | **对** | 门禁无产物即 `SKIP` 且不设 exit≠0（`:83-86`）；`.gitignore:16` 忽略 `clients/*/dist/`；`ci.yml:750` 跑聚合链、`:847/858` 才构建 mp-weixin |
| R23X-06 (P2) | 网关 70s ≥ trade 60s 无代码强制；超时落 FAILED 而兜账只救 CANCELLED | **对** | `SessionWatchdog.java:75` 默认 70000；`application.yml:364` 默认 60；`VisionResultIngestService.java:97-109` 只对 CANCELLED 救援；FAILED 仅 `reportLateRecognition` |
| R23X-07 (P2) | 元判据只认 `--self-test`，5 个 `--selftest` 门禁卡 exempt | **对** | `check-audit-gates-wiring.mjs:59` 同时要求 `--self-test`；5 个文件仅 `--selftest`（`check-admin-artifact-pairing.mjs:112` 等）；`gate-fixture-registry.json` 的 exempt（67/69，条目带 `.mjs` 后缀）确含这 5 个 |
| R23X-08 (P2) | 横幅棘轮 52 vs 实测 49 | **对** | 实跑 `--print` → `total=49`；`BASELINE=52`；门禁只判 `total > BASELINE` ⇒ 3 个免费名额 |
| R23X-09 (P3) | 接线门禁闭包 `reached.add(name)` 应为 `m[1]` | **对** | `check-audit-gates-wiring.mjs:174-183` 逐字如此；当前绿靠规则 2 的兜底路径 |
| R23X-10 (P3) | `FUND_KEY_CEILINGS` 4 值不在任何门禁判据内 | **对** | 4 键 Map（`SystemConfigService.java:485-489`）；`check-commission-param-bounds` 只扫 `set*()` 调用点；全仓 `scripts/` 仅 `fix-batch-registry.json` 提到该常量名 |
| R23X-11 (P3) | admin 产物配对门禁两处 fail-open | **对** | `:197-209` `merge-base` 失败即 SKIP/exit 0；`mb==head` 即 OK/exit 0 |
| R23X-12 (**P2**，2026-11-15 上调) | 设备押金：无业务上界 + 穿透全局 0 关闭 | **对（拆两半）** | 「`Math.min(Integer.MAX_VALUE,…)` 无业务上界」→ P2，不需要拍板；「0 是否该压倒设备押金」→ P3 待拍板。`ConsumerPreauthService.java:71` + `OpsDeviceAdminService.java:607-609` 实证 |
| R23X-13 (P3) | PROJECT_KNOWLEDGE 规模数字漂移 | **对** | `PROJECT_KNOWLEDGE.md:37` 仍写 379 / V385；实测 474 / V481 |

### 2.2 阴性结论

| 编号 | 其结论 | 复核 | 说明 |
|---|---|---|---|
| N-01 | 474 迁移 checksum 474/474 与 dev 库一致 | **对（我全量复算）** | 按 Flyway 算法（去 BOM、逐行去行尾、连续 CRC32、转 int32）复算全部 474 个文件：**ok=474 / mismatch=0 / missing=0**，与库内 `installed_checksum` 逐条相等 |
| N-02 | 分账 rate=0 / LEDGER_ONLY / 按腿口径自洽 | 复核不到 | 我读过相关代码，未重跑 |
| N-03 | 状态机迁移是白名单 | **对** | `SessionState.canTransitionTo` 是显式 switch 枚举 |
| N-04 | 兜账只救 CANCELLED 是刻意 | **对** | 注释与 R23X-06 的分工一致 |
| N-05 | `alertSilentCallback+return` 不是静默丢弃 | **对** | 见 `PaymentService.java:528-545` 等分支 |
| N-06 | admin 产物配对 fail-open 属刻意取舍 | **对** | 文件头注释 + `--selftest` 8 例 + CI 字节级重建 |
| N-07 | `check-fix-batch-coverage`/`check-ledger-evidence` 能真红 | 复核不到 | 未复跑注入 |

### 2.3 其 §6 三处分歧的裁决

| 分歧 | 独立报告的结论 | 我的复核 |
|---|---|---|
| §6-1 已应用迁移被改写 ⇒ validate 拒服 | 后果不成立（dev 474/474 一致），建议降级 | **同意**。我全量复算一致；「历史上改过文件」与「现在会拒服」是两件事。残留风险只在 UAT/生产是否 repair 过——注意本项目口径下 `120.25.246.179` 是测试服务器，不是生产 |
| §6-2 0=关闭预授权对设备押金失效 | 倾向语义分层，降 P3 | **同意**（与四路 MA-178 同判 P3）；但「设备押金优先于全局关闭」应写进 javadoc + 配置页提示，否则仍会被当成 bug 反复报 |
| §6-3 admin 产物配对三处放行 | 真的，但属刻意取舍，P3 | **同意**；与 N-06 一致 |

---

## 3. 两报告之间的冲突与重合

### 3.1 冲突（1 处，已裁决）

**B 路「13 注入全红、零假绿」 vs 独立路 5 条门禁缺陷。**
- 两者不矛盾的事实层：B 的 12 门禁抽样确实全红；独立挖出的 5 条（write-endpoint 覆盖、mp-scoped-hash 空转、selftest 豁免、banner 基线、闭包 no-op）**都不在 B 的抽样里**。
- 裁决：**B 的结论只能限定在抽样门禁**。`check-write-endpoint-attribution` 的 455 端点是坏的解析器产物，说明「门禁面板数字可信」这一层**没有**被 B 覆盖。独立报告的 §5 总结（「判据主体是活的，坏的是覆盖面与基线纪律」）更准确。

### 3.2 重合（互相印证，置信度最高）

| 缺陷 | 四路 | 独立 | 我的复核 |
|---|---|---|---|
| 两资金任务无 Spring 回退 | MA-260 P2 | R23X-02 P1 | 同一条；P2 更准 |
| 设备押金：无业务上界 | MA-178 P3 | R23X-12 P2 | 同一条；**2026-11-15 拆两半：无上界 P2、0 压倒 P3 待拍板** |
| 台账数字/状态漂移 | MA-226/MA-261 | R23X-13 | 同一族；MA-226 已收口、MA-261 与 R23X-13 是不同文件 |
| 门禁基线/覆盖面 | MA-200/201 | R23X-01/05/07/08/09/10/11 | 互补，无重复 |
| 站点合同清空失效 | MA-224 | — | 独立路未覆盖 admin 表单清列 |
| 库存读改写残留 | MA-176 | — | 独立路未覆盖库存写点 |

---

## 4. 两份报告共同遗漏（按我独立轮的 P1 排）

> 原因：四路的九类镜头与独立路的门禁/调度镜头，都没有「**功能权限通过、数据范围未过**」这一轴；也没有「**失败/半失败路径的补偿**」这一轴。

| 我的编号 | 级别 | 缺陷 | 为什么两轮都没看到 |
|---|---|---|---|
| R23Q-02 | P1 | 交易视频播放无对象级授权（role 2 可遍历 id） | C 路镜头②只查「跨端单边」，未查 service 层操作员范围 |
| R23Q-03 | P1 | 改价/重置价格无设备范围（role 2 可改他商户柜机） | 同上 |
| R23Q-04 | P1 | 设备 HTTP 凭据签发无设备范围（可拿明文 secret） | 同上 |
| R23Q-05 | P1 | 日毛利锁固化只读权限可写全局日键 | 同上 |
| R23Q-01 | P1 | 提现 FAILED 双重解冻 | 镜头⑨只看状态机，没把「解冻」当资金动作核幂等 |
| R23Q-06 | P1 | 月结期初盘点取错一期 | 四路看迁移/门禁、独立看调度，都没读月结算式 |
| R23Q-07 | P1 | 微信手工退款同步并发双记 refunded_cents | 独立路查的是「nonce 占位」，没查「去重与累加分属两事务」 |
| R23Q-08 | P2 | 一致性修复退多收不重算分账 | 四路未审巡检修复动作的下游 |
| R23Q-09 | P2 | 分账毛额 vs 净额无巡检 + 1 笔真实漂移（293 分） | 两轮都只核了「分账行内闭合」 |
| R23Q-10/11/12 | P2 | 月账单退款日/流量费源/场地租金起止 | 四路查运行时，未把月账单当账期口径核 |
| R23Q-13/14/15 | P2 | 扣款事务内 HTTP / 支付分通知竞态 / 分账锁不覆盖提交 | 独立查回调占位，未查事务边界 |
| **R23Q-23（交叉复核新发现）** | **P2** | **`OpsFeeBillJob` 的「流量费月结开关」同时门控了场地租金出账**：`OpsFeeBillJob.java:51-54` 默认 `false` 直接 SKIPPED，而 `:60` 的 `siteRentBillService.autoGenerate(month)` 是场地租金**唯一**自动入口；开关文案只写「流量费月度自动出账」 | 四路 D 只清点了任务是否在跑，没读开关门控的业务面；独立路也没读这个 job 体 |

**R23Q-23 的完整证据链**
- `OpsFeeBillJob.java:18` `TASK_KEY=ops-fee-bill-monthly`；`:51-55` 开关 false → `finish(SKIPPED)` 并 return；
- `:60` `rentBillService.autoGenerate(month)`（场地租金，付给房东）；全仓 grep 无第二个自动调用方（另一入口 `generateForAllActive` 需运营手动点）；
- `SystemConfigService.java:985` 默认 `"false"`，描述写的是「**流量费**月度自动出账开关：日扣模式下默认停用」——与 `:60` 的租金调用不同源；
- 结论：**2026-10-14 之后，场地租金月度账单在默认配置下不再自动生成**。需产品确认：若场地租金本就该人工出账，应把 `:60` 从被禁路径里拆出、并改文案；若不是，这是漏付房东的功能缺口。

---

## 5. 数字勘误（供汇审直接引用）

| 项 | 四路/独立报告 | 我的实跑 |
|---|---|---|
| 写端点真实注解数 | 477（独立） | **477**（四服务、98 个写控制器） |
| 门禁解析数 / 基线 | 455（两轮一致） | **455**（门禁实跑输出） |
| `@Scheduled` | 44（D） | **44**；其中 cron 型 **12**，全部带 `zone` |
| `scheduled_task` | 44（D） | **44**（2 disabled） |
| XXL 托管 / 活库 | 38/38（D） | **38/38** |
| Flyway | 474 / V481（D、独立） | **474 / 481**；checksum **474/474 一致** |
| 门禁脚本 / check-*.test | 194 / 49（B） | **194 / 49**；`scripts/**/*.test.mjs`=**53**（独立引 53 亦对） |
| PROJECT_KNOWLEDGE 迁移数 | 379/V385（现行文档，独立指出漂移） | 文档仍 379/V385，实测 **474/V481** ⇒ 漂移成立 |

---

## 6. 给汇审的处置建议

1. **采纳独立报告的门禁族**（R23X-01/05/07/08/09/10/11）：这 7 条补上了四路 B 路「数字可信」的空洞；其中 R23X-01 应升为修复批第 1 优先（基线与解析器同源，所有写端点归因判据都在它上面）。
2. **MA-260 与 R23X-02 合并成一条 P2**，修复时同时覆盖 staging（`.env.staging=false`）——staging 是月任务验收环境。
3. **R23X-03 改写为「回调延迟窗口」**并照 H13 把占位移到入账前；不要写成「丢失」，避免被渠道重投与查单兜底的事实推翻。
4. **四路「P1×0」改为「四路镜头内 P1×0」**，并在终报补一句「运营端数据范围越权与失败路径补偿不在本轮镜头内」。
5. **共同遗漏的 R23Q P1 单开一批**（尤其 R23Q-02/03/04/05 四条同族，一次补 `requireDeviceAccess` 可关一类）。
6. **R23Q-23（场地租金开关耦合）先拍板**：人工出账 vs 自动出账，拍板后再动码。
7. **不要把「P2-12 依赖 P2-18」写进修复顺序**：`merchant_fee_ledger` 的 REN/DATA 行由 `MerchantDailyIncomeNotifyScheduler`（每日 00:20，`chargeDailyPeriodFees`）写入，**不受** `OpsFeeBillJob` 的 `feebill.auto_generate.enabled` 门控；月账单改读该表与场地租金开关无关，两项可并行修。
8. **补 `@Scheduled` 时保留 `tryBegin`**：`PlatformFundService.java:281` / `DeviceRentalService.java:545` 已有守卫；其余 43 个 `@Scheduled` 也靠 `tryBegin` 互斥，所以补回退不会双跑。注意 `OpsFeeBillJob` 是唯一同时带 `@Scheduled` 与 `@ConditionalOnProperty` 的调度文件（交集 1）——「44 个无一带条件注解」的说法需订正，但不影响「tryBegin 防双跑」的结论。

---

## 附录：复核用的只读命令（节选）

```bash
# 写端点真实注解数（四服务）
python - <<'PY' # 计数 @(Post|Put|Patch|Delete)Mapping → 477 / 98 controllers
PY
node scripts/check-write-endpoint-attribution.mjs   # → 455
node --input-type=module -e "…controllerEndpoints(AdminJiangyiController)…"  # → 21 vs 27

# 门禁
node scripts/check-admin-banner-ratchet.mjs --print   # total 49；BASELINE=52
node scripts/check-audit-gates-wiring.mjs             # OK：194 门禁、78/145、67/69
node scripts/check-xxl-job-wiring.mjs                 # OK：38/38/38/43
node scripts/check-scheduled-task-seed.mjs            # OK：44↔44

# Flyway（全量 474 复算）
python - <<'PY' # 按 Flyway 算法 CRC32 → ok=474/mismatch=0
PY

# 运行库
docker exec ai-cabinet-postgres-1 psql -U aicabinet -d aicabinet -tAc \
  "select count(*), max(version::int) from flyway_schema_history where version is not null and success;"
docker exec ai-cabinet-postgres-1 psql -U aicabinet -d aicabinet -tAc "select count(*), count(*) filter (where not enabled) from scheduled_task;"
```

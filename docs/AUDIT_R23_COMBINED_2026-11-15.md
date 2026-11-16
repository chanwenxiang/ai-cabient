# R23 三份审计组合版（2026-11-15）

> **来源**：① 模块轮四路（`module-audit/R23/*`，MA-176…261）；② 独立轮（`AUDIT_R23_INDEPENDENT_2026-11-15.md`，R23X-01…13，原 R23I）；③ 四端轮（`AUDIT_FOUR_END_R23_2026-11-15.md`，R23Q-01…23）。
> **基线**：`HEAD=fdf5f3e4`（模块轮/独立轮锚 `a95a2fa3`，其后仅一笔 docs 提交，业务代码零差异）。
> **本文为准（2026-11-15 终裁版）**：**P1 = 5 / P2 = 21 / P3 = 16，合计 42 条**（另有 §7-4 修复期新发现 C43/C44 两条 P2，见文末）。P1 来源：独立轮 1（C06）+ 四端轮 4（C01–C04）；**模块轮 0 条 P1**（其 11 条全为 P2/P3）。分布口径：P2 表 21 行（C05 + C07–C26），P3 16 条（C27–C41 共 15 + C42 排在 P2 段末以免拆散 C26 对照）。
> **两次降级已落文**：① `R23Q-06/07` 原 P1 → P2（C07/C08，触发条件见 §2-3）；② `R23Q-05` 原 P1 → P2（C05，理由见 §3-8，可回滚）。
> **拆分**：C26 按「无上界 P2 + 0 压倒押金 P3」拆为 C26/C42。
> **引用口径**：独立报告现用 `R23X-01…13`（原 `R23I-*`，为避免与 R23Q 撞号已改名；磁盘上已同步）。
> **标记**：`✓` 亲验到代码或数据 · `◐` 静态读过、形态成立 · `○` 采信原报告未核。

---

## 1. 组合缺陷清单（去重后 42 条）

### P1（5 条）

| # | 级 | 缺陷 | 来源 | 验 |
|---|---|---|---|---|
| C01 | P1 | 提现单打款失败已解冻后，运营点「取消」**再解冻一次** → 若该商户另有在途单，会吃掉 B 的冻结额，B 打款成功后回滚 ⇒ 钱已出账、余额未扣 | R23Q-01 | ✓ |
| C02 | P1 | 交易视频 `playUrls(id)` 无对象级授权，任一运营遍历 id 看任意柜机录像 | R23Q-02 | ✓ |
| C03 | P1 | 改价/重置**全文件无设备范围**：`listPricing:75`（读面也能跨商户列价）、`updatePricing:101`、`resetPricing:196` 三个入口都只 `requirePermission`+`requireDevice`（后者只查存在）；`ops:pricing:edit` 授 role 2 | R23Q-03 | ✓ |
| C04 | P1 | 设备 HTTP 凭据签发/吊销无设备范围，且回**明文 secret**（`issue` :47-53、`revoke` :66-70） | R23Q-04 | ✓ |
| C06 | P1 | 写端点归因门禁正则缺 `.` ⇒ 477→455，净漏 **22 个 / 9 文件**（Admin* 6、非 Admin 16；运营台口径 **17**）；基线 `totalEndpoints=455` 是坏解析器产物 | R23X-01 | ✓ |

> C05（日毛利锁）原 P1，经复核**降为 P2**（见 §3-8），故 P1 为 5 条；其行号在 P2 段首。

### P2（21 条）

| # | 级 | 缺陷 | 来源 | 验 |
|---|---|---|---|---|
| C05 | P2（原 P1 降） | 日毛利锁用**读权限**写全局日键：`FundBillService:409` 只要 `ops:finance:view`，`sumScoped:565` 零范围直接返回 0，`:433` 以 `bizDate` 为 ID upsert 全局行 ⇒ finance 角色/零绑定账号可覆盖全平台某日 revenue/cogs/margin。**降级理由**：全仓无资金任务消费该表（仅 `:162` 列表、`:433` 写、`:451-455` 读），管理员重新固化即可覆盖，属权限模型违规+报表完整性，非资金完整性 | R23Q-05 | ✓ |
| C07 | P2 | 月结「期初盘点」取错一期（`prevSheetCounted` 命中即短路，**仅上期未关账时**触发；EXT1「任一口径必错」不成立）— 原 P1 **降级** | R23Q-06 | ✓ |
| C08 | P2 | 手工退款去重与累加跨两事务，同 `refund_id` 并发重投双记 — 原 P1 **降级**；**定级分歧 2:1**：EXT1 主张 P1（运营双击/多标签可并发），本版维持 P2 **待拍板** | R23Q-07 | ✓ |
| C09 | P2 | 退多收不回算分账 + 毛净无巡检；**运行库实测 1 笔**（`1793669053437918137747` 多记 293 分） | R23Q-08/09 | ✓库 |
| C10 | P2 | 商户月账单退款按**订单创建日**分日，跨月退款进不了当月 | R23Q-10 | ✓ |
| C11 | P2 | 月账单费用源已过期：流量费恒 0、租金完全不计（日扣实际写 `merchant_fee_ledger`） | R23Q-11 | ✓ |
| C12 | P2 | 场地租金出账**不看合同起止日** | R23Q-12 | ✓ |
| C13 | P2 | `chargeOrder` 在 DB 事务内发渠道 HTTP，同时持会话行锁 + 库存行锁 | R23Q-13 | ◐ |
| C14 | P2 | 支付分通知「读-判-写」无锁，DONE 可被迟到 EXPIRED 覆盖并误记欠款 | R23Q-14 | ◐ |
| C15 | P2 | 分账锁 `finally unlock` 早于提交；唯一键冲突后同事务重查必失败（PG 25P02） | R23Q-15 | ✓ |
| C16 | P2 | 温控下发 / 将邑关门·重启无设备范围（跨商户运维） | R23Q-16 | ◐ |
| C17 | P2 | 「流量费月结开关」误门控**场地租金**出账，默认关 ⇒ 租金不再自动出账 | R23Q-23 | ◐ |
| C18 | P2 | `doAddRestock` 既有批次「读→算→绝对 save」，`increaseQuantityAtomic` 就在同文件未用 | MA-176 | ✓ |
| C19 | P2 | 站点合同清空日期不落库（`setStartDate(null)`+`updateById` 跳 null）+ status 分叉 | MA-224 | ✓ |
| C20 | P2 | 两资金任务无 `@Scheduled` 回退；`@Scheduled` 全域 **44 处/38 文件**（trade 单服务 43/37）；compose 默认有调度但 **`.env.staging:70=false` ⇒ staging 永不跑** | MA-260 ≡ R23X-02 | ✓ |
| C21 | P2 | 看板 LOW_STOCK 判 `/replenishment`（`menu.ts:207`）却跳 `/stock-health`（`:87`），两个权限码 | R23X-04 | ✓ |
| C22 | P2 | `check:mp-scoped-hash` 在 CI **恒 SKIP**（产物 gitignore，门禁 `ci.yml:750` 早于构建 `:847`） | R23X-05 | ✓ |
| C23 | P2 | 网关 70s ≥ trade 60s 无代码强制；超时落 `FAILED` 而兜账只救 `CANCELLED` | R23X-06 | ✓ |
| C24 | P2 | 元判据只认 `--self-test`，5 个 `--selftest` 门禁仍卡 exempt 67/69 ⇒ 棘轮永不触发 | R23X-07 | ✓ |
| C25 | P2 | banner 棘轮 `BASELINE=52` vs `--print` 实测 **49**，3 个免费名额 | R23X-08 | ✓ |
| C26 | P2 | 设备押金 `Math.min(Integer.MAX_VALUE,…)` **无业务上界**、不在 `CommissionParamBounds` 族（可写出荒谬押金并冻结消费者余额） | MA-178 ≡ R23X-12 | ✓ |
| C42 | P3 | 「全局 0=关闭预授权」对配了设备押金的柜机不生效——语义分层待拍板（与 C26 拆开：上界必须修，优先级可辩） | R23X-12（后半） | ✓ |

### P3（16 条）

> C42 行排在 P2 段末（为与 C26 紧邻对照），计入本段的 16 条。

| # | 级 | 缺陷 | 来源 | 验 |
|---|---|---|---|---|
| C27 | P3 | 回调「先占位后入账」，失败不释放；**充值有 5min 清扫⇒自愈≈35min**，退款同步无同频兜底 | R23Q-17 ≡ R23X-03 | ✓ |
| C28 | P3 | 租机押金登记/扣款无 `requireDeviceAccess`（当前仅 admin 持 `ops:rental:manage`，不可利用） | R23Q-18 | ○ |
| C29 | P3 | 月租 `rentCents/daysInMonth` 逐日整数截断，每台每月少收 0~29 分 | R23Q-19 | ○ |
| C30 | P3 | 结算单按 `split.created_at` 归月、月账单按 `order.created_at` 归月 ⇒ 跨月补缴差一笔 | R23Q-20 | ○ |
| C31 | P3 | CLOSE watchdog 以 `response != null` 即注销，`TOO_EARLY` 也算成功 | R23Q-21 | ○ |
| C32 | P3 | `runWithOrderSplitLock` 把 `IllegalStateException` 包成 500 而非 409 | R23Q-22 | ○ |
| C33 | P3 | `device_sku_lot` 无 (device,sku,batch) 唯一键（V24 全无 UNIQUE） | MA-177 | ✓ |
| C34 | P3 | `buffer_yuan/tolerance_yuan` 不在守卫族；YuanFen 超两位由静默变抛错 | MA-179 | ✓ |
| C35 | P3 | `ops:recognition-demo:view` 豁免空转 + R8 拍板项漂移出台账 | MA-200 | ○ |
| C36 | P3 | `check-feature-flags` R2 判据未剥注释（`:365-371` 直匹配原文） | MA-201 | ✓ |
| C37 | P3 | admin 两新面板轮询无代际守卫（MA-173 复发） | MA-225 | ◐ |
| C38 | P3 | 文档/台账数字漂移三处：`PROJECT_KNOWLEDGE` 379→**474**/V385→**V481**；`SCHEDULED_TASK_MANAGEMENT` 32 vs 实测 44/38；MA-226 台账 open 但代码已修 | R23X-13 + MA-261 + MA-226 | ✓ |
| C39 | P3 | 接线门禁闭包 `reached.add(name)` 应为 `m[1]` ⇒ 规则 4 no-op | R23X-09 | ✓ |
| C40 | P3 | `FUND_KEY_CEILINGS` 4 个资金键改成 `999999999L` 全部门禁仍绿 | R23X-10 | ✓ |
| C41 | P3 | admin 产物配对两处 fail-open（merge-base 失败即 SKIP 绿；`mb==head` 即 OK） | R23X-11 | ✓ |

---

## 2. 两份他路报告的问题（用户复核结论）

1. **模块轮「P1×0」是镜头结论，不是全局结论（最容易被误读）**
   11 条里零资金链、零权限范围、零并发——四路镜头没把「功能码通过 / 数据范围未过」当独立判据扫。同日四端轮靠这条轴捞出 **3 个 P1**（C02/C03/C04），加上「资金失败路径」的 C01 共 **4 个 P1**（C05 复核后降 P2）。对外引用必须写「四路镜头内零 P1」。

2. **模块轮 MA-260 范围错了，照原文修会做无用功**
   原文「38 个 XXL 托管任务全无 `@Scheduled` 回退」。实测 trade-service 真实注解 **43 处 / 37 文件**（`SessionExpireService` 自己 5 个，`expireStaleOpeningSessions` 就是 key `session-opening-expire`；gateway watchdog 另 1 处）。真缺回退的只有 `platform-fund-daily` / `device-rental-daily` 两个——按 38 个去补会多改 36 个。
   **本组合版已把台账 MA-260 行改为「2 个」，正文口径保留。**

3. **四端轮 2 条 P1 定级偏高**
   `R23Q-06` 月结期初：`prevSheetCounted` 命中上一期月结单时 `prevFromSheet=true` 短路，出错那行不执行，只有「上期没关账」才触发。`R23Q-07` 退款：需同一 `refund_id` 并发重投，微信串行重投时外层查重必命中。两条缺陷都真实，但都不是 P1。
   **本组合版已降为 C07/C08（P2）。**

4. **四端轮 22 条一条都没进 `ISSUES.md`**
   `grep -c 'R23Q-'` = 0，全在报告正文里；按台账派工会直接漏掉 **4 个 P1**（原 5 个，C05 复核后降 P2）。已在本轮补登：C01–C17、C21–C25、C27–C32、C38a、C39–C42 → **MA-262…294**（详见 §4）。

5. **「零假绿门禁」只对抽样 12 个成立**
   模块轮 B 路恰巧没抽 `check-write-endpoint-attribution`，而那正是坏的（`totalEndpoints=455` 就是缺陷产物）。所以「门禁面板数字可信」这一层没被覆盖，C06 才漏网。
   （归属订正：这条是对模块轮 B 路的评价，不是四端轮的。）

6. **四端轮编号不可按序读**
   `R23Q-01…16` 在 P1/P2 段，`R23Q-17…22` 跳到 P3 段，`R23Q-23` 又插在 P2 段末尾。按号顺序读会漏条，引用时只能按标题搜。**本组合版用 C01–C41 按严重度重排，解决该问题。**

7. **「重复合并」的准确口径（修正原措辞）**
   - **三份报告都提到的缺陷：0 条**（模块轮没有回调占位那条）。
   - **两方完全重合：1 条** —— C27（回调占位）：四端轮 `R23Q-17` 与独立轮 `R23X-03` 判断与定级路径一致（都从「24h 丢失」收敛到「≈35min 延迟、无资损」）。
   - **两方部分重合：2 条** —— C20（MA-260 范围写错「38 个全无回退」vs R23X-02 只指 2 个，结论交集是这 2 个）、C26（MA-178 只说「穿透 0 语义」vs R23X-12 另有「无上界」半条）。
   - 原「三方唯一的完全重合是 C27」不成立，已作废。

8. **编号撞车（必须先处理再提交）**
   第三路对抗验证报告 EXT1（当时未跟踪、未入库）用 `R23I-01…22` 指**四端轮**，而 `R23I-01` 在独立轮旧编号里是「写端点门禁漏枚举」——同一号两个含义；它 §6 的号段（280…299）又与台账已占用 **MA-280…293** 重叠 8 个号（它的 280=提现，台账 280=mp-scoped-hash）。
   **处置**：台账号已落库且被下游引用 ⇒ **让号的是 EXT1**：`R23I-*` 应读作 `R23Q-*`，其 §6 号段应改名到 **295…314**（或在报告开头写明号段让出）。本组合版不改台账号，仅在 §4 声明已占用区间。

---

## 3. 组合版仍需注意的七点（复核附注）

1. **C06 的「基线由坏解析器种下」是强推断**：基线 `totalEndpoints=455` 与门禁输出逐字相等，但生成脚本 `.tmp/seed-endpoint-baseline.mjs` 已不在仓，未取到运行记录。缺口本身（477 vs 455）已实证。
2. **C09 的实锤在 dev 库**：`1793669053437918137747` 是 2026-11-03 的 dev 数据，不是生产数据；它证明「不变量可被打破且巡检不覆盖」，不证明线上已有资损。
3. **C20 的触发面要按环境读**：`docker-compose.{apps,full,ha,prod-managed}` 默认 `XXL_JOB_ENABLED:-true`，只有 `infra/.env.staging:70=false`。所以「默认零触发」只对裸 JVM/IDEA 与 **staging** 成立；修复必须覆盖 staging。**补注解时必须保留 `tryBegin`**（两方法 :281/:545 已有），否则才会真正双跑——其余 43 个 `@Scheduled` 都靠 `tryBegin` 互斥，补回退与现有 42 个同构，安全。
4. **重复合并口径见 §2-7**：三份都提到 = 0；两方完全重合 1 条（C27）；两方部分重合 2 条（C20、C26）。别再用「三方唯一重合」的旧说法。
5. **C06 的营运端数订正**：独立报告原文写「共 15 个」，按 6+4+3+3+1 实为 **17 个**；总漏枚举仍是 **22 个/9 文件**（Admin* 6、非 Admin 16）。
6. **「P2-12 依赖 P2-18」是错的**：`merchant_fee_ledger` 的 REN/DATA 行由 `MerchantDailyIncomeNotifyScheduler`（每日 00:20，`chargeDailyPeriodFees`）写入，**不受** `OpsFeeBillJob` 的 `feebill.auto_generate.enabled` 门控。月账单改读该表不依赖场地租金开关；修复顺序可以并行，不存在「先修账单会读到 0」。
7. **「44 个 @Scheduled 无一带 `@ConditionalOnProperty`」不精确**：交集实为 1——`OpsFeeBillJob` 同时带 `@Scheduled` 与 `@ConditionalOnProperty(prefix="aicabinet.fee-bill", name="auto-generate-enabled", matchIfMissing=true)`。不影响「tryBegin 防双跑」的结论。
8. **C05 由 P1 降 P2 的依据（2026-11-15 终裁，可回滚）**：全仓 `finance_margin_daily_lock` 的读者只有 `FundBillService` 自身（`:162` 列表、`:433` 写、`:451-455` 读回显），**没有任何资金任务、结算或分账链路消费它**；错误固化可由管理员重跑当日固化覆盖恢复。因此性质是「权限模型违规（读权限写全局键）+ 报表完整性」，不是资金完整性 → P2。若后续出现「下游按该表出账/结算」的消费点，应立刻回 P1。
   反面留痕：C03 的**读面**（`listPricing:75`）不可漏修——原四端轮只写「单柜改价/批量重置」，实际读端点同样缺 `requireDeviceAccess`，修法必须覆盖三个入口。

---

## 4. 台账补登对照（MA-262…294）

| MA | C# | 级别 | 一句话 |
|---|---|---|---|
| MA-262 | C01 | P1 | 提现 FAILED 二次解冻 |
| MA-263 | C02 | P1 | 视频播放无对象级授权 |
| MA-264 | C03 | P1 | 改价/重置无设备范围 |
| MA-265 | C04 | P1 | 设备 HTTP 凭据无设备范围 |
| MA-266 | C05 | P2（原 P1 降） | 日毛利锁只读权限写全局（无下游资金消费） |
| MA-267 | C06 | P1 | 写端点归因门禁漏 22 个 |
| MA-268 | C07 | P2 | 月结期初取错一期（原 P1 降） |
| MA-269 | C08 | P2 | 手工退款并发双记（原 P1 降） |
| MA-270 | C09 | P2 | 退多收不回算分账 + 巡检缺口 |
| MA-271 | C10 | P2 | 月账单退款日口径 |
| MA-272 | C11 | P2 | 月账单费用源过期 |
| MA-273 | C12 | P2 | 场地租金不看合同起止 |
| MA-274 | C13 | P2 | 扣款事务内 HTTP |
| MA-275 | C14 | P2 | 支付分通知竞态 |
| MA-276 | C15 | P2 | 分账锁不覆盖提交 |
| MA-277 | C16 | P2 | 温控/关门/重启无设备范围 |
| MA-278 | C17 | P2 | 场地租金被流量费开关连带关闭 |
| MA-279 | C21 | P2 | 看板按钮权限不同源 |
| MA-280 | C22 | P2 | mp-scoped-hash CI 恒 SKIP |
| MA-281 | C23 | P2 | 开门超时不变式无强制 |
| MA-282 | C24 | P2 | selftest 豁免棘轮失效 |
| MA-283 | C25 | P2 | banner 基线 52 vs 49 |
| MA-284 | C27 | P3 | 回调占位延迟窗口 |
| MA-285 | C28 | P3 | 租机押金无设备范围（不可利用） |
| MA-286 | C29 | P3 | 月租整数截断 |
| MA-287 | C30 | P3 | 结算单/月账单归月口径 |
| MA-288 | C31 | P3 | gateway accepted=false 当成功 |
| MA-289 | C32 | P3 | 分账锁 500 应为 409 |
| MA-290 | C38a | P3 | PROJECT_KNOWLEDGE 数字漂移 |
| MA-291 | C39 | P3 | 接线闭包 no-op |
| MA-292 | C40 | P3 | FUND_KEY_CEILINGS 无门禁 |
| MA-293 | C41 | P3 | admin 产物配对 fail-open |
| MA-294 | C42 | P3 | 0=关闭预授权不敌设备押金（待拍板；与 C26 拆开） |

已登记（不重复）：C18=MA-176、C19=MA-224、C20=MA-260（本轮订正为 2 个）、C26=**MA-178（本轮由 P3 上调 P2，仅「无上界」半条）**、C33=MA-177、C34=MA-179、C35=MA-200、C36=MA-201、C37=MA-225、C38b=MA-261、C38c=MA-226（已 fixed）。

**号段声明（防撞车）**：台账已占用 **MA-262…MA-294**（本表全部条目）。EXT1 §6 的号段声明（280…299）与此重叠 8 个（其 280=提现，本表 280=mp-scoped-hash），必须让到 **295 起**；其 `R23I-*` 一律读作四端轮 `R23Q-*`。

**行号订正（2026-11-15 复核）**：
- MA-264 原写 `:57-63`（那是构造器）→ 实为**三个入口**：`OpsDevicePricingService:75`（读）/ `:101`（写）/ `:196`（重置）。
- MA-265 原写 `:46-53`（那是 `record IssuedCredential` 定义）→ 控制器入口：`DeviceHttpCredentialController:47-53`（签发）/ `:66-70`（吊销）。
- MA-262/263/266/267 的行号已逐条亲验，无需订正。

**同族关联（派工前必读，避免重复排期）**：
- MA-263（视频越权）↔ **AU-062**（`docs/AUDIT_LEDGER_2026-10-13.md`，视频台账列表回原始实体含对象键给 `ops:device:list`，待 DC-11 拍板）——同一「视频权限口径分裂」家族，修时一并定 DC-11，别只补 `requireDeviceAccess` 又留列表侧。
- MA-269（手工退款并发双记）↔ **MA-088**（同代码点幂等键塌缩，已 fixed）——MA-088 封的是顺序重投，本条是并发残余。
- MA-270（退多收不回算分账）↔ **MA-124**（同一 fix 端点，MA-124 修的是「无 status 闸可重放/返回值未检查」，本条修的是「不重算分账」）。
- 越权族 MA-263/264/265/277 ↔ **MA-068 家族**（盘点凭证跨租户写，已按 `requireDeviceAccess` 修复）——**说明这是同类缺陷复发，不是孤例**：修复批 1 应同时落「写端点必须过 `requireDeviceAccess`」的门禁规则，否则第四次还会漏。

---

## 5. 修复批次（不变，仅编号换成 C）

| 批 | 条目 | 验收判据 |
|---|---|---|
| 1 权限同族 | C02/C03/C04、C05、C16、C28 | 受限运营对范围外 deviceId 全部 403（**含读面**：`listPricing`/`playUrls`）；日毛利锁改为写权限点 + 仅全局范围可固化 |
| 2 门禁可信 | C06、C22/C24/C25、C39/C40/C41、C38a | 重种 477 基线；5 门禁移出 exempt；banner=49；**新增「写端点缺 `requireDeviceAccess`」静态门禁**（关 MA-068 复发族） |
| 3 资金失败路径 | C01/C08、C09/C14/C15 | 并发回放 + 巡检三判据能红 |
| 4 账期口径 | C07、C10/C11/C12/C17、C30 | 同月重算差异表；场地租金开关拆开 |
| 5 事务边界 | C13、C32 | 慢渠道注入下连接池/锁 TTL 压测 |
| 6 调度兜底 | C20 | staging + 裸 JVM 两环境跑通 |
| 7 其余 P3 | C27/C28/C29/C31/C33/C34/C35/C36/C37/C38b/C38c | 随批带走 |

---

## 6. 流程洞已修复：审计报告 → 台账的机制门禁（2026-11-15）

**洞是什么**：本轮四端轮 22 条全在报告正文、`ISSUES.md` 零登记（`grep -c 'R23Q-'` = 0）。人工补 33 行只补了**数据**，补不上「下次还会漏」。

**修法**：新增 `scripts/check-audit-report-registered.mjs` 并接进 `check:audit-gates`。

| 判据 | 内容 | 能红？ |
|---|---|---|
| R1（阻断） | 报告内每个 `MA-*` / `C##` / `R2x-XX` 必须能解析到台账：① ID 列存在；② 台账行内出现该字面串；③ 组合版映射表（`\| MA-262 \| C01 \|` 或 `C18=MA-176`） | ✓ 夹具 S2（注入未登记编号即红） |
| R2（WARN） | 报告声明的「建议新 MA 号段」与台账已占号段重叠 | 只告警，说明见下 |
| 防假绿 | 报告数 < 3 / 台账条目 < 100 ⇒ 直接红；扫前 `maskComments()` 抹 HTML 注释与代码块 | ✓ 夹具 S7 / S4 |
| 豁免 | 只认报告内显式声明「不写 ISSUES.md」「仅本报告标注」，不认文件名白名单（文件名白名单会腐化成永久放行）；豁免仍逐条 WARN | ✓ 夹具 S6 |

**R2 为什么只 WARN（有意，不是漏）**：静态文本无法区分「本轮预留窗口」（`README` 的 `MA-176..199`，留白正常）与「抢占已占号段」（EXT1 的 280…299）。首版判红实测误伤 **11 条**，全部是合法区间描述 ⇒ 按铁律「判据要贴真实责任边界」降为 WARN，阻断只由 R1 承担。撞号由本报告 §2-8 与台账号段声明人工兜底。

**验证**：
- 门禁实跑：`报告 18 份 / 台账 219 条，全部编号可解析`（exit 0）。
- 夹具 `scripts/devops/verify-audit-report-registered-gate.mjs`：**S1–S8 全 PASS**（含 S2 判据能红、S3 还原、S4 防假红、S5 R2 边界、S6 豁免识别、S7 台账截断能红、S8 真实仓 md5 前后一致）。
- 接线：`check:audit-report-registered` + `-negative` 两条 npm 脚本、聚合链插在 `check:ledger-evidence-negative` 之后、`gate-fixture-registry.fixtureMap` 已登记；`check-audit-gates-wiring` 复跑绿（195 个门禁全部接线、147 引用有定义、夹具 79/146）。

**台账同步（本轮已落）**：MA-264 行号改为三入口 `:75/:101/:196`；MA-265 改为 `Controller:47-53/:66-70`；补同族标注 5 处（MA-263↔AU-062、MA-269↔MA-088、MA-270↔MA-124、MA-277 与 MA-263/264/265↔MA-068 家族）；补 `R23X-02`（MA-260）、`C38b`（MA-261）、`C38c`（MA-226）关联；新增号段声明（MA-262…294 已占用，EXT1 须让号）。

---

## 7. 修复批 1 落地（2026-11-15，本轮已改码）

**范围**：C02/C03/C04/C05/C16/C28。**已改**：6 条全部修复；**未改**：其余 36 条仍 open。

### 7-1 逐条处置

| 条 | 修法 | 判据（怎么证明修好了） |
|---|---|---|
| C02 | `JiangyiOrderVideoService` 全读面收口：`playUrls(id, operatorId)` 按行上 `deviceId` 过 `requireDeviceAccess`；`listByOrderRedacted/listByTaskRedacted/listRecentByDeviceRedacted` 一律必传 operatorId（**删掉无操作员重载**，对象级授权留绕过口等于没有）；控制器 4 个端点改传 `operatorId(request)` | `JiangyiOrderVideoServiceTest` 17 例通过；越权路径由 `requireDeviceAccess` fail-closed |
| C03 | `OpsDevicePricingService` 三入口（`listPricing:75` 读面 / `updatePricing:101` / `resetPricing:196`）统一补 `merchantScopeService.requireDeviceAccess`，置于 `requireDevice` 之前（越权不泄露「设备是否存在」） | 新增 `OpsDevicePricingServiceTest#pricingEntries_rejectOutOfScopeDevice`：三入口全 403 + 断言未触达 `deviceRepository/priceRepository` |
| C04 | 由 `OpsDeviceScopeInterceptor` 统一覆盖：两个凭据控制器类级映射就是 `/api/v2/ops/admin/devices/{deviceId}/...`，issue/revoke/**status** 三面自动过闸（HTTP 与 MQTT 同源，不存在只修一侧） | 拦截器 R1/R3 判据 + 夹具 S2/S5；**不在控制器内重复注入**——重复只增签名改动（`openapi-regen` 门禁要求同提交重生成类型），机制覆盖即可 |
| C05 | `FundBillService.solidifyMargin`：权限点从读权限 `ops:finance:view` 改为写权限 `ops:fund:manage`（V341 只授 admin），并对 `operatorId != null` 追加「必须全局范围」闸；定时任务 `operatorId=null` 不受影响 | 新增 `FundBillServiceTest` 两例：受限运营 403 且不写表；null operator 跳过闸且照常落库 |
| C16 | 温控 `DeviceTempPlanService`（get/upsert/applyNow）+ 将邑控制面 `JiangyiDeviceOpsService`（setSound/requestDeviceInfo/closeDoor/reboot/requestDoorState/pullOrderVideo）补范围校验 | 受影响 4 个测试类共 23 例通过（含 closeDoor/reboot 派发与审计断言） |
| C28 | `DeviceRentalService` registerDeposit/deduct/settle 补范围（deduct/settle 用行上 `deviceId`，避免再查一次） | `DeviceRentalServiceTest` 28 例通过 |

**回归证据（本机实跑）**：`mvn -o -pl services/trade-service -am test` → **Tests run: 3086, Failures: 0, Errors: 0**，`BUILD SUCCESS`（含 `TradeArchitectureTest` 6 例：新拦截器落 `auth` 包未破分层）；`node scripts/pre-push-ci-preflight.mjs` → **✓ 预检通过**（format / mp type-check / lint / audit-gates / migration / openapi-regen 全绿）。

### 7-2 机制解：为什么不再逐个端点补

`requireDeviceAccess` 从 MA-068 起已复发**四次**，根因不是「忘了写」，而是**修法本身把正确性押在人的记忆上**。本轮 `AdminJiangyiController` 一个类就有 34 个 `/devices/{deviceId}/...` 端点没查——按条补只能补到被审到的那几个。

**改法**：在 `/api/v2/ops/**` 前缀上落 `OpsDeviceScopeInterceptor`，凡路由里有 `{deviceId}` 路径变量就先过范围（判据与 `requireDeviceAccess` 同源），端点作者无需记得任何事，**新端点自动继承**。顺序 `.order(10)` 晚于 `AuthInterceptor`（否则取不到 `ATTR_USER_ID`，全站运营后台 401）；拿不到身份一律 401 fail-closed，不静默放行。

**为什么不读 query 参数**：拦截器只认路径变量。`request.getParameter("deviceId")` 会触发 form body 解析，把 `@RequestBody` 吃掉（POST 端点直接 500）。查询串形态改由门禁 R4 管：要么改成 `@PathVariable`，要么自身过 `requireDeviceFilter`，要么在基线带原因登记。

### 7-3 新增门禁 `check:ops-device-scope`

| 判据 | 内容 | 能红？ |
|---|---|---|
| R1 拦截器本体 | 必须调 `requireDeviceAccess(`、必须从 `URI_TEMPLATE_VARIABLES_ATTRIBUTE` 取 `deviceId`、无身份必须 401、禁止 `getParameter("deviceId")` | ✓ S2 |
| R2 注册面 | 必须用 `OPS_PREFIX` 注册、`order > 0`、**不得**挂 `excludePathPatterns` | ✓ S3（排除口）/ S4（顺序） |
| R3 前缀一致性 | `OPS_PREFIX == /api/v2/ops/**`；所有运营控制器类级前缀都落在 `/api/v2/ops` 下；扫描面下限（api 文件 ≥40、运营控制器 ≥30）防「扫空即绿」 | ✓ S5 |
| R4 查询串例外 | `@RequestParam` 形态的 deviceId 必须 ≤4 跳内过范围判据，否则进基线带原因；基线条目一旦修好即告警要求删除（棘轮只降不升） | ✓ S6 |

**夹具** `scripts/devops/verify-ops-device-scope-gate.mjs`：S1–S6 全 PASS，零残留（真实仓 3 文件 md5 前后一致）。已接 `check:audit-gates` 聚合链与 `gate-fixture-registry.fixtureMap`；`check-audit-gates-wiring` 复跑绿（196 门禁全接线、夹具 80/147）。

**R4 的三个自身缺陷（本轮实证，留痕防复发）**：
1. 朴素注释正则 `/\*[\s\S]*?\*/` 会把字符串 `"/api/v2/ops/**"` 里的 `/**` 当注释起点，一路吃掉整段代码 ⇒ 判据全假绿。改状态机（code/str/chr/line/block 五态）。
2. `^[^;{]*?\b(\w+)\s*\(` 懒匹配会在 `@GetMapping(` 处命中，方法名解析成 `GetMapping`、形参表解析成注解自己的参数表 ⇒ 每个端点都被 `continue` 跳过，R4 恒绿。改为「先找 parenDepth=0 的方法体 `{`，再往前配平形参表」。
3. 方法体解析按 `\bname\s*\(` 会把**调用点**当定义（`AdminDashboardService` 里首个 `listSessions(` 是别处的调用），拿到的是下一个方法的方法体 ⇒ 真校验读成「没校验」，11 条假红。改为锚在修饰符上 + 同名重载全拼 + 去重键带 depth。

### 7-4 修复过程中新发现（本轮追加，编号接 C42）

| 条 | 级 | 位置 | 一句话 | 台账 |
|---|---|---|---|---|
| C43 | P2 | `OpsReplenishmentAdminService:51-61`、`:114-117`（→ `ReplenishmentService:337/353/362`） | 补货路线 / 履约任务 / 到期下架三个分页把 `operatorId` 收下后**丢掉**：`deviceId` 不过范围、不传即返回全量 | MA-295 |
| C44 | P2 | `InTransitService:183-200`、`TallyLedgerService:36-46` | 领货未上柜分页不接收 `operatorId`（无范围参数）；理货台账自拼 SQL 的 WHERE 只有时间窗 + `assignee>0`，不带设备范围 | MA-296 |

**为什么这一轮不顺手改**：两条都是「列表取数范围下推」——要把单值 `deviceId` 换成设备**集合**下推（`IN (...)`），涉及 3 个 mapper + 分页 SQL，与已被 `check:workbench-scope-pushdown` 钉住的同类修法同源。半修（只加 `requireDeviceFilter`）会让人误以为已闭环：传了 filter 越权 403、**不传 filter 仍全量**。已按「显式欠账」登记在 `scripts/ops-device-scope-baseline.json`，门禁会持续提示，修复后必须删条目。

> **2026-11-16 更正**：C43/C44 已在 §9 批 4（`b1764c72`）修复为集合下推，台账 MA-295/296 `fixed`，基线 7→5。上面「本轮不顺手改」是修复前快照，引用时以本条和台账为准。

### 7-5 真栈点验（2026-11-15 12:25，重建镜像后实测）

**前提**：`docker compose -p ai-cabinet -f infra/docker-compose.full.yml -f infra/docker-compose.win-ports.yml build trade-service` → jar 内含拦截器（`unzip -l /app/app.jar | grep -c OpsDeviceScope` = **1**，产物级证据）→ `up -d --pull never --no-deps --force-recreate trade-service` → healthy。

**口径**：超管 `13900000001`（admin ⇒ 全局范围）；运营 `13900000003`（operator，绑 MCH-DEFAULT + MCH-OTHER）。设备 `EG943322621391` 属 MCH-DEFAULT（范围内）；`719325384517` 无商户归属（范围外）。

| 端点 | 无 token | 超管·范围内 | 超管·范围外 | 运营·范围内 | 运营·范围外 |
|---|---|---|---|---|---|
| `GET /ops/admin/devices/{id}/pricing` | 401 | 200（有价目） | 200（空表） | 200（有价目） | **403** |
| `GET /ops/admin/devices/{id}/temp-plan` | 401 | 200 | 200 | 200 | **403** |
| `GET /ops/admin/jiangyi/order-videos/by-device?deviceId=` | — | 200（有行） | 200（有行） | 200（有行） | **403** |
| `GET /ops/admin/devices/{id}/http-credential` | — | 400（业务校验：非 12 位数字） | 404（尚未签发） | 400 | **403** |
| `PUT /ops/admin/devices/{id}/jiangyi/sound` | — | — | — | — | **403**（未下发） |
| 不存在设备 `999999999999/pricing` | — | 404 | — | **403** | — |
| 伪造 JWT | 401 | — | — | — | — |

**三条结论（对应 §7-2 的机制主张）**：

1. **拦截器确实在跑**：`http-credential` 控制器内**没有**范围校验（C04 回退时不注入、纯靠机制），运营访问范围外得 403 —— 该 403 只可能来自 `OpsDeviceScopeInterceptor`，不可能是控制器或权限码。
2. **闸在业务之前、不泄露存在性**：同一不存在的 deviceId，超管得 404（业务层），运营得 **403**（范围层）——范围判定先于设备查询，越权者不能靠 404/403 差异探测哪些柜存在。
3. **不误伤**：超管与运营对范围内设备全部 200；`sound` 对范围外 403 且未向设备下发指令。

**未覆盖（有意）**：XXL/定时任务路径（`operatorId=null` 不受拦截器影响，已由 `solidifyMargin_nullOperatorSkipsScopeGate` 等单测断言）；非 `/api/v2/ops/**` 前缀的运营端点（R3 判据当前为 0）。

## 8. 修复批 2 / 批 3 落地（2026-11-15 下午，本轮已改码）

**范围**：批 2 = C06（门禁自证盲区）；批 3 = C01（提现二次解冻）。**未改**：其余 P2/P3 仍 open（C43/C44 已带 why 记不采纳）。
> **2026-11-16 更正**：C43/C44 随后已在 §9 批 4（`b1764c72`）修复（MA-295/296），此处「不采纳」仅记录当时状态；引用时以台账为准。

### 8-1 批 2：C06 归因门禁「解析面漏 22 个写端点」（MA-267，P1）

**缺陷的两层**（都在「方法声明解析」这一处）：
1. 返回类型含**限定名**（`ApiResponse<com.aicabinet.common.dto.PayContractDto>`、`JiangyiRestockService.SubmitResult`）——旧正则字符类 `[\w<>,\s]` 缺 `.` ⇒ `sig` 找不到 ⇒ 端点被**静默跳过**（`continue`，不进快照、不受任何棘轮约束）。
2. 注解与签名**同行**（`@PostMapping("/{id}/retry") public ApiResponse<OpsExceptionDto> retry(`）——旧实现从注解的**下一行**起扫，同行签名必然扫不到。

后果不是「少算一个数」而是**自证陷阱**：基线 `totalEndpoints=455` 正是坏解析器的输出，逐字相等 ⇒ 门禁全绿。实测 477 个写注解只解析 455 个（9 个控制器 / 22 个端点，`AdminJiangyiController` 21 vs 27 首当其冲）。

**修法**：
- `declNameOf(line)`：先抹注解（允许同行），再取 `(` 前**最后一个标识符**；窗口 12 行、从注解**本行**起扫；`findMethod`（委托链解析用）同源改造。
- **新增类级不变量：写注解数 == 解析端点数**（`maskJava` 之后计数）。这是这类盲区的正确判据——不再依赖「基线由解析器自己种」，任何解析不出来就红。
- 重种基线 **477 / 255 / 222**：+22 端点里 15 个归因、**7 个无业务审计**；`maxUnattributed` 215→222 属解析面纠偏（原 215 是漏算结果），note 写明此后只许减。

**同族顺手修**（同一「注解同行 ⇒ 返回类型抽不到」缺陷，另一个门禁）：`check-openapi-regen-required` 的 `mappingSignature` 先剥行首注解再判返回类型——`OpsExceptionController` 11 个端点此前**改响应类型不触发重生成**，`openapi.ts` 静默过期。

**新暴露的 7 个无审计端点** → **MA-297**：`OpsExceptionController` 三个危险动作（`cancel-session`/`retry`/`manual-resolve`）+ `SystemConfigController` 的 `alert-test` 属真缺口；`AccountController` 2 个消费端签约与 `DemoDataInternalController.ensure` 按既有口径不计（消费端 8 端点全 0 归因、内部端点无操作者），条目里已点名区分。

**验证**：夹具 34 项（新增合成三形态 + 全仓注解对账）；三态负向 —— 缺 `.` ⇒ 夹具与门禁双红（14 项）、窗口退回 `i+1` ⇒ 夹具红（方法名被顶替成 `drop`）、openapi 退回整行判 ⇒ 2 条新断言红；两次还原 md5 零残留。

### 8-2 批 3：C01 提现二次解冻（MA-262，P1）

**缺陷**：打款失败落 `FAILED` 时已 `releaseFrozen` 一次；运营再点「打款失败取消」（`cancelFailed` 只校验状态=FAILED）**又释放一次** ⇒ 本单冻结早为 0，第二次减掉的是**另一张在途单**的冻结额；那张单打款成功走 `consumeFrozenSplit` 时冻结不足 ⇒ 事务回滚，而渠道钱已出账（钱出账、余额未扣）。单据状态挡不住：`FAILED` 不区分「已释放」与「重试前重新冻结」。

**修法（放在冻结账汇聚点，不在 5 个入口各判一次）**：
- `WalletLedgerMapper.listByRefAndEntryTypes`：按 `(domain, owner, refType, refId)` 取冻结生命周期原始流水。空列表 = **无流水（不可判）**，不等于净额 0。
- `WalletEngine.releaseFrozen` 幂等：`held = |FREEZE| - |RELEASE| - |PAID| - |FEE|`；`held<=0` ⇒ 空转（info 留痕）；`0<held<金额` ⇒ 只释放 `held`（历史脏数据不越界、不滞留）；无流水 ⇒ 退回账户级旧口径并 warn（迁移前老单据没有冻结流水）。
- `WalletEngine.consumeFrozenSplit` 加 ref 级闸：**本单**占用不足即 412 拒付，把资损挡在渠道调用之前（账户冻结合计够 ≠ 本单冻结够）。

**首版公式被真库击穿（留痕防回归）**：先按 `-Σamount` 算，真库 `ref=1` 是 `FREEZE -1000 / PAID -994 / FEE -6`、`frozen_after` 最终 0 —— 合并合计得出「仍占用 2000」，恰好是「多释放吃别人冻结」的方向。根因：`amount_cents` 记的是**余额**方向，而冻结方向三类各不相同（FREEZE 负记但冻结 +、RELEASE 正记且冻结 -、PAID/FEE 负记且冻结 -）。已改为按类型分别累加并加例钉住。

**验证**：新增 `WalletWithdrawFreezeIdempotencyTest` 7 例（内存**真实流水账**，不 mock 掉流水——幂等判据的输入正是流水）：二次解冻幂等 / A 重复解冻不吃 B 冻结且 B 可正常扣款 / 本单冻结已释放时拒付 / FAILED 重试重新冻结后扣款正常 / 部分占用只释放剩余 / 无流水退回旧口径 / 已打款单持有 0。负向两态：打回账户级旧口径 ⇒ 4 例红；打回 `-Σamount` ⇒ 钉住真库形态那例红。全量 `mvn -o -pl services/trade-service -am test` ⇒ **Tests run: 3093, Failures: 0**。

### 8-3 批 3 真栈点验（重建镜像后实测）

| 步骤 | 证据 |
|---|---|
| 镜像产物 | `unzip` 出 jar 内 `WalletEngine.class` 含 `heldFrozenCents`、`WalletLedgerMapper.class` 含 `listByRefAndEntryTypes`（产物级，非源码级） |
| 真栈申请提现 | `POST /api/v2/merchant/wallet/withdraw`（1000 分）→ `requestId=3`、`status=PAID`、`payChannel=MOCK`、`feeCents=6` |
| 流水落账 | `FREEZE -1000 (frozen_after 1000) → PAID -994 (6) → FEE -6 (0)` —— 新查询在真实 Postgres 上跑通（失败会 500 回滚） |
| 占用额对账 | `held(ref 1/2/3) = 0`；账户 `217801 → 216801`，`frozen=0` |
| 状态闸仍在前 | `POST /ops/admin/merchant-withdraws/3/cancel` → **409 仅打款失败的提现单可取消解冻** |
| 存量核查 | 全库 `over_released_refs = 0`、`wallet_account.frozen` 全 0 ⇒ 本环境无存量多发，缺陷属潜伏路径 |

**未覆盖（如实）**：`FAILED → cancel` 双重解冻路径**无法在真栈制造**——MOCK 渠道恒成功，强制失败需要真渠道或改渠道配置（后者会动真实转账行为，不做）。该路径由单测 3 例覆盖（含真库流水形态）。真栈探针留下一笔 MOCK 提现单（`requestId=3`，PAID，¥10），属正常 dev 数据。

## 9. 修复批 4：C43/C44 列表取数设备范围下推（2026-11-15 下午）

**范围**：MA-295（C43）、MA-296（C44），外加同判据扫出来的 5 个同族端点与 2 处门禁盲区。**未改**：MA-298（新登记，口径待拍板）。

### 9-1 缺陷的共同形态：把 `operatorId` 收下，然后在下游丢掉

| 面 | 位置 | 表现 |
|---|---|---|
| 补货路线分页 | `OpsReplenishmentAdminService:51-61` → `ReplenishmentService.listRoutesPage` | 传的是单值 `deviceId`；不传即**全平台**路线 |
| 履约任务分页 | 同上 `:57-61` | 同上 |
| 到期下架分页 | 同上 `:114-117` | 同上 |
| 柜机库存台账 | `:36-38` → `listInventory(deviceId, …)` | 不传柜号 = 全平台库存行（2000 上限内） |
| 资费 / 租机账单 | `DeviceDataFeeBillService.list`、`DeviceRentalService.listBills` | `operatorId` 只用于 `requirePermission`，WHERE 里只有 `device_id = ?` |
| 领货未上柜 | `InTransitService.listInTransitPage(deviceId, …)` | 签名里**没有** operatorId，物理上无法带范围 |
| 理货台账 | `TallyLedgerService.ledger`（自拼 SQL） | WHERE 只有「时间窗 + `assignee>0`」 |
| 导出面 | `exportReplenishmentRoutesCsv`、`exportRiskEventsCsv` | 比列表更狠：不翻页，一次落盘全量 |

### 9-2 修法：一个入口 + 集合下推（不物化「不限」）

`MerchantScopeService#pushdownDeviceIds(operatorId, requestedDeviceId)`：

| 入参 | 返回 | 语义 |
|---|---|---|
| 不给柜号 + 全局账号 | `null` | 不限，SQL **不加** `device_id IN (…)` |
| 不给柜号 + 域受限 | `Set`（可能为空） | 只查这些柜机；空集 ⇒ 调用方直接空页 |
| 给了柜号 | `List.of(dev)` | **先 `requireDeviceAccess`**：越权 403，不返回空表 |

刻意不把「不限」物化成全表 ID 集合：那是几千个占位符，而且**新建的柜机会落在集合外**（等于给新设备设一个隐形不可见期）。

配套：所有列表查询签名从 `String deviceId` 改为 `Collection<String> deviceIds`，**旧单值重载全部删除**——留着就是「下一个调用方仍然只按柜号过滤」的口子（同 C02「删掉无操作员重载」的判据）。

### 9-3 顺手删掉死门面 `OpsCommercialFacade`（488 行，零调用方）

改签名时才发现它没有一个调用方：它是「自原 OpsCommercialFacade 按域抽出」之后留下的残骸，却仍然保留着**同一批列表的第二套无范围实现**（`listRoutesPage`/`listFulfillmentTasksPage`/`listInTransit` 全部把 operatorId 收下再丢）。门禁只看控制器，看不到它。删掉比修好更省事，也更安全——留着只等哪天有人复用。

### 9-4 门禁：本轮被两个门禁的「识别面」咬了两次

1. **`check:ops-device-scope` R4 只认带引号的 `@RequestParam`**：`@RequestParam(required = false) String deviceId`（Spring 按形参名取参）整批不进判定面。补上后立刻暴露 6 个端点 —— 3 个真泄漏（本批修好）、3 个经 `allowedDeviceIds + contains` 交集实现的（登记为「非同族缺陷」，基线条目写清由谁兜底）。基线 7 → 5 条（修好的必须删，棘轮只降不升）。夹具 S7/S8 分别钉「隐式命名必红」「把修好的范围退回 null 必红」。
2. **`check:concurrency-declaration` 的归属判据（本轮被它误报才发现）**：
   - `isMethodSignature` 是「含括号、不以分号结尾」的近似 ⇒ `DeviceSkuInventory inv = existing.orElseGet(() -> {` 被当成方法起点，30 行 blob 命名成「orElseGet」；
   - 只找「签名行 ≤ 变更行」⇒ hunk 落在新方法的 javadoc/注解上时算到**上一个**方法头上（实测：给只读 `listRoutes` 加 javadoc，报「写方法 upsertInventory 无并发策略」）。
   改为**真声明形态判据**（括号后只能跟 `{` 或行尾、括号前不得有 `=`/`->`、不得是链式续行）+ **前导块归属**（空白/注释/注解直接算给下面的方法）。夹具 8 → 14 项。

### 9-5 验证

| 项 | 证据 |
|---|---|
| 全量单测 | `mvn -o -pl services/trade-service -am test` ⇒ **3104 / 0 fail / 0 error**（+11） |
| 新增用例 | `OpsReplenishmentScopePushdownTest` 5 例（下推的是范围集合／越权 403 且零取数／`null` 保持不限／空集不当全量）+ `MerchantScopeServiceTest` 5 例（null/空集/单元素/越权 403/未绑定零范围）+ 理货台账空集短路 1 例（`verifyNoInteractions(jdbc)`） |
| 三态负向（业务） | `pushdownDeviceIds` 退回「不看范围」⇒ 2 红；本单 routes 下推退回 null ⇒ 1 红；理货空集降级成不过滤 ⇒ 1 红；还原 md5 逐字节一致、零 `NEG-INJECT` |
| 三态负向（门禁） | 并发门禁退回旧谓词 ⇒ 夹具 3 红；R4 退回只认显式命名 ⇒ 夹具 S7 红且 gate exit 0（**正是原来的假绿**） |
| 预检 | `pre-push-ci-preflight` 全绿（format / lint / audit-gates / migration / openapi） |

**真栈点验**：见 §9-6（2026-11-15 补做，已从「未覆盖」转为已覆盖）。

**新登记**：MA-298（P3）——`MerchantInventoryPortalService` 的 `lowStockOnly` 分支仍是「全局 500 窗口 + 内存过滤」，修它要连带拍板低库存口径（`findLowStockByDeviceIds` 多收 `'DEPLOYED'` 且自带 LIMIT 500，直接换会改行为）。**已于同日晚修复，见 §10。**

### 9-6 真栈点验：跨商户 / 柜机级范围实际不可见（2026-11-15）

单测只能证明「拼出来的 SQL 带了谓词」，证明不了「换一个账号登进来真的看不到」——那是 `pushdownDeviceIds` 与 `allowedDeviceIds` 在真库上的合流。§9-5 原先把这一项标为未覆盖，本节点验补上。

**方法**：镜像重建（`docker compose build trade-service` + `--force-recreate`）后，用真账号登录（`admin-password-login`，验证码从 Redis `aicabinet:captcha:<id>` 直取），对**同一个 token** 做 A/B。唯一的变量是 DB 里的一行绑定/范围偏好，请求与令牌一律不变。

| # | 场景 | 账号（角色 / 绑定 / 货柜范围） | 结果 |
|---|---|---|---|
| 1 | 基线：数据确实在那儿 | 13900000001（admin / 全局） | 库存 8+2+3 行（含 `CAB-OTHER`）、任务 9+1+21、在途 2 行（`CAB-OTHER` 在列） |
| 2 | 双商户绑定 | 13900000004（replenisher / MCH-DEFAULT+MCH-OTHER / ALL） | `CAB-OTHER` 可见（库存 2 行、任务 1 行、路线命中 2 次） |
| 3 | **解绑 MCH-OTHER**（只改这一行） | 同上，同 token | 库存只剩 `500909625160`/`EG943322621391`；任务 9+21；路线里 fixture 命中 **0**；`?deviceId=CAB-OTHER` ⇒ **HTTP 403** |
| 4 | 恢复绑定 | 同上，同 token | `CAB-OTHER` 回来（库存 2 行、任务 1 行），指名查询 200 |
| 5 | 柜机级求交（DEVICE_IDS） | 同上 + `scope=EG943322621391` | 库存仅 3 行（EG）；指名**同商户**另一台 `500909625160` ⇒ **403**；在途 total 0 |
| 6 | 柜机级求交反向 | 同上 + `scope=500909625160` | 在途 1 行（`500909625160`）、台账 6 行、库存 8 行；`CAB-OTHER` 始终 0 |
| 7 | 资金账单同源 | 13900000002（finance / 双商户） | 无货柜范围 ⇒ 租机账单 1 行；`scope=EG` ⇒ **total 0**；`scope=500909625160` ⇒ 1 行 |
| 8 | 导出口径一致 | 13900000004（`scope=500909625160`） | 路线 CSV 200 / 808 字节：`500909625160` 9 行，`CAB-OTHER` 0 行，fixture 路线 0 行 |

第 3/4 条是**同 token 的前后对照**：范围是每个请求现算的（`allowedDeviceIds` 每次查库），没有把范围缓存进令牌——这点只能真栈验，也顺手排除了「解绑后仍旧可见」这类缓存型泄漏。

**造数与清理**（一次性行，全部带 `E2E-SCOPE` 标记，验完即删，零残留已复核）：`CAB-OTHER` 的 2 条库存行、1 条路线（`route_id=36`）+ 1 条任务（`task_id=38`）、复用出库单 24 的 2 条 `IN_TRANSIT` 行；账号侧只动 `ops_user_merchant` 一行与 `ops_user_device_scope(_pref)` 两行，验完全部还原（`bind9=MCH-DEFAULT,MCH-OTHER`、`scope_pref=0`、`scope_rows=0`）。

**为什么在途要先造 `IN_TRANSIT` 行**：`listInTransitPage` 只列开单在途（`IN_TRANSIT`），dev 库里 24 行全是 `RECEIVED`/`CANCELLED` ⇒ 任何账号都是 0 行，直接拿它当「看不见」的证据是假绿。第 1 条先证明 admin 能看见这两行，第 5/6 条才有意义（负向 + 正向双控）。

**仍未覆盖**：`device_data_fee_bill` 在 dev 库是空表，资费账单端点只验了代码路径与单测，没做真栈数据面；归到 MA-298 那批一起造数。

## 10. 修复批 5：MA-298 商户门户低库存 / 临期告警的窗口顺序（2026-11-15 傍晚）

§9 把「设备范围」推到 SQL 时，`MerchantInventoryPortalService` 只改了带柜号的那条分支，留下两处「全局窗口 → 内存过滤」。本批收掉，并给这一类补上静态判据。

### 10-1 缺陷形态：同一个文件里两处 B13

| 入口 | 原写法 | 后果 |
|---|---|---|
| `listInventory(lowStockOnly=true, deviceId=null)` | `findLowStockLimit(500)` 取**全平台**前 500 条低库存行，再 `filter(inDeviceScope)` | 别家行占满 500 条时，域受限商户自己的低库存行被挤出窗口 ⇒ 界面显示「没有要补的」 |
| `listExpiryAlerts(userId)` | `findByStatusOrderByCreatedAtDesc("OPEN", 500)` 取**全平台**最近 500 条，再 `filter(inDeviceScope)` | 临期告警同样「自家的排在窗口外就看不见」，而计数走的是带范围的方法 ⇒ 数字与列表互相打架 |

共同点还是那句话：**上限设在过滤之前**。B13 已经在工作台待办里修过三轮，这是第四个落点。

### 10-2 修法：把「范围分支」前移到全局窗口分支之前

```java
} else if (allowed != null) {              // ← 先按范围取行（新增位置）
    rows = inventoryRepository.findByIdDeviceIdIn(allowed);
} else if (lowStockOnly) {                 // ← 只剩全局账号会走到这里
    rows = inventoryRepository.findLowStockLimit(500);
}
```

低库存判据不在这里下结论，仍交给下游的 `SkuStockLevel`（`0 < 可售 ≤ 阈值` 含缺货、只收在架 SKU）——同一端点的两条分支只该差「库存档位」，不该差「判据来源」。

**为什么不用现成的 `findLowStockByDeviceIds`**（台账里当时写的待拍板项）：那条 SQL 与本端点有三处口径差，直接换会静默改变可见行——

| 维度 | `findLowStockByDeviceIds` | 本端点（改后） |
|---|---|---|
| 生命周期 | `INNER JOIN device_info` 且只收 `DEPLOYED`（`normalizedLifecycle`） | 不筛生命周期（与本端点全量台账分支一致：IDLE/INBOUND 柜机照样列） |
| 上限 | 自带 `LIMIT 500`，同样是「过滤前就截断」 | 受范围约束，不在过滤前截断 |
| 数量源 | SQL `effectiveSellableQty` | Java `SkuStockLevel.sellableQty`（同一套批次账本判据，逐柜缓存） |

结论：**取行按范围、判据留一处**。这条判断写进了代码注释，防下一个人「顺手对齐」。

**全局账号刻意保留窗口**（`allowed == null` 才走 `findLowStockLimit`）：那里没有「范围」这一层过滤，窗口只影响列表长度、不会让某个账号的行消失；而它只取低库存行，比「全量 2000 条再内存筛」的召回更准。

### 10-3 门禁：同类的第四个落点，形态却不一样

`check-workbench-scope-pushdown` 原有判据是「两个工作台服务里 `inDeviceScope(` 必须零出现」。这个文件**刻意保留**了 `inDeviceScope` 当二道防线（范围已下推，再复核一次），套上去就是假红。于是给它单独一套判据：

1. **分支顺序**：`findByIdDeviceIdIn(` 必须出现在 `findLowStockLimit(` **之前**——反过来就是本缺陷的字面形态，也是唯一能静态判定「窗口在过滤之前」的锚点；
2. 临期告警必须用带范围的 `findByStatusAndDeviceIdInOrderByCreatedAtDesc(`，无范围的同名方法在该文件零调用；
3. 正向锚点：上面两个带范围的入口必须存在（防「整段删掉」也判绿）。

夹具从 5 项扩到 7 项：⑥ 把 else-if 调回「窗口在前」⇒ 必须命中「分支顺序」那一条；⑦ 把范围分支整段换成 `findLowStockLimit(500)` ⇒ 必须命中「正向锚点缺失」。两条都实测红过，还原后全绿，真实树 md5 前后一致。

### 10-4 验证

| 项 | 结果 |
|---|---|
| 新增单测 | 3 例：范围受限 `lowStockOnly` 不许调全局窗口（且 Mapper 多给的别家行仍被二道防线滤掉）、全局账号仍走低库存专用查询、临期告警走范围下推 |
| 三态负向 | 把 `else if (allowed != null)` 改成 `allowed != null && !lowStockOnly`（一个 token，单变量）⇒ **1 红**（`expected: <1> but was: <0>`，正是新用例）；还原后 **14/14 绿**，源码 md5 与备份逐字节相同 |
| 门禁负向 | `verify-workbench-scope-pushdown` 6 项全过（新增 ⑥⑦） |
| 全量单测 | `mvn -o -pl services/trade-service -am test` ⇒ **Tests run: 3107, Failures: 0, Errors: 0**（上批 3104，+3） |

**未覆盖（如实）**：`lowStockOnly=1` 这条分支没有做真栈点验。它的范围来源与 `inventory` 普通列表是同一个 `allowedDeviceIdsForPack`，§9-6 已在真库上验过该来源的三种形态（全局 / 商户绑定 / 柜机级求交）；本批只改了分支顺序，没有引入新的范围来源，因此没有再重建镜像跑一遍。

## 11. 修复批 6：MA-269 退款同步的「扣款 ↔ 单据推进」同源（2026-11-15 夜间）

### 11-1 缺陷：判据放在写入之前，两个并发投递都能过闸

`syncChannelSideRefund`（微信商户平台手工退款通知）的时序：

| 步 | 动作 | 并发两次投递（同 `refund_id`） |
|---|---|---|
| 1 | `findByIdempotencyKey("channel-refund-sync:"+refundId)` | T1、T2 都**查不到**（流水还没提交）⇒ 双双放行 |
| 2 | `balanceLedgerService.change(...)` | 幂等键在「用户锁 + `user_account` 行锁 + 二次复查」内生效 ⇒ **只扣一次**，T2 是 no-op |
| 3 | `self.finalizeChannelPartialRefund(orderId, refundCents)` | **两次都执行** ⇒ `refunded_cents` 累加两次，订单提前 `REFUNDED` |

结果是「钱只退了一次、账上记了两次」：后续合法退款的可用额度被虚高的已退额吃掉，`PaymentService.prepareChannelPartialRefund` 直接以「可退金额不足」拒绝。MA-088 封的是**顺序**重投（重投时流水已提交，第 1 步就能拦住），本条是并发残余。

### 11-2 修法：让写入方回答「本次到底扣没扣」

```java
var applied = balanceLedgerService.changeTracked(userId, -refundCents, "RECHARGE_REFUND", orderId, idemKey, …);
if (!applied.applied()) {           // 幂等键已存在 ⇒ 本次是重投，什么都没扣
    log.info("wechat refund sync duplicate delivery (ledger already applied) …");
    return;                        // 单据一步都不许推进
}
self.finalizeChannelPartialRefund(orderId, refundCents);
```

`changeTracked` 返回 `AppliedChange{operation, applied}`，`applied` 只能由 `doChangeTracked` 在「真的插入了流水」那一支给出——判定点与写入点在同一事务、同一把锁内，不存在第二个答案。`change(...)` 签名不变，内部委托 `changeTracked(...).operation()`，33 处调用方零改动。

**两条没走的路**（写在这里防下次重走）：

| 方案 | 不选的原因 |
|---|---|
| 用 `recharge_order` 行锁把整段包进一个事务 | `BalanceLedgerService` 的锁序约定是「Redis 用户锁 → 短事务 → 解锁」，事务包住整段会让 DB 连接在等 Redis 锁时被占住；而且 `finalize` 在渠道 HTTP 之后，本来就不该在同一事务里 |
| 新建 `refund_sync_claim(refund_id PK)` 表 + `ON CONFLICT DO NOTHING` | 要用 Flyway 迁移 + 失败清理策略；而**账本流水本身就是永久事实**（幂等键唯一索引），同一件事没必要记两份 |

### 11-3 门禁：把「同源」钉成静态判据

`check-payment-callback-silent-trace` 已经按方法体提取 `syncChannelSideRefund`，直接加一组判据（②b）：

1. 方法体内不得出现 `balanceLedgerService.change(` —— 拿不到「本次是否真扣款」的答案；
2. 必须出现 `balanceLedgerService.changeTracked(`；
3. 必须出现 `.applied()` —— 闸被删掉时前两条仍可能满足（赋值了不用）。

自测夹具 11 → 13 条：新增「丢掉 applied 闸（回到无条件推进）」与「退回 `change(`」两条变异，各自必须命中指定判据；`--self-test` 同时验注释免疫与磁盘 md5 零残留。

### 11-4 验证

| 项 | 结果 |
|---|---|
| 新增单测 | `handleWeChatRefundNotify_concurrentDuplicate_ledgerNotApplied_skipsOrderAdvance`：快速通道未命中 + 账本回 `applied=false` ⇒ 断言 `findByIdForUpdate` 一次都不调、`refunded_cents`/`status` 原样 |
| 三态负向 | 闸改 `if (false)` ⇒ **1 Error**，且恰好是新用例；还原后 md5 与备份逐字节一致，36/36 绿 |
| 门禁 | 正向 OK；`--self-test` 13 条全过（含新增两条变异） |
| 全量单测 | `mvn -o -pl services/trade-service -am test` ⇒ **Tests run: 3108, Failures: 0, Errors: 0**（上批 3107，+1） |

**未覆盖（如实）**：并发窗口没有真栈复现——要同时打两条同 `refund_id` 的微信退款通知进同一个实例，且要让两条都落在第 1 步之前；单测是用 `AppliedChange(applied=false)` 直接构造第 3 步的输入。真栈价值有限：判据在账本一侧，而那侧的幂等（唯一索引 + 行锁）已被既有用例与 `e2e-fund-safety` 覆盖。

**残留边界**：`applied=false` 与「上一次扣款成功、`finalize` 之前进程死掉」在信号上不可区分，那个窗口里的重投不会补推进单据（**与修前相同**——修前第 1 步同样整段跳过）。要彻底闭合得让推进本身可回放（按退款单号落一条推进记录）或由对账反查，已归到 MA-270（退后不回算分账 + 巡检缺判据）那一批。

## 12. 修复批 7：账务四连（MA-270 / 271 / 272 / 273，2026-11-15 傍晚）

上一轮列 open 时把「账真的错了」排在最前，本批一次收掉四条 P2。四条互不依赖，按缺陷分了四个提交。

### 12-1 四条缺陷与修法对照

| 编号 | 缺陷 | 修法 | 提交 |
|---|---|---|---|
| **MA-270** | 退多收只改 `cabinet_order.refunded_cents`，`order_revenue_split.gross_cents` 不跟随 ⇒ 佣金/GMV/月结按虚高毛额算。dev 库实锤 1 笔（paid=586 / refunded=293 / gross=586，**多记 293 分**） | 退多收成功后 `adjustSplitAfterPartialRefund(refreshed, full)` 按净额回算；新增巡检 `REVENUE_SPLIT_REFUND_STALE` | `37b5927a` |
| **MA-271** | 月账单退款按订单 `created_at` 分日 ⇒ 3 月下单 4 月退款的钱记进 3 月（4 月利润虚高 + 历史月被追溯改写） | `selectMerchantBillRefundsByDay` 改 `COALESCE(refunded_at, created_at)` 过滤+分桶 | `6a8a21b2` |
| **MA-272** | 月账单流量费读 `device_data_fee_bill`（月度出账开关默认关闭 ⇒ 恒 0），而日扣真实写 `merchant_fee_ledger` ⇒ 利润虚高 | 全量视图改读台账 DATA 行按 `fee_date` 落月 | `4039f98d` |
| **MA-273** | 场地租金出账只看合同 status，`startDate/endDate` 完全没用 ⇒ 未起租/已到期仍出账（**对外付款**） | 新增 `isContractEffectiveInMonth`；手动 400、批量跳过 | `ebd0a54b` |

### 12-2 三处「不猜口径」的判断

这四条最容易翻车的地方都是「顺手把口径也改了」，本批刻意都停住了：

1. **MA-270 的台账第 2 项判据没单列**。台账原文要求补 `PAID+refunded>0` 判据，但亲验 `OrderPaymentService#refundOrder` 全路径**从不改订单状态**（`grep setStatus` 只见 PENDING 与 payment_operation 流水态），`PARTIAL_REFUNDED` 只由 `SettlementPartialRefundService` / `DisputeService` 显式设置 ⇒ 「退过款却仍 PAID」是常态，单列会全库假阳性且修完永远消不掉。于是并进 `gross ≠ netCents` 一条：**退过款 + 分账毛额没回算**才是可判定、修完即消解的信号。状态机不迁移另登记 **MA-300**（需拍板：牵动 6 条退款调用链）。

2. **MA-272 单设备视图没有按销售额分摊**。`merchant_fee_ledger` 的列是 `merchant_id, fee_date, fee_type, amount_cents…`，**没有 device_id**，而商户账单页支持按柜机筛选。按销售额比例分摊是无业务依据的自创口径，商户拿银行流水一对就露馅。所以全量视图修好、单设备视图维持原数据源（开关关闭时恒 0），缺口登记 **MA-299**（需拍板：加列+回填 vs 明确「费用是商户级、单设备不展示」）。

3. **MA-273 月内部分覆盖不按天折算**。合同 3-15 起租时 3 月仍出全额月费。首月是否折算属于商务约定，UI（「起租 / 到期」）与旧系统都没有折算语义；本条只修「完全没有交集仍出账」，不动金额。**null = 不限** 也照 UI 文案（起租空显示「暂无」、到期空显示「不限」）—— 若把 null 判成「不在租期」，改动当天就会让所有未填日期的历史合同停止付款，比原缺陷严重得多。

另外撞见一处既有不一致并顺手收口：`CabinetConstants.FEE_TYPE_SITE_RENT="SITE_RENT"` / `FEE_TYPE_DATA_FEE="DATA_FEE"` 全仓零引用（死常量），而日结批实际写的是 `"REN"` / `"DATA"`。已把写入侧与读取侧统一到 `MerchantFeeLedger.FEE_TYPE_REN/DATA`，防下一个人拿另一种拼法查出 0 行。

### 12-3 真库实证（MA-270）

容器恢复后，把新判据 SQL 直接在 dev 库跑了一遍：

| 项 | 结果 |
|---|---|
| 命中行 | `1793669053437918137747 \| split=1793669055849902670496 \| expected=293 \| actual=586` —— **与台账「多记 293 分」逐字对上** |
| 对照组（不是靠没数据才绿） | 有退款的非作废分账共 **7 行，6 行自洽、1 行不自洽** |
| 收敛性 | 事务内回算 ⇒ 判据命中 **0**，随后 `ROLLBACK` 验证 |
| 数据修复 | 正式 `UPDATE`：`gross 586→293`、`merchant 586→293`（platform 保持 0，该商户费率 0%），`failure_reason` 追加 `MA-270 按净额回算(2026-11-15)` 留痕；复跑判据 **0 命中** |

> 该修复是**开发库数据修正**，不是代码绕过：代码侧的防复发在 `refundOverchargeIfNeeded` + 巡检判据里。

### 12-4 验证

| 项 | 结果 |
|---|---|
| 单测 | `SiteRentBillServiceTest` 7→11 + 新增 `SiteRentBillContractTermTest` 5 例；`MerchantFinanceServiceTest` 5→7；`DataConsistencyServiceTest` 85→88；`MerchantBillRefundDaySqlIT` 新增 4 例 |
| 全量 | `mvn -o -pl services/trade-service -am test` ⇒ **Tests run: 3122, Failures: 0, Errors: 0**（上批 3108，+14） |
| 三态负向 | MA-273 判据恒 `true` ⇒ **6 红**；MA-271 SQL 退回 `created_at` ⇒ IT **1 红**；MA-272 全量分支退回出账表 ⇒ **1 红**；三次均还原且源码/SQL md5 与备份逐字节一致 |
| 门禁 | 预检第一次跑红 2 项，**都是门禁在正确工作**：新增 `checkRevenueSplitRefundStaleConsistency` 未入 `consistency-rule-registry.json`（`check:consistency-rule-fixtures` 要求新规则必须带正负双夹具）、新增 `MerchantBillRefundDaySqlIT` 未入 `check-docker-tests-guarded` 的 GUARDED 清单（要求写明由 integration/failsafe 守）。已补齐登记（`f47e4165`）：规则 24→25、双夹具齐 25 缺 0/0。两处**负向验证**：删规则登记 ⇒ 报「巡检规则未登记」红；把 GUARDED key 改名 ⇒ 同时报「未登记」+「清单已失效」红；还原后 md5 逐字节一致、正向复跑绿。**第二次预检又抓到自测自身一处缺陷**：`check-consistency-rule-fixtures.test.mjs` 用例 6 用 `seedFixtureOk + 1` 造退化，规则数 24→25 后 `+1` 恰好等于真实 `fixtureOk` ⇒ 退化判据不触发、自测假红。已改为从真实 `evaluate(...).stats.fixtureOk` 取基线，并把 `seedFixtureOk` 同步到 25；负向注入 `seed=26` ⇒ 报「夹具退化」红，还原后绿、自测 6 项全过。**第三次预检由 `check-gate-test-coupling` 拦下**：本批动了 `check-docker-tests-guarded.mjs`（GUARDED 加条目）却没同批动它的负向夹具（S8③）⇒ 补 3 条断言钉住新条目（必须在清单里 / `job=integration` / why 必须点名 `refunded_at` 归日与「历史月不被追溯改写」），自测 14→17 项全过、耦合门禁 OK。第四次预检 ⇒ **✓ 通过**（format / mp type-check / lint / 149 个 audit-gates / migration）。三次拦截说明「新增面必须同批补登记与夹具」的链路是活的，不是纸面规则。台账门禁：18 份报告 / 225 条编号全解析、状态锚点 250 行对账通过、246 活文档锚点全解析；批登记 38 批全对账 |

**意外收获（顺手修掉一个顺序依赖的假绿）**：全量第一次跑出 2 Errors，都在 `ApprovalNodeUpsertTest`（`can not find lambda cache for ApprovalDefinition`）。单独跑同样红 ⇒ 不是我引入的，是它**靠「前面某个 `@SpringBootTest` 先执行过」蒙绿**。我新增两个测试类改变了 surefire 类顺序，才把它照出来。修法：`@BeforeEach` 显式 `TableInfoHelper.initTableInfo(...)`（`d5cb124a`），单独跑 2/2 绿、全量 3122 绿。

### 12-5 未覆盖 / 残留（如实）

- **没有做 HTTP 级真栈点验**：本轮 trade-service 容器仍是旧镜像（未 `build` + `--force-recreate`）。四条改动各自有单测 / SQL IT / dev 库 SQL 实证，但没有走一遍真实接口。
- **MA-271 精度边界**：同一订单多次部分退款时 `refunded_cents` 是累计值、`refunded_at` 只记最后一次，整笔会归到最近一个月（修前是整笔归下单月）。精确到笔需要退款流水表，当前数据模型没有 —— 已写进 XML 注释与台账。
- **MA-270 的 fix 端点不自动修**：回算会冲正商户钱包（动钱），与 `PAID_GROSS_MISMATCH` 同档，只巡检、留人工；`SysDictBootstrap` 已补中文标签「分账未按净额回算」。
- 新登记 **MA-299**（单设备流量费无 device 维度）、**MA-300**（退款不迁移订单状态），两条都**需拍板**，未猜口径实现。
- 过程中 Docker 引擎一度掉线（`wsl.exe` / `sc.exe` 被本机安全策略列入黑名单，无法绕过），用户手动拉起后才完成 §12-3；期间 Testcontainers 用例被 `disabledWithoutDocker` 跳过，恢复后单独复跑 `MerchantBillRefundDaySqlIT` 4/4 绿。

## 13. 修复批 8：门禁轻项三条（MA-282 / 291 / 283，2026-11-15 傍晚）

上一轮建议「门禁轻项一轮清」列了 5 条，本批**修 3 条、明确留 2 条**。留的两条不是忘了，是牵扯 CI 检出与构建顺序，本地改完没有真栈能证，硬推可能把 CI 挂红。

### 13-1 修掉的三条

| 编号 | 缺陷 | 修法 | 提交 |
|---|---|---|---|
| **MA-291** | `check-audit-gates-wiring.mjs` 传递闭包写 `reached.add(name)`（把**引用者自己**又加一遍）⇒ 被 `pnpm check:xxx` 引用的脚本永远进不了 `reached`，规则 4 的「间接接线」判据整体 no-op | 改 `reached.add(m[1])`，并抽成导出纯函数 `extendReachability`，sibling test 用合成脚本图 `root→mid→leaf` 验链真的传递 | `b8dd8b59` |
| **MA-282** | 元判据只认 `--self-test` + `selfTest(` 一种写法，把 `--selftest` + `process.argv.includes('--selftest')` 内联自测块误判成「无夹具」，一批门禁长期挂在 exempt | 认两种参数拼法 × 两种可执行入口（源已剥注释，注释里提到不算）；按实跑结果移出 exempt | `b8dd8b59` |
| **MA-283** | 横幅棘轮 `BASELINE=52` 而 `--print` 实测 **49** ⇒ 白送 3 个名额；且只判 `total > BASELINE`，横幅删到 45 时基线仍挂 52，下一个人又能加回 7 个 | 基线降到 49 + 补**双向判据**（低于基线同样红，逼着同步下调）；抽纯函数、导出基线、加 `import.meta` 守卫 | `86aa27b7` |

**MA-282 的实际盲区比台账记的更大**：改完识别面后门禁报出 **7 个**「豁免陈旧」而非台账的 5 个，多出 `check-env-placeholders.mjs` 与 `check-scheduled-task-trybegin.mjs`（它们用 `--self-test` + argv 内联块，同样被旧的 `selfTest(` 判据漏掉）。逐个 `node scripts/xxx.mjs --self?test` 实跑 7/7 全绿后才移出 exempt ⇒ 夹具覆盖 **80→88/147**、豁免 **67→59/69**（`maxExempt=69` 与 `seedExempt=69` 不动，棘轮只减不增）。

**MA-283 顺手补的一层**：新建 `check-admin-banner-ratchet.test.mjs`（8 项，含「`BASELINE` 必须**恰好等于**实测横幅数」），并按仓库既有范式接进 `check:admin-banner-ratchet` 的 package.json 命令串（`node …test.mjs && node …mjs`）。这一步不能省 —— 新建 `.test.mjs` 若没人调用，`check-audit-gates-wiring` 规则 2 会把它当「没有可执行接线」的门禁报红（本批实跑撞到并修好）。

### 13-2 明确留下的两条（附亲验结论）

| 编号 | 本批查到的事实 | 为什么不在这批改 |
|---|---|---|
| **MA-280** | 本地 4 个 `dist/*/mp-weixin` 目录都在、门禁真判 OK；但 `git ls-files` 显示 **`dist/build/mp-weixin` 零跟踪** ⇒ CI 干净检出没有产物。聚合链在 `ci.yml:750` 跑，mp-weixin 构建在 `:847/:858`（同一 `mini-programs` job 更晚）⇒ CI 上恒 SKIP | 修法必须是「从聚合链摘出 + 构建后加独立 step + `--require-artifacts`」。但那两步构建**条件依赖** `MP_WEIXIN_APPID_*` / `VITE_API_BASE_URL` secrets，缺失时 `exit 0` 跳过 ⇒ 不先确认 secrets 实际配置，改完只能让 CI 红或换个位置继续假绿 |
| **MA-293** | `merge-base` 失败即 SKIP 绿、`mb==head` 即 OK 绿，两处亲验。后者在 CI 里 HEAD 就是 `origin/dev` 的 HEAD ⇒ 恒绿（这才是恒零覆盖的实质）；前者取决于 checkout 是否提供 `origin/dev` 引用（`fetch-depth: 0` 只保证当前分支全历史） | 正解要用 push event 的 `github.event.before` 或显式范围参数，属于 CI 检出语义变更，需要真栈点验 |

两条都在 `ISSUES.md` 里补了「批 8 评估」原文，不是静默跳过。

### 13-3 验证

| 项 | 结果 |
|---|---|
| 门禁 | `check-audit-gates-wiring` OK（197 个 `check-*.mjs` 全接线 / 160 个 `check:*` 中 158 可达 / 夹具 88/147 / 豁免 59/69）；`check-admin-banner-ratchet` OK（49=49）；`check-gate-test-coupling` OK（本批 72 文件 / 7 个判据、夹具同批对账平）；`check-script-table-set` OK；`check-fix-batch-coverage` OK（38 批 + 59 个 fix/perf 提交全登记或豁免） |
| 夹具 | `check-audit-gates-wiring.test` 15→20 项；新建 `check-admin-banner-ratchet.test` 8 项；7 个曾豁免的门禁 `--self?test` 实跑全绿 |
| 三态负向 | ① 闭包退回 `add(name)` ⇒ 断言「传递闭包没把 leaf 加进 reached」红；② 识别面退回只认 `--self-test` ⇒ 真实仓面报 5 个门禁「无夹具」红；③ 删「低于基线」判据 ⇒ 断言 `total < baseline 必须红` 失败；④ `BASELINE` 回 52 ⇒ 「BASELINE 与实测不一致」失败。四次均还原且 md5 逐字节一致 |
| 登记 | 两个 `fix(gates)` 提交按既有惯例进 `unregisteredAllowlist`（门禁基建、零业务代码），带 why；prettier 全绿 |

**本批没再做「四次预检」**：按批 7 的教训，新增夹具 / 改判据 / 加 `.test.mjs` / `fix(...)` 提交四处登记在一轮里同时改完，第一次预检即通过。

## 14. 修复批 9：CI 真栈取证 + MA-280（2026-11-15 傍晚）

上一批把 MA-280 / MA-293 留了下来，理由是「需要真栈确认」。本批把 CI 日志拉下来实证，两条都拿到了决定性证据；**修掉 MA-280，MA-293 仍留（但阻塞点已从「不知道」变成「知道要验什么」）**。

### 14-1 取证方式

`gh run view --log --job` 走流式日志被代理掐断（`stream ID 1; CANCEL`），改走
`gh api repos/<owner>/<repo>/actions/jobs/<jobId>/logs` 直接取文本日志（222966 字节），可检索、可存档。
取的是已完成的 `43698709479`（批 7/8 的 `bdc34d86`）里 `mini-programs` job `132764842835`。

另外 `gh api .../actions/secrets --jq '.secrets[].name'` 确认 `MP_WEIXIN_APPID_CONSUMER` /
`MP_WEIXIN_APPID_MERCHANT` / `MP_API_BASE_URL` 三个 secret 都在（只看名字，不读值）。

### 14-2 MA-280 实锤与修法修正

| 时刻 | 日志 | 结论 |
|---|---|---|
| `09:21:12` | `[check-mp-scoped-hash] SKIP：未找到产物目录（先构建小程序）` | 聚合链（`ci.yml:750`）跑在构建之前 ⇒ **恒零覆盖**，台账成立 |
| `09:23:18` | `> … uni build -p mp-weixin && … && node ../../scripts/check-mp-scoped-hash.mjs dist/build/mp-weixin` | secrets 有值，构建真跑；命令末尾**内嵌**了产物级检查 |
| `09:23:25` / `09:23:37` | consumer / merchant 各输出一次 `OK：…/dist/build/mp-weixin 的 wxml/wxss scoped 哈希全部成对` | **真实产物其实被检查了** |

这改变了修法：不需要「摘链 + 构建后加 step」（那样还得处理 secrets 缺失时的跳过语义），而是让无参数那条**不再假装判过**：

- 无产物且无显式参数 ⇒ 验两个 `build:mp-weixin` 都内嵌了带 `dist/build/mp-weixin` 路径的检查，缺一侧即红；
- 有显式参数却找不到产物 ⇒ 红（构建没产出不得判绿）；
- 有产物 ⇒ 保持原有 wxml/wxss scoped 哈希比对。

新增 `MP_SCOPED_HASH_ROOTS` 让夹具能复刻「CI 干净检出无产物」。新建 `check-mp-scoped-hash.test.mjs`（9 项：接线三态 / 真仓面 / 无产物验接线 / 显式路径负向），接进 `check:mp-scoped-hash` 命令串并移出 exempt ⇒ 夹具覆盖 **89/147**、豁免 **58/69**。

**残留（如实）**：`dist/dev/mp-weixin` 是本地 watcher 产物、不入库，CI 里不存在也不检查；只有本地能查。这是刻意的——把 dev watcher 产物搬进 CI 需要另起构建，超出本条范围。

### 14-3 MA-293 实锤，以及为什么不在这批硬改

`09:20:40` 输出 `[check-admin-artifact-pairing] OK：无待推提交` ⇒ `merge-base(origin/dev, HEAD) == HEAD` 在 CI 里**恒真**（checkout 把 `origin/dev` 指向 HEAD），两处 fail-open 均实锤。

阻塞点：`ci.yml` 同时由 `push` **和** `pull_request` 触发。

- `push` 可以用 `github.event.before..HEAD`（GitHub 提供推送前 SHA）；
- `pull_request` 没有 `github.event.before`，且 PR 检出的是 merge commit，`origin/dev` 引用不保证存在 ⇒ 若按台账字面把「merge-base 失败即 SKIP 绿」直接改成红，会挂掉所有 PR CI；
- 本地预检里 `mb == head` 是**真的**「刚推完、无待推提交」，不能一刀切判红。

正解要按事件类型分别取范围（push 用 `github.event.before`，PR 用 `origin/${base_ref}...HEAD` 或 merge 第一父），并保留本地语义；这轮需要一次 PR CI 点验才能确认。证据与方案已写进 `ISSUES.md` MA-293 的「批 9 真栈实证」段，**不是静默跳过**。

### 14-4 验证

| 项 | 结果 |
|---|---|
| 真栈 | CI 日志三条关键输出逐条核对；secrets 名单核对；`git ls-files dist/build/mp-weixin = 0` 解释「干净检出无产物」 |
| 门禁 | `check-mp-scoped-hash` 本地 4 个产物目录全 OK；`check-audit-gates-wiring` OK（198 个 check 文件全接线 / 夹具 89/147 / 豁免 58/69）；`check-gate-test-coupling` OK（74 文件 / 8 个判据同批夹具齐） |
| 三态负向 | ① 退回无产物 SKIP ⇒ 断言「无产物时没有验接线」红；② 关掉显式路径 fail-closed ⇒ 「显式路径不存在时竟然 exit 0」红。两次均还原且 md5 逐字节一致 |
| 上批 CI | `bdc34d86` **completed/success**（build / admin-artifacts / mini-programs / e2e-h5 / integration 五个 job 全绿） |

## 15. 修复批 10：推送通道 + MA-268 月结期初账期（2026-11-15 晚）

### 15-1 推送通道

批 9 的三个提交用 `https://x-access-token:...` 推送时被本机代理挡了两轮（`CONNECT tunnel failed, response 502` / `Empty reply from server`）。用户指出本仓 `origin` 本来就是 SSH、不需要代理 ⇒ 改 **`git push origin dev`** 一次成功：`9f6bbdea..907ef7a4  dev -> dev`。`git ls-remote origin` 复核远端 HEAD = 本地 `907ef7a4`、未推 0。

> **规则（用户 18:53 指正）**：本仓 `origin` 本来就是 SSH `git@github.com:...`，推送一律 `git push origin dev`，**根本不需要代理**。此前批 7–9 用 `https://x-access-token:$GH_TOKEN@github.com/...` 是绕远路，还把自己绑死在本机代理上；`GH_TOKEN` 只留给 `gh` 查 CI / `git credential fill`。长期记忆铁律 53 已同步改正。

### 15-2 MA-268：期初外部盘点取错账期

| 项 | 内容 |
|---|---|
| 缺陷 | `closeSheet` 里 `prevFlowStocktake = flowStocktakeBySku(prevLabel, wh)`，而同文件 `flowStocktakeBySku` 的 javadoc 明写「本期期初（=上期末盘点）在 `periodLabel`」 |
| 口径 | 外部盘点导入是「N 期末盘点填**下一账期**」⇒ 本期期末在 `nextLabel`，上期期末（本期期初）在 `periodLabel`；`prevLabel` 拿到的是**上期期初**（上上期末）的旧盘点 |
| 触发条件 | 仅上期未关账（`prevFromSheet=false`）、需要外部流水兜底时 ⇒ 症状隐蔽，但期初数整整错一个月 |
| 修法 | 改 `flowStocktakeBySku(periodLabel, wh)`，删掉不再使用的 `prevLabel` 变量（`e1911a38`） |

**新增 `WarehouseMonthlyCloseOpeningLabelTest` 2 例**：

1. 捕获 `flowMapper.selectList` 的 lambda wrapper 参数，钉住第一次外部盘点查询用 `202610` 而非 `202609`；
2. 端到端：本期标签放 42、上期放 99、下期（期末盘点）放 7，断言 `openingQty=42`（修前会取 99）。

踩到两个已记过的坑并当场处理：纯单测里 `Wrappers.<Entity>lambdaQuery().eq(...)` 缺 MyBatis-Plus TableInfo 缓存（同批 7 的 `ApprovalNodeUpsertTest`）⇒ `@BeforeEach` 注册 `ExternalChannelFlow` / `DeviceInfo` / `InventoryWriteOff` / `WarehouseStocktake`；以及 `getParamNameValuePairs()` 要先调一次 `getSqlSegment()` 才会填充（直接读是 `{}`）。另外 `PERIOD_FMT` 是 `yyyyMM`（紧凑 `202610`），与 `closeSheet` 入参的 `yyyy-MM` 不是一回事。

### 15-3 MA-293 的 PR 复核（仍不动）

查了最近一次 PR run `39228287044`（2026-10-19）的 mini-programs job 日志：里面**没有** `check-admin-artifact-pairing` / `check-mp-scoped-hash` 的输出——两条门禁当时还没入链。所以 PR 事件的范围语义**仍无实证**。GitHub checkout 对 PR 会额外 fetch base ref，理论上 `mb != head`、门禁能正常工作，但这是推断不是实测；按「不猜着写」的规矩，本批仍不改 `ci.yml`，等一次含该门禁的 PR run 点验。结论已补进 `ISSUES.md` MA-293。

### 15-4 验证

| 项 | 结果 |
|---|---|
| 全量单测 | `mvn -o -pl services/trade-service -am test` ⇒ **Tests run: 3124, Failures: 0, Errors: 0, Skipped: 0**（上批 3122，+2；Docker 恢复后 IT 也真跑了） |
| 三态负向 | 把 `periodLabel` 改回 `period.minusMonths(1).format(PERIOD_FMT)` ⇒ **2 红**：`MPGENVAL1=202609` 与 `expected:<42> but was:<99>`；还原后源码 md5 与备份逐字节一致、2/2 绿 |
| 台账/登记 | MA-268 置 fixed（e1911a38）；批登记 39 批全对账；MA-293 补批 10 复核结论 |
| CI | 上三批 `bdc34d86` / `9f6bbdea` / `907ef7a4` 均 **completed/success**（含批 9 的 `43704057041`） |

## 16. 修复批 11：MA-176 补货已有批次的原子累加（2026-11-15 晚）

### 16-1 同族残留，配方就在隔壁

`InventoryLotService.doAddRestock` 对**已有批次**仍是老三段：

```java
lot.setQuantity(lot.getQuantity() + command.quantity());  // 内存里算
lot.setStatus(resolveLotStatus(lot));
lotRepository.save(lot);                                  // 全列绝对覆盖
```

`DeviceSkuLot` 没有 `version`，`save` 是全列写。设备锁正常时没问题，但锁失效窗口内，补货拿到的旧快照会把并发的下架 / 报损 / FEFO 扣减**覆盖回去**（账面虚高、已卖的货还能再卖）。P-1 既定标准是「单语句原子 UPDATE 免锁免疫」。

关键是：**同一个文件里 `doRestoreToBatch`（退款/回补路径）早在 R18-2 就改成这个配方了**，并写了长注释说明为什么不能改回 `save`。本条是当时只修了一条、漏掉另一条的同族残留。

### 16-2 修法（`b20435c3`）

| 分支 | 修后 | 理由 |
|---|---|---|
| 已有批次 | `increaseQuantityAtomic(lotId, delta)` + `updateStatusAndSlotById(lotId, status, null)`，两段同事务 | 数量走单语句 `quantity = quantity + ?`；status 是低冲突属性列，用只写 status/slot 的独立 UPDATE，不碰 quantity |
| 新建批次 | `quantity` 随 INSERT 一次写入 | 新建没有「读→算→写」窗口，保持原行为 |

两处刻意的「不改」：`slotId` 传 `null`（SQL 里 `COALESCE(#{slotId}, slot_id)` ⇒ 保持原值，不引入主货道回填这种新行为）；`status` 仍无条件 `resolveLotStatus(lot)`（与修前 `save` 的行为一致，不照抄隔壁只在 DEPLETED/BLOCKED 时重算）。

### 16-3 验证

| 项 | 结果 |
|---|---|
| 新增单测 | `InventoryLotRestockAtomicityTest` 3 例：已有批次必须调原子累加且 `save` 零调用 / 新建批次仍一次 INSERT 且 quantity=5 / 传的是 delta=7 而不是绝对量 17 |
| 相关回归 | 连跑 `InventoryLotConcurrencyTest` / `InventoryLotBatchDeductTest` / `InventoryLotExpiryScanR18Test` + 两个真库 IT（`InventoryConsistencyR18IT` 4 例、`InventoryDeductAtomicityIT` 6 例）⇒ **20/20 绿** |
| 全量 | `mvn -o -pl services/trade-service -am test` ⇒ **Tests run: 3127, Failures: 0, Errors: 0, Skipped: 0**（上批 3124，+3） |
| 三态负向 | 把已有批次分支换回 `setQuantity(get+delta)` + `save` ⇒ **2 红**（`increaseQuantityAtomic` 未调用 / `save` 被意外调用）；还原后源码 md5 与备份逐字节一致 |
| 台账 | MA-176 置 fixed（b20435c3）；批登记 40 批全对账；台账三门禁 OK |

### 16-4 本批取舍

一开始列了四条「改动面小」的 P2（MA-176 / 178 / 224 / 279），实际只做了 MA-176：它是**库存并发正确性**、配方现成、能一次验透（含真库 IT）；而 MA-178 要定押金业务上界、MA-224 要同时改前后端清列口径、MA-279 要动菜单权限映射，各自都需要单独一轮口径确认，不适合塞在同一批里凑数。其余三条仍在 open 台账。

## 17. 修复批 12：四条 P2 一起收（MA-178 / 224 / 279 / 289，2026-11-15 晚）

用户反馈「进度太慢、可以做多点」⇒ 本批改成**一轮四条**：代码集中改、一个 fix 提交、一次全量、一次预检、一次推送。四条互不依赖，各自仍有单测/静态判据与负向验证。

### 17-1 四条对照

| 编号 | 缺陷 | 修法 |
|---|---|---|
| **MA-224** | 更新合同时 `updateById` 跳 null 列 ⇒ 运营清空起租/到期后 DB 仍留旧日期，而 `status` 已按 null 重算 ACTIVE（页面与状态分叉，MA-273 的出账判据跟着错） | 可空列（address/landlord*/startDate/endDate/remark）改用 `update(null, Wrappers.lambdaUpdate().set(…))` 显式写，含 null。**前端 `startDate \|\| null` 本身正确，没改** —— 问题在后端 |
| **MA-178** | 设备押金是预授权第二来源：读侧只有 `Math.min(Integer.MAX_VALUE, deposit)`、写侧仅钳负 ⇒ 可写荒谬押金并冻结消费者余额 | 新增 `CommissionParamBounds.requirePreauthCents`（与全局 `checkout.preauth_cents` **共用** `PREAUTH_CENTS_CEILING`=¥1万，不另立第二套上界）；写侧超界 400，读侧超界的存量行 warn 后回落配置默认（与全局键超限同款处置） |
| **MA-289** | `runWithOrderSplitLock` 把 `IllegalStateException` 一律包成 500 ⇒ 分账不存在 / 记录已变更 / 仅 LEDGER_ONLY 可确认完结这些可预期的状态竞争，在界面变成「服务器错误」 | 在 `ResponseStatusException` 透传之后、`Exception` 包 500 之前加 `IllegalStateException ⇒ CONFLICT(409)` |
| **MA-279** | `DashboardView` 的 `LOW_STOCK` 判据（`actionTargetPath`，`canAccessPath` 用）指 `/replenishment`，实际 `goAction` 跳 `/stock-health` ⇒ 有目标页权限的人看不到按钮、有判据页权限的人点进去被拦 | 判据改 `/stock-health`（`REPLENISHMENT` 保持原路径）；并在 `check-approval-action-path-nav` 增**规则 E**：逐 case 比对两个 switch 的路径 |

### 17-2 静态判据（规则 E）

前端改动不能只靠肉眼，所以抽了 `scripts/lib/dashboard-action-path.mjs`（`casePaths` / `mismatchedActionPaths`，处理「连续 case 共享同一段」），主门禁 import 后逐 case 比对；新建 sibling 夹具 8 项（真仓零不一致 / 合成旧路径必红 / 连续 case 解析 / 锚点失效必红），接进 `check:approval-action-path-nav` 命令串并移出 exempt ⇒ 夹具覆盖 **90/147**、豁免 **57/69**。

### 17-3 验证

| 项 | 结果 |
|---|---|
| 单测 | `CommissionParamBoundsTest` +1、`ConsumerPreauthServiceTest` 新建 3 例、`SiteContractServiceTest` +1、`RevenueSplitServiceTest` +1；相关 57 例全绿 |
| 全量 | `mvn -o -pl services/trade-service -am test` ⇒ **Tests run: 3133, Failures: 0, Errors: 0, Skipped: 0**（上批 3127，+6） |
| 四处三态负向 | ① LOW_STOCK 判据改回 `/replenishment` ⇒ 门禁报「判据=/replenishment 跳转=/stock-health」红；② 删清列 wrapper ⇒ 合同用例红；③ 读侧退回 `Math.min(Integer.MAX_VALUE,…)` ⇒ 超界用例红；④ 删 ISE catch ⇒ 409 断言红。四处还原后 4 份源码 md5 与备份逐字节一致、正向全绿 |
| 门禁 | `check-approval-action-path-nav` OK（菜单登记 78 项）、`check-audit-gates-wiring` OK（199 个 check 文件全接线 / 夹具 90/147 / 豁免 57/69）、`check-gate-test-coupling` OK、台账四门禁 OK、批登记 41 批全对账 |

**如实边界**：MA-178 的写侧调用点（`OpsDeviceAdminService` → `requirePreauthCents`）没有单独写一条 Mockito 用例，防线是纯函数 4 例单测 + 调用点代码审查；`check:commission-param-bounds` 门禁的既有扫描面未针对该调用点扩展。这一侧比读侧薄，下批可补。

### 17-4 取舍

剩余 open 里 P2 还有 MA-260（XXL 逃生门，需拍板兜底策略）、MA-274/275/276（并发与事务，需专门并发用例）、MA-278（开关串联，需拍板）、MA-281（超时同源）等，都不是「顺手一行」能安全改的；本批挑的四条都是口径明确、配方现成、能一次验透的。剩余 open 降到 **23 条**。

## 18. 修复批 13：三条 P2 + 两条 P3（MA-281 / 275 / 276 / 201 / 200，2026-11-15 夜）

延续批 12 的批量打法：五条互不依赖，分两个 fix 提交（`fea2c0e7` 门禁+迁移、`e6cfefe9` 服务端）、一次全量、一次预检、一次推送。

### 18-1 对照表

| 编号 | 缺陷 | 修法 |
|---|---|---|
| **MA-281** | 将邑 watchdog 70s 与 trade 会话过期 60s 的时序契约只活在注释里，任一侧调参都可能让 watchdog 先于 trade 处置生效（兜账只救 CANCELLED ⇒ 弱网真开门被误杀成 FAILED） | common-core 新增 `SessionTimeouts`（同源常量 + 两个装配期断言）；watchdog 构造器与 `SessionExpireProperties` 各自 fail-fast；`defaults()` 兜底 90→60（10s 时代遗留） |
| **MA-275** | 订单态通知读-判-写无锁 ⇒ 同单并发通知（DONE vs 迟到 EXPIRED）都能过快照判据，后写者把 DONE 覆盖回 EXPIRED 并误记欠款 | 乐观 CAS（快照态为现值才落库），抢失败重读现值重放判据再试 ≤3 次，仍丢则放弃投递交渠道重投 |
| **MA-276** | `recordSplit`「事务包住锁」：锁在提交前释放 ⇒ 失效窗口横跨解锁→提交；PG 里 DKE 后同事务重查必 25P02 | 「锁包住事务」：recordSplit 去注解持锁，self 代理进 `doRecordSplitInTx`（REQUIRES_NEW）；DKE 重查走 `findSplitAfterRace`（REQUIRES_NEW） |
| **MA-201** | feature-flags R2 消费者判定对裸源码 includes，注释里提到键即算「有消费者」⇒ 死开关可被注释掩护 | `maskCommentsOnly`（只剥注释、留字符串字面量）+ `findConsumerlessFlags` 抽 lib；夹具 +5 |
| **MA-200** | `ops:recognition-demo:view` 豁免空转（页面已删、全仓零引用）+ R8 拍板项漂移出两份台账 | 两门禁 ALLOWLIST 删除；V482 置 RETIRED；拍板回填 PENDING_DECISIONS_R23 第 ⑬ 条 |

### 18-2 三处设计判断

1. **MA-275 丢投递优先于倒退状态**：CAS 三次抢不到就放弃本次投递——渠道会重投，重投会拿着新的现值重新走判据；而「无条件落库」会把已收款的 DONE 倒退成 EXPIRED 并记欠款。单向「只进不退」对 DONE 侧保留自愈（迟到 DONE 仍能 CAS 回 DONE 并入账，`creditIfAbsent` 幂等）。
2. **MA-276 用 REQUIRES_NEW 而不是把整段塞进调用方事务**：支付分 DONE 回调/结算完结都自带事务，若内层 REQUIRED 会 join 外层 ⇒ 提交点又落回外层、锁照样提前释放。REQUIRES_NEW 的代价（外层回滚时分账行留存）由 `creditIfAbsent` 幂等键 + REVENUE_SPLIT_* 巡检兜底，已在 javadoc 如实写明。
3. **MA-281 的「15s 仍可配」旧对照用例与新契约正面冲突**：它验证的正是本条要消灭的行为。改写成「低于地板装配期拒绝 + 高于地板仍可配」，可配性保留在契约之内。

### 18-3 验证

| 项 | 结果 |
|---|---|
| 单测 | `SessionTimeoutsContractTest` 新建 6 例；`SessionWatchdogTest` 4→5；`PayScoreOrderNotifyTest` 9→12（+ 顺序依赖假绿修复）；`RevenueSplitServiceTest` 31→32（+注解形态钉）；`feature-flags.test` +5 |
| jiangyi-gateway | `mvn -o -pl services/jiangyi-gateway -am test -Dtest=SessionWatchdogTest` ⇒ 5/5 绿 |
| 全量 | `mvn -o -pl services/trade-service -am test` ⇒ **Tests run: 3141, Failures: 0, Errors: 0**（上批 3133，+8） |
| 三处负向（各单变量） | ① CAS 退回无条件落库 ⇒ **3 红**（恰好是 3 个新并发用例）；② recordSplit 复挂 @Transactional ⇒ 形态钉 **1 红**；③ 地板断言 no-op ⇒ **1 红**。还原后 4 份源码 md5 逐字节一致 |
| 门禁 | feature-flags OK（119 开关 / 读取点 117）；menu-permissions OK（豁免 0）；frontend-perm-codes OK（豁免 0）；迁移 number-order / safety / immutable / seed-separation OK；台账三门禁 OK；批登记 43 批全对账 |

**如实边界**：MA-275 的 CAS 只覆盖「同单两条通知并发」这一形态，`selectById` 未命中时回落无条件落库（与修前一致）——生产上该行刚被读到、不可能真消失，回落只为夹具兼容；MA-276 的 REQUIRES_NEW 改变了「与调用方事务原子」这一隐含契约，边界见 §18-2 第 2 条。

## 19. 修复批 13/14：五条 P2+P3（§18-§19 同日）与终局清扫（2026-11-15 夜）

### 19-1 批 13（§18，e6cfefe9 / fea2c0e7 / 419f77aa，未推送随批 14 一并推）

MA-281（超时契约同源+装配期断言）/ MA-275（订单态乐观 CAS）/ MA-276（锁包住事务）三条 P2；MA-201（feature-flags 判据剥注释）/ MA-200（识别演示豁免退役+V482）两条 P3。详见 §18。

### 19-2 批 14：剩余 18 条一次性收口（dac8776f / 56c9072d / 48bb2cb6）

用户拍板「一次性都修了」⇒ 剩余全部 open（3 P2 + 15 P3）在**一轮**内集中处理，含全部需拍板项——拍板口径逐一回填 `PENDING_DECISIONS_R23` 第 ⑬⑭ 条：

| 编号 | 修法（摘要） | 提交 |
|---|---|---|
| MA-260 | **证据关闭**：两资金 key 已有 26h 停摆告警链（KEYS+MAX_SILENCE+Monitor→Dispatcher），拍板②已有实现；@Scheduled 双保险落选 | 台账 |
| MA-274 | `LockHeartbeat`（租约/3 心跳续租）消除「锁过期双跑」半；HTTP 移出事务的残留如实记录 | dac8776f |
| MA-278 | 拆开关：租金键默认 true、流量费键保持 false | dac8776f |
| MA-284 | 退款同步兜底告警（RECHARGE_REFUND 流水合计>refunded_cents ⇒ HIGH，挂既有清扫腿） | dac8776f |
| MA-286 | 月租余数按日序分摊（全月恰等月额） | dac8776f |
| MA-287 | 结算单归月改按订单 created_at（与月账单同源） | dac8776f |
| MA-288 | edge-results 仅 accepted 才注销 CLOSE watchdog | dac8776f |
| MA-294 | 全局 preauth=0 判据前移（0 压倒设备押金） | dac8776f |
| MA-179 | 元单位资金键写侧校验（≤2 位小数 + 天花板，不进整数守卫族） | dac8776f |
| MA-297 | 4 个运营写端点补 admin_audit_log | dac8776f |
| MA-300 | 拍板不迁移状态 + 勿顺手补注释 | dac8776f |
| MA-177 | V483 唯一键（dev 实测 0 重复） | dac8776f |
| MA-225 | 两面板轮询代际守卫（MA-173 admin 同族） | 56c9072d |
| MA-299 | 单设备流量费显示「商户级」（拍板②，拒绝自创分摊口径） | 56c9072d |
| MA-293 | CI push 注入 before SHA 走权威直连范围（解两处 fail-open）；PR 语义留点验 | 48bb2cb6 |
| MA-292 | 新门禁 check-system-config-fund-ceilings（天花板逐键钉死） | 48bb2cb6 |
| MA-261/290 | 文档数字实测回填（44 @Scheduled / 38 XXL / 476 迁移 / V483） | 台账 |
| MA-201/200 | 批 13 已修（§18） | fea2c0e7 |

### 19-3 验证

| 项 | 结果 |
|---|---|
| 定向测试 | LockHeartbeatTest 2 / OpsFeeBillJobTest 3（新）；SchedulerTest（423）/ RebuildTest（6，补订单桩+lambda 缓存）/ ConsumerPreauthServiceTest（+全局 0 压倒）/ OpsExceptionJiangyiRetryGuardTest / OrderPayment 五件套 / PayScoreOrderNotifyTest ⇒ 全绿 |
| 负向 | MA-293 夹具 7 项含「before 引用缺失必红」；MA-292 夹具 5 项含台账原缺陷形态（999999999L）；批 13 三态负向见 §18 |
| 编译 | `mvn -o -q -pl services/trade-service,services/jiangyi-gateway -am test-compile` ⇒ 绿（两模块） |
| 全量 | **本地不跑全量，交给 CI**（用户要求提速；批 12/13 全量基线 3133+14=3147 预期，CI integration job 为准） |
| 台账 | 4 门禁 OK（18 份报告/225 条、45 批对账、250 行状态锚点、246 活文档）；剩余 open **0 条** |

**如实边界**：MA-274 的渠道 HTTP 仍在 DB 事务内（心跳修的是锁半边，事务外重构留专项）；MA-293 的 PR 事件语义维持旧状待真 run；全量单测数字以推送后 CI 为准。


## 20. 专项批 17：B3 退款流水表 + B4 流量费按柜机归集 + B1 Phase B 设计稿（2026-11-16）

用户拍板三项专项全开工。B3/B4 当日落地（含真栈验证），B1 Phase B 出设计稿待评审后实施。

### 20-1 B3：refund_flow 退款流水表（95089a90，V485）

| 要点 | 内容 |
|---|---|
| 数据模型 | 逐笔（订单/商户/原收款渠道/金额/refund_method/refundNo 唯一幂等/refunded_at 分日键）；订单 refunded_cents 保持聚合=资金真值 |
| 存量回填 | BACKFILL_AGGREGATE（每单一笔聚合流水，refunded_at=COALESCE 同款）——归月精度与切换前**完全等价**，不比现状差 |
| 月账单切换 | selectMerchantBillRefundsByDay 切流水分桶；c1/c2 原收款渠道口径、c3 订单数口径、FAILED 排除逐字不变 |
| 一致性 | 新判据 REFUND_FLOW_VS_ORDER_MISMATCH（流水 vs 聚合，仅巡检） |
| 验证 | RefundFlowServiceTest 4 + IT 4 绿；dev 真栈：回填 10 笔、巡检 0 命中 |

### 20-2 B4：流量费按柜机归集（770a7b8b，V486/487/488）

| 要点 | 内容 |
|---|---|
| 维度恢复 | DATA 逐台落行——源头 `device_info.data_fee_cents` 本就分/天逐台计收，**零分摊口径决策**；RENT 保持商户级（站点合同维度另有 site_rent_bill 链路） |
| 约束迁移 | 真库验证抓两个缺口：旧唯一约束是独立 INDEX 形态（DROP CONSTRAINT no-op）⇒ V488 补 DROP；新约束 (merchant,fee_date,fee_type,device_id) |
| 幂等兼容 | 切换日同 (商户,日,类型) 存在任意行即整体跳过——防「旧聚合行+新逐台行」双收据 |
| 展示 | DTO 加 dataFeeBasis（DEVICE/MERCHANT/NONE，record+手写 interface+gen:api-types 三件套）；bills.vue 按 basis 展示金额/「商户级」/0 |
| 验证 | scheduler 断言加 deviceId；IT 夹具适配 V483 粒度（同批拆两货道=slot 未启用的过时模型）；dev 真栈：单设备 777/DEVICE、全量 2011/MERCHANT |

### 20-3 B1 Phase B 设计稿（b84706db，docs/B1_PHASE_B_DESIGN.md，待评审）

补缴链路（collect/collectByUser @Transactional → markPaid → chargeOrder HTTP）拆「prepare(tx)→HTTP(无事务)→finalize(tx)」三段。状态收敛矩阵、幂等键矩阵（全复用既有：CHARGE 幂等键+痕迹 REQUIRES_NEW）、券补偿为唯一新逻辑、外层去事务化隔离性代价、实施 7 步、待评审 Q1-Q4（占券幂等性/强取消拦截/清扫阈值/recordSplit join 复核）。**评审通过后才实施**——动支付主链不裸奔。

### 20-4 如实边界

- B3 回填是聚合口径：历史月多次部分退款仍整笔归月（与切换前一致）；逐笔精度只对新退款生效。
- B4 历史聚合行不拆分（历史日设备集合与费率无快照）；单设备视图历史月显示「商户级」。
- integration job 的 read-only INSERT 报错（AdminAuditLog/ScheduledTask）为 IT 环境后台任务杂音，唯一失败测试（V483 键撞 IT 夹具）已修，2/2 绿。

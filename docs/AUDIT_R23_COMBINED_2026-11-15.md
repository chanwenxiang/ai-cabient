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

**未覆盖（如实）**：真栈点验未做——本批改的是「按范围收口」而非资金路径，且单测已能构造 `null/空集/单元素/越权` 四种输入；需要真栈才能验证的只剩「跨商户实际不可见」，那要有第二个商户的柜机与一个域受限账号，dev 库现有数据（MCH-DEFAULT/MCH-OTHER）具备条件，留到下一批与 MA-298 一起点验。

**新登记**：MA-298（P3）——`MerchantInventoryPortalService` 的 `lowStockOnly` 分支仍是「全局 500 窗口 + 内存过滤」，修它要连带拍板低库存口径（`findLowStockByDeviceIds` 多收 `'DEPLOYED'` 且自带 LIMIT 500，直接换会改行为）。

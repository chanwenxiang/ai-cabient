# 上线前审计 · 待拍板简报（DC-26 / DC-27 / DC-29）

> 2026-10-16 由新会话按「不采信文档自报」纪律，在 **本地 dev @ 1fc7cb4b**（3 ahead / 14 behind origin/dev）上逐条重验后撰写。
> 台账 `docs/AUDIT_LEDGER_2026-10-13.md` 的 DC 表是 10-13/10-16 口径，本文只补「今天在盘上重验到的事实」，两者冲突以本文为准并回写台账。

## 0. 先纠一处编号

上一轮收尾清单写的「AU-070/071 vision-service 退役」**在台账里不存在这两个编号**（`git grep -E "AU-07[01]"` 在 `docs/audit-findings-2026-10-16` 全 ref 零命中；台账 AU 号从 AU-064 直接跳 AU-074）。
vision-service 退役真正的条目是 **AU-134 / DC-27**（对应 `docs/JIANGYI_ONLY_FINAL_VERDICT_2026-10-11.md` 的批 2/3/6）。事实本身成立，只是别按错误编号去找。

## 1. DC-26 · V62 内嵌 `SKU-TEA-5839`（东方树叶茉莉花茶335ml）

**今天重验到的事实**

| 项 | 实测 |
|----|------|
| 种子位置 | [V62__oriental_leaf_tea_aliyun_mapping.sql:3](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/resources/db/migration/V62__oriental_leaf_tea_aliyun_mapping.sql#L3) 插 SKU 本体，`:9` 插阿里云映射 `5839` |
| V344 清理守卫是否覆盖 | **否** —— `grep -c TEA-5839 V344*.sql` = 0（V344 锚点逐条对上 V2/V25/V54/V56，V62 这条是漏网） |
| 后果 | 全新生产库重建后，它会作为**唯一内嵌商品**长出来 |
| 下一空迁移号 | **V372**（V371 已在盘上，属 CB-050 商户设备控制权限） |

**要你回答的只有一句话**：试点柜里到底有没有真实在售「东方树叶茉莉花茶335ml」？

- **(a) 有 → 保留**：不动数据，只登记口径「演示型内嵌商品种子到此为止」；防复发已由 `check-flyway-seed-separation`（演示锚点不得进新 V 写语句）覆盖，无需新增门禁。
- **(b) 没有 → 补清理迁移 V372**：必须照 V344 的写法带 `seed_env=none` 守卫 + fail-loud 断言，且要先清点引用（订单/货道绑定/价格行），否则真机联调库上会撞外键。

**建议**：这条我判断不了业务事实，只能问。若你不确定，先按 (a) 保留 + 在 V344 注释里点名它是「有意豁免」，比在没取证前删生产数据安全。

## 2. DC-27 · 重力/云端识别残留（AU-134）

**今天重验到的事实**（全部在本地 dev 盘上，非转述）

| 残留面 | 实测位置 |
|--------|----------|
| 重力兜底结算分支 | [SettlementRecognitionService.java:267](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/java/com/aicabinet/trade/service/SettlementRecognitionService.java#L267) `allowsGravityEvidenceSettle` / `:272` `tryStagingGravitySettle`，调用点 :133/:162/:189/:202/:221/:310 |
| 识别模式值域 | `SystemConfigService:204` `settlement.recognition_mode` —— **值域无校验**，运营可直写 `VISION_GRAVITY` |
| vision-service 整目录 | 仓库根 `vision-service/`，`git ls-files` = 51 个跟踪文件 / 盘上 468K |
| 编排与环境 | `docker-up.ps1:67` 仍列 `vision-service`；`infra/.env.example` 仍留 `VISION_API_KEY` / `RECOGNIZER_BACKEND=mock` / `VISION_FORCE_REAL=false` |

**⚠️ 新查到的落地前置**：`FUND_GUARDED_KEYS`（AU-048 的资金键写侧守卫）**只在 origin/dev 上，本地 dev 还没有**。所以选项 (a) 里「把该键纳入 FUND_GUARDED_KEYS」这一步必须等 #1 rebase 之后做，否则和 AU-048 的 hunk 撞车。

**风险定性（沿用台账，未变）**：不是生产活资损面（`ensureJiangyiOnlyShopping(mock=false)` 禁非端侧识别柜机购物、`SessionDoorService:83-85` 让这类柜停在 RECOGNIZING 不内联结算）。风险在**误导面/测试面**——dev/staging 把重力兜底配活之后，「测过」不等于「端侧识别链测过」。本仓已有一次实证：F1 前一轮曾用 `VISION_GRAVITY` + `gravityDeltasJson` 凑出结算绿，属造绿，驱动脚本里已删并留痕。

> 📌 用词更正（2026-10-16 用户指出）：**柜机是我们自己的资产**（商户资产、平台全托管），将邑只提供套件与协议适配中间件。台账原文里的「将邑柜」是简称，别读成「柜子是将邑的」；本仓口径统一说「端侧识别柜机 / 装了将邑套件的柜机」。

**选项**

- **(a) 立即收口（小改）**：`settlement.recognition_mode` 加值域白名单（只允许 `JIANGYI` 侧口径）+ 纳入 `FUND_GUARDED_KEYS`，残留代码分批删。成本≈1 个类 + 1 组单测；**前置=先完成 #1 rebase**。
- **(b) 按拍板整体删（大改）**：批 2（重力兜底 + 同步识别分支）+ 批 3（vision-service 整目录 + compose/env/告警）+ 批 6（`videoUri`/`videoClipsJson` 旧字段透传）。必须同步删钉住旧路径的用例与入口（铁律 62），改动面横跨 trade / device-service / infra / 仓库根目录。

**建议**：上线前做 (a)，(b) 排二期。理由是 (a) 恰好堵住「配活残留路径造绿」这个已实证的坑，而 (b) 的删除面会撞上 peer 正在热编辑的 dev，收益不抵返工风险。

## 3. DC-29 / DEV-04 · V346–V368 新增资金域是否补 B-1 扩展审

**今天重验到的事实**：V346–V371 共 26 个迁移在盘上，其中资金域 9 条：
V347 外部渠道对账导入 / V355 商户费用台账 / V356 机器账单 / V357 线长佣金基数 / V359 补货员钱包 / V360 押金月度自动退还 / V361 佣金随退款冲正 / V364 渠道退款重试 / V365 撤机押金结算。
（V369–V371 属 CB-049/050 场景限额与商户设备控制权限，非资金域。）

**🔴 新发现，直接推翻一个选项的前提**：V365 里 `install_fee_clawback_enabled BOOLEAN NOT NULL DEFAULT TRUE`、`install_fee_deduct_cents DEFAULT 10000`（100 元）。
⇒ 撤机安装费扣回/佣金冲回是**开箱即活**的资损路径。台账 DC-29 给的退路「若不在上线范围，须确认默认关闭且迁移不激活资损路径」**当前不成立**，要选这条得先补关闭开关。

**要你回答**：这 9 条能力里，哪些是上线范围（试点就要用）？

- **在范围 → 补 B-1 扩展审**：面积大于原 Track B-1；按本审计纪律要**负向测试先行**，不接受 dev 侧 `93bf2697` 自报的「上线前审计 🔴 修复（6 项阻断）」——那是内联自评，无负向测试留痕。
- **不在范围 → 先关再上**：把 `install_fee_clawback_enabled` 之类默认 TRUE 的资损开关改成默认关（新迁移 V372+），并逐条确认迁移不激活扣款/退款路径。

**建议**：先按「9 条里只保留试点真用到的」缩小范围，其余改默认关。这样上线面只剩已审过的链，二期再逐域补审。

## 4. 本轮已顺手闭环的（不需要你定）

- **#5 空 worktree 目录**：`.qoder/worktrees/audit-pre-launch` 已 `rmdir`。注册信息其实早没了（`git worktree list` 只剩主树、`.git/worktrees` 空），只剩空壳。
- **#6 前半 AU-077**：[scripts/verify-production-readiness.ps1:233](file:///D:/ai-generated%20code/ai-cabinet/scripts/verify-production-readiness.ps1#L233) 路径里的 BEL(0x07) 已改回 `\auth`。实测**旧值不是返回 false，而是让 `Test-Path` 抛 `ArgumentException`**（路径含非法字符），P1-6b 一致性检查静默不执行。修复后：生产 CSV 存在 ⇒ `Test-Path` True 护栏真跑；本 checkout（该 CSV 已 gitignore）⇒ False 正确跳过。本地提交 `ce5fdd6e`，**未推送**。
- **#1 预验**：`git merge-tree --write-tree origin/dev dev` 退出 0 ⇒ peer 提交浪结束后，`git pull --rebase` 预期零冲突。脏树（329 处、15 分钟内 229 个文件被写）上 rebase 跑不起来，也不会由我代推。

**仍未闭环**：#4 C-admin 运行期实测（F1 后半 + F2–F8，驱动器还只在 `origin/worktree-audit-pre-launch`）、#6 后半共享 Postgres 的 F1 写入痕迹清点（等点头）。

## 5. 🔴 追加：V369 同号雷 + 「未跟踪但已上库」的迁移（2026-10-16 晚，比三项 DC 都急）

| 树 | V369 | 状态 |
|----|------|------|
| origin/dev | `V369__jiangyi_device_identifier_nullable.sql`（AU-133，已提交已推） | tracked |
| 本地 dev | `V369__device_scene_type.sql`（CB-049）+ V370/V371 | **untracked，且已应用到共享 dev 库** |

- `git merge-tree` 干净是**文本层面**的：两个不同文件名的同号迁移不冲突，但 Flyway 起服务会 `Found more than one migration with version 369` ⇒ trade 启动失败。门禁 [check-migration-safety.mjs:105](file:///D:/ai-generated%20code/ai-cabinet/scripts/check-migration-safety.mjs#L105) 会在 preflight 判红（`duplicate Flyway version V369`）。这是 DEV-01 记过的 V346 撞车二次武装。
- `flyway_schema_history` 实测：本地库已跑到 **V371（370 行）**，即 peer 的 CB-049/050 三条迁移**跑过但没提交**——git 里没有，库里已有。
- 处置（**由持有该 WIP 的会话在提交/rebase 时做**，我不代推、不改别人未跟踪文件）：本地三连 **V369/370/371 → V372/373/374**（必须整组平移保序，V370 的 `sku_scene_limit` 语义上跟在 V369 的 scene_type 之后）；随后 DC-26 清理迁移取 **V375**，DC-29 默认值改关取 **V376**。
- **为什么现在不能「直接全新库重建」**：重建的判据是「git 里那套迁移能从零跑起来」，而 V369/370/371 还不在 git 里 ⇒ 此刻重建只会造出一个**假绿**（跑的是磁盘快照，不是交付物），同时把 peer 正在用来测 WIP 的库清掉。正确时点 = 三条提交入库 → rebase → 改号 → 再重建，且这一步本来就是台账里「fresh 全新库重建实测」那条欠账的兑现。

## 6. 追加：DC-29 九条能力对照（老系统 / 竞品台账 / 是否开箱即活）

「开箱即活」= 无人主动开启，钱或义务就会自己动。三条关键判定（V364 `@Scheduled(fixedRate=60_000)`、V355 每日批扣且无 UI、V357 无消费方）已在本盘复核。

| 能力 | 开箱即活 | 老系统 easygo | 竞品/台账 | 直接建议 |
|---|---|---|---|---|
| V347 渠道对账导入 | 否（人工上传、纯记录） | ⚠️**本行三处描述已在 2026-10-17 复核更正，见下方「V347 行勘误」** | 同行是 API 自动下载，「人工导入」形态无条目；CB-020 结论3 已进 backlog；三通道 `isReady()` 恒 false 拉不到流水 | 留但不审（不出钱）；形态本身补 CB 条目 → ✅ 已补 **CB-055** |
| V355 商户费用台账 | 🔴**是**：每日 00:20 `chargeDailyPeriodFees` 真扣商户余额，**全库无 UI** | 部分（流量费每日真扣，无台账/欠费判定） | 友宝按台月收部署费+交易费从分成扣（CB-041/CB-020） | 补 B-1 扩展审；同时要么加 UI 要么给批扣加开关 |
| V356 机器账单 | 否（要先配点位才出账，线下结清） | **有**，自动出账+自动代付 | CB-034 采纳「人工标记收款」、CB-043 单收款人；DIFF 记「线下标记 payout 是用户拍板，非复刻老代付」 | 留（纯记录） |
| V357 线长佣金基数 | ⚪**死列**：domain 无字段，代码读 V358 的 `line_route_member` | 有（公式对齐，仅直营机、归属人=配置人） | **无条目**；DIFF 判「老基数=已扣退款净额，新口径违背」待修 | 按 V346 死表先例判**清理**，不占审计 |
| V359 补货员钱包 | 🔴**入账是**（随 `line-commission` 每日 00:20 自动发佣入账）；出账人工、仅记账 | 半有（有账户无冻结位、流水无 balance_after） | **无同行条目**；P3 评估早写「泛型账本原语暂缓，出现第三钱包域再评估」——V359 正是第三域 | 补 B-1 扩展审 + 先补 CB 检索（铁律 36） |
| V360 押金月度自动退还 | 需人工带参开（UI 不暴露），一开即自动跑、产生可提现余额 | 代码有但**未接线**，线上实为人工点「退押金」 | 台账**自相矛盾**：CB-034 结论1「不采纳按月自动返还」→ CB-044 记 10-14 拍板反转 | 种子行默认改 FALSE（V376）+ 把矛盾回写台账 |
| V361 佣金随退款冲正 | 🔴**是**（复用 line-commission 锁，钱包可扣至负） | **无**（老佣金永不回冲，本次新造） | 跨行业压倒性「退款必冲+负数待扣+90天」（有赞/Amazon/AliExpress/TikTok），但 **3 家开门柜 peer 均查不到**；CB-040 自标「自己的设计判断」 | 保留（方向对）+ 补 B-1 扩展审；同行证据缺口入台账 |
| V364 渠道退款重试 | 🔴**是**：`@Scheduled(fixedRate=60_000)`，**真向渠道重发退款** | 相邻不同物（老只有提现重试；消费者退款无重试无痕迹） | **无条目**（CB-003 催缴扣款重试、CB-033 提现重试，都不是退款） | 需你定闸门语义（见 §7 唯一待问项） |
| V365 撤机押金结算+安装费扣回 | 需人工点 settle，但 `install_fee_clawback_enabled DEFAULT TRUE`；「倒扣商户余额」分支前置依赖 V360 跑完 ⇒ V360 关则永不触发 | **死代码**（两处调用点全被注释） | 唯一支持主体是旧 easygo，**未达 ≥3 主体门禁**；同行撤机=违约金/损失抵偿，无「安装费」名目 | 默认 TRUE 改 FALSE（V376）；能力本体待补 CB 条目 |

**V347 行勘误（2026-10-17，写 CB-055 时逐文件复核发现）**：上表把三件不同的事挤进了一行，原文口径有三处错，按现行事实重记：
1. **V347 不是资金对账**。`V347__external_channel_import.sql` 建的是**外部渠道进销存数据**导入（大黄鹅/嗨便利/健怡/民盛无人店/乐摇摇/客情用水：`external_import_template`/`external_product`/`external_product_mapping`/`external_import_batch`/`external_channel_flow`，`COMPETITOR_REF: CB-016,CB-017`），月结时只出「仓库外调/客情用水」两列，**不出钱也不对渠道流水**。
2. **「三通道 `isReady()` 恒 false」讲的是出款侧**，与 V347 无关：`PayoutReconciliationService.java:30-45` 头注写的是**提现打款**没有渠道账单可拉，故只做「提现单⇄钱包流水⇄手续费流水」本地自洽。收入侧对账**早就走 API**：`WeChatPlatformBillProvider.fetchDailyBill:41-58` 真调 `/v3/bill/tradebill` 拿 `download_url` 再下载 CSV，未配凭据直接抛异常（不允许空账单伪装成 MISMATCH），`RECON_MOCK_ENABLED` 被 `ProductionStartupValidator:140` 挡在生产 profile 外。⇒ 「同行是 API 自动下载、我们还是人工」这句对本仓**不成立**，人工的只有 V347 那批渠道进销存文件。
3. **「老系统全库零对账代码」过头**。业务侧确实**没有任何已实现的对账逻辑**，但不等于零提及：`LingNanTongServiceImpl.java:42` 留着一条被注释的意图（「需要从 FTP 下载对账文件对账完之后才能设置」，TODO 未做），`DeliverOrder.java:125`「订单对账结算月份」与 `EgoOptPositionBillPayeeMonthlyServiceImpl.java:134`「采购与对账的账单以实际填写点位名称为准」是**月结账期标签**不是流水核对；仓库 vendored 的支付宝 SDK 里有 `AlipayDataDataserviceBillDownloadurlQueryModel`，业务代码零调用。

**执行清单（不分一期二期，按此顺序做）**：
1. ~~DC-27(a) 后端值域闸门~~ ✅ 已落（见 §7）；
2. ~~V376：`deposit-monthly-refund` 与 `refund-retry` 种子行 `enabled=FALSE`、`install_fee_clawback_enabled` 默认改 FALSE~~ ✅ 已写并验证（见 §8；仍是「已提交未跑」，等 peer 改号）；
3. ~~DC-26：V375 清 `SKU-TEA-5839`（`sku_catalog` + `aliyun_category_mapping` 两行），照 V344 的 `${seed_env}='none'` 守卫 + fail-loud 断言~~ ✅ 已写并在 dev 库事务内实测（见 §8；实删三行：映射 + 下架评审 + 本体）；
4. B-1 扩展审：V355 / V359 / V361（负向测试先行，不采信 `93bf2697` 内联自评）；
5. ~~V357 死列清理~~ ✅ 已落 V377（见 §9）；
6. 台账欠账：V347/V357/V359/V364/V365 补 CB 条目、CB-034↔CB-044 矛盾回写 —— ✅ **已落（2026-10-17）**：
   - V364 → **CB-053**（微信商户/合作伙伴 FAQ + 申请退款 API、易宝退款/结束退款/极速退款三份 .md、支付宝 `alipay.trade.refund`（🟡 SPA 快照）、哆啦宝退款接口；结论=采纳「先查单 + 同号重试」、有意不同=30 分钟耗尽转人工）；
   - V359 → **CB-054**（旧系统 `CommissionAccount` + Nayax 官网「员工权限/交易对账」+ 微信商家转账 + 易宝极速退款到卡；柜机同行「员工钱包/自动发佣」公开规格**零命中**；结论=入账自动/出账人工、`balance_after` 必须有、打款能力实际未通）；
   - V347 → **CB-055**（微信服务商对账指引 + 全球站账单接口、支付宝「手工对账 vs 接口对账」、易宝对账单下载、Nayax 平台内建对账、老系统 FTP TODO）；本表 V347 行的三处错误描述已就地勘误；
   - V365 → 已由 **CB-044** 承载，2026-10-17 补记写清「安装费扣回只有旧系统单一主体」「与 CB-034 的矛盾回写」「V376 默认关 ⇒ 倒扣分支默认永不触发」；CB-034 结论 1 同步加了口径往返说明；
   - V357（线长佣金基数）：死列已由 V377 清理，**不再需要新条目**；DIFF 文档 §L29/§L67 判的「新基数违背老口径」对线长佣金这条**已过时**——`LineCommissionJob:181-182` 实测走 `CommissionBaseCalc.netGmv`（`total−refunded`、含 `PARTIAL_REFUNDED`、全额退款与未支付不计，审计 T2 2026-10-15）。DIFF §L51 另两提（**分账侧 / GMV 统计侧**是否同用净额）本轮**未复核**，`RevenueSplitService` 内 grep 无 refunded 关键字（它按单结算，退款侧处理在别处），列为待办，不在本轮下结论。

## 7. 本轮已落 / 唯一待问

**已落**：DC-27(a) 后端闸门 —— [SystemConfigService.java](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/java/com/aicabinet/trade/service/SystemConfigService.java) 唯一写入口 `doUpsert` 加 `assertConfigValueAllowed`，`settlement.recognition_mode` 只收 `VISION`（大小写/首尾空格放行，其余 400 且**零残留**）。新测 `SystemConfigRecognitionModeTest` 6 例 + SystemConfig 全家族 36 例全绿。

**同一闸门的两块前置阻塞已在 2026-10-17 解除并落地 ⇒ DC-27(a) 完整闭环**：
- 前端 [SystemConfigView.vue:342](file:///D:/ai-generated%20code/ai-cabinet/clients/admin-vue/src/views/system/SystemConfigView.vue#L342)
  的 `VISION_GRAVITY` 选项**已删**（下拉只剩 `VISION`）。此前不能改是因为改 .vue 必须随 admin 产物
  重建一起提交，否则 CI `admin-artifacts` 字节比对必红；本轮与产物同批提交。
- 「强制留痕」没有把该键塞进 `FUND_GUARDED_KEYS`——那族同时附带「值必须是非负整数」的写侧校验
  （`validateFundKeyValue`），枚举串塞进去会把合法的 `VISION` 判成 400。改为并列的
  `ALWAYS_AUDITED_KEYS`，`recordChangeIfEnabled` 走 `isForcedAuditKey()`：审计开关被运营显式
  写成 `false` 时，改这个键**仍然**落 `system_config_history` + `admin_audit_log`。
  负向实证：把判据退回只认资金键族 ⇒ 恰好 `recognitionMode_forcedAuditEvenWhenAuditSwitchIsOff`
  变红（对照组 `ordinaryKey_staysSilentWhenAuditSwitchIsOff` 保持绿，证明不是恒真断言）；
  `SystemConfigRecognitionModeTest` 8 例 + `SystemConfigAuditHistoryTest` 9 例 + `SystemConfigFundKeyGuardTest` 8 例全绿。
- 仍未做的只剩 **DC-27(b) 整体删除**（批 2 重力兜底 + 批 3 `vision-service/` 整目录 + 批 6 旧视频字段透传），
  属排期决策，见 §12 第 1 项。

**唯一待问（资金语义，我不替你定）**：V364 退款重试的环境闸门要哪种？
- **(i) 种子行默认 FALSE**（运营台手动启）：生产上线必须记得开，忘了=退款失败无人闭环；
- **(ii) 代码级 `@Value("${trade.refund.retry.enabled:false}")`**：非生产默认不跑，生产靠 env 打开，与 (i) 等价但多一道「env 没配就不跑」的保险；
- **(iii) 判据换成「渠道凭据非沙盒才跑」**：最贴风险本身，但要先确认 dev 现在配的商户凭据是不是真的（若是真的，(iii) 等于没关）。

## 8. 拍板落地（2026-10-16 晚）：V375 + V376 = 选项 (i) 的「靠运营台手动开」

**你选的 (i) 有真实落点**（先取证再写）：`ScheduledTaskService#tryBegin` 在
`row != null && !TRUE.equals(row.getEnabled())` 时 `recordSkip("task disabled")` 返回 false ⇒
调度入口当场退出；运营台 `PUT /api/v2/ops/admin/scheduled-tasks/{taskKey}/enabled`
（[ScheduledTaskController.java:89](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/java/com/aicabinet/trade/api/ScheduledTaskController.java#L89) + `ScheduledTaskView.vue`）可一键启停。
⇒ **关的是数据行，不用加代码开关**（(ii) 的代码级 flag 因此没必要）。

- [V376__fund_jobs_and_clawback_default_off.sql](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/resources/db/migration/V376__fund_jobs_and_clawback_default_off.sql)：`refund-retry` + `deposit-monthly-refund` 种子行 `enabled=FALSE`（V152 起 `DEFAULT TRUE`，V364/V360 的 INSERT 没写 enabled ⇒ 只能追加 UPDATE，铁律 50 不改已应用迁移）；`install_fee_clawback_enabled` 列默认改 FALSE + 存量行翻转；末尾**零残留断言**（没关上就 fail 迁移）。**全环境生效**（不加 `${seed_env}` 守卫）：dev 的渠道凭据可能是真的，「测试环境就该自动跑」不成立。
- [V375__production_seed_sku_tea5839_guard.sql](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/resources/db/migration/V375__production_seed_sku_tea5839_guard.sql)：DC-26 按「没在售」处置（你的口径=全测试环境未上真机）。照 V344 的 `${seed_env}='none'` 生产守卫，但**不做级联清理**——先全库普查 27 个 `sku_id/assigned_sku_id` 列，任何真实引用 ⇒ `RAISE EXCEPTION` 交人工；无引用才删 映射 + 下架评审 + 本体三行。
- 代码侧配套：[DeviceRentalService.java:167](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/java/com/aicabinet/trade/service/DeviceRentalService.java#L167) `registerDeposit` 与 `:232` `settle` 的「空值=开」改成「空值=关」。只改列默认是**假修复**：`installFeeClawbackEnabled == null || …` 会让运营台（表单没暴露该参数，实证 `DeviceRentalView.vue` 零命中）写入的行全部默认开。
- 钉住旧行为的两个用例按铁律 62 同步改（`defaultToLegacy` → 改名并断言 `isFalse`；`settle_..._clawsBackInstallFee` 显式 `setInstallFeeClawbackEnabled(true)`），并新增两例 `registerDeposit_clawbackNull_notEnabledByDefault` / `settle_clawbackNull_treatedAsDisabled`。
- 运营台欠的一块：押金登记表单没有三件套参数位 ⇒ 「逐柜手动开追回」目前只能走登记接口传 `installFeeClawbackEnabled=true`。**没顺手加 UI**（属 CB-044 参数面补全，未点单）。

**验证（负向验证按铁律 4 走完三段：绿 → 注入红 → 还原绿）**

| 项 | 结果 |
|---|---|
| V375 生产路径 | dev 库事务内试跑（占位符手填 `none`）⇒ NOTICE `零引用普查列数=27`、三表 in-tx 计数 0、`ROLLBACK` 后夹具仍在 ⇒ 无副作用 |
| V375 能红 | 首版普查未白名单 `sku_delist_review` ⇒ 当场 `RAISE EXCEPTION`（dev 里 2026-10-07 选品诊断生成的 1 行）——**断言真的会拦**，随后按 V344 口径把它列入「目录附属同删」 |
| V376 路径 | 事务内 `UPDATE 2 / ALTER TABLE / UPDATE 1 / DO` 全过，三处状态 0/0/false |
| V376 能红 | 单独跑断言块 + 重新启用一条任务 ⇒ `仍有 1 条…enabled=TRUE` |
| 单测 | `DeviceRentalServiceTest` 21 例全绿；把 `clawback` 改回旧表达式 ⇒ **恰好 `settle_clawbackNull_treatedAsDisabled` 变红**（证明不是恒真断言），还原后 21 例复绿 |
| 迁移门禁 | `check-migration-safety` / `check-flyway-seed-separation` 对 V375/V376 **零判红**（余下 6 红全在 peer 未跟踪的 V369/V370/V371 上，非我方线，按铁律 61 不接盘） |

🔴 **事故与恢复（如实记录）**：做 V376「能红」第二段时，psql 命令**漏了 `BEGIN;…ROLLBACK;` 包裹**，
把 V376 正文真的应用到了共享 dev 库（两条任务被停、押金行翻转、列默认变 false）。
已用一条事务恢复原状并实测：`enabled tasks=2 / clawback true=1 / col default=true / flyway_schema_history max=371`（**没写历史行** ⇒ V376 仍会由 Flyway 正常应用）。
教训：**任何对共享库的写测试，模板必须是 `BEGIN;` + 正文 + 断言 + `ROLLBACK;` + `-v ON_ERROR_STOP=1`**；负向注入要放在同一个包裹里，不能另起一条无包裹会话（铁律 55 家族）。

**编号前置仍未解**：V375/V376 排在 peer 的 CB-049/050 三连之后（他们把 untracked 的 V369/370/371 改号为 V372/373/374 才成立）。
`check-migration-safety` 现在不报同号（它只扫磁盘目录，磁盘上还没有 origin/dev 那条 V369）⇒ **真正的撞车在 rebase 之后才会显形**，
所以这两个文件是「已提交但未跑」状态：迁移号若被 peer 的改号占用，改号方向是**我方后移**（V375→V377…），因为 peer 的三条已进共享库、我方两条还没跑。

## 9. V377 · DC-29 执行清单第 5 项：V357 死列清理

[V377__drop_dead_line_manager_commission_columns.sql](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/resources/db/migration/V377__drop_dead_line_manager_commission_columns.sql) `DROP COLUMN line_manager.commission_basis / commission_per_unit_cents`。

死列取证（不是「看着没人用」而是逐条对照）：

| 检查 | 结果 |
|---|---|
| domain | `LineManager.java` 只有 `commissionRateBps` / `commissionFixedCents`，无对应字段 |
| 全仓 grep 两个列名 | 命中**全部属于别的表**：V358 `line_route_member`（真正在用的双基数，`LineRouteMember.java` 有字段 + `LineRoutesView.vue` 有编辑面）、V361 `staff_commission_daily` |
| raw SQL / mapper XML / 脚本按这两个列名读 line_manager | 零命中 |
| 成因 | V357 自己注释写明「双基数 2026-10-14 二次修正后落到 V358，本迁移保留兼容列」⇒ 兼容面为零，按 V346 死表先例清理 |

- 不带 `IF EXISTS`：列由 V357 无条件添加，缺失即库状态异常 ⇒ 宁可迁移当场红。
- 门禁：DROP COLUMN 必带 `MIGRATION_REVIEWED: yes` + `LOCK_RISK/NOTES/TABLES`（[check-migration-safety.mjs](file:///D:/ai-generated%20code/ai-cabinet/scripts/check-migration-safety.mjs) 的硬要求），已配齐；`line_manager` 非热表。
- 实测：dev 库事务内 `BEGIN;`+正文 ⇒ `cols left=0`，`ROLLBACK;` 后仍是 2 列（共享库无副作用）。
- **Java 侧无需改动**（零引用实证），所以本项不牵动任何测试；V357 本体按铁律 50 不动。


## 10. dev 库 flyway history 越序残留 · 定性为「观感」，不单独修（2026-10-16 收尾）

改号后 `flyway_schema_history` 里 `installed_rank` 与 `version` 不再单调。全 378 行实测仅 **3 处越序**：

| installed_rank | version | 上一行 version |
|---|---|---|
| 331 | 329 | 333 |
| 371 | 370 | 372 |
| 376 | 369 | 377 |

**判「观感」而非「故障」的三条实证**
1. rank 331 那处**早于本次改号**（V329/V333 属历史越序）⇒ 越序不是这次引入的新病，dev 一直容忍。
2. 全仓 grep `installed_rank` / `flyway_schema_history`：唯一消费方是 [AdminDataManageService.java:66](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/java/com/aicabinet/trade/service/AdminDataManageService.java#L66) 的**禁改表黑名单**，没有任何代码按 rank 顺序判读版本。
3. trade-service 20:57 启动实测：`Successfully validated 375 migrations` → 应用 V378 → `Started TradeServiceApplication`，零 validate 异常 ⇒ rank 乱序不影响 Flyway 判定（它按 version+checksum 匹配）。

**为什么不「顺手排好」**：`installed_rank` 由 Flyway 在插入时取 max+1，手工重排等于第三次改写共享 dev 库的迁移史，且下次 boot 插新行仍会撞号；更关键的是这会让「dev 库到底跑过什么」这条取证链不可信 —— 本审计纪律的前提就是 history 行是原始证据。

**真正的消法** = 台账已欠的那笔「fresh 全新库重建实测」：空库按 369→370→371→372→…→378 顺序重跑，既验证「git 里那套迁移能从零跑起来」（本轮已静态确认 V370 不依赖 V372 的列，无反向依赖），又顺带产出一份单调干净的 history。重建须在**隔离 compose 项目/独立端口**做（铁律 69：`ai-cabinet-postgres-1` 是跨会话共享库）。

## 11. fresh 全新库重建实测（台账欠账已兑现，2026-10-16 晚）

**做法**：隔离 docker 网络 `aicab-fresh-net` + 两套空 `postgres:16-alpine`（与 compose 同镜像、同 16.15）+ 现网镜像 `ai-cabinet/trade-service:local`（jar 内 `classpath:db/migration` 即 HEAD 交付物）。**不发布主机端口、不接 `ai-cabinet_default`** ⇒ 物理上碰不到共享 dev 库；跑完即整套拆除（`docker ps -a` 零残留，共享栈未动）。

**两轮对照（同一套迁移，只换 `SEED_ENV`）**

| 断言 | `SEED_ENV=none`（生产语义） | `SEED_ENV=local`（dev/uat 语义） |
|---|---|---|
| 空库跑到哪 | `Successfully applied 374 migrations, now at version v378`，零 Flyway 报错 | 同 |
| 版本号唯一 | 除 2 行 R__（version NULL，正常）外无重号 | 同 |
| `installed_rank` 越序 | **0 处** ⇒ §10 的乱序纯属 dev 手改痕迹，重建即消，不必再去动 history | 0 处 |
| `sku_catalog` 总数 | **0**（生产空库无任何内嵌商品） | 8（演示夹具按守卫长出） |
| `SKU-TEA-5839` | 目录 + `aliyun_category_mapping` 均 0 行 ⇒ **V375 生产守卫真清** | 各 1 行 ⇒ **V375 正确跳过** |
| `refund-retry` / `deposit-monthly-refund` | 均 `enabled=false` | 均 false（V376 **不带** seed_env 守卫，有意：dev 渠道凭据可能是真的） |
| `install_fee_clawback_enabled` 默认 | `false` | `false` |
| `line_manager` 两枚死列 | 0 | 0 |

**结论**：「git 里那套迁移能从零起来」这条判据由实跑取得，非文档自报；DC-26（V375）与 DC-29（V376/V377）在两种 seed_env 下均拿到正向+负向实证。附带确认一个上线口径：**生产空库 `sku_catalog=0`**，商品全靠运营/商户登记，没有内嵌在售商品。

## 12. 待拍板清单（2026-10-17 汇总，DC-26/27/29 之后的真实剩余面）

> 本表只列**我不能替你定**的项。每条的「今天重验到的事实」都是本盘实测（grep/ls/迁移号），
> 不是转述；能自行推进的（DC-27(a) 收尾、台账欠账补条目）已直接做完并从本表剔除。

| # | 项 | 要你回答的那一句话 | 今天重验到的事实 | 我的建议 |
|---|----|--------------------|------------------|----------|
| 1 | **DC-27(b) 识别残留整体删除** | 排二期（上线后）还是上线前动工？ | 批 2 仍在：`SettlementRecognitionService` 的 `tryStagingGravitySettle` 三处调用（:133/:162/:189）+ `allowsGravityEvidenceSettle`（:202）；批 3 仍在：`git ls-files vision-service` = **51 个跟踪文件**、[docker-up.ps1:67](file:///D:/ai-generated%20code/ai-cabinet/docker-up.ps1#L67) 的 `$appServices` 仍列 `vision-service`、`infra/.env.example` 仍留 `VISION_API_KEY`(:21)/`RECOGNIZER_BACKEND=mock`(:151)；批 6 仍在：`videoUri`/`videoClipsJson` 在 common-core 多个 DTO 里透传 | 上线后二期。(a) 的两道闸（值域 + 强制留痕）已把「配活造绿」这条路封死，剩余是体积问题不是资损问题；删除面横跨 trade/device/infra/仓库根，且要按铁律 62 同步删钉住旧路径的用例 |
| 2 | **B-1 扩展审剩余三条 🟡** | F-5 / F-6 / F-8 修不修？（F-1/F-2/F-3/F-4/F-7 已落） | F-5 扣费失败时日结通知仍显示 `fee 0.00 / net=收入全额`（[AUDIT_B1_EXTENDED_FUND_2026-10-16.md:77](file:///D:/ai-generated%20code/ai-cabinet/docs/AUDIT_B1_EXTENDED_FUND_2026-10-16.md#L77)）；F-6 逐设备整数除法分摊不闭合、无月末清算行；F-8 线长侧冲回取**实时** `bps/fixed`，费率上调后的窗内退款会按新费率多冲、员工侧多扣（代码注释自标「已接受的近似」） | F-5 值得修（纯展示口径，改文案不动资金）；F-6/F-8 是**口径选择**不是 bug：要不要「月末清算行」和「计提时冻结费率」都得改账务算法，建议二期与 DC-27(b) 一起排 |
| 3 | **dev 自报 6 项里两条本轮未审** | 🔴3 押金月退超退、🔴4 三任务七处同步要不要补审？ | 复核表原文标「本轮未审」；🔴3 的能力已被 V376 关到默认关（`deposit-monthly-refund enabled=false`），🔴4 属调度注册面（`check-scheduled-task-seed` 只校种子齐，不校七处同步） | 🔴3 可判「不审了——默认关，开的人负责」；🔴4 建议补审，它是 `tryBegin` 那类「注册了但没接线」的静默失效面 |
| 4 | **CB-044 押金登记 UI 参数位** | 「逐柜开安装费扣回」要不要给运营界面？ | 押金登记表单没有三件套参数位（`DeviceRentalView.vue` 零命中 `installFeeClawbackEnabled`），V376 把默认改成关之后，**目前唯一开法是直接调登记接口传 true** | 要么点单加三个输入位（小改，属参数面补全），要么明确「这条路径只给技术运维用」并写进上线手册；现在是「能力存在但运营看不见」 |
| 5 | **Track C-admin 运行期实测** | 现在跑还是上线前跑？ | 驱动器 `clients/admin-vue/tests/audit-admin-flows-uat.mjs` 仍**只在** `origin/worktree-audit-pre-launch`，dev 上没有；F1 后半 + F2–F8 无运行留痕 | 先取回并登记进 `check-audit-gates-wiring`（不登记=跑无痕，等于没测），跑不跑你点头 |
| 6 | **共享 Postgres 的 F1 写入痕迹** | 要不要我清？ | `ai-cabinet-postgres-1`(:15433) 是跨会话共享库，F1 那轮写入的将邑登记/会话痕迹未清点；本仓纪律是「动共享库前先问」 | 若 peer 还在用同库测 WIP，建议不清（清点=写操作），改成「上线前换新库」自然带走 |
| 7 | **平台分账日后要不要真收** | 恢复抽成的前置实证由谁发起、什么时候？ | 本轮按你拍板把 `platform_rate_bps` 归 0（V382），且 0 抽成**不发微信分账**——因为 rate=0 时 `receiver.amount` 就是订单毛额，而 CB-032 记的「服务商默认单订单分账上限 30%」只有 ⚪ 旁证、提额真实性未确认 | 收之前必须先与微信侧书面确认比例上限/提额，或改走持牌分账（CB-032 结论③）；否则线上一开就是批量分账失败 |
| 8 | **上线口径确认：V376 关掉的两条任务** | 试点上线当天要不要开？ | `refund-retry`、`deposit-monthly-refund` 种子行 `enabled=false` 且在两种 `seed_env` 下都实测为 false（§11）。退款重试关=退款失败无人闭环；月退关=押金永不自动退（回到人工，对齐老系统形态） | 押金月退**保持关**（这是拍板方向）；`refund-retry` 要不要开必须写进上线手册的勾选项，别靠记忆 |
| 9 | **第三钱包域要不要收敛 + 冻结位要不要启用**（2026-10-17 由 CB-054 新增） | 商户/线长/补货员三套钱包要不要抽泛型账本原语？补货员钱包的 `frozen_cents` 要不要用起来？ | P3 当初写「出现第三钱包域再评估」，V359 正是第三域；实测三域差异在**权限与出账通道**（线长走 `LineWithdrawPayoutService` 但三出款通道 `isReady()` 恒 false、补货员只能运营台标记出账），记账原语本身已高度同形（`creditIfAbsent`/`payoutIfAbsent`/`debitIfAbsentAllowNegative` 三份雷同）；`frozen_cents` 在补货员钱包只于建账写 0（`StaffWalletService:55`），三条写路径都不再改它 ⇒ **恒 0 的死列**，而线长钱包真在用（`LineWalletService:272,:319`） | 两问都不在本轮资金修复范围内：抽原语=架构级重构（要动三域全部幂等 ref 与测试），启用冻结=新能力（涉及「佣金待结算」语义，等同改变发佣可提时点）。建议：冻结位二选一——要么明确「补货员不设冻结」并在 V3xx 迁移删列（不留死列，铁律「不留假旋钮」），要么给出发佣 T+N 冻结的具体业务规则后再接；抽原语留二期 |

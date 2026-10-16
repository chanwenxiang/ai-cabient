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
| V347 渠道对账导入 | 否（人工上传、纯记录） | **无**（全库零对账代码） | 同行是 API 自动下载，「人工导入」形态无条目；CB-020 结论3 已进 backlog；三通道 `isReady()` 恒 false 拉不到流水 | 留但不审（不出钱）；形态本身补 CB 条目 |
| V355 商户费用台账 | 🔴**是**：每日 00:20 `chargeDailyPeriodFees` 真扣商户余额，**全库无 UI** | 部分（流量费每日真扣，无台账/欠费判定） | 友宝按台月收部署费+交易费从分成扣（CB-041/CB-020） | 补 B-1 扩展审；同时要么加 UI 要么给批扣加开关 |
| V356 机器账单 | 否（要先配点位才出账，线下结清） | **有**，自动出账+自动代付 | CB-034 采纳「人工标记收款」、CB-043 单收款人；DIFF 记「线下标记 payout 是用户拍板，非复刻老代付」 | 留（纯记录） |
| V357 线长佣金基数 | ⚪**死列**：domain 无字段，代码读 V358 的 `line_route_member` | 有（公式对齐，仅直营机、归属人=配置人） | **无条目**；DIFF 判「老基数=已扣退款净额，新口径违背」待修 | 按 V346 死表先例判**清理**，不占审计 |
| V359 补货员钱包 | 🔴**入账是**（随 `line-commission` 每日 00:20 自动发佣入账）；出账人工、仅记账 | 半有（有账户无冻结位、流水无 balance_after） | **无同行条目**；P3 评估早写「泛型账本原语暂缓，出现第三钱包域再评估」——V359 正是第三域 | 补 B-1 扩展审 + 先补 CB 检索（铁律 36） |
| V360 押金月度自动退还 | 需人工带参开（UI 不暴露），一开即自动跑、产生可提现余额 | 代码有但**未接线**，线上实为人工点「退押金」 | 台账**自相矛盾**：CB-034 结论1「不采纳按月自动返还」→ CB-044 记 10-14 拍板反转 | 种子行默认改 FALSE（V376）+ 把矛盾回写台账 |
| V361 佣金随退款冲正 | 🔴**是**（复用 line-commission 锁，钱包可扣至负） | **无**（老佣金永不回冲，本次新造） | 跨行业压倒性「退款必冲+负数待扣+90天」（有赞/Amazon/AliExpress/TikTok），但 **3 家开门柜 peer 均查不到**；CB-040 自标「自己的设计判断」 | 保留（方向对）+ 补 B-1 扩展审；同行证据缺口入台账 |
| V364 渠道退款重试 | 🔴**是**：`@Scheduled(fixedRate=60_000)`，**真向渠道重发退款** | 相邻不同物（老只有提现重试；消费者退款无重试无痕迹） | **无条目**（CB-003 催缴扣款重试、CB-033 提现重试，都不是退款） | 需你定闸门语义（见 §7 唯一待问项） |
| V365 撤机押金结算+安装费扣回 | 需人工点 settle，但 `install_fee_clawback_enabled DEFAULT TRUE`；「倒扣商户余额」分支前置依赖 V360 跑完 ⇒ V360 关则永不触发 | **死代码**（两处调用点全被注释） | 唯一支持主体是旧 easygo，**未达 ≥3 主体门禁**；同行撤机=违约金/损失抵偿，无「安装费」名目 | 默认 TRUE 改 FALSE（V376）；能力本体待补 CB 条目 |

**执行清单（不分一期二期，按此顺序做）**：
1. ~~DC-27(a) 后端值域闸门~~ ✅ 已落（见 §7）；
2. V376：`deposit-monthly-refund` 与 `refund-retry` 种子行 `enabled=FALSE`、`install_fee_clawback_enabled` 默认改 FALSE —— **等改号后**；
3. DC-26：V375 清 `SKU-TEA-5839`（`sku_catalog` + `aliyun_category_mapping` 两行），照 V344 的 `${seed_env}='none'` 守卫 + fail-loud 断言 —— **等改号后**；
4. B-1 扩展审：V355 / V359 / V361（负向测试先行，不采信 `93bf2697` 内联自评）；
5. V357 死列清理；
6. 台账欠账：V347/V357/V359/V364/V365 补 CB 条目、CB-034↔CB-044 矛盾回写。

## 7. 本轮已落 / 唯一待问

**已落**：DC-27(a) 后端闸门 —— [SystemConfigService.java](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/java/com/aicabinet/trade/service/SystemConfigService.java) 唯一写入口 `doUpsert` 加 `assertConfigValueAllowed`，`settlement.recognition_mode` 只收 `VISION`（大小写/首尾空格放行，其余 400 且**零残留**）。新测 `SystemConfigRecognitionModeTest` 6 例 + SystemConfig 全家族 36 例全绿。

**同一闸门的两块未落，都不是「忘了」而是被前置卡住**：
- 前端 `SystemConfigView.vue:345` 下拉里那个 `VISION_GRAVITY` 选项还在 —— 改 .vue 必须随 admin 产物重建一起提交，否则 CI 产物保真 job 红；peer 正在热编辑同树，不抢。
- 「纳入 `FUND_GUARDED_KEYS`」该常量只在 origin/dev，**rebase 之后**才能加。

**唯一待问（资金语义，我不替你定）**：V364 退款重试的环境闸门要哪种？
- **(i) 种子行默认 FALSE**（运营台手动启）：生产上线必须记得开，忘了=退款失败无人闭环；
- **(ii) 代码级 `@Value("${trade.refund.retry.enabled:false}")`**：非生产默认不跑，生产靠 env 打开，与 (i) 等价但多一道「env 没配就不跑」的保险；
- **(iii) 判据换成「渠道凭据非沙盒才跑」**：最贴风险本身，但要先确认 dev 现在配的商户凭据是不是真的（若是真的，(iii) 等于没关）。


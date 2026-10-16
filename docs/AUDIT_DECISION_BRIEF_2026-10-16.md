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

**风险定性（沿用台账，未变）**：不是生产活资损面（`ensureJiangyiOnlyShopping(mock=false)` 禁非将邑柜购物、`SessionDoorService:83-85` 让将邑柜停在 RECOGNIZING 不内联结算）。风险在**误导面/测试面**——dev/staging 把重力兜底配活之后，「测过」不等于「将邑链测过」。本仓已有一次实证：F1 前一轮曾用 `VISION_GRAVITY` + `gravityDeltasJson` 凑出结算绿，属造绿，驱动脚本里已删并留痕。

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

**仍未闭环**：#4 C-admin 运行期实测（F1 后半 + F2–F8，驱动器还只在 `origin/worktree-audit-pre-launch`）、#6 后半共享 Postgres 的 F1 写入痕迹清点（等点头）、DC-26/27/29 三项拍板。

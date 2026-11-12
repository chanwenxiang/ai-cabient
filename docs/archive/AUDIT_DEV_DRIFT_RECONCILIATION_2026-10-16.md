# 上线前审计 · 与 dev 主干的漂移对账（2026-10-16）

> 目的：回答"dev 又走了 82 个提交，之前的审计结论要不要重做"。
> 结论：**不重做**，但必须做一次机械对账 + 处置 9 条 dev 侧问题（其中 3 条会让合并直接炸或让 P1 复活）。

## 0. 基线与口径

| 项 | 值 |
| --- | --- |
| 审计分支 | `worktree-audit-pre-launch` @ `6cf61cca` |
| 审计代码基线（merge-base） | `3d627ec1`（2026-10-13，dev 当时 tip） |
| dev 现状 | `d397b410`，领先基线 **82 提交 / 752 文件 / +26353 −11825** |
| 审计分支领先 dev | 26 提交（含 15 条 AU 修复 + 台账/报告 + UAT 驱动） |
| 对账方法 | 脚本 `.tmp/reverify-citations.mjs`：抽取 15 份 `docs/AUDIT_*.md` 全部 `类:行号` 引用 → 取 HEAD 该行文本 → 在 dev 版本中定位同一文本 |
| 复验命令 | `node .tmp/reverify-citations.mjs`（见 §6） |

**判定口径**：行号移动 ≠ 结论失效。只有"锚点文本消失"或"所处代码链被 dev 删除/重写"才需要重判。

## 1. 结论分层对账结果（机械可复现）

| 状态 | 条数 | 处置 |
| --- | --- | --- |
| 原位仍成立（行号内容一致） | **92** | 无需动作 |
| 行号漂移，内容原样还在 | **61** | 只改坐标，结论不动（体力活） |
| 锚点文本在 dev 上找不回 | **8** | 逐条重判（见 §3） |
| 引用文件被 dev 删除 | **0** | 无 |
| 符号未能解析（多为 `Class.method:行` / 非 java 文件写法） | 47 | 抽样人工过一遍 |

⇒ **可解析的 165 条 file:line 里 153 条（93%）在 dev@`d397b410` 上仍指到同一句话**。B 轨道（B-1~B-9）的缺陷定性、Track A 存量复扫、AU-132 那类"证伪"结论、产品口径类判断均不随行号移动而翻案。

内容性抽验（非行号级）——AU-134 残留清点在 dev + 当前工作树**仍全部成立**：

| AU-134 残留项 | 复验方式 | dev/工作树 |
| --- | --- | --- |
| 重力兜底结算路径 | `SettlementRecognitionService.tryStagingGravitySettle` | 5 处调用仍在（:133/:162/:189/:221/:272） |
| `vision-service/` 整目录 | 仓库根 `ls` | **仍在**（注意：路径是仓库根，不在 `services/` 下） |
| `docker-up.ps1` 仍列 vision-service | 仓库根 `docker-up.ps1` | 仍在 |
| `infra/.env` 的 `VISION_*` / `RECOGNIZER_BACKEND` | `grep -cE` | 6 个键仍在 |

> 台账 AU-134 的 `vision-service/` 未标注"仓库根"，本轮差点误判为"已被 dev 退役"。已在本报告钉正，建议台账同步补一句。

## 2. dev 侧问题清单（逐条带证据）

| ID | 级别 | 问题 | 证据 | 处置 |
| --- | --- | --- | --- | --- |
| **DEV-01** | **P1 阻塞** | **Flyway 版本号撞车**：审计分支 `V346__jiangyi_device_identifier_nullable.sql` 与 dev `V346__drop_replenishment_request_and_monthly_close_task.sql` 同号不同名 | `git ls-tree refs/heads/dev …/db/migration` V346=drop_replenishment_request；本分支 V346=identifier_nullable | 两树一旦合并，Flyway validate 报 "Found more than one migration with version 346"，**后端启动即失败**。修法：把 identifier 可空迁移改号 **V369**（dev 现最高 V368） |
| **DEV-02** | **P1（主干未修）** | AU-133「将邑一期登记第 1 步恒 500」在 dev 上**依然存在** | dev `V332__jiangyi_device.sql:22` 仍 `identifier VARCHAR(32) NOT NULL`；dev 全部 23 条新迁移（V346–V368）**无一触碰** `jiangyi_device.identifier` | 真机联调日第一步仍会 `DataIntegrityViolationException`。且 dev 正在给该表加列（V366 `auth_enabled`、V367 `device_http_credential`）⇒ 演进仍在继续，缺陷不会自愈 |
| **DEV-03** | **P1（修复未落地）** | 本审计落地的 **20 条 AU 修复（12 个 `fix(...)` 提交）在 dev 上一条都没有** | `git log refs/heads/dev..HEAD` 中 12 个 fix 提交覆盖 AU-006/007/038/039/040/047/048/060/061/074/075/076/080/087/088/090/110/111/124/133；`git grep -oE "AU-[0-9]{3}" refs/heads/dev -- services docs` → **0 命中** | 若上线候选取 dev，这 20 条全部按 open 计。含安全/资金类：AU-060/061（设备归属、tracker orderNo 归属）、AU-087/088（税资料 BOLA、异常台账跨租户）、AU-047（运营状态越权）、AU-048（资金阈值键写侧守卫）、AU-074/080（密钥占位符/弱值黑名单）、AU-090/110/111（要货溢出、券核销旁路、任务锁竞态）、AU-038（结算金额溢出）。⚠️ 台账 AU-087/088/074/075/076/090/110/111 等行的"处置"列仍写"待修"，与 `bc82afbc…d7a676ae` 流水不一致——**台账散文不可当事实源，以提交+回归证据为准** |
| **DEV-04** | **范围缺口** | dev 的 82 提交新增一整套**资金域**，全部未经本六轨道审计 | 新迁移 V355–V365：`merchant_fee_ledger`/`machine_payee_bill`/`line_commission_basis`/`staff_wallet`/`deposit_auto_refund`/`commission_clawback`/`scheduled_task_refund_retry`/`deposit_removal_settlement`；提交 `93bf2697`/`bfc0a6f5`/`1bb31e4f`/`f9281249`/`00abda8c`/`2197a097` | B-1 资金域审计范围必须扩到：补货员钱包、押金月度自动退还、佣金随退款冲正、渠道退款重试、机器账单、外部渠道对账导入。注意 `93bf2697` 自报"上线前审计 🔴 修复（Explore 全量审计 6 项阻断）"是**内联自评**，非本审计口径，未见负向测试先行留痕 ⇒ 需按本审计纪律复验 |
| **DEV-05** | **P2（语义冲突面）** | 15 个文件两边都改，其中 dev 近乎重写：`ReplenishmentService` +116/−81、`MerchantReplenishmentService` +33/−320、`CouponService` +46/−35、`DeviceReportController` +43/−0、`TradeInternalClient` +29/−1 | `git diff --numstat 3d627ec1..refs/heads/dev` | 锚在这些文件上的修复**不能机械 merge**，要在 dev 版本上重新实施：AU-110（券核销范围）、AU-111（cancelEmptyTask 任务锁）、AU-060/061 调用面。另：dev 已整链删除 `ReplenishmentRequest`（HEAD 21 文件引用 → dev 0 引用），凡旧结论引该链者需重定位（本次 15 份报告 grep 该链 = 0 命中，无结论作废） |
| **DEV-06** | P2 | 61 条 file:line 行号漂移 | §1 表 | 体力活：对账时批量改坐标 |
| **DEV-07** | P2 | 运行期证据全部过期 | 被测镜像 `ai-cabinet/trade-service:audit-c13` 建自 `d7a676ae`；E-02 行为指纹「当前后端含审计分支入口校验」只对那个构建成立 | Track C-admin F1 前半 12 项、AU-133 运行期复验、F1 结算链观测——**dev 冻结后必须重跑**（环境争用解决前重跑无意义） |
| **DEV-08** | P2 | 台账头基线声明已失真 | `AUDIT_LEDGER_2026-10-13.md:3` 仍写"基线 commit `9911a2ee`（dev 最新）" | 改为"代码基线 `3d627ec1`；复验 SHA 见本报告"，并给每条加"复验 SHA"栏，避免下个会话再猜 |
| **DEV-09** | P3 | 台账路径写法可致误判 | AU-134 的 `vision-service/`（实在仓库根）、`docker-up.ps1`（实在仓库根，非 `scripts/`） | 补全路径前缀 |

## 3. 8 条失踪锚点逐条（必须人工重判）

| 引用 | 所属 | HEAD 锚点文本 | dev 现状与判断 |
| --- | --- | --- | --- |
| `SettlementOrderSupport:89` | AU-038 | `// AU-038：金额一律按 long 算再夹进列域…` | dev 该文件 **+28/−0**（纯新增，未删旧码）⇒ AU-038 的溢出缺陷在 dev 上**大概率仍未修**（dev 无 `AU-` 标记），需按 int 溢出重核 |
| `DeviceReportController:64` | AU-060/061 | `commandTracker.complete(Kind.OPEN, …)` | dev 已有自己的 open-failed 链（`postOpenFailed` 3 处，文案不同）⇒ 归属校验要在 dev 版本重核调用面是否等价，**这条真可能翻案** |
| `DeviceReportController:70` | AU-060 | `tradeInternalClient.postOpenFailed(body.orderNo(), "将邑设备上报开锁失败", deviceId);` | 同上 |
| `SessionOpenService:113` | AU-061/B-2 | `sessionId, session.getDeviceId(), reportingDeviceId);` | dev `SessionOpenService` 有改动（见 MOVED 名单），需重定位 |
| `MerchantOpsExceptionController:30` | AU-088 | `*/`（锚点取到了注释块边界，取证瑕疵） | 台账取证时的行号选取本身不精确，需改为符号锚（方法名） |
| `CommercialFlowService:61` | AU-124 | `this.securityProperties = securityProperties;` | 构造器赋值行——dev 若加参数即漂移，非结论性问题；AU-124 的拒绝逻辑要在 dev 版重核 |
| `MerchantSkuPricingService:486` | B-5 | `if (priceCents < min) {` | dev 新增 `V350__device_sku_price_enable_and_min_price.sql`（**价格下界进了主干设计**）⇒ AU/结论需重判：我方下界守卫与 dev 的 min_price 列谁权威 |
| `MerchantSkuPricingService:503` | B-5 | `private static int maxAllowedPrice(SkuCatalog sku) {` | 同上，上界逻辑要对齐 dev 新列语义 |

## 4. 20 条修复（12 个 `fix(...)` 提交）的去向矩阵

| 修复 | 承载文件 | dev 是否触碰同处 | 落地方式 |
| --- | --- | --- | --- |
| AU-133 | `V346`→**改号 V369** + `JiangyiOnboardingRegisterSqlIT` | 仅号段撞车，语义零交集 | **可直接合**（改号即可） |
| AU-006/007 | `MerchantWithdrawService`(+2/−1) | 微改 | 可直接合，需复验 |
| AU-047 | `OpsRbacService` / `AdminDeviceOpsService`（MOVED） | 行号漂移 | 可合，重定位即可 |
| AU-048 | `SystemConfigService`(+8/−0) | dev 纯新增 | 可合，需复验 `FUND_GUARDED_KEYS` 现值 |
| AU-060/061 | `DeviceReportController`(+43/−0)、`TradeInternalClient`、`CommandTracker` | dev 独立引入 open-failed 链 | **要在 dev 版重做归属校验**（§3） |
| AU-087/088 | `MerchantPortalController` / `MerchantPortalGuard` | MOVED + 锚点瑕疵 | 可合，锚点改符号 |
| AU-090 | `ReplenishmentService`(+116/−81) | dev 大改 | **必须在 dev 版重做** |
| AU-110/111 | `CouponService`(+46/−35)、`MerchantReplenishmentService`(+33/−320) | dev 近乎重写 + `ReplenishmentRequest` 整链删除 | **必须在 dev 版重做，且先判缺陷是否已被 dev 的重写顺带消解** |
| AU-038/039/040 | `SettlementOrderSupport`(+28/−0)、录像归属 | 纯新增 | 可合，逐条复验 |
| AU-074/075/076/080 | infra 模板 + `.env.production.example`（双方都改） | dev 改了 4 个 infra 文件 + 同名 example | 需手工并表，门禁脚本可合 |
| AU-124 | `CommercialFlowService` | 构造器漂移 | 可合，重定位 |

## 5. 建议处置顺序

1. **今天就做（防炸）**：把 AU-133 迁移改号 V369 并合入 dev（新分支 `fix/au133-jiangyi-identifier-nullable`，带 3 例 IT）。成本半天；不做则真机联调日 + 合并启动双炸。
2. **dev 冻结前**：跑一次 §1 对账 pass（61 条改坐标 + 8 条重判），台账加"复验 SHA"列；把 DEV-03 的 20 条以「dev 上仍 open」的口径同步进拍板清单（DC-28）；顺带修台账销账遗漏：AU-087/088 处置列仍写「待修」，实际已由 `705aa4a5` 带负向测试修毕（已补正）。
3. **dev 冻结后**：按新增资金域（DEV-04）补一轮 B-1 扩展审计（补货员钱包 / 押金月退 / 佣金冲正 / 退款重试 / 机器账单 / 外部对账导入）——**这才是真正需要"重审"的部分，且面积大于原 B-1**。
4. **运行期实测（Track C-admin F1–F8、C-mp）只在冻结后做一次**，且必须用隔离 compose project，否则证据不可复现（见 `AUDIT_TRACK_C_ADMIN_FLOWS_2026-10-13.md` §0.1）。
5. DC-27（重力/云识别残留）拍板可以照旧——残留事实在 dev 上未变。

## 6. 复现命令

```bash
# 漂移对账（统计 + 需人工复验文件清单）
node .tmp/reverify-citations.mjs

# 分叉量与重叠面
git rev-list --count 3d627ec1..refs/heads/dev        # 82
git rev-list --count refs/heads/dev..HEAD            # 26
comm -12 <(git diff --name-only 3d627ec1..HEAD | sort -u) \
         <(git diff --name-only 3d627ec1..refs/heads/dev | sort -u)

# Flyway 号段撞车
git ls-tree --name-only -r refs/heads/dev services/trade-service/src/main/resources/db/migration | grep -E 'V34[6-9]'

# dev 是否含任何 AU 修复
git grep -oE "AU-[0-9]{3}" refs/heads/dev -- services docs   # 空 = 一条都没有

# AU-134 残留复验（仓库根，非 services/）
ls vision-service && grep -cE '^VISION_|^RECOGNIZER_BACKEND' infra/.env
grep -n "tryStagingGravitySettle" services/trade-service/src/main/java/com/aicabinet/trade/service/SettlementRecognitionService.java
```

## 7. 逐条结论 × dev 判定表（台账 59 行全量，对 dev@`c272c1ef`）

> 生成：`.tmp/au-vs-dev.mjs`（对每条台账行的 `file:line` 锚在 dev 版本查同文本）。等级/处置取台账原列，截断 34 字。

| 判定 | 行数 | 含义 |
| --- | --- | --- |
| 🔧 修复在本分支、dev 未合 | **20** | 这些 AU 的**修复代码**只存在于 `refs/heads/dev..HEAD` 的 12 个 fix 提交 ⇒ 上线候选取 dev 时按 open 计 |
| 🟢 锚点原位成立 | 4 | 结论照搬 |
| 🟡 坐标漂移 | 3 | 只改行号 |
| 📄 无行号锚（文档/口径/登记型） | 29 | 多为 `AU-013~016` 这类区间登记行，需按主题过一遍（非逐条重审） |
| ✅ 判定不依赖行号 | 3 | 判非缺陷类（含 AU-003/086/132），沿用 |

| AU | 等级 | 台账处置（截断） | 行号锚 | dev 判定 |
| --- | --- | --- | --- | --- |
| AU-006 | **P1** | 已修(待 CI 口径)：幂等命中加归属+金额比对，跨主体显式 409 | 2 | 🔧 修复在本分支，dev 未合 ⇒ dev 上仍 open |
| AU-007 | **P1** | 已修(待 CI 口径)：拆先锁后事务两层+锁内重查（self.can | 2 | 🔧 修复在本分支，dev 未合 ⇒ dev 上仍 open |
| AU-038 | P2 | 已修(批九)：金额全程 long+行金额/合计逐个夹 [0,INT_ | 1 | 🔧 修复在本分支，dev 未合 ⇒ dev 上仍 open |
| AU-039 | P2 | 已修(批九，口径=拒非钳)：checkConfidence 对每行+ | 1 | 🔧 修复在本分支，dev 未合 ⇒ dev 上仍 open |
| AU-040 | P2 | 已修(批九)：upsertReport 落库前比对会话 device | 1 | 🔧 修复在本分支，dev 未合 ⇒ dev 上仍 open |
| AU-047 | **P1** | 已修(待 CI 口径)：INACTIVE 分支补 userHasRo | 1 | 🔧 修复在本分支，dev 未合 ⇒ dev 上仍 open |
| AU-048 | **P1** | 已修(批七：审计默认开+资金键族18键无视开关强制留痕+写侧值校验[ | 1 | 🔧 修复在本分支，dev 未合 ⇒ dev 上仍 open |
| AU-060 | **P1** | 已修(待 CI 口径)：trade markOpenDoorFail | 1 | 🔧 修复在本分支，dev 未合 ⇒ dev 上仍 open |
| AU-061 | P2 | 已回归 | 2 | 🔧 修复在本分支，dev 未合 ⇒ dev 上仍 open |
| AU-074 | **P1** | 已修：trade validator requireSecret 尾 | 0 | 🔧 修复在本分支，dev 未合 ⇒ dev 上仍 open |
| AU-075 | **P1** | 已修：jiangyi 新增 ProductionStartupVal | 0 | 🔧 修复在本分支，dev 未合 ⇒ dev 上仍 open |
| AU-076 | **P1** | 已修：模板补齐三处（MiniApp/余额-only 分档注释、MQT | 0 | 🔧 修复在本分支，dev 未合 ⇒ dev 上仍 open |
| AU-080 | P2 | 已修(随 AU-074 批)：check-env prod 档六键（ | 0 | 🔧 修复在本分支，dev 未合 ⇒ dev 上仍 open |
| AU-087 | **P1** | 已修(批三 705aa4a5，负向测试先行)：改 requireAc | 2 | 🔧 修复在本分支，dev 未合 ⇒ dev 上仍 open |
| AU-088 | **P1** | 已修(批三 705aa4a5)：补 requireAccess，nu | 2 | 🔧 修复在本分支，dev 未合 ⇒ dev 上仍 open |
| AU-090 | **P1** | 已回归 | 1 | 🔧 修复在本分支，dev 未合 ⇒ dev 上仍 open |
| AU-110 | **P1** | 已回归 | 1 | 🔧 修复在本分支，dev 未合 ⇒ dev 上仍 open |
| AU-111 | **P1** | 已回归 | 1 | 🔧 修复在本分支，dev 未合 ⇒ dev 上仍 open |
| AU-124 | P2 | 已修(8d)：runFullFlow 入口运行期拒绝——mock-e | 2 | 🔧 修复在本分支，dev 未合 ⇒ dev 上仍 open |
| AU-133 | **P1** | 已修(批十)：V346 `ALTER COLUMN identifi | 1 | 🔧 修复在本分支，dev 未合 ⇒ dev 上仍 open |
| AU-062 | P2 | 待拍板 DC-11 后修 | 1 | 🟡 坐标漂移，结论仍指同一处 ⇒ 只改行号 |
| AU-063 | P2 | 待修(随批) | 1 | 🟡 坐标漂移，结论仍指同一处 ⇒ 只改行号 |
| AU-135 | **P2** | 建议：绑定入口加环境闸门（prod 凭据 + 非 prod prof | 1 | 🟡 坐标漂移，结论仍指同一处 ⇒ 只改行号 |
| AU-001 | — | 已回归(复扫)；未修项另立 AU-010~016 | 0 | 📄 无行号锚（文档/口径/登记型）⇒ 需按主题人工过 |
| AU-002 | P1 | 待修(接真前) | 0 | 📄 无行号锚（文档/口径/登记型）⇒ 需按主题人工过 |
| AU-004 | P1 | dev 侧已补偿（10-13 午 merge 带入）：V344 守卫 | 0 | 📄 无行号锚（文档/口径/登记型）⇒ 需按主题人工过 |
| AU-005 | — | 已回归(报告落盘) | 0 | 📄 无行号锚（文档/口径/登记型）⇒ 需按主题人工过 |
| AU-008 | P2 | 待修(随批) | 0 | 📄 无行号锚（文档/口径/登记型）⇒ 需按主题人工过 |
| AU-009 | P1 | 待拍板兜底方式后修 | 0 | 📄 无行号锚（文档/口径/登记型）⇒ 需按主题人工过 |
| AU-010 | P1 | 待修(接真硬件前) | 0 | 📄 无行号锚（文档/口径/登记型）⇒ 需按主题人工过 |
| AU-011 | P2 | 待排期 | 0 | 📄 无行号锚（文档/口径/登记型）⇒ 需按主题人工过 |
| AU-013 | P3 | 登记(延后) | 0 | 📄 无行号锚（文档/口径/登记型）⇒ 需按主题人工过 |
| AU-017 | P2/P3 | 登记(随批修) | 0 | 📄 无行号锚（文档/口径/登记型）⇒ 需按主题人工过 |
| AU-028 | P2 | 待拍板(是否补锁门指令/告警) | 0 | 📄 无行号锚（文档/口径/登记型）⇒ 需按主题人工过 |
| AU-029 | P2 | 待修(随批) | 0 | 📄 无行号锚（文档/口径/登记型）⇒ 需按主题人工过 |
| AU-030 | P3 | 登记(延后) | 0 | 📄 无行号锚（文档/口径/登记型）⇒ 需按主题人工过 |
| AU-041 | P3 | 登记(随批/延后) | 0 | 📄 无行号锚（文档/口径/登记型）⇒ 需按主题人工过 |
| AU-046 | P3 | 挂起(接真 checklist 运营动作：改邮箱) | 0 | 📄 无行号锚（文档/口径/登记型）⇒ 需按主题人工过 |
| AU-049 | P2/P3 | 登记(随批/延后；S-12 进 F-6 部署校验) | 0 | 📄 无行号锚（文档/口径/登记型）⇒ 需按主题人工过 |
| AU-058 | 存疑 | 待拍板 DC-10 | 0 | 📄 无行号锚（文档/口径/登记型）⇒ 需按主题人工过 |
| AU-059 | — | 已回归(落盘)；D-2 三项并入接真前 checklist；DC-3 | 0 | 📄 无行号锚（文档/口径/登记型）⇒ 需按主题人工过 |
| AU-064 | P3 | 登记(延后/随批) | 0 | 📄 无行号锚（文档/口径/登记型）⇒ 需按主题人工过 |
| AU-077 | P2 | 待修 | 0 | 📄 无行号锚（文档/口径/登记型）⇒ 需按主题人工过 |
| AU-078 | P2 | 待修(文档) | 0 | 📄 无行号锚（文档/口径/登记型）⇒ 需按主题人工过 |
| AU-079 | P2 | 登记(部署门禁) | 0 | 📄 无行号锚（文档/口径/登记型）⇒ 需按主题人工过 |
| AU-081 | P3 | 登记(延后) | 0 | 📄 无行号锚（文档/口径/登记型）⇒ 需按主题人工过 |
| AU-091 | P2 ×13 | 登记(随批；102 建议接真前必修) | 0 | 📄 无行号锚（文档/口径/登记型）⇒ 需按主题人工过 |
| AU-104 | P3 ×6 | 登记(延后) | 0 | 📄 无行号锚（文档/口径/登记型）⇒ 需按主题人工过 |
| AU-112 | **P1*** | 等级随 DC-20 拍板：是→必修(requireDeviceAcc | 0 | 📄 无行号锚（文档/口径/登记型）⇒ 需按主题人工过 |
| AU-113 | P2 ×8 | 登记(随批；116/120 建议接真前必修) | 0 | 📄 无行号锚（文档/口径/登记型）⇒ 需按主题人工过 |
| AU-121 | P3 ×3 | 登记(延后) | 0 | 📄 无行号锚（文档/口径/登记型）⇒ 需按主题人工过 |
| AU-126 | P3 ×6 | 登记(延后/随批) | 0 | 📄 无行号锚（文档/口径/登记型）⇒ 需按主题人工过 |
| AU-012 | P2 | 待排期 | 1 | 🟢 锚点原位成立 |
| AU-089 | **P1** | 待拍板 DC-14 后修(建议门户域 deny-by-default | 2 | 🟢 锚点原位成立 |
| AU-125 | P2 | 待修(删除或补码+audience 过滤+DTO) | 1 | 🟢 锚点原位成立 |
| AU-134 | **P2** | 不判 P1 的边界（实测）：生产非活资损面——`ensureJian | 1 | 🟢 锚点原位成立 |
| AU-003 | — | 判非缺陷 | 0 | ✅ 判定不依赖行号，结论沿用 |
| AU-086 | — | 判非缺陷 | 0 | ✅ 判定不依赖行号，结论沿用 |
| AU-132 | — | 判非缺陷(残留=无 profile 部署纪律，归 AU-051/07 | 0 | ✅ 判定不依赖行号，结论沿用 |

### 7.1 表格里被 🔧 覆盖、但 dev 上可能已"另一实现覆盖"的抽样复核

机械口径只看 `AU-` 标记，会**高估**"dev 未修"。已抽一条深查，结论是仍需补：

- **AU-088（异常台账跨租户）**：dev `MerchantOpsExceptionController.list` 已改走 `scopeService.allowedDeviceIds(userId)`（看似有 scope），但下游 `OpsExceptionService.listForDevices:149-153` 仍是 `deviceIds == null → findFiltered(statusFilter, null, false, …)` 即**全量读**；本分支的修法是控制器加 `merchantPortalGuard.requireAccess(userId)` 拒绝全局态——**dev 上没有这一层**。⇒ 判定：AU-088 在 dev 上**仍 open**，且落地方式应是"在 dev 版控制器补一行守卫"，而不是移植我的整块补丁。
- `MerchantPortalGuard` 在 merge-base `3d627ec1` 上已有 29 个文件引用 ⇒ 它是**项目原有类**，不是我造的，所以 dev 侧同名类存在不代表 AU-087/088 已修。
- `FundKeyGuard`、`check-env-placeholders` 在 dev 上 **0 命中**（HEAD 各 2）⇒ AU-048/AU-074 的门禁确实没进主干。

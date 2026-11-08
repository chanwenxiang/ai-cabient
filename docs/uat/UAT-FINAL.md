# UAT 数据测试总结报告（2026-11-07 定稿）

> 环境：uat 空库 `aicabinet_uat` 切栈（Flyway V1→V447 全链 443 迁移一次通过）；三端真实操作（admin 浏览器/API 链、consumer 微信开发者工具真实扫码+登录、模拟柜 MQTT/设备事件）；「数据说话」=每步库断言。
> 计划：docs/UAT_DATA_TEST_PLAN.md；轮报告：docs/uat/rounds/。

## 一、七轮执行结果

| 轮 | 域 | 结果 | 新发现 |
|----|----|------|--------|
| D0 | 基线 | ✅ 空库切栈+443 迁移+181 表底表 | — |
| R1 | 主链冒烟 | ✅ 扫码开门→购物→结算 PAID，全链对账平 | **MA-163（P2）Hikari 只读连接污染** |
| R2 | 用户账号小点 | ✅ 运营/商户/门户/线长全生命周期+RBAC 矩阵双向 | 零新缺陷（2 个「疑似」经查均为正确契约） |
| R3 | 商品/库存/仓储 | ✅ SKU 生命周期+采购全链+三层账+盘点 ADJUSTED+调拨 | 零新缺陷（MA-069/157/103 回归 ✓） |
| R4 | 营销/会员 | ✅ **MA-147/099/096 三修复回归实证全绿** | 零新缺陷 |
| R5 | 退款/争议/钱包 | ✅ 全额退+WAIVE 免单裁决（余额/券/分账全对平） | 零新缺陷 |
| R6 | 定时/日结/月结 | ✅ fee-rerun×2/机器账单×2/recon×2/巡检×2/调度 run 幂等全验 | 零新缺陷（feeDate 未来值 400 边界 ✓） |
| R7 | 并发/边界/收口 | ✅ 覆盖终检（181 表三分类）✅ 边界部分（2026-11-07 补，见「四·补 R7 边界」） | **F1 调账审批：坐实 → 已修并复验 PASS** | 死表候选 ×2 |

**新缺陷合计：MA-163 一条（P2）+ F1（2026-11-07 R7 边界轮实测坐实，**2026-11-08 已修并复验 15 项 PASS，转 CLOSE**）**；回归实证：MA-147/099/096/069/157/103/066 + CB-082 禁券（R1/R17 矩阵）。

## 二、修复验证（白天修复 AI 的工作验收）

白天修复的缺陷在 UAT 中全部回归实证：MA-147（全额退还券 ✓ UNUSED）、MA-099（召回幂等 409 ✓）、
MA-096（负积分成本 400 ✓）、MA-069（佣金限幅 400 ✓）、MA-157（调拨取消留痕 ✓）、MA-103（盘点行锁 ✓）。

## 三、上线结论与建议

**可上线**，附四个前置动作：
1. **MA-163（P2）**：Hikari 加 `connection-init-sql: SET default_transaction_read_only=off` 兜底（一行配置）——修复前生产存在间歇写失败风险（UAT 两轮窗口实证）。
2. **部署前置 SQL**：DEPLOYMENT_CHECKLIST §3.1.5（V435 重复组）+ V431 逐表 count——服务器库必跑。
3. **小程序发布产物**：必须从 dist/build 重建上传（merchant 曾发现产物落后 5.3 小时）。
4. **F1 调账审批（2026-11-07 R7 实测坐实，2026-11-08 修复并复验）**：已采用方案 B（补 409 守卫 + 返回/审计真实实例状态），重建 trade-service 镜像并复跑 `r7-f1-verify-fix.mjs` 全 15 项 PASS，详见 rounds/R7-boundary.md §5b/§5c。改动已 commit（`27e00b90`）但 **未 push**，上线前随本次提交真正推上；脚本收口谓词漏删行的真实性已在 §5c 复核记录。

## 四、遗留（不阻断上线）

| 项 | 说明 |
|----|------|
| 消费者 UI 小点 | 昵称/偏好/注销四守卫——token 过期，白天 devtools 登录后 10 分钟可验 |
| 争议 ADJUST/KEEP 裁决 | 同族 CONFIRM/WAIVE 已实测；单测 6 例覆盖 |
| 真机开门/支付分生产 API | 已留痕（支付分生产未接=501，设备摄像头售后中） |
| R6 佣金日结 | 周期扫描型，造数后等下一轮（幂等键已在位） |
| R7 边界未覆盖 3 项 | 跨角色双操作员并发——**2026-11-08 R10 已真库闭环（43 PASS，见「四·补 R10」→ rounds/R7-boundary.md §10.8）**；弱网恢复提示、小程序四闸门人工复验仍在排队（需开发者工具人工）。<br>**账期切换窗口——2026-11-08 R11 已做边界取证（只读，见 `rounds/R11-barcode-gs1-consistency.md §六`）：窗口常量从源码修正为 `Asia/Shanghai` 本地 00:00；分仓侧 11 表按 26→25 两账期打点属实。** |
| **F1 调账审批** | **已修复（2026-11-08）：补 409 守卫 + 返回真实实例状态，重建镜像后复验 15 项 PASS，转 CLOSE**（见「四·补 R7 边界轮 §5b」）——上线前确保该改动随本次提交推送并回归一次 |

## 四·补 R8 空表填充轮（2026-11-07，详见 rounds/R8-fill.md）

**181 表触达 108 → 123（+15）**，全部真实 API 链+库断言，新缺陷 0：
- 新触达：user_feedback/user_notify_pref/device_fault_report/http+mqtt_credential/ops_2fa_recovery_code/
  site_contract/site_rent_bill/device_temp_plan/announcement/invoice_request/data_consistency_record/
  balance_refund_request/balance_refund_allocation/repair_ticket（报修自动建单，CB-077 分线 ✓）
- 部分退线上复证（PARTIAL_REFUNDED 250/500 对平）+ MA-092/119 回归 ✓
- 余额原路退**双人审批流**全链 ✓（运营审核→财务复核→allocation 分摊到原微信充值单）
- consumer token 续期链打通（automator login→真 wx-login，全程无需人工扫码）
- 一致性核查器真库实测：抓故意 +777 立即告警，还原自动 FIXED；6 条存量 FAIL 全部定性为 UAT 造数直改 SQL 遗留（非产品缺陷）——建议部署前跑一次 `POST /consistency/run` 作上线自检

## 四·补 R7 边界轮（2026-11-07 晚，详见 rounds/R7-boundary.md）

**边界项中 F1 真实触发已实测坐实；2026-11-08 采用方案 B 修复并复验全绿（下表为溯源，原 open 缺陷记录）：**

- `MerchantWithdrawService.reviewAdjustment(319-332)`：对**无审批实例**的调账单点「通过」仍返回
  HTTP 200 + `status=APPROVED`，且写假审计行（期望 409）。驳回路径同失真，且**同 bizId 幂等无守卫**（再点仍 200）。
- **更深一层**：`MERCHANT_WALLET_ADJUST` 定义是**双节点**（运营审核 → 财务复核）。即使实例真实存在、
  运营节点通过，方法仍报 `APPROVED`，而库内实例仍 `PENDING`（停在财务复核节点）。`status` 完全**硬编码**，
  未读 `instanceStatus(bizId)`。
- UI 影响：`MerchantWithdrawView.vue:927-932` 只看 HTTP 200 即 `ElMessage.success('已通过')`，无法区分真通过与静默空操作。
- 收口：本轮造的两条账目/假审计行/临时实例已全部回滚至基线（`balance=209900`、`approval_instance=14`、`admin_audit_log=306`）。
- 同轮 F3（`ApprovalController.startInstance` `Map.of` NPE）实测**不可触发**：空字段被参数校验拦在 400，同单重复发起 409 —— fail-loud 生效，维持低风险。

> 说明：上表 R7 行的「覆盖终检」系早先已完成项，与边界/F1 两块不同；边界/F1 至本轮才算真实执行。
> **F1 修复方案 B（2026-11-08）已执行并复验全 15 项 PASS，详见 `rounds/R7-boundary.md §5b`；§5c 为同轮对运行容器判真 + 收口谓词的诚实复核。**
> R7 未覆盖：并发双操作、弱网、账期切换窗口、全表覆盖终检三分类补跑（需双会话/开发者工具人工/下一轮）。

## 四·补 R10 三端横切 · 跨角色并发（2026-11-08，详见 rounds/R7-boundary.md §10.8）

**R10 以 `MERCHANT_WALLET_ADJUST` 真金库路径对跨角色并发做了闭环（真库 UAT 真实回读）：**

- **方法**：`/api/v2/ops/admin/merchant-wallets/{merchantId}/adjust` 发起 ≥阈值调账自动建双节点审批实例；用 `/api/v2/ops/admin/merchant-withdraws/wallet-adjustments/{bizId}/review` 并发双发 `approve=true`。
  会话 A = 超管 `13900000001`（`operator_id=100000001`，node1 处理人）、会话 B = `13900000002`（`operator_id=100000007`，node2 处理人）。
- **结果：43 PASS / 0 FAIL**，基线精确复原（`approval_instance=14`、`approval_task=27`、`wallet_ledger=26`、`admin_audit_log=306`、`balance_cents=209900`、marker 残留 0）。
- **核心安全不变式**：① node1 由 A **唯一处理且至多推进一次**；② node2 **恰好 1 个任务行**（跨角色并发未见重复节点任务，双提交重复记账窗口不存在）；③ B 从不批准 node1（会被 `FOR UPDATE` 行串行 + 处理人校验挡 403）；④ 终态再点 409。
- **看到的合法次序**（与 `ApprovalInstanceMapper.xml` 的 `findByIdForUpdate … FOR UPDATE` 语义一致）：A=200/B=200（B 阻塞在实例行锁，A 提交后 B 落到 node2 合法完成）或 A=200/B=403（B 抢 node1 失败，实例停在 node2 保留财务待办）。**二者均安全**。
- 负控制：B 处理 node1 → 403 `您不是当前审批节点的处理人`；A 跨节点再点 → 403；终态再点 → 409（`当前状态不可审核`）。
- 本轮 **未改任何产品代码**；该轮真实缺陷的脚手架谓词问题（`JSON.stringify` 被 psql 当作 SQL identifier、`ORDER BY` 写在聚合外、`const` 做 `&&=`、回填顺序）已在 `r10-cross-role-race.mjs` 内修正并诚实记录。
- 仍待用户：`claimForPayout` 并发抢行（活库无 `APPROVED(非 mock)`）、账期切换窗口、小程序四闸门人工复验（微信开发者工具）。

## 四·补 R12 CI 产物门禁收口（2026-11-08，详见 rounds/R12-artifact-guard.md）

**R12 不是新业务测试，是把 R11 barcode 改动造成的"推不推得上去"闭环：**

- R11 提交后 `pre-push-ci-preflight` 唯一红 = `check:admin-artifact-pairing`：
  区间里有 `clients/admin-vue/src/...` 改动，却没有随源码提交的 `static/admin` 重建产物
  （Admin 静态产物被 git 跟踪，CI 对干净检出重建做**字节比对**，设计如此）。
- 解法：**干净检出重建**（`git clone` → `pnpm install --frozen-lockfile` → `node scripts/build-admin.mjs`），
  用 `node:24.18.0` 容器对齐 CI，**不碰宿主 `node_modules`/`.env.local`**（`VITE_*` 会被 vite 内联进产物 ⇒ 哈希必不同 ⇒ CI 必红）。
  `ADMIN_PAIR_PROVE_INERT=1` 买不到绿：barcode 改动确实进 bundle。
- 结果：产物随源码提交（`1a32a93f`），重跑 preflight **exit 0**
  （`✓ check:admin-artifact-pairing`、`✓ admin-bundle-budget`）。旧产物已备份在 `.tmp/stale-admin-ci-r12-admin`。
- ⚠️ **仍待真实 Actions 验**：CI `admin-artifacts` 的非 Windows 字节比对，须 push 后由 Actions 确认。

## 五、全库表覆盖（179 表 · R11 分类已作准）

> ✅ **2026-11-08 R11 整轮重跑已完成，分类已作准**（脚本 `.tmp/r11-table-coverage.mjs`，报告
> `rounds/R11-table-coverage.md`，详表 `docs/uat/table-coverage-final.md`）。
> 　合并判据：`information_schema.tables WHERE table_schema='public'` = **179**；`pg_tables WHERE schemaname='public'` = **179**；
> `flyway_schema_history` = **446 行**。
> 　🔴 R11 **实测 179 = 140 触达 + 39 空**；旧表头「139/40」差 1 的根因是文档**自相矛盾**
> （`announcement` 系 R8 已填充却仍被列进 A 类空表，实库 `announcement=1`）。
> 　旧「2 张 `*_bak_20261011` 备份表尚未清理」口径不成立——**两表已在 V448 DROP**（`pg_tables LIKE '%_bak%'` 空）。
> 　R11 新增 **C′**：`merchant_settlement_bill` 恒空属**设计如此**（实库 `order_revenue_split` 3 行全 `VOIDED`，
> 被 `buildRows()` 的 `isReversed` 口径排除），**非资金缺陷**，与日志「重建 0 商户」一致。
> 　残项：该报表的**确切金额从未用一笔非 VOIDED 实单实跑**——上线前若需实证，建议补一笔无退款实单后重跑 `settlement-bill`。

详见 docs/uat/table-coverage-final.md（Header/§A/§结论已在 R11 返正；39 空表全名单存
`.tmp/r11-table-coverage.out.txt`）。

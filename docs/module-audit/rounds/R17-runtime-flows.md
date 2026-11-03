# R17 资金剧本运行时 — 轮报告

> 镜头：活栈（trade :18080 + dev 库）真实 HTTP/SQL 跑资金剧本；栈健康门（docker ps + announcements 冒烟）通过后才动手。
> 铁律：支付/出款通道全 mock、绝不真打款、绝不清理类命令；资质类前置数据临时填+立即还原（R11 模式）。

## 批 1（2026-11-03 凌晨，锚 `10c6c839`，栈空闲无真实用户）

### ① 会员链 E2E 运行时复验（Testcontainers 真 PG，当前 HEAD）

`MemberOrderChainE2ETest` 全绿（exit 0），六层判据逐层过：开门→RECOGNIZING→端侧 edge-results 采纳→SETTLING→COMPLETED；
落库 `R11-LAYER order=1793643480547476513237 qty=2 gross=700 member=14 activity=0 coupon=200 total=486 lineSum=686`
⇒ `700−14−0−200=486` ✓、`686−200=486` ✓；分账 `LEDGER_ONLY gross=486 platform=0 merchant=486`、钱包 `SPLIT_CREDIT=486`、`walletBalance=50486`。
**意义**：MA-029 会员链 + MA-039/043/045/067 修复后的代码在当前 HEAD 运行时复验通过（T0 修复真实性证据补强）。

### ② 提现边界五发（活栈 HTTP，MCH-DEFAULT，MOCK 通道）

| 发 | 入参 | 实测 | 判定 |
|----|------|------|------|
| 1 | amountCents=0 | 400「amountCents不能为空」 | 拒绝正确；文案误导 → **MA-126** |
| 2 | amountCents=-500 | 400 同上 | 拒绝正确（同 MA-126） |
| 3 | amountCents=99999999 | 400「单笔提现上限 10000.0 元」 | 拒绝正确（单笔上限先于余额校验生效，符合设计） |
| 4 | amountCents=100 | 400「最低提现 10.0 元」 | 拒绝正确 |
| 5 | 并发同 requestNo（¥10 ×2 并行） | **两发同回 requestId=2 / status=PAID（幂等重放同一笔）** | 恰 1 行申请 + 三行流水（WITHDRAW −1000 / −994 / −6，1000=994+6 ✓）+ 余额 52745→51745 精确 ⇒ **零双花**，AU-006 requestNo 重放校验 + 钱包锁串行化活体验证 |

### ③ 数据留痕与清理

- 资质前置：`legal_person/business_license_url` 临时填 → 探测完立即还原 NULL（两次核验 `t t` ✓）。
- 遗留测试数据（留档复用，R11 同模式）：提现单 `R17-RACE-002`（requestId=2，¥10，MOCK PAID）及其三行 wallet_ledger；MCH-DEFAULT 余额现为 51745。
- E2E 跑在自有 Testcontainers，共享 dev 库零残留。

### ④ 新发现

- **MA-126**（P3）：@Positive 校验失败误报「不能为空」——RANGE_MIN_PATTERN 漏 "must be greater than 0" 形式（2026-10-23 同族修复漏网，代码注释即家族锚）。

### 未挣 / 待批

- **8 组合矩阵（活动×券×会员全组合）+ 部分退路径 + 余额+微信组合支付**未跑：活栈路需要消费端 token + 模拟柜开门机械（automator 不可无人值守）；干净路径是扩展 `MemberOrderChainE2ETest` 成矩阵——但**写测试代码越过「审/修」线，需用户拍板**（R11 段 1 有「E2E 形态交付」先例）。两案留批 2：①授权写矩阵 E2E；②白天活栈人工跑。

## 批 2（2026-11-03 上午，用户授权矩阵 E2E，R11 段 1 先例）

**交付物**：`services/trade-service/src/test/java/com/aicabinet/trade/e2e/SettlementComboMatrixE2ETest.java`（入仓随 CI，Testcontainers 真 PG 全链真 HTTP）。
**结果**：1/0 绿；与 `MemberOrderChainE2ETest` 同跑回归 2/0。

### ① 8 组合矩阵（会员×活动×券，全链真结算）

| 组合 | gross | member | activity | coupon | total | lineSum | 券状态 | 关键判据 |
|------|-------|--------|----------|--------|-------|---------|--------|----------|
| 0-0-0 | 700 | 0 | 0 | 0 | 700 | 700 | - | 毛额=目录价×2，恒等式 ✓ |
| 1-0-0 | 700 | 14 | 0 | 0 | 686 | 686 | - | member=gross−Σ行（只在单价折一次）✓ |
| 0-1-0 | 700 | 0 | 200 | 0 | 500 | 500 | - | activity 端到端 ✓ |
| 0-0-1 | 700 | 0 | 0 | 200 | 500 | 700 | **USED** | 券自动选用+核销 ✓ |
| 1-1-0 | 700 | 14 | 200 | 0 | 486 | 486 | - | 先会员后活动、member+activity=gross−Σ行 ✓ |
| 1-1-1 | 700 | 14 | 200 | **0** | 486 | 486 | **UNUSED** | 🔴 **CB-082 活动优先禁券端到端实锤**（有券在手也未被占用） |
| 1-0-1 | 700 | 14 | 0 | 200 | 486 | 686 | USED | 会员+券可叠加 ✓ |
| 0-1-1 | 700 | 0 | 200 | 0 | 500 | 500 | UNUSED | 活动禁券（无会员形态）✓ |

每组合同时断言：恒等式 `gross−member−activity−coupon=total`、折后式 `Σ行−coupon=total`、**consumer 与 merchant 读面五字段逐一等于落库（R16 运行时·订单实体腿）**。

### ② 全额退三面（独立券单链）

退款 150/150 对平、订单 REFUNDED、**分账 VOIDED、钱包净额冲平（SPLIT_CREDIT +150 / SPLIT_REVERSE −150，按 ref_id 求和=0）**；券仍 USED ⇒ **新发现 MA-147（P2）**，测试已按「已知行为」钉断言（修复后翻转即回归位）。

### ③ 部分退三面（会员+活动 qty=2 退 1）

refunded=243（=实付一半，预估==写侧端到端）、gross=700 不变（V424 契约）、折后式闭合、**分账保份额 LEDGER_ONLY 且钱包净额=剩余商户份额 243（SPLIT_PARTIAL_REVERSE 差额冲回已入账，AU-182 端到端实证）**；MA-129 已知缺口实测留痕：拆解缺口 350（gross 700 − member 7 − activity 100 − total 243），与静态审计结论一致。

### ④ 补扣链腿（MA-039 端到端）

剧本：余额充足开门（冻结 300）→ 购物后 balance 改 350 → 结算 capture 300 后余款不足 ⇒ MA-066 信号转 **PENDING**（pendingTotal=486 原样保留=MA-040 ✓、PENDING 不占券=MA-067 ✓）→ 充值 → pay ⇒ **paidCoupon=0、couponUser=UNUSED（MA-039 补扣禁券端到端实锤）**、分账/钱包正常闭合。

### 过程发现（不入账的环境/测试契约认知）

- `CheckoutProperties.resolvePreauthCents` 0 值回落 `MIN_BALANCE_CENTS=2000`（防呆）——关预授权的正确姿势是 device.deposit_cents 与 system_config 同改；
- 预授权冻结只动 frozen 列、capture 才扣 balance 列——「低余额开门被拦 vs 结算转 PENDING」是两道不同的闸；
- 11 条链同用户会撞 session 限流 20/h 与风控开门 5/h（测试属性放宽）。

### 未挣

- **余额+微信组合支付**（R17 批 3 剩余项）：需要微信渠道 mock 环境的 prepay/capture 联动，Testcontainers 单渠道 mock 覆盖不到，留待下次（活栈或 CI 扩展）。

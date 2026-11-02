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

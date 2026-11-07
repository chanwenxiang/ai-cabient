# 上线前全量 UAT 测试计划 v3（UAT 库 aicabinet_uat，可真实执行）

> 落地于 2026-11-07。承接 `docs/UAT_DATA_TEST_PLAN.md`（v2，R1/R2/R5 已收口）。
> **本版只放开 R3/R4/R6/R7 的待执行项**，并给出**可照抄的命令/步骤**；每轮末必须更新
> `docs/uat/table-coverage.md` 的触达表打分，覆盖缺口要显式列出。
>
> 铁律（沿用 v2 且最近被实证）：
> 1. **每条流程真实点写操作**——小程序用微信开发者工具 automator 真点，后台用 Playwright 浏览器真点；API/SQL 只用于**造数与库断言**，绝不代替操作。
> 2. 每轮**轮首快照 → 轮末快照 → 差额必须被业务解释**。
> 3. 输出前缀固定 `AUDIT-`；每轮结束**断言残留归 0**，并用**负向对照**证明查询本身有效。
> 4. P0 发现即停轮，先领 MA 号。
> 5. 不确定的业务语义**不许猜**，先停问用户。

## 0 · 登录态与执行前置（一次性）

```bash
# 采集三端 token（落 .tmp/tokens.json）：运营 / 消费端(短信) / 商户
node .tmp/login-tokens.mjs
# 起/确认微信开发者工具 automator 端口
#   cli.bat auto --project clients/consumer-mp/dist/dev/mp-weixin --auto-port 9420
#   cli.bat auto --project clients/merchant-mp/dist/dev/mp-weixin --auto-port 9421
netstat -ano | select-string "9420|9421"
```

- 环境事实见 `docs/uat/UAT-REVIEW-FINDINGS.md` §8。
- 关键前置：**微信 `wx-login` 不可用（dev 假 appid）** ⇒ 消费端一律走**短信登录**；这是环境事实非缺陷。

## 1 · 轮次总览（本轮起逐轮执行）

| 轮 | 域 | 关键动作 | 状态 |
|----|----|----------|------|
| R1 | 主链冒烟 | 扫码开门→购物→识别→结算→订单三端同读 | ✅（见 v2） |
| R2 | 用户与账号小点 | 运营全生命周期、商户、消费者、RBAC、部门/仓库 | ✅（见 v2） |
| R3 | 商品/库存/仓储 | SKU 生命周期、采购→收货→应付→退货、三层账对账、盘点/调拨、效期 | **本轮执行** |
| R4 | 营销/会员/增长 | 券全态、活动三型+展示位+禁券、积分兑换、召回幂等 | **本轮执行** |
| R5 | 退款/争议/钱包 | 部分退、争议四裁决、提现状态机、余额退款审批 | ✅（核心，见 v2） |
| R6 | 定时/日结/月结 | 逐任务触发，**每个任务触发两次验证幂等** | **本轮执行** |
| R7 | 并发/边界/收口 | 并发双操作、弱网、账期边界、F1 真实触发、全表覆盖终检 | **本轮执行** |

## 2 · R3 商品/库存/仓储（真实执行步骤）

### 2.1 轮首快照
```bash
docker exec ai-cabinet-postgres-1 psql -U aicabinet -d aicabinet_uat -Atc "
SELECT count(*), COALESCE(sum(qty),0) FROM device_sku_inventory;
SELECT COALESCE(length(sku_code),0) FROM sku_catalog ORDER BY sku_id LIMIT 5;"
# + wallet_account 余额合计
docker exec ai-cabinet-postgres-1 psql -U aicabinet -d aicabinet_uat -Atc "SELECT sum(balance_cents) FROM wallet_account;"
```

### 2.2 造数（以 `AUDIT-` 前缀命名）
- 后台真实点击：**商品管理** → 新建 SKU（名前缀 `AUDIT-`）。
- 详情绑定到演示柜 `EG307002410770`。
- 入库：**补货/入库** → `amount=FEW`（如 +10）。

### 2.3 真实操作（后台 Playwright，非 API）
1. 登录运营后台 `13900000001 / 123456`。
2. SKU 全生命周期：新建(SKU-DEMO 同效期) / 编辑 / 停用 / 删除裁判（删除被引用 SKU 应被拦截/提示）。
3. 采购→收货→应付→退货：入库单状态机每态停留核对，退货断言数量回流。
4. 三层账对账：`sku_catalog / device_sku_inventory / device_sku_lot` 同一 SKU 数量一致（S3 断链即缺陷）。
5. 盘点/调整/报损：写一个 `AUDIT-` 盘点差异，看 `inventory_write_off` 与台账联动。
6. 调拨全态：跨仓移库（MA-157 热区回归），断言两仓库存一增一减。
7. 效期：过期 SKU 的下架/禁止销售行为。

### 2.4 断言（库 + 跨端）
- 三层账对账等式成立；无效 SKU 不可被采购引用；退货回到可售。
- **消费端 cross-check**：小程序首页 `?deviceId=EG307002410770` 应能出现新入库商品（真实点开首页核对，用 automator 截图/`cell-count`）。

### 2.5 轮末收口
- 快照重取，差额 逐笔解释。
- 残留：删除 `AUDIT-` 造数、确认查询 0 命中（负向对照：`WHERE name LIKE 'AUDIT-%'` 前后都为空 ⇒ 证明删除真生效）。
- 更新 `docs/uat/table-coverage.md`。

## 3 · R4 营销/会员/增长

1. **券全态**：后台发券模板 → 发放 → 领取 → 核销 → 返还（用 `AUDIT-` 券），实战过一遍退款回滚。
2. **活动三型 + 展示位 + 禁券**：新建促销活动绑定 `AUDIT-` SKU，验证展示位弹窗"当天只弹一次"、设置了禁券的活动不叠券。
3. **会员/积分**：维度规则验证（会员价、积分兑换门槛、负数被拒）。
4. **召回幂等**：同一召回任务二次触发不双发（`notifications` 不重复、`user_recall` 行不重复）。
5. **消费端 cross-check**：小程序会员中心/券包/积分页真实点开核对显示与后台一致。
6. 轮末对账 + 清理 `AUDIT-` → 归 0 + 负向对照。

## 4 · R6 定时/日结/月结（逐任务，触发两次验幂等）

| 任务 | 触发方式 | 断言 | 幂等 |
|------|----------|------|------|
| 日费用日扣 | `rerun-daily-fees?feeDate=` | `merchant_fee_ledger` + wallet 流水 | 重复 rerun 不双扣（唯一索引） |
| 日结收入通知 | scheduler `taskKey` run | `notification(DAILY_INCOME)` | 不重发 |
| 佣金日结 | 周期扫描（无手动端点） | `wallet_ledger COMMISSION = gmv×bps` | 重扫不双发 |
| 机器账单月出账 | `machine-payee-bills/generate?billMonth=` | `machine_payee_bill` 行 | 再 generate 409；>10000bps 应 400（MA-090 回归） |
| 场地租金/数据费 | generate endpoint | 账单行 + appendLog | 再 generate 409 |
| 月结差异处置 | close-sheets→approve→disposition | `inventory_write_off` 索赔行 | 并发双击不双挂 |
| 资金对账 | `reconciliation/run?date=` | recon 行 + MISMATCH | 重跑删旧重建不残留 |
| 欠款催缴 | 造 PENDING 观察阶梯 | 通知/风控事件 | 不重复催同档 |
| 一致性巡检 | consistency run/fix | `data_consistency_record` | FIXED 重放拒绝 |
| 效期/补货建议 | expiry/suggest | 告警 + 拉货任务 | 同批不重开 |
| SMS 到达率巡检 | sms-delivery-report | 巡检记录 | 重复不重报 |

**每个任务：触发一次记录 → 触发第二次断言无变化/被幂等拒绝。**

## 5 · R7 并发/边界/收口

1. **并发双操作**：同一单据两个操作员并发通过/驳回（库存占用、审批占用），断言不脏更新。
2. **弱网**：AUTOMATOR 关闭网络后请求，小程序应出现可恢复提示（非白屏/崩溃）。
3. **跨日/账期边界**：账期切换窗口前后下单、日结任务跨日触发。
4. **F1 真实触发**（这次走查发现，见 `docs/uat/UAT-REVIEW-FINDINGS.md` §2）：
   造一个**无审批实例**的调账单 → 后台「调账审批」tab 点**通过**，期望 **409**；若实际返回 200+APPROVED，即坐实 F1。
5. **调账发起实例**：后台「发起实例」弹窗真实点击，验证 F3 的响应字段无 null。
6. **全表覆盖终检**：`docs/uat/baseline-tables.txt`（181 表）三分类：已触达 / 刻意空（写原因）/ 未触达（= 测试缺口）。

## 6 · 收口标准

- R3–R7 全部完成或显式列出阻塞项。
- 每轮 `table-coverage.md` 增量；终版全表三分类。
- 所有 `AUDIT-` 前缀造数已清理且归 0（负向对照）。
- 新发现全部领 MA 号并明确状态（open/fixed/wontfix）。
- 输出上线放行结论（通过/有阻塞项）。

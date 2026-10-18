# G11 冻结毛额对账判据 · G9 CORS 钉入 · G10 32 分人工归集（2026-10-18 晚）

> 三件事同轮收口。判据代码 `80c35b72`、admin 产物 `e43eaece`；隔离栈被测镜像 `ai-cabinet/trade-service:aicabuat`（含 V392，`unzip -l` 实锤）。

## 1. G11 `PAID_GROSS_MISMATCH` 四段运行时证明（隔离栈 `aicabinet-uat`）

口径：`cabinet_order.paid_amount_cents` = 该单 `payment_operation` COMPLETED 入账类型
（`CHARGE/ADJUST_CHARGE/PREAUTH_CAPTURE`，清单取 `PaymentOperationTypes.inboundTypesFilterSql`）之和；
退款走 `refunded_cents` **不进毛额**。仅巡检（`applyFix` 拒绝，改列=改佣金基数）。

- **段① 播一致探针行**：`PG-PROBE-ORDER`（PAID，冻结 486）+ `CHARGE 486 COMPLETED`。
- **段② 账平静默 + 对照版**：对照 SQL（判据同款结果集）对全仓列出 expected vs actual——
  探针行 486=486、全仓 0 条差异 ⇒ 巡检 API `POST /consistency/run` 对 `PAID_GROSS_MISMATCH` 输出 **0 条**（静默=真账平，不是没扫到）。
- **段③ 故意破坏证明能红**：`UPDATE … SET paid_amount_cents=999` ⇒ 复跑即 FAIL：
  `expected=999 actual=486`，提示语「冻结毛额 999 大于流水入账和 486：流水缺钱（挂错 order_id/重复冻结/流水缺失），**资损方向**，禁止改列迁就」。
  探针单（无明细行、无分账行）连带触发同族 `ORDER_AMOUNT`/`REVENUE_SPLIT_MISSING` 各 1 条 ⇒ 扫描族整体在工作。
- **段④ 清理后 stale 自愈**：恢复 486 ⇒ 复跑 record 21 即 `FIXED`；删除探针三行（流水/订单/会话）⇒ 复跑
  `ORDER_AMOUNT`/`REVENUE_SPLIT_MISSING` 探针记录全部自愈 `FIXED`，**failCount 回基线 1**（`CROSS_LINK/DSP|…` 为 F2 驱动器既有工单，非本轮范围）。
  探针数据行零残留（三行 DELETE；遗留巡检记录全 FIXED 留痕，符合「同 key 再检出再插行」的既有口径）。

单测面：`DataConsistencyServiceTest` **44/44**（新增 4：SQL 形状/两方向提示语/仅巡检拒绝）；
`DataConsistencyJudgeSqlIT` **12/12**（新增 3：账平含退款静默 / 资损方向报警 / 欠冻方向报警，Testcontainers 真 Postgres）。

## 2. 设计取舍留痕

- **方向语义**：冻结>入账和=流水缺钱（资损方向，V384 回填 fail-loud 同款）；冻结<入账和=冻结后未重放
  `freezePaidAmount`——**争议改单加价（`ADJUST_CHARGE`）属已知设计形态**（V384 头注释「争议改单不得改写本列」；
  `finalizeAdjustCharge` 只写流水不补冻结），佣金基数按首冻口径偏小。若日后拍板「争议加价应补冻结」，
  须在 `finalizeConfirmDispute` 补 `freezePaidAmount` 调用，该方向报警随之收敛——本判据不改写侧，只把形态浮出来。
- **修复语义**：`PAID_GROSS_MISMATCH` 不进 `isFixable`/switch 修复白名单，与 POINTS 两类同款「仅巡检」。

## 3. G9 CORS_ORIGIN 钉入（拍板：钉）

`docker-compose.uat-isolated.yml` trade-service 补 `CORS_ORIGIN: http://localhost:28080`（注释含缘由）。
验证：容器重建后带 `Origin: http://localhost:28080` 打 `/api/v2/auth/captcha` =
**200 + `Access-Control-Allow-Origin: http://localhost:28080`**（此前恒 403 `Invalid CORS request`；
curl 不带 Origin 测不出，教训同 #325 家族：静默≠有效）。

## 4. G10 存量 32 分人工归集（拍板：现在归集）

流水证据（隔离栈 member 1）：`EARN` 合计 **181** = `total_points` ✓；`USE` 合计 **32**（4 笔退款扣回×8，旧代码只减 available 漏归 used）。
归集：`UPDATE member SET used_points=32 WHERE member_id=1 AND total=181 AND available=149 AND used=0 AND expired=0`（前置条件防漂移，UPDATE 1）。
恒等式 `181=149+32+0` 成立；复跑巡检 **`POINTS_IDENTITY` 自愈 FIXED**（record 6），`POINTS_BALANCE` 保持绿，failCount 2→1。
代码侧修复（扣回补记 used 桶）已在 `959a4694` 落库，此后不再产生新坏行。

## 5. 诚实边界

- A 选项（扣回归 used）的真积分退款链路 e2e 未跑（现网无 points-paid 探针链；unit+真库归集证据已闭合口径本身）。
- 隔离栈探针用的 user_id=0/演示设备，属被测栈演示数据，无生产影响。
- CI run 39136250689（e43eaece）结果见 Actions；本地 preflight 47 门禁 + IT 12/12 已绿。

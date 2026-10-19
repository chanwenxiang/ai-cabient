# A 选项真链路 e2e：EARN → 退款 → 扣回归位（2026-10-19）

> 场地：UAT 驱动：隔离栈 `aicabinet-uat`（origin/dev 合并后代码，V393 已应用）。
> 驱动脚本：`scripts/e2e-points-clawback-refund.ps1`（场地参数化，凭据从容器 env 自解析）。
> 闭合 G10/CB-058 A 选项最后一块证据：扣回补记 `used_points` 此前只有 unit 级，本轮升级为真链路级。

## 链路（全部真 API/真 MQTT/真资金路径）

1. **fixtures**：设备 `719325384517`（UAT 模拟器容器绑定机）播 `device_sku_inventory`（SKU-DEMO-001 ×10）；
   模拟器容器以 `AICABINET_SIM_GRAVITY_JSON=[{SKU-DEMO-001,-2}]` + `SHOPPING_MS=6000` 重建（预装购物车）；
   消费者 13800138000 余额置 50000 分；设备 `refundPolicy=AUTO_REFUND`（PATCH API）。
2. **MQTT 购物成单**：session OPENING→SHOPPING→**DISPUTED**（云识别已退役：`recognition_task_id=RETIRED-*`、
   `gravity_deltas` 已记录但无端侧识别结果 ⇒ RECOGNITION_FAILED，符合台账既有口径）。
3. **运营工作台真实人工路径成单**：`POST /ops/admin/exceptions/{exception_id}/manual-resolve`
   `resolutionType=CONFIRM, items=[{SKU-DEMO-001,2}]` → `confirmDisputedItems` → `chargeOrder` →
   `finalizeOrder`（冻结毛额 + `onOrderPaid` 积分发放）→ 订单 `1792375807321493654175` **PAID 686 分**。
4. **消费者自发全额退**：`POST /api/v2/orders/{id}/refund` → `REFUNDED 686` → clawback 触发。

## 断言结果（member 1，修复后代码）

| 时点 | total | available | used | expired | 恒等式 |
|---|---|---|---|---|---|
| 前 | 181 | 149 | 32 | 0 | =0 ✓ |
| EARN 后（P=8） | 189 | 157 | 32 | 0 | =0 ✓ |
| **退款后** | **189（不动）** | **149（−8）** | **40（+8）** | 0 | **=0 ✓** |

- `usedDelta=8 == availDelta=8 == P=8`（修复点：**used 桶归集**，修复前此路径 used 恒 0）。
- `points_log`：`EARN:8` + `USE:-8` 双行在案（USE 行语义与账户列对齐）。
- 巡检复跑：**POINTS_IDENTITY / POINTS_BALANCE 零报警**（failCount=1，仅存量 `CROSS_LINK/DSP` 既有工单）。

## 备注

- EARN P=8 而非 6（686 分×rate1.00）＝该单使 total_spent 越档升至 SILVER（rate 1.20，6.86×1.2→8）——
  断言全按 delta 设计，不依赖费率。
- 云识别退役后的成单路径（RECOGNITION_FAILED → 工作台人工确认）本身即为真实运营流程，
  本 e2e 顺带覆盖了「争议确认成单」资金链（chargeOrder/freeze/分账/EARN）。
- 脚本提交：`a664c143`（feat/e2e-points-clawback → dev）。遗留：`Enter-E2eLock` 异常路径不释放句柄
  （脚本侧已补 finally 释放；lib 层改进另议）。

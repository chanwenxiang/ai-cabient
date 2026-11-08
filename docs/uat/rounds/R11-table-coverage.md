# UAT R11 · 全库表覆盖终检返正 + 商户结算单口径实证（2026-11-08）

> 用户目标：三端/后端上线前反复仔细测试（很多方面），测试计划要真实，多轮慢慢测。
> 本轮性质：文档返正 + 真库实证（非新缺陷排查，而是确认「空表」不构成资金缺口）。

## 一、结论总览

| 项 | 结果 |
|----|------|
| 实库表计数 | ✅ **179 = 140 触达 + 39 空**（旧文档 139/40，差 1） |
| 旧文档错因 | ✅ `announcement` 自相矛盾（R8 已填，旧版却列 A 类空表）⇒ 已返正 |
| 商户结算单 `merchant_settlement_bill` 空 | ✅ **设计如此（非资金缺陷）**：split 全 VOIDED 被口径排除 |
| 备份表候选 | ✅ 2 张 `_bak_*` 表**已在 V448 删除**（旧「待清理」口径不成立） |
| `order_revenue_split` 行数 | ⚠️ 旧 1 → 实库 3（全 VOIDED），文档已同步 |

## 二、全库表覆盖实测（`.tmp/r11-table-coverage.mjs`）

- 脚本：`node .tmp/r11-table-coverage.mjs`；非破坏、可重跑；基线落 `.tmp/r11-table-coverage-baseline.json`。
- 取法：`information_schema.tables` 去视图 → 一条 `UNION ALL` 逐表 `count(*)`。
- 实测输出尾部：
```
total=179 touched=140 empty=39
===== RESULT: 4 pass / 0 fail =====
```
- **行数 Top 25**（节选）：device_temperature_reading 15209、sys_dict_data 593、ops_role_permission 485、
  flyway_schema_history 446、admin_audit_log 306、ops_permission 241、system_config 118、…
- 合计跨 179 表约 **18,347 行**；39 张空表名单见 `.tmp/r11-table-coverage.out.txt`。

## 三、返正点（旧文档 `table-coverage-final.md` 三处失真）

1. **计数**：Header 「139 触达 + 40 空」⇒ 「**140 + 39**」。
2. **`announcement`**：旧 §A 列空，但 `rounds/R8-fill.md:21` 记「admin 建+publish 行=1」、`UAT-FINAL.md` §四·补 R8 节同列
   ⇒ **自相矛盾**；实库 `announcement=1` ⇒ 移出 A 类。
3. **两张 `_bak_*` 备份表**：旧 §C 记仍待清理 ⇒ 实为 **V448 已 DROP**（`pg_tables LIKE '%_bak%'` 空）；C 类改为「已结清」。

## 四、商户月度结算单为何空——**设计如此，非资金缺陷**（C′ 新增）

**现象**：定时任务 `settlement-bill` 每日 02:40 跑、执行日志 `SUCCESS`，但 `observation = 0 商户`，`merchant_settlement_bill` 恒空。

**溯源**：
- `@Scheduled(cron="0 40 2 * * *")` → `rebuildMonth(上月+本月)` → `doRebuildMonth` 按 `created_at` 在
  `[10-01, 12-01) Asia/Shanghai` 窗口取 `order_revenue_split`，**逐个 `buildRows` 聚合成 merchant_settlement_bill**
  （`MerchantSettlementBillService.java:94-130`）。
- `buildRows()` 明确**跳过 `isReversed(status)`**（`:160`）——`isReversed` 口径即 `VOIDED/REVERSED` 会被排除。
- **实库**：`order_revenue_split` 全表 **3 行、全部 `VOIDED`**：
  - `1793976576044748108876` gross 1074 / `1794017491698733162060` 250 / `1794019755167728594983` 500（均 `MCH-DEFAULT`，platform=0）
  - 均落在 2026-11-06/07（Asia/Shanghai 22:49 / 10:11 / 10:49），**在账期窗内**。
- ⇒ **排除发生在「状态」，不在「时间窗口」**：重建 2026-10/2026-11 都被 `isReversed` 踢光 → 0 商户，**与日志完全一致** ⇒ 正确。
- **「上一版记 1 行 vs 实库 3 行」**：旧文档行数快照过期，已同步为 3（全 VOIDED）——**非脏数据泄露**。

**旁证口径一致**：`merchant-daily-income-notify` 日志「昨日(2026-11-07)日结通知发送 0 商户」同为 `isReversed` 口径（`MerchantDailyIncomeNotifyScheduler.java:424` 复用 `isReversed`）。

**结论**：`merchant_settlement_bill` 在 UAT 恒空属**数据形态使然**（无有效 split），**不是 bug**；
真要出账单需一笔 **非 VOIDED/非 REVERSED 的 split**。**上线前若要实证该报表**，建议补一笔未发生退款的实单或调账。

## 五、修复/核对矩阵

| 对象 | 旧口径 | R11 实测 | 处置 |
|------|--------|----------|------|
| `docs/uat/table-coverage-final.md` Header | 139/40 | 140/39 | 已返正 |
| `announcement` | A 类空 | =1 | 移出 A 类 |
| 两张 `_bak_*` | C 类待清理 | 已 DROP | C 类「已结清」 |
| `order_revenue_split` | 1 行 | 3 行全 VOIDED | 已同步（§C′） |
| `merchant_settlement_bill` | 空 | 空（设计如此） | 记 C′，判非缺陷 |

## 六、遗留与建议

- **未验真**：`merchant_settlement_bill` 的确切账期/金额正确性仍**未实跑**（需一笔非 VOIDED split）。
  若要上线前实测该报表金额，建议补一笔无退款的实单后重跑 `settlement-bill`。
- **C′ 口径复核**：`buildRows` 的等待 `settled/pending/failed` 三分类与 `isReversed` 的边界已静态核对；
  若对「VOIDED 是否应入账单」有异议，属业务口径修正项，待用户拍板（不自动改码）。
- 本轮未触及任何业务代码，无新缺陷带来。

# 货道遗留数据运维清单（2026-11-05，批 3b 后续）

> 范围：**只清数据，不改代码、不删列**。
> 「列不删」是 2026-10-28 已拍板项（`device_slot` 表与 `device_sku_lot.slot_id` 保留）；
> 「生产必须没有货道面」由第八刀的**不变量门禁**保证（`check:stocktake-chain` E 段），
> 不靠本清单。本清单只处理 **dev/UAT 里已经长出来的存量行**。
>
> 🔴 生产档实测无需执行：全新库跑完 V1→V445 后 `device_slot` **0 行**（第八刀/第十一刀两次实测一致），
> 且历史清除迁移 V344 在位。⇒ 下面所有动作**只在 dev/UAT 有意义**。

---

## 0. 现在的真实状态（2026-11-05 实测，dev 库 `aicabinet`）

```sql
-- 判据（只读）
SELECT count(*) AS device_slot_rows FROM device_slot;
SELECT d.device_id, d.device_name, coalesce(j.status, '未登记将邑') AS jiangyi_status, count(s.device_id) AS slots
  FROM device_slot s
  JOIN device_info d ON d.device_id = s.device_id
  LEFT JOIN jiangyi_device j ON j.device_id = d.device_id
 GROUP BY d.device_id, d.device_name, j.status;
SELECT count(*) AS lot_with_slot_id FROM device_sku_lot WHERE slot_id IS NOT NULL;
SELECT id, check_type, status FROM data_consistency_record
 WHERE check_type LIKE 'SLOT_%' AND status <> 'FIXED';
```

实测结果：

| 项 | 值 | 归属 |
|---|---|---|
| `device_slot` 行 | **16** | `CAB-OTHER`「门店二号柜」8 行（**未登记将邑**）+ `500909625160`「演示智能柜」8 行（将邑 **RETIRED**） |
| `device_sku_lot.slot_id` 非空 | **3** | 全部属于 `500909625160`（将邑 RETIRED） |
| SLOT_* 未结案记录 | **2** | `SLOT_PHYSICAL` FAIL、`SLOT_SKU_MISMATCH` FAIL |
| 引用悬空 | 0 | `device_slot` 里没有找不到 `device_info` 的行 |

🔴 本表是**执行前快照**。§C 那 2 条未结案已按清单在 dev 结案（见 §C.1，复核值 0）；
A 段那 16 行 `device_slot` 与 B 段 3 行 `lot.slot_id` **未执行**（等你或运维点头再动，属删数据）。

⚠️ 这两台柜**都不是** `BOUND`，按代码判据 `isDoorCabinet()`（＝`jiangyi_device.status='BOUND'`，
见 `JiangyiDeviceDirectory.isJiangyi:45-59`）都算「非开门柜」，所以**仍会被货道读面消费**
（补货建议 `listShortageRows`、运营设备详情 `DeviceDetailDto.slots`…）。
也就是说：**清空这 16 行会让这两台演示柜的货道视图变空**，但不会影响任何 BOUND 门柜（本就恒空）。

---

## A. 清 `device_slot` 16 行（可选，风险低）

```sql
BEGIN;
-- 先看要删什么（务必肉眼过一遍柜号）
SELECT device_id, slot_code, assigned_sku_id, enabled, last_physical_qty
  FROM device_slot
 WHERE device_id IN ('CAB-OTHER', '500909625160')
 ORDER BY device_id, slot_code;

DELETE FROM device_slot WHERE device_id IN ('CAB-OTHER', '500909625160');
-- 复核：期望 0
SELECT count(*) FROM device_slot;
COMMIT;
```

- 影响面：运营设备详情的 `slots` 数组、补货建议行、货道容量/FEFO 相关算式对这两台柜变空。
  钱与库存真相**不依赖货道**（`selectSumBookQtyBySlot` 带 `slot_id IS NOT NULL`；
  真柜 lot 的 `slot_id` 为 NULL），所以不会改变任何订单/结算/余额数字。
- 回滚：删的是演示柜配置，无业务流水；要恢复只能靠 `DemoDataService` 重新播种（不建议）。
  🔴 执行前先 `pg_dump -t device_slot`。

## B. `device_sku_lot.slot_id` 3 行置 NULL（可选）

```sql
BEGIN;
SELECT id, device_id, sku_id, quantity, slot_id FROM device_sku_lot WHERE slot_id IS NOT NULL;
UPDATE device_sku_lot SET slot_id = NULL WHERE slot_id IS NOT NULL AND device_id = '500909625160';
SELECT count(*) FROM device_sku_lot WHERE slot_id IS NOT NULL;  -- 期望 0
COMMIT;
```

- 与真柜口径一致（`EG943322621391` 的 lot 行 `slot_id` 全 NULL）⇒ 这一步是**把演示数据对齐生产口径**。
- 不做也不影响：货道相关算式对 RETIRED 柜仍会跑，但第 A 步清掉 `device_slot` 后自然无行可匹配。

## C. 那 2 条 SLOT_* FAIL 记录 —— **应用内关不掉，需要你拍板**（✅ 已按第 2 条在 dev 执行）

- 现状（逐行读过代码，非推测）：`POST /api/v2/ops/admin/consistency/{id}/fix` 是唯一能把记录
  改成 `FIXED` 的入口（`DataConsistencyService.doFixInconsistencyDetailed:1363-1399`），
  而 `applyFix:1418-1445` 对 `SLOT_PHYSICAL` / `SLOT_SKU_MISMATCH` 落到 `default` 分支
  ⇒ 返回「不支持自动修复的类型」，`fixed=false` ⇒ **状态不变、永远挂着 FAIL**。
  巡检侧第十一刀后也不再复查这两类（不会自愈、不会产生新行）。
- 于是只有三条路，请选一条：
  1. **只留痕不清**：什么都不做。代价：`/failures` 列表里长期有 2 条与现行口径无关的红点，
     运营会反复问「这两条怎么修不了」。
  2. **运维 SQL 结案**（一次性，改状态并追加说明，保留原始证据文本）：

     ```sql
     BEGIN;
     UPDATE data_consistency_record
        SET status = 'FIXED',
            fixed_at = now(),
            error_message = error_message
              || E'\n[MANUAL-CLOSE 2026-11-05] SLOT_* 巡检已随货道概念停跑（第十一刀 A 段），'
                 '本条为历史遗留，无自动修复路径，运维结案留痕'
      WHERE check_type IN ('SLOT_PHYSICAL', 'SLOT_SKU_MISMATCH', 'SLOT_CAPACITY')
        AND status <> 'FIXED';
     SELECT count(*) FROM data_consistency_record WHERE check_type LIKE 'SLOT_%' AND status <> 'FIXED';
     COMMIT;
     ```
     ⚠️ 直改库**不会写审计日志**（`CONSISTENCY_FIX` 审计只在 `/fix` 路径里追加），
     这是它与 1/3 的实质差别。
  3. **补一个能力**：给后台加「人工结案（不改业务数据）」动作——只置状态 + 写审计，
     不复用 `/fix` 的自动修复语义。这是产品级改动（要权限码、UI、测试），建议单独排一轮。
- 我的建议：**dev 走第 2 条**（演示数据，无审计价值），**生产上线前不碰**（生产根本不会有这类行）；
  若以后仍会产生命中率高的「只巡检不可修」类型，再考虑第 3 条。

### C.1 执行记录（2026-11-05 13:14:56 +08，dev 库 `aicabinet`，用户选第 2 条）

| 项 | 值 |
|---|---|
| 命中行 | `id=152 SLOT_PHYSICAL`、`id=156 SLOT_SKU_MISMATCH`（`UPDATE 2`，`SLOT_CAPACITY` 无未结案行） |
| 改前整值备份 | `.tmp/slot-fails-before-1105.txt`（md5：152=`e71a…4d5e`、156=`f97d…cf0c`）+ `.tmp/slot-fails-rawmsg-before-1105.txt` |
| 改后 | `status=FIXED`、`fixed_at=2026-11-05 05:14:56.718911+00`、`msg_len` 16→93 / 41→118（**追加**留痕，原文「近30天盘点 2 ≠ 在架批 9」等未被覆盖，与 MA-116 的「不许整值覆盖证据」同语义） |
| 复核 | `check_type LIKE 'SLOT_%' AND status<>'FIXED'` = **0**；`SLOT_% AND status='FIXED'` = 4（A 段前 2 条 + 本次 2 条）；`check:stocktake-chain` 判据不受影响 |
| 执行脚本 | `.tmp/close-slot-fails-1105.sql`——事务内带两道硬闸（中文留痕必须 2 行都命中、不得残留未结案），任一不过即 ROLLBACK，不会留半套 |
| 回滚脚本 | `.tmp/slot-fails-restore-1105.sql`——还原 status/fixed_at/整段 error_message，并用改前 **md5** 自证还原到位 |
| ⚠️ 未做的部分 | 本路径**不写 `ops_audit_log`**（`CONSISTENCY_FIX` 审计只在 `/fix` 里追加）。若以后要在审计台可见，只能走第 3 条（补「人工结案」动作）。 |
| 顺带发现 | dev 整表 `status<>'FIXED'` 仍有 **19** 条其他类型 FAIL，不属货道范围，未处理。 |

## D. 不建议做的事

- ❌ 删 `device_slot` / `device_sku_lot.slot_id` **列**：已拍板保留。
- ❌ 删 `sys_dict_data` 里 `SLOT_*` / `SLOT_DISCREPANCY` 的 INACTIVE 行：它们是历史行**唯一译名来源**
  （删了后台显示「未知类型」），第十一刀/ A 段刻意只置 INACTIVE。
- ❌ 靠删数据来「验证生产没有货道面」：生产由第八刀的不变量门禁 + V344 保证，与本清单无关。

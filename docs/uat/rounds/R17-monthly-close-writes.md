# R17 — 月结写态 approve / disposeLine / updateLine（真 UAT 库 / 真 API）

> **轮次**：R17（A 类真库）。**日期**：2026-11-08。**结果**：**37 PASS / 0 FAIL，exit 0，0 残留**（脚本内部断言 + 外部全 179 表 count(*) 双核，仅温度遥测漂移）。
> **驱动**：`.tmp/r17-monthly-close-writes.mjs`。**证据**：`.tmp/r17-run2.log`、`.tmp/r17-after2.txt`。

## 1. 被测面（对着源码读死）

`OpsWarehouseController`（`/api/v2/ops/admin`，权限 `ops:warehouse:edit`）：
- `POST /warehouse/close-sheets/{closeId}/approve` → `WarehouseMonthlyCloseSheetService.approve`
- `POST /warehouse/close-sheets/{closeId}/lines/{lineId}/disposition`（body `{disposition,responsibleParty,claimNo}`）→ `disposeLine`
- `PATCH /warehouse/close-sheets/{closeId}/lines/{lineId}`（body `{countedQty,rockLossQty,feeDeductQty,lineRemark}`）→ `updateLine`

**状态机**（`WarehouseMonthlyCloseSheetService`）：
- `approve`：仅 DRAFT → APPROVED，置 approvedBy/approvedByName/approvedAt，锁单。
- `updateLine`：仅 DRAFT 可改；改 countedQty→重算 gap=counted−expected、金额=|gap|×单价；置 editedFlag=true；单头 loss/surplus 重算。
- `disposeLine`：仅 APPROVED 且行 gapQty≠0 且 gapDisposition=PENDING；CLAIM→`doClaim` 建 `inventory_write_off`（SHINKAGE、责任方、索赔额）并回写 claim_write_off_id；NORMAL_LOSS/SURPLUS 仅置状态；行锁 `findByIdForUpdate`（MA-103 防双击双挂索赔）；处置不可逆。

## 2. 基线现状与造数

实库 3 张 2026-10 月结单（close 1/2/3）均 **DRAFT、line_count=0**（`warehouse_monthly_close_line`=0 行）。**0 行无支持 update/dispose**（需 lineId）。故用 close_id=1（仓 326765247551）造一条**最小盘亏行**（SKU-WATER-001，expected 10 / counted 8 / gap −2 / 单价 100）测三接口；收口删行+还原单头+删索赔台账，精确可逆。

## 3. 执行与断言（37 PASS / 0 FAIL）

- **updateLine（DRAFT）**：countedQty:7 → gap −3、金额 300、edited_flag=true、行备注写入；单头 loss_qty=3 / loss_amount=300 重算命中。负控：countedQty:−1 → 400。
- **负控：DRAFT 单不可处置** → 409「审批后才可处置差异」。
- **approve**：DRAFT→APPROVED，approved_by=100000001、approved_by_name=运营测试、approved_at 回填。负控：重复审批 → 409；审批后改行 → 409。
- **disposeLine CLAIM（APPROVED，gap<0）**：建 `inventory_write_off`（数量=3、reason_category=SHRINKAGE、责任方、索赔单号、索赔额=300、仓=326765247551），行处置=CLAIM、claim_write_off_id 回填；`inventory_write_off` +1。负控：重复处置 → 409（不可逆）；CLAIM 缺责任方 → 400。

## 4. 收口与 0 残留

清理：删索赔台账（write_off_id）→ 删造的行 → 单头按轮首快照逐字段还原（status→DRAFT、清 approved_*、loss/surplus 归 0）。**脚本内部断言**：单头逐字段复原、close_line 行数复原、inventory_write_off 行数复原、audit max 复原，全 PASS。**外部独立核对**：轮首/轮末全 179 表 count(*) diff，仅 `device_temperature_reading` 漂移。

## 5. 观察

- 3 张 DRAFT 单**均 0 行**（2026-10 窗口无流水）⇒ update/dispose 在真实存量上无对象，本轮造最小行覆盖。若后续账期有随流水生成的单，可直接在其上复跑本驱动（去掉造行段）。
- 未发现产品缺陷；三接口状态机与留痕（索赔台账/审批人/编辑标记）均按源码预期工作。
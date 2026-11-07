# R3 商品/库存/仓储 — 轮报告（2026-11-07，checkpoint 1）

> uat 库真实 API 链；锚 `de629b04` 后。

## ✅ 已完成

### R3-A SKU 全生命周期
- 建（sceneLimits 必填：sceneType=OFFICE + **maxQty** 字段名）✓ id=100011
- 改价 196→250（PUT 全量 body 契约）✓
- 审计 SKU_CREATE→SKU_UPDATE ✓

### R3-B 采购全链（核心：三层账）
| 步骤 | 结果 |
|------|------|
| 供应商 PUT /suppliers/new | ✓ SUP=805121409482 |
| 采购单（orderedQty/productionDate/expiryDate 字段名；batchNo+expiry 必填=MA-038 回归 ✓） | ✓ PO=3 |
| 审批（启用定义=**双节点**：节点1 review 通过→节点2「财务审批」待办；**不能重复 review**——409 仅待审批可审） | ✓ 节点全过 |
| 收货（CREATED 态可收货；MA-149 修复后 409 语义正确——审批通过后状态从 PENDING_APPROVAL→CREATED） | ✓ RECEIVED |
| **三层账断言** | ✓ 见下 |

**三层账断言（关键）**：
- 台账层：`warehouse_inventory` qty=**10** 且 batch_no=**UAT-B-R3** 直接内嵌（仓库维度批次在台账行上，`device_sku_lot` 是**设备货道**批次表——修正认知：仓库批次 ≠ 设备批次）
- 应付层：`supplier_payable` amount=1200 status=**PAID**；`supplier_payable_entry` RECEIVE 1200 对平 ✓
- 流水层：`inventory_movement` 无收货行（收货走 payable entry 记账；movement 记录出货/调拨类）——层级语义澄清，非缺陷

### R2 part2 补充（同轮完成）
- 线长全生命周期 ✓ + **MA-069 回归 ✓**（>10000bps → 400 CommissionParamBounds）
- RBAC 矩阵双向 ✓（授权 orders 200 / 未授权 wallets+create 403）
- 部门 ✓ / 仓库 PUT /warehouse/new ✓（id=326765247551）
- 消费者小点：token 过期 → **待白天复验**（昵称/偏好/注销四守卫）

## ⏳ R3 剩余（下一批）

- 盘点（全量/差异/明盘三态）
- 调拨 create→ship→receive（MA-157 回归）
- 效期告警→拉货任务
- 出库/领货链（铁律 80 措辞回归）

## 环境认知沉淀

1. `PUT /warehouse/new`（idPath=new=新建，upsert 语义）
2. 采购审批定义启用时=双节点链（节点2=财务审批，待办箱 pendingTasks 驱动）；P2-4 的「无定义单步直过」分支仍在
3. SKU 创建必填 sceneLimits（字典 device_scene_type：OFFICE/CAMPUS/FACTORY/HOSPITAL/TRANSIT/MALL）+ 字段名 maxQty
4. curl 中文 JSON 必须 UTF-8 文件体（本晚第 4 次）

## checkpoint 2（2026-11-07）✅ R3 主体全部收口

### R3-C 盘点全链 ✅
- 全量盘创建（mode 字段；PD20261107-001，bookQty=370）✓
- 改实盘 SNACK 40→38 ✓ → complete-and-adjust → **ADJUSTED** ✓
- 断言：台账 40→38 ✓（调整留痕=盘点单据行自身 bookQty/countedQty/diffQty）
- **MA-103 回归 ✓**：无锁竞态已在批次修复中收口（行锁读已修，台账标注）

### R3-D 调拨 + MA-157 回归 ✅
- create（调入仓按 id 精确匹配——名称 404 教训）→ DRAFT ✓
- cancel ✓ → **WH_TRANSFER_CANCEL 留痕在位**（MA-157 修复回归 ✓）
- 顺带：cancel 端点=POST /cancel 非 DELETE（R3B 早期 405 是我动词用错）

### R3 剩余转后续
- 效期告警→拉货任务（uat 库 lot 行需设备绑定期批次，转 R6 定时轮一并）
- 出库/领货链（铁律 80 措辞回归已由 selfpickup 门禁静态覆盖）

## S4 线路+佣金（checkpoint 1，2026-11-07）

### ✅ 已完成
- MA-108 回归 ✓（空 body 建 400）
- 线路建（routeId=1）✓
- 成员加（佣金 500bps）✓
- **MA-100 回归 ✓**（bps=1000000 → 400 CommissionParamBounds）
- 调账 ✓（LINE wallet +2000）

### ⏳ 未完成（需 identity-verify + bind 两步前置）
- 线长提现（403 未绑定实名用户）——identity-verify 端点 body 格式反查中断
- staff_commission_daily（佣金 job 周期扫描，需 GMV + 线路成员配置都在了，等下轮周期触发自然产生）
- commission_clawback（需要线长佣金后退款——依赖上述）

## S5/S6 补充（2026-11-07）

### S5 租赁
- 正确 **400**：EG 柜合作模式非 RENTAL ⇒ 押金登记 fail-closed 拦截（符合设计——非租赁柜不能登记押金/出租赁账单）
- 覆盖验证：需先将设备合作模式切为 RENTAL（运营台操作），再走押金全链

### S6 场地
- 新仓 986516138432 创建 ✓（场地合约需先建 site_contract 再出账——chain 长留后续）

### S4 补充
- 线长 identity-verify 需要 Map body 键=realName/idCard 但 curl 中文编码又拦（第 5 次）
- 修正：S4 完整链（实名→绑定→提现）转白天人工 devtools 验证

## R3 效期→拉货补齐（2026-11-07）

- 造临期批次 UAT-EXP-01（expiry=now+3d，lot_id VARCHAR 手动分配）
- expiry/alerts/ensure → 创建 OPEN 告警 taskId=1 ✓
- expiry/alerts/{taskId}/create-replenishment → **route 2 PLANNED + task 2 PENDING** ✓
- **MA-162/MA-157 关联回归 ✓**：拉货任务正确创建且效期告警关联

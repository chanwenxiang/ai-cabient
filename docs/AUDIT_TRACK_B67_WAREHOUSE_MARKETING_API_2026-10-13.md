# Track B-6/B-7 分报告 · 仓储补货域 + 营销会员域接口契约审计（2026-10-13）

> 范围：`OpsReplenishmentController`（~30 端点）、`OpsWarehouseController`、`WarehouseTransferController`、`ReplenishmentReportController`、`CouponController`、`MarketingController`、`MemberController`、`AdminGrowthController` 及服务族。
> 方法：只读子代理初审（含其对自身初判的二次核验，两处初判 P0 复核降级为 F5/P2）+ **主会话亲验**（✨ 标记者已读码复核）。
> 台账 ID：子代理原编号 F1~F14/Q1~Q6，正式号 AU-110~123 / DC-20~24（重映射）。
> 路径缩写：T = `services/trade-service/src/main/java/com/aicabinet/trade/`

## 发现

### P1

| 台账ID | 原号 | 位置 | 发现 | 证据 | 处置 |
|--------|------|------|------|------|------|
| **AU-110** ✨ | F1 | `T service/CouponService.java:237-265`（入口 `CouponController:37-44` `POST /api/v2/coupons/use`） | 手动核销券**不校验 DEVICE/MERCHANT 范围**：doUseCoupon 仅查定义状态/满减/折扣（:243-247），body deviceId 只落库不校验（:252）；`withinScope` 全文件仅自动选券 `evaluateCoupon:479` 一处调用（主会话 grep 实证）→ 定向券可在任意柜机套现=活动资损 | 主会话复核属实 | 待修(必修)：doUseCoupon 以订单 deviceId 调 `withinScope(def, …)`，不过则 400 |
| **AU-111** ✨ | F2 | `T service/ReplenishmentService.java:1094-1119` | `cancelEmptyTask` 裸 `findById`（:1096），无 runWithTaskLock、无 findByIdForUpdate，ReplenishmentTask 无 @Version→与 submitTaskLines/completeTask 竞态：明细已上架加库存后被并发置 CANCELLED 并撤销出库（:1105-1107），库存/在途双失真。对照同文件 completeTask(:799+804)/checkIn(:584+588)/cancelEmptyRoute(:1127+1131) 全持锁 | 主会话复核属实 | 待修(必修)：包 `runWithTaskLock` + `requireTaskForUpdate`（模式=AU-007 同族） |
| **AU-112** ✨ | F3 | `ReplenishmentService`（listInventory :199-224、upsertInventory :230-263）、`InventoryOpsService:76-87,140-170,224-274`、`OpsReplenishmentAdminService`（全文件） | 补货/设备库存域**0 处设备数据权限范围**（主会话 grep：三文件 merchantScopeService 命中 0，对照同域 `DeviceSlotService` 13 处）→ 挂线路/商户的 replenisher（V118 授码）可读写任意商户设备库存（PUT /inventory、write-off、stocktakeAdjust、跨商户 accept/reject） | 主会话 grep 复核属实 | **等级随 DC-20 拍板**：生产若存在 merchant/route 挂接形态则 P1 必修（OpsReplenishmentAdminService 设备入参统一 requireDeviceAccess），否则 P2 文档化豁免 |

### P2（AU-113~120，原 F4~F11）

| 台账ID | 原号 | 位置 | 发现（摘要） |
|--------|------|------|-------------|
| AU-113 | F4 | `config/GlobalExceptionHandler.java:30-39` + 全域 70+ 处锁包装 | 内部异常 `e.getMessage()` 经 500 reason 外泄（SQL/表名/NPE 摘要）；域内命中 13 文件（CouponService:885、ReplenishmentService:1744/1759 等）。**集中修 handler 一处即全域收口**（与 AU-094 同族合并修） |
| AU-114 | F5 | `OpsWarehouseController:106-112`；`OpsReplenishmentController:429-432` | 两写/读端点控制器缺 `@RequiresPermissions`，单层防线在 service（现无洞，重构剥离即成实洞） |
| AU-115 | F6 | `OpsReplenishmentController:59-63` + `ReplenishmentService:254-259` + `DeviceInventoryDto:5` | `PUT /inventory` 无 @Valid 零约束：quantity/capacity/lowThreshold 可负、deviceId/skuId 不查存在→负库存/脏复合键行 |
| AU-116 | F7 | `PointsRedeemService:166-181` | adminUpsert 无值域校验：负 pointsCost 使兑换检查恒过（:134-137）、兑换**反向加积分**（:144-145）（仅管理员可触发） |
| AU-117 | F8 | `MemberLevelAdminService:86-97` | 等级规则 upsert 无范围/连贯校验：90% 折扣或负倍率即全局生效 |
| AU-118 | F9 | `WriteOffClaimLedgerService:62` + 入口 `OpsReplenishmentController:390-397` | 索赔台账 `new Page<>(page,size)` 无钳制，size=100000 整表拉取（含金额） |
| AU-119 | F10 | `InventoryOpsService:108,155` | `unitCost * quantity` int 乘法溢出为负 costCents（WriteOffRequest quantity 仅 @Min(1) 无上界），先于 ≥0 守卫落账 |
| AU-120 | F11 | `PromotionService:88-95,144-157,39-54` | 活动生命周期无守卫：updateStatus 任意态直写无锁；create 不校验时间窗口；`budget > 0` 才设闸⇒**负预算=静默无限预算** |

### P3（AU-121~123，原 F12~F14）

| 台账ID | 原号 | 位置 | 发现（摘要） |
|--------|------|------|-------------|
| AU-121 | F12 | common dto 6 个 Request record + `CouponService:208-209` | @Valid 空挂（record 无 bean 约束）；batchIssue userIds 无上限且逐单事务，中途异常部分成功不回滚 |
| AU-122 | F13 | `OpsReplenishmentController:388,403` | GET（索赔台账读）要求 `ops:replenishment:edit` 写码——权限语义错位 |
| AU-123 | F14 | `DeviceSlotService:757-758,1214-1224` | slotType 自由文本入库无枚举白名单；不查 maxLevel≥parLevel |

## 干净面声明（子代理报告，抽查一致）

- **券超发/领取并发**：issueToUser 锁+def FOR UPDATE+maxIssueCount 闸+预算预留（`CouponService:167-205`）；claimCampaign 限流+锁+FOR UPDATE+窗口+每人限。
- **券重复核销/订单归属**：per-coupon 锁+FOR UPDATE、归属、订单归属+状态集、DISPUTED 拒、双用查（:267-311）——除 AU-110 范围旁路外干净。
- **会员积分**：earnPoints 幂等+points≤0 跳过；退款扣回触底不为负；兑换双锁+条件 UPDATE 抢库存 SQL。
- **补货提交/完成**：submitTaskLines/completeTask/check-in 全锁+FOR UPDATE+终态 fail-closed+地理围栏 fail-closed。
- **仓储侧**：盘点/货位/调拨全链锁+FOR UPDATE+expectedVersion(400/409)+过账重算 delta+出库防负。
- **AU-040 同族**：商户侧补货证据链 task→device→requireDevicePack 通过。
- **DevMock**：`aicabinet.security.mock-enabled` 条件装配，生产关闭（与 B-9 结论一致）。
- **权限码种子**：8 控制器 31 码全部有迁移种子，后端两处一致；**前端菜单映射未核验**（Q6 遗留，转 C-admin RBAC 矩阵实测覆盖）。

## 存疑 → 拍板清单

- **DC-20**（Q1）：生产 replenisher/线长是否存在 merchant/route 挂接（scope 受限）形态？决定 AU-112 等级与修法。
- **DC-21**（Q2）：仓库面（warehouse/bins/close-sheets/transfers）完全不挂设备 scope——仓库实体是否不属商户资产。
- **DC-22**（Q3）：checkInTask 丢弃 operatorId、completeTask 不校验 assignee——任何 edit 权限运营可代签到/代完成，绩效与视频证据归属链错位是否接受。
- **DC-23**（Q4）：IN_PROGRESS 盘点单不可取消（仅 DRAFT 可）——现场中断处理路径。
- **DC-24**（Q5）：V118 授 replenisher `ops:warehouse:list`（补货员翻全仓库存）——最小权限口径。

## 建议修复顺序（子代理 + 主会话认可）

AU-110 → AU-111 → AU-112（随 DC-20）→ AU-116/120 → AU-113（handler 一处集中修）→ 其余 P2/P3。

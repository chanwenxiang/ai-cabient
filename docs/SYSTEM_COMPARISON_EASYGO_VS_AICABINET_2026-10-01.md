# 新旧系统全面对比：前海易购弹簧柜（easygo）→ AI 开门柜（本仓）

> **日期**：2026-10-01
> **背景**：本仓（ai-cabinet）是前海易购产品线从「弹簧货道柜」到「AI 开门柜」的演进版本；旧代码 `D:\ideaCode\easygo`（ego-automat + mis-server/mis-web 等）是同一家公司已上线的弹簧柜系统。本文对比两条产品线的设计，回答「哪些该学、哪些该简化、哪些坚决不抄」。
> **方法**：双代码库只读探索 + 关键论断逐条源码抽查（均给 `文件:行号` 证据，非文档自报）。
> **结构**：Part I 仓储三域（采购入库/盘点/补货）→ Part II 核心业务域（订单支付争议/钱包分账/营销/设备/任务对账）→ Part III 客户端面（含双小程序）→ Part IV 汇总建议 → Part V 证据索引。

---

## 0. 结论速览（TL;DR）

**总判断**：旧系统「简单」的本质是把控制转嫁给人（月台账人工对账、补货无单据、无批次效期、无对账体系、硬编码运维）；新系统「麻烦」的大部分是把控制做进了代码（批次 FEFO、部分收货、置信度三道闸、预授权、对账+一致性巡检）。**演进方向是对的，不建议照搬旧系统；新系统自身有 3 处真冗余（仓储）+ 4 处收尾项（核心域/客户端）值得定向处理。**

| 板块 | 一句话结论 |
|------|-----------|
| **仓储三域** | 要改，但方向是「删冗余，不删控制」：P1 批次后置收货 / 柜机盘点口径合一 / 在途单向化；P2 审批默认单步等 4 项（§5） |
| **核心业务域** | 新系统整体是旧系统的升级而非偏离；旧系统最值钱的「FALL 出货失败三层兜底」思想已被「争议先免单+超时转人工」等价覆盖；自身冗余：商户/线长双套同构钱包栈、营销三轨并行（§6–§10） |
| **客户端（小程序）** | 旧系统 6 个触点碎片化（M3/M8/M9 三套支付入口、前端硬编码 MD5 secret）；新双小程序架构明显更干净（Endpoints 收口+门禁+soft-fallback）；收尾 2 项：首页 3641 行上帝页、tracker 滞后（§11–§13） |
| **一个意外发现** | 旧系统里已有**第一代开门柜（M8 模块）**：开门→关门 syncStock 按差值出单→余额扫码付——本仓的会话/结算模型正是它的正规化演进，血缘论证见 §6.4 |

**明确不抄清单**（详见 §14.2）：19 种支付渠道枚举、硬编码运维（直营 ID/补货员名单/前端 MD5 secret）、僵尸注释任务、无对账（downloadbill 零引用）、三套设备 API 并存、补货直接覆盖库存、月台账人工盘。

---

## 1. 系统判定与调研范围

### 1.1 旧系统（D:\ideaCode\easygo）：一条产品线，两代后台，6 个触点

| 触点 | 角色 | 判定证据 |
|------|------|----------|
| `ego-automat` | 弹簧柜主系统：设备 API（M3/M8/M9 三代）+ 老管理后台 + 运营员 App 后端（JPA） | 模块清单；鉴权拦截器 `ego-automat-operation\...\OptUserAuthInterceptor.java:29-53` |
| `mis-server` / `mis-web` | 新一代运营后台（RuoYi-Vue-Plus），仓配/报表/佣金/飞书入口 | `mis-server\README.md`；`mis-web\src\views\operation\` |
| `ego-automat-operation` | 运营员/补货员 App 的后端（WAR） | 端点组：补货/开锁/改价/采购/账单/提现/维修/电子合同/子账号/自升级 |
| `m9managepro` | M9 生态 web 管理后台（品牌商/总监/经理/店长） | `webmanage\...\DirectorController/ManagerController/brandAdminController` |
| `dinngdang-wx` | **dvm 线（信箱柜/公益/信义卡/彩票/加盟）消费者小程序（mpvue），与弹簧柜无关** | `src/config.js:7-72`（`/donate/*`、`/letterbox/*`、`/dream/*`）；后端落 `dvm\dvm-wx\...\{DonateCtrl,LetterBoxCtrl,BleBlessingGift*Ctrl}` |
| `dvm` | 另一代机器平台后端 | 包结构 `letterbox/donate/dream/xinyicard` |

> 注：另有旁挂 `feishu-purchase-order`（采购申请外移飞书多维表格）——旁证旧 MIS 采购流后期已部分弃用。

### 1.2 新系统（本仓）

领域大脑 `services/trade-service`（222 个 Service，设备薄桥 `device-service`，vision-service FastAPI）；三端：`admin-vue`（运营后台）+ `consumer-mp`（消费者小程序）+ `merchant-mp`（商户小程序），共享层 `packages/shared-*`。

### 1.3 证据纪律

本文所有「现状」均经源码核实；两个会话探索代理产出后，主会话对两端各 5+ 条承重论断做了行级抽查复核（状态机枚举、自动退款任务、对账零引用、置信度阈值、XXL 任务数、双钱包同构方法、首页行数等），全部吻合。

---

# Part I 仓储三域（采购入库 / 盘点 / 补货）

## 2. 旧系统三域现状（含证据）

### 2.1 采购与入库：两级单据，一次性全额入库

- **申请单** `ego_deliver_order_purchase`（order_status 0 等待审核/1 通过/2 不通过）：分仓运营端提交 `OptorMachineController.java:441-459` → `OptStockRecordDailyService.commitPurchaseOrder`（`ego-automat-domain\...\OptStockRecordDailyService.java:595-652`）。
- **厂商订单** `ego_deliver_order` 状态机 `1等待审核→2等待配送→3等待收货→4已收货→5已结算 / 6已退货 / 7审核不通过`（`mis-manage\...\DeliverOrder.java:121-123`）；申请审核通过时自动 new 一张厂商订单（`DeliverOrderPurchaseServiceImpl.java:91-178`）。
- **入库**：状态到 4/5 且 `order_warehousing=0` → `warehousingUpdate` 把**下单支数**一次性累进月台账 `stock_order_input`（`DeliverOrderServiceImpl.java:149-182`）。**无部分入库**（实际到货数 `order_num_act` 仅展示不入账，`:105-119`）；删除已入库单反向冲回（`:200-212`）。
- **批次/效期**：全库无 batch/lot 字段；仅采购单上 `manufacture_time` + `expiration_date`（**天数**，不是日期，`DeliverOrder.java:137-143`），且效期从不参与任何计算。
- **应付**：无独立账表，靠「月结」字符串 + `order_settle_month` 人工勾账；`receive_pic` 有字段无上传实现。

### 2.2 盘点：无盘点单实体，月台账人工覆盖

- **载体**是月台账行 `ego_opt_stock_record_month`（唯一键 = 账期月 yyyyMM + 分仓手机号 + SKU；账期 26 日~25 日，`DeliverOrderServiceImpl.stockMonth :136-144`）。
- **公式化字段**：`期初 + 采购入库 + 调入 − 调出 − 机器补货 − 摇机损耗 − 过期损耗 − 客情 − 抵扣 = 应有 stock_proper_num`（`EgoOptStockRecordMonth.java:70-170`）；同一公式在 `DeliverOrderServiceImpl.java:162`、`EgoOptStockRecordMonthDetailServiceImpl.java:218/273/332` 三处一致。
- **动账明细** `ego_opt_stock_record_month_detail` 的 stock_type 正负号枚举：`1 盘点 / 2 采购入库 / 3 调入 / −4 调出 / −5 机器补货 / −6 摇机损耗 / −7 过期损耗 / −8 客情 / −9 订单退货`（`EgoOptStockRecordMonthDetail.java:64`），支持 Excel 导入。
- **实盘录入**：运营端 `/opt/v2/machine/warehouseStock/act` → native `update ... set stock_act_num=...`（`OptStockOptorRecordRepository.java:71-74`）。**盘盈盘亏不单独过账、不回写余额**；无盘点期间冻结禁售。

### 2.3 补货：无单据无状态机，直接覆盖货道库存

- **入口 A（运营员 App/PDA）**：`PUT /oper/v1/machine/aisle/stock`（`MachineOperController.java:126-133`）→ `MachineAisleInfoService.updateStockWithRecord`（`MachineAisleInfoService.java:216-265`）：**前端传目标库存，服务端差值 = 传入 − 现有**，逐货道覆盖写（read-modify-write，无锁），按 SKU 汇总落补货流水 `ego_opt_stock_operator_record`。
- **入口 B（M8 机器上报）**：`/m8/v1/machine/asiles/syncStock` → 同一个 `updateStockWithRecord`（补货与取货共用差异记账，`M8MachineService.java:230-262`）。
- **缺货预警**：出货 FALL 后 CAS 扣减防负数（`MachineOrderService.java:117-143`），扣后推送运营员（`:395-423`）；**一键补满** `aKeyFill`（`:444-453`）。
- **绩效闭环**：定时任务从补货流水聚合日统计→推飞书→分成结算（`mis-quartz\...\RyTask.java:190-345`，**补货员 ID 硬编码 13 人** `:284`）。

### 2.4 库存记账模型

机器库存 = 货道行 `ego_machine_aisle_info.goods_stock`；仓库库存**没有余额表**（寄存月台账发生额）；流水三套互不相通（台账明细/补货流水/订单明细）；**无批次模型**；多处支付服务直接 `setGoodsStock(stock-1)` 覆盖写（`M9AppPayService.java:211,376` 等）。

## 3. 新系统三域现状（含证据）

### 3.1 采购与入库：三段式 + 部分收货 + 质检闸

- **链路**：建单 `POST /purchase-orders`（`OpsProcurementController.java:105-110`）→ 逐节点审批 `/review`（`:112-122`，V228 通用审批流 4 表多节点，UAT 种子 `V254` 造「卡财务节点」）→ 收货 `/receive`（`:124-131`）。
- **状态机**：`PENDING_APPROVAL → CREATED → PARTIAL_RECEIVED → RECEIVED`（旁支 REJECTED，`ProcurementService.java:24-25,371,431`）——**支持部分收货**。
- **收货**（`processReceiveLine :388-423`）：质检闸（过期/效期<7 天/无成本整行拒收 409）→ `receivePurchaseStock`（入库单+加库存（锁）+流水+应付一个事务）；收货仓可与下单仓不同且回写（H33）。
- **⚠️ 批次号在下单时就要定**：`validatePurchaseLine`（`:470-481`）要求采购行 batchNo/expiryDate **必填**；收货**不能覆盖**——用 `line.getBatchNo()`（`:417-419`）。
- 存在自动批量过审 `ensurePurchaseOrderApproved`（`:222-238`）——审批链是日常阻力的旁证。

### 3.2 盘点：整仓四步过账 + 柜机两套并行入口

- **整仓** `warehouse_stocktake(+line)`（V160）：create（快照，支持盲盘）→ 逐行录实盘 → **complete 全行必盘**（`WarehouseStocktakeService.java:197-199`）→ **adjust 独立过账**（流水 STOCKTAKE，`:212-249`）；拍照盘点视觉填数（置信度≥0.5，`:271-326`）。
- **柜机侧两套并行口径**（无盘点单表，即时生效不可复核）：整机 `/inventory/stocktake`（book=lot 汇总，盘盈造「STOCKTAKE-日期」批次，`InventoryLotService.java:438-468`）vs 货道 `/devices/{id}/slots/stocktake`（book=货道账，`OpsReplenishmentController.java:225-231` → `:474-525`）。

### 3.3 补货：全链最深的状态机

- **建议**三级：货道 PAR → 低库存 → ROP，扣在途、按货道余量截断（`WarehouseService.java:289-430`）。
- **规划**：`/replenishment/plan` → `planAndCreateRoute`（`ReplenishmentService.java:356-416`）：route+每设备 task+**同事务生成出库单**（FEFO 分配）。
- **仓配**：拣货 → 发运（扣仓库+流水+handover=IN_TRANSIT+写 `warehouse_in_transit`，`:632-679`）。
- **现场**（merchant-mp 四步向导：签到→开门→核对→上架）：地理围栏 fail-closed（`:599-624`）→ 完成**必须先签到**（`:805-808`）→ 柜机 lot+ → 在途签收+handover 收口（`:889-907`）→ 凭证/开门可配置闸（V266/V267）。
- **状态机规模**：route 4 态 × task 4 态 × outbound 4 态 × handover 6 态 × 在途 × 要货单 4 态；任务行 3 条生成路径 + 完成时差额对冲；防脏账收口代码引用 OBS-017/BUG-010/H33/M01/BUG-014 等历史事故。

### 3.4 库存记账模型

仓库账 `warehouse_inventory`(+bin_stock)+`warehouse_movement`（7 类型）；柜机账 `device_sku_lot` **真账本**（`InventoryLotService.java:82-87`）+ `device_sku_inventory` **派生缓存**（`syncAggregateInventory`；V260 乐观锁=发生过并发漂移的痕迹）+ `inventory_movement`（8 类型，含 REFUND/REFUND_KEPT 审计）；批次效期全链传递（采购行→仓批→出库 FEFO→任务行→柜机 lot）；分布式锁 key 至少 8 种。

## 4. 仓储逐域对比

| 维度 | 旧 easygo（弹簧柜） | 新 ai-cabinet（开门柜） | 评 |
|------|---------------------|--------------------------|-----|
| 采购单据 | 申请单→厂商订单两级，状态 1-7 | 采购单+通用审批流多节点 | 新审批链对小额采购偏重 |
| 入库 | 状态 4/5 **一次性全额**；实际到货数不入账 | **部分收货**+质检闸+四合一落账 | 新正确；旧会账实漂移 |
| 批次/效期 | 无；效期是「天数」且不参与计算 | 全链 lot+FEFO+效期下架 | 不可退回；但**批次号下单必填**是新系统自加的负担 |
| 盘点 | 无盘点单；实盘覆盖写，盘盈亏**不过账** | 整仓四步过账；柜机侧**两套并行口径** | 新正确；柜机口径应合一 |
| 补货 | 无单据；传目标库存直接覆盖货道 | 五层单据+差额对冲+凭证闸门 | 新单据化正确；「在途」与 handover 状态互为复制可收敛 |
| 记账 | 货道行=库存；仓库无余额表；流水三套不通 | 仓/柜双账本；lot 真账本+汇总缓存 | 新结构正确；缓存派生化可再简化 |
| 防错 | 出货 CAS 防负数、机器上报差值落流水 | 行锁+乐观锁+8 种锁+收口代码 | 开门柜有争议/退款/识别不确定，弹簧柜出货是确定性的——复杂度有业务根源 |

**为什么不能直接照搬**：弹簧柜出货是确定性事件，开门柜是概率性识别（视觉→争议→退款回库→部分认定）。新系统的 lot 账本、REFUND/REFUND_KEPT、争议改判调整都是开门柜业务的**必要复杂度**，旧弹簧柜模型没有对应物。

## 5. 仓储推荐做法（P1/P2）

### P1-1 批次号后置到收货环节 ⭐ 最推荐

真实采购里**下单时不知道供应商发哪个批次**。当前建单强制填 batchNo（`ProcurementService.java:473`）且收货不可覆盖（`:417-419`），操作员只能编占位号，反而污染批次数据、破坏 FEFO 根基。**改法**：采购行 batchNo/expiry 下单**选填**；收货**必填且可覆盖**（收货请求逐行带批次，缺省沿用订单行）；`validatePurchaseLine` 拆「下单校验」与「收货校验」两段。影响面：ProcurementService + DTO + admin 表单 + shared-types 重生成。

### P1-2 柜机盘点两套口径合一

slot 级为**真源**（货道盘点→汇总口径派生重算）；整机 `/inventory/stocktake` 保留 API 但内部展开为 slot 级调整（或标注 legacy 逐步下线）；UI 收敛为一个盘点入口。消除两口径 book 值打架 + 即时生效不可复核的问题。

### P1-3 在途三层收敛（写路径单向化）

`warehouse_outbound_line.handover_status`（V34）与 `warehouse_in_transit`（V32）状态互为复制，`receiveForDevice` 与 `markDeviceHandoverReceived` 必须成对调用（`ReplenishmentService.java:833-834,903-904`）。**第一步**：handover_status 为唯一真源，在途表只由发运/签收两个入口写，补「两账一致性」巡检项；**第二步（可选）**：在途表查询期物化，删表。

### P2-4 采购审批默认单步

V228 通用审批流保留为**可选**（大额/配置开启），默认 review 一步直过；要货单等其它使用方不受影响。

### P2-5 单柜场景不暴露「路线」概念

单柜要货/临期下架生成的 route 与 task 恒为 1:1（`ReplenishmentService.java:1314-1335`）。「路线」对单柜无语义。后端不动，纯展示层收敛（admin/merchant-mp 按 1:1 折叠）。

### P2-6 盘点「完成并过账」合并动作

`complete`（不落账）与 `adjust`（落账）之间留出「已完未调」中间态。加「完成并过账」合并动作（同事务串调），独立 adjust 保留给复核场景。

### P2-7 单柜「一键补满到 PAR」（借鉴旧 aKeyFill）

旧系统 `aKeyFill`（`MachineAisleInfoService.java:444-453`）的好用点。新系统建议引擎已算得出目标量，补一个单柜「按 PAR 一键生成补货任务」快捷入口，日常小补不进规划对话框。

### 仓储借鉴但只改巡检

旧系统最有价值的思想是**月台账公式**：`期初 + Σ流水 = 应有余额`。新系统两本账都有流水表，把一致性巡检扩两条公式断言（仓库账、柜机账各一条）——存量漂移立刻现形，也是本部分所有改造的安全网。

---

# Part II 核心业务域

## 6. 订单 · 支付 · 结算 · 争议：两代信任模型

### 6.1 旧系统：先扣款后出货 + FALL 三层兜底

- **两段式状态机**：支付状态 `OrderStatus`（PAY_SUCCESS/AUTO_REFUND/SYS_REFUND/…）× 出货状态 `ShipmentStatus`（`WAITING_FALL, FALL, NOT_FALL, IR_NOT_FALL`，已抽查）——支付回调**不扣库存**（`saveOrderInfoWithoutDecreaseStock`，`MachineOrderService.java:208-244`），库存扣减完全依赖机器上报 CONFIRM/TRADE（`MqttMachineService.java:304-312`）。
- **支付渠道 19 种枚举**（`ego-automat\ego-automat-api-share\...\api\paytype\view\PayCategory.java`：Cash/YangchengTong/MerCard/WuhanTong/YiQianbao/AmwayPay/…，已抽查），每渠道一套 Service/Controller/回调。
- **FALL 出货失败三层兜底**（最值钱的遗产）：
  1. 机器回报失败 → 微信订阅消息通知用户（`MqttMachineService.java:312`）；
  2. 定时任务超时**自动原路退款**：`RyTask.refundForWaitingFall`（`:413-441`，已抽查：「超30秒未出货，系统自动退款」SYS_AUTO）→ 按 payCategory 分发支付宝/微信/银联退款（`OrderRefundService.java:109-184`）；
  3. 机器上报赔付单 `POST /api/v1/order/compensated` → `saveGoodsCompensated`（事务内 CAS 扣货道+赔付单落库，`MachineOrderService.java:104-122`）。
- **对账：未实现**——`downloadbill` 全库零引用（已抽查）；仅支付宝转账单查询。

### 6.2 新系统：先拿货后定账 + 三道闸

- **会话状态机**：`SessionState.canTransitionTo` 全矩阵（`common-core\...\SessionState.java:22-54`），非法迁移 409+指标+领域事件（`SessionService.transition :551-568`）；开门链幂等键→设备锁→频控→预授权冻结（`SessionService.java:121-148`、`SessionOpenService.java:60-129`、表 V257）。
- **结算三道闸**（扣款前「要不要扣」）：
  1. **置信度门槛**：整体 0.72 / 单品默认 0.92 / 中置信带 0.80（`SettlementConfidenceService.java:15-18`，已抽查）；
  2. **mock/重力一致性拦截** `blocksSilentSettle`（mock/fallback/gravity-mismatch/gravity-fill 禁止静默扣款，`SettlementService.java:103-123`，已抽查）；
  3. **余额/免密可用性预判**（余额不足转 PENDING，`SettlementOrderFinalizeService.java:136-163`）。
  任一闸不过 → **先不扣款转 DISPUTED**（低置信 `SessionSettleService.java:93-101`；转人工先释放预授权，BUG-001 注释 `SettlementRecognitionService.java:365-372`）。
- **高置信出单**：选券→扣库存回批号→扣款→券核销→分账→积分→通知→归档（`SettlementOrderFinalizeService.java:75-133`）。
- **支付**：幂等键 CHARGE:orderId:amount、H41 CHARGE_PENDING 先落痕、显式渠道不降级 412、余额兜底（`OrderPaymentService.java:152-234`）；充值限额/回调验签/30 分钟过期（`PaymentService.java:126-485`）；退款原路（`:514-561`）；**半成品**：支付分取消退款仍是 REFUND_REQUIRED+log 转人工（`SessionService.java:440-465` 自认未接渠道 API）。
- **争议结案**：KEEP/WAIVE/ADJUST/CONFIRM → 免单退款/按行部分退款/库存回库（`DisputeService.java:566-617`、`SettlementService.java:156-188`）。

### 6.3 对比与借鉴

| | 旧（弹簧柜） | 新（开门柜） |
|---|---|---|
| 信任模型 | 先付款后出货，出货即交易完成 | 先开门拿货，关门后识别定账，**存疑免责**（先不扣款转人工） |
| 履约事实源 | 机器上报 FALL/NOT_FALL | 视频/重力识别 + 置信度 |
| 失败兜底 | 订阅消息 + **超时自动退款** + 赔付单 | 超时→DISPUTED→人工结案（免单/部分退款/回库） |
| 对账 | 无 | 微信/支付宝/Mock CSV 每日对账 + 一致性巡检 24+ 项 |

**借鉴**：旧「超时自动退款」的思想值得评估移植——新系统识别/结算超时目前全部转人工（DISPUTED/RECON_SCHEDULED），量大时人工压力随单量线性增长；可做**可配置的自动免单退款**（仅限「无任何识别证据」的场景，与 F2 免单旁路修复、预授权释放语义严格对齐）。**警示**：旧系统 19 渠道、三套支付入口的碎片化不可重演。

### 6.4 血缘：旧系统里已有第一代开门柜（M8）

旧代码 `api\m8\controller\M8MachineController.java`（`:99` 开门、`:138` 门状态、`:221/260` opened/closed 查询、`:61` **关门后按实际取货差值下单支付**）+ `M8MachineService.syncStocks`（差值记账）+ `BalanceQrcodePayService`（余额扫码付）+ `m8_user_account`（充值+余额账户），就是**第一代开门柜的完整范式**：开门→关门→按差值出单→付款。本仓的演进是把这个「关门算差值」升级为「识别算差值 + 置信度定责 + 预授权闭环」。**这是同一条产品线的直系血缘，不是两套无关系统。**

## 7. 钱包 · 充值 · 提现 · 分账

### 7.1 旧系统

- **两套余额体系**：M8 用户账户 `m8_user_account`（充值回调+余额扫码消费，`M8AccountController.java:105-431`、`BalanceQrcodePayService.java:120-169`）；dvm 信义卡/公益卡储值。
- **账单分桶漏斗**：`no_ticket→no_assessment→cash_available`（`BillGenerationTask.java:431-577`），运营商固定分成千分之 20（`:40`）。
- **提现**：微信企业付款/支付宝转账/转银行卡/PFB 四渠道 + 自动重试 + 余额不足短信财务（`WithdrawalController.java:157-985`、`WithdrawalAutoRetryTask.java:47`）。退款**不回余额**（只原路退）。

### 7.2 新系统

- **消费者余额**：复合流水台账（`BalanceLedgerService.java:45`）+ 余额退款三段式（审批门/资金/渠道 HTTP 事务外）+ O6 自动审批阈值（`BalanceRefundService.java:109-284`）。
- **商户钱包/提现**：credit/debitIfAbsent 按 refType+refId 幂等（`MerchantWalletService.java:109-423`）；提现冻结-打款两段 + **PAYING 超时语义**（MOCK 自动失败解冻、真实渠道**转人工**防双重支出，F3，`MerchantWithdrawService.java:476-513`）。
- **分账**：平台抽成→D+1 结算批→全额退款作废→部分退款调整→重同步（`RevenueSplitService.java:63-217`）+ 微信分账 API/回退/重试/ledger-only 降级（`WeChatProfitSharingService.java:67-416`）+ 告警。
- **线长（地推）**：独立 7 张表体系（V133）+ 佣金日入账 3 天回扫去重（`LineCommissionJob.java:60-111`）。

### 7.3 观察：双套同构钱包栈

`LineWalletService` 与 `MerchantWalletService` 几乎逐行同构（freezeForWithdraw/consumeFrozen 成对，已抽查 `:213/:309` vs `:285/:381`）；`LineWithdrawService` 与 `MerchantWithdrawService` 的 mockEnabled 语义行完全一致。可抽**公共资金账本原语**（账户+冻结+流水+幂等），两域只留差异层。收益中等、改动面大 → 评估级。

## 8. 营销

- **旧**：弹簧柜促销（限购/免费单，`PromotionService.java:123-262`）+ 微信代金券（`WxCouponService.java:135-236`）+ M8 首单免费（一人一次）+ dvm 券/抽奖/积分；**无会员等级**；半价打标任务硬编码直营 sellerId=6783；dvm 小程序另有 advert 广告位/广告下单（`AdvertCtrl/AdvertOrderCtrl`）+ m9managepro 广告推送（`AdvertisementPushController`）。
- **新**：五套并存——优惠券（选最优券结算，`CouponService.java:385`）、充值/发券活动（claim 预留预算+分布式锁，`PromotionService.java:137-162`）、会员四级+积分流水/兑换（`MemberService.java:31-154`、V163）、广告投放（柜机屏内容+打点去重，`AdCampaignService.java:188-248`）、ROI 分析。
- **观察**：promotion_activity / ad_campaign / line_promo_task 三套「活动+预算+打点」体系并行，当前体量疑似重叠，可评估收敛（不紧急）。

### 8.1 广告/轮播现状与缺口（2026-10-01 追查，竞品消费者小程序的标配能力）

**现状**（全部源码核实）：
- 消费者小程序轮播**只在营销页**（`pages/marketing/index.vue:6-29` swiper），营销页入口藏在「我的」/券页/会员页；**首页推广位有组件但设备绑定**——`pages/index/index.vue:195` `<DeviceAdBanner v-if="deviceId && adBannerVisible">`，无柜码（Tab 落地）场景不出，且默认开关关（fail-closed）。
- 轮播内容**不是广告**：`ConsumerMarketingService.banners()`（`ConsumerMarketingService.java:87-116`）取进行中 `promotion_activity` 生成文案横幅（标题/副标题/emoji/预置配色，无图片位）；无活动时兜底一张写死的「领券更优惠」。
- **F4 广告变现设计已存在**（`docs/AD_MONETIZATION_DESIGN.md`，**流量主**方向，切片 1 已落地 2026-09-20）：`device-ad-banner.vue`（首页设备位，优先级 自有投放→微信原生广告→占位图，`utils/promo-slot.ts` 纯函数）+ `wx-ad-slot.vue`（腾讯流量主组件，unit-id 走系统配置）。**缺口是「无设备上下文」的投放渠道**：设备位只在扫柜后出现且需要按柜机定向的投放数据。
- 广告投放模块（V174：`media_asset` 素材库 + `ad_campaign(+item/device)` + 排期；`screenContent(deviceId)` 按设备出内容；媒体同源代理 `/api/v2/media/ad-assets/{id}` 已在 WebConfig 白名单，消费者可直载；打点 IMPRESSION/COMPLETE/CLICK + `AdPlayEventDeduplicator` 60s 去重）。
- admin 素材库上传**是真实现**（`AdAssetsView.vue` doUploadFile multipart + 前端校验；早前 UAT「stub 文件框」仅反映当时未执行上传，勿再据此判定缺实现）；投放计划表单已有 scope/档期/素材。

**缺口定性**：竞品消费者小程序首页的「运营位/广告轮播」是标配；本仓家底齐备，缺四件事——① `ad_campaign` 无投放端维度（只有设备 scope，天生只喂柜机屏）② 无**无设备上下文**的广告消费 API（banners 只接活动）③ 营销页轮播无真图、首页无柜码场景无轮播位 ④ MINI_PROGRAM 渠道的投放范围语义。→ 方案编号 **P3-6**（§14.1，已批）。

## 9. 设备运维与告警

- **旧**：`ego_machine_status` 一表全量（温度/门磁/投币机/信号/ICCID/经纬度，`MachineStatus.java:20-48`）；OTA 按 machineTypeId 下发 apk；告警实际靠「异常机清单定时任务+飞书多维表格」（`RyTask.java:123,460-479`）；**僵尸任务**：离线日报、温度巡检整类被注释（`MachineOfflineTask.java:51-82`、`MachineStatusTask.java`）——告警链路从未稳态运行。
- **新**：device-service 薄 MQTT 桥（门事件去重+手动 ACK+失败计数丢弃，`MqttEventListener.java:115-174`）；指令跟踪 422 行（超时/重试）；生命周期（离线自动锁机/稳定在线自动解锁，`DevicePresenceService.java:153,238`）；温控排程（`DeviceTempPlanService`）；OTA O2 全套（发布/检查/进度/看板，`OtaService.java:29-235`）；可用性 KPI 日快照（V151）；告警四通道（飞书加签/钉钉/企微/webhook）+ 通道探针（`OpsAlertDispatcher.java:76-498`）。
- **借鉴**：旧「一表全量状态快照」的字段面可参考（新系统心跳已带版本/温度，可对照补漏）；「告警走飞书多维表格」的轻量思路可保留为运营侧兜底。

## 10. 定时任务与对账

- **旧**：三套定时体系并存（mis-quartz RyTask 27 任务 / bill-timer、rpt-timer 为 crontab 拉起的 one-shot 跑批）；任务无幂等说明；硬编码遍布（直营 6783、补货员名单、淘宝密钥、区块链内网 URL）。
- **新**：31 个业务任务全量托管 XXL-JOB（`XxlJobManagedTasks.java:56-82`，已抽查计数）+「新增任务同步 7 处」双门禁（`:38-52`）；每日对账 01:30（微信/支付宝/Mock 三 Provider CSV 平台账，`ReconciliationService.java:108`）；一致性巡检 24+ 项资金/库存/分账交叉检查、**只巡不修**（`DataConsistencyService.java:182-204`）。
- **观察**：「7 处同步」是流程复杂度而非业务复杂度——但对这类资金系统是可接受的门禁成本；旧系统 one-shot+DB sys_job 双轨与无幂等是反面教材。

---

# Part III 客户端面（含小程序）

## 11. 旧系统 6 触点：碎片化的教训

- **消费者（弹簧柜）两条链路**：① 扫码 → **M3 微信 H5 收银台**（`M3WXWapPayController.java:53/87` externalpay/internalpay + 模板页 `js_weixin_pay.html`）；② **M9 机器端 Android 大屏 App**（`M9AppController.java:34-59` App 内拉起微信/支付宝，机器轮询支付结果）。
- **运营/补货员 App**：ego-automat-operation 后端（补货/开锁/改价/采购/账单/提现/维修/电子合同/子账号/App 自升级）；开锁测试接口硬编码 userId 白名单（`MachineChangeHistoryController.java:59-70`）。
- **管理侧三套**：ego-automat-manage（老）+ m9managepro（M9 生态）+ mis-web（新 RuoYi）。
- **dinngdang-wx** 是 dvm 线小程序（36 页：dream/信义卡/彩票/加盟…），**与弹簧柜无关**；但它是反面教材：**MD5 签名密钥硬编码在前端**（`src/config.js:66-69`）。
- **教训**：M3/M8/M9 三代设备 API 并存、三套支付入口、三套库存同步接口（syncStock/syncStock2/singleAisles）——协议碎片化是多年叠加的结果，新柜必须一份设备 API、一份支付入口。

## 12. 新系统三端：双小程序

### 12.1 consumer-mp（消费者，uni-app）

- **页面全景**：22 页（6 主包+16 分包，`pages.json:2-290`，含 preloadRule 与 3 tabBar）；主链路 落地/扫码→开门→live-cart 购物→关门结算→订单/争议→充值/券/积分/会员 全齐（`pages/index/index.vue`）。
- **前端架构**：端点集中 `ConsumerEndpoints`+字面量门禁（`api/endpoints.ts:24-154`，`check-consumer-endpoints.mjs`）；请求封装走 `packages/shared-uni`（token 过期读校验、鉴权下载、服务重启强制重登、幂等重试策略——仅传输错误重试，业务错误直抛，`utils/consumer-api.ts:80-699`）；错误兜底 soft-fallback（label+toast，禁 `.catch(()=>[])`）；自动刷新 onShow/onPageShow 双钩子纯函数化。
- **支付**：微信充值 mock/live 双模（`utils/recharge.ts:258-279`，live 字段缺失按 live 兜底防误调 mock 确认接口）；支付宝 H5 表单白名单+DOMParser 重建提交（禁 document.write）；免密签约（支付分/支付宝协议）幂等解约；mock 入口靠 dev 开关矩阵锁死（生产包强制 false，`runtime-flags.ts`；后端 mock 端点 `@ConditionalOnProperty` prod 拒启）。
- **技术债**：C1–C12 全部收口（仅 C9 平台敏感 uni.* 守卫知情延后）；**遗留**：首页仍 3641 行上帝页（已抽查）——C5 拆了纯函数但页面体积未降。

### 12.2 merchant-mp（商户，uni-app）

- **页面全景**：4 tab+19 分包（`pages.json`）；角色模板 店长/财务/补货员（V140），财务钱包只读；导航「前端写死+功能包(field/biz/team)+权限点」裁剪（`config/merchant-nav.ts:16-152`）。
- **防抬权**：缓存剥离 permissions、软失败不回读 storage、页面级 hasPerm 门禁几乎每页都有（`useMerchantMe.ts:29-131`）。
- **补货四步向导**（`useReplenishmentFulfillment.ts`）：签到（定位两段超时+坐标缺失 fail-closed）→ 开门（必须已签到；M26：sessionId 缺失绝不标 doorOpened）→ 核对（容量超限自动调低确认）→ 完成（顺序门闩：清单确认→开门→证据→终确认；至少 1 张现场照）。
- **技术债**：M1–M12 除 M9 全 done；**M9 tracker 滞后**——`pages.json:331-338` easycom 实际已指向 shared-uni，tracker 仍标 open（文档修正即可）。

### 12.3 admin-vue

71 业务视图，CrudTable 全量迁移、UAT 册体系覆盖（见各 UAT 文档），此处不展开。

## 13. 客户端对比

| | 旧 easygo | 新 ai-cabinet |
|---|---|---|
| 消费者触点 | H5 收银台（模板拼页）+ 机器屏 App | 独立小程序（扫码开门+live-cart+结算闭环） |
| 运营触点 | 运营员 App + 三套 web 后台并存 | admin-vue 单后台 + merchant-mp |
| API 纪律 | 前端硬编码 secret、路径散落 | Endpoints 收口 + 门禁脚本 + soft-fallback |
| 补货作业 | 运营员 App 传目标库存直接覆盖 | 四步向导 + 签到/开门/证据三硬门闩 + 地理围栏 |
| 营销面 | 微信代金券/促销散点 | 券/积分/会员/活动/投放五套 + ROI |

**收尾建议**：consumer 首页上帝页二次拆解（立 C13 或复开 C5 验收口径改为「页面 ≤N 行」）；merchant-mp M9 tracker 文档对账（5 分钟）。

---

# Part IV 汇总建议

## 14.1 全部建议分级表

| 编号 | 项 | 板块 | 收益 | 影响面 | 风险 |
|------|-----|------|------|--------|------|
| P1-1 ✅已落地（2026-10-01） | 批次号后置到收货（下单选填/收货必填可覆盖，matchLine sku 兜底容歧义） | 仓储 | 高（字段少+数据真） | 中 | 低 |
| P1-2 ✅闸门落地（2026-10-01） | 柜机盘点口径合一：lot 账本设备禁止整机盲调（旧实现直写汇总会被 syncAggregate 冲掉=静默丢账，全库核实零 UI 调用方）409 指向货道盘点；slot 口径确认为唯一主入口（真源语义本就正确：只动该货道 lot+last_physical_qty+汇总派生）；非 lot 设备保留旧路径兼容 | 仓储 | 中高 | 小（闸门式） | 低 |
| P1-3 ✅落地（2026-10-01） | 在途单向化：receiveHandoverPair 唯一成对入口（两处散写收敛）+ 一致性巡检 OUTBOUND_HANDOVER 两向漂移检测（孤儿在途/挂起交接，真库存量绿）；写路径本就收敛在 InTransitService 三个入口（发运/签收/取消），无需动表 | 仓储 | 中 | 小（入口收敛+巡检） | 低 |
| P2-4 ✅落地（2026-10-01，形态修正） | 采购审批默认单步——核实发现是真缺陷而非纯简化：无启用审批定义时 isInstanceApproved 恒 false，采购单永久卡 PENDING_APPROVAL；改为无定义单步直过、配置了定义走多节点链不变（2 用例） | 仓储 | 中（修缺陷） | 小 | 低 |
| P2-5 ⛔核实降级（2026-10-01） | 单柜不暴露路线——现有 UI 以任务为主体、路线仅分组视图，冗余度可接受；保留观察 | 仓储 | 低 | 小 | 极低 |
| P2-6 ✅已落地（2026-10-01） | 盘点「完成并过账」合并动作（completeAndAdjust 同锁同事务，端点 /complete-and-adjust，弹窗按钮） | 仓储 | 中 | 小 | 低 |
| P2-7 ⛔核实关闭（2026-10-01） | 一键规划已等价覆盖（DeviceRemoteOpsCard 单柜入口 + /replenishment/plan 内置建议引擎），不重复建设 | 仓储 | — | — | — |
| P3-1 | 商户/线长双钱包栈抽公共资金账本 | 核心域 | 中 | 大 | 中（评估先行） |
| P3-2 | 营销三轨收敛评估 | 核心域 | 低中 | 中 | 低（评估） |
| P3-3 🔪切一落地（2026-10-01） | consumer 首页拆解：落地页抽 `HomeLanding.vue`（逐字搬移+defineModel，3641→3167 行）；债表 C13 跟踪，切二=shop 块+编排 composable | 客户端 | 中 | 中 | 低 |
| P3-4 ✅已实施（2026-10-01，用户授权按建议判断） | 争议超时自动免单：72h 阈值、滚动 7 天单用户 ≤3 防薅（AUTO_WAIVE 标记统计）、单轮 ≤50、开关默认 OFF（dev 观察）；autoWaiveTicket 与人工 WAIVE 同链落账；4 用例+看护阈值+两道接线门禁绿（31 托管任务） | 核心域 | 中（省人工） | 中 | 中低（零资金移动+fail-closed） |
| P3-5 ✅已落地（2026-10-01） | merchant-mp M9 tracker 对账（easycom 已直指 shared-uni、本地副本已清，事实完成） | 客户端 | 小 | 极小 | 无 |
| P3-6 | 消费者小程序广告轮播位对齐竞品：ad_campaign 加投放端维度（CABINET_SCREEN/MINI_PROGRAM）+ 消费者 banners 接广告真图 + 首页独立轮播组件（空数据不渲染）+ 素材库上传接真（MinIO）；复用现成媒体代理与打点 | 客户端 | 中高（竞品标配） | 中 | 低 |

**P3-6 已批方案（2026-10-01 用户拍板，Step 1 最小可用；含用户补充的「投放范围」要求）**：
1. 迁移 V296：`ad_campaign` 加 `channel`（CABINET_SCREEN 默认/MINI_PROGRAM，老数据零影响）+ `link_url` 小程序深链 + `(channel,status)` 索引。
2. **投放范围**（用户要求）：复用 `device_scope`+`ad_campaign_device` 语义扩展到小程序渠道——`ALL`=全场景；`SPECIFIC`=仅指定柜机上下文（`GET /marketing/banners?deviceId=` 传入，落地页无柜码时只出 ALL）。不新建表。
3. 后端：`listMiniProgramBanners(limit, deviceId)`（RUNNING+档期+MINI_PROGRAM，按计划序取首个 ACTIVE 图片素材）；`banners()` 广告位优先、活动横幅补位、静态兜底保持（完全兼容）；打点复用 `recordPlayEvent`（新端点 `POST /marketing/ads/{id}/events`，须登录，deviceId 语义=MP-U{userId}，60s 窗口去重；MINI_PROGRAM 渠道跳过设备范围校验——范围在渲染侧已过滤）；`screenContent` 屏蔽小程序渠道防串投。
4. consumer-mp：营销页轮播加图片模式（有图显图/无图退 emoji 卡）+ 曝光/点击上报；新组件 `marketing-ad-banner.vue`（无柜码上下文的营销位，与设备位 `DeviceAdBanner` **互斥**渲染避免双轮播，fail-silent 无数据不渲染）。
5. admin：投放计划表单加「投放端」radio + MINI_PROGRAM 深链输入 + 范围文案随渠道（全部场景/指定柜机场景）；素材库上传本就是真实现，零改动。
6. 收尾：新下发字段过 R6 门禁、`shared-types` 重生成、admin 重建（镜像内 `vue-tsc` 挡构建 ⇒ 先手工补 generated 类型解阻塞、栈起后 `OPENAPI_IGNORE_CACHE=1` 重生成核对一致）。
Step 2（后续评估）：投放定向/频控、点击转化报表、微信流量主第三方广告（对齐 F4 切片 2/3）。
| 巡检 | 一致性巡检加两条余额公式断言（仓账/柜账 `期初+Σ流水=余额`，借鉴旧月台账） | 通用 | 高（安全网） | 小 | 低 |

## 14.2 明确不抄清单

1. **19 种支付渠道枚举 + 每渠道一套 Service**（历史交通卡/壹钱包/安利/牛奶卡遗迹）——新系统收敛 微信/支付宝/余额/支付分 即可。
2. **硬编码运维**：直营 userId=6783、补货员 ID 名单、unLock 用户白名单、淘宝 AppKey/Secret、区块链内网 URL、**前端 MD5 secret**（dinngdang-wx config.js:66）。
3. **僵尸注释任务**（离线日报/温度巡检整类注释）——要做就做成配置开关。
4. **无对账**（downloadbill 零引用）——新系统每日对账+一致性巡检必须保住。
5. **三套设备 API/支付入口/库存同步并存**——新柜坚持一份设备 API。
6. **补货直接覆盖货道库存、月台账人工盘**（Part I 已述）。
7. **两代后台并发写同表、覆盖写余额无锁**。

## 14.3 实施顺序

**巡检公式断言（安全网）✅ → P3-6 广告轮播 ✅ → P1-1 批次后置 ✅ → P2-6 完成并过账 ✅ → P2-7/P2-5 核实关闭/降级 → P3-5 ✅ → P1-2 盘点口径闸门 ✅ → P1-3 在途单向化 ✅ → P2-4 审批无定义单步直过 ✅。全部 P1/P2 收官；P3-4 已实施（开关默认 OFF，决策记录见设计稿 §6）；余 P3-1/P3-2/P3-3 评估级待排期。**

风险纪律：新系统复杂度不少来自真实事故修复（OBS-017/BUG-010/H33/M01/BUG-014/F2/F3 注释在案）。每项改造：① 先落巡检并确认存量绿；② `node scripts/pre-push-ci-preflight.mjs` + 相关 `mvn` 测试类 + admin 重建；③ 账本语义类改动须 A/B 门禁证明判据会红；④ P3-4 涉资金，先写设计文档单独评审。

---

# Part V 证据索引

**旧系统**（`D:\ideaCode\easygo`）：
- 仓储：`mis-server\mis-manage\...\{DeliverOrder.java:118-148, DeliverOrderServiceImpl.java:136-212, DeliverOrderPurchaseServiceImpl.java:91-178, EgoOptStockRecordMonth.java:70-170, EgoOptStockRecordMonthDetail.java:64, EgoOptStockRecordMonthDetailServiceImpl.java:126-430, EgoMachineAisleInfo.java:21-76}`；`ego-automat\ego-automat-domain\...\{MachineAisleInfoService.java:216-265,395-453, MachineOrderService.java:100-143, OptStockRecordDailyService.java:595-652, OptStockOptorRecordRepository.java:63-80}`；`ego-automat-api\...\{MachineOperController.java:126-133, M8MachineController.java:99-320}`；`ego-automat-m8-domain\...\M8MachineService.java:230-262`；`mis-quartz\...\RyTask.java:94-502`。
- 订单支付：`ShipmentStatus.java / OrderStatus.java`；`WeiXinNotifyController.java:50`、`WeixinReceiveNotifyService.java:156-220,386`；`MqttMachineService.java:151-312`；`OrderRefundService.java:109-184`；`OrderController.java:63-75`；`PayCategory.java`（api\paytype\view）；`M3WXWapPayController.java:53-189`、`M3WXWapPayService.java:110-150`、`M9AppPayService.java`；`BalanceQrcodePayService.java:120-169`、`M8AccountController.java:105-431`。
- 运维/结算/任务：`MachineStatus.java:20-48`、`MachineAndroidUpdateController.java:32-68`、`MachineOfflineTask.java`（注释）、`MachineStatusTask.java`（注释）；`BillGenerationTask.java:40,99-577`、`WithdrawalController.java:157-985`、`WithdrawalAutoRetryTask.java:47`、`TransferService.java:63`；bill-timer/rpt-timer `Application.java:35-93`。
- 客户端：`dinngdang-wx\src\config.js:7-72`、`app.json:2-38`；`dvm\dvm-wx\...\{DonateCtrl,LetterBoxCtrl,LuckDrawCtrl,CouponCtrl}`；`ego-automat-operation\...\{OptorController.java:79-151, MachineController.java:135-283, OptorMachineController.java:125-459, WithdrawalController.java, MachineRepairController.java:74-333}`；`m9managepro\...\webmanage\**`。

**新系统**（本仓，相对根）：
- 会话/结算：`services/common/common-core\...\SessionState.java:22-54`；`services/trade-service\...\service\{SessionService.java:121-148,263-342,440-465,551-640, SessionOpenService.java:60-129, SessionDoorService.java:64-183, SettlementSettleOrchestrator.java:34-97, SettlementConfidenceService.java:15-72, SettlementService.java:103-188, SettlementRecognitionService.java:254-372, SettlementOrderFinalizeService.java:75-227, SessionSettleService.java:74-101,157-219, OrderPaymentService.java:152-234,459-561, PaymentService.java:126-485, PayScoreService.java:118-439}`；迁移 V257。
- 钱包/分账：`{BalanceLedgerService.java:45-99, BalanceRefundService.java:109-284, MerchantWalletService.java:67-423, MerchantWithdrawService.java:319-513, RevenueSplitService.java:63-217,445, payment\WeChatProfitSharingService.java:67-416, LineWalletService.java:124-309, LineWithdrawService.java:266, LineCommissionJob.java:60-111, LinePromoTaskService.java:49-125}`；迁移 V133/V139/V15。
- 营销：`{CouponService.java:68-602, PromotionService.java:39-162, ConsumerMarketingService.java:71-162, MemberService.java:31-154, AdCampaignService.java:90-248, MarketingRoiService}`；迁移 V66/V163/V174/V191。
- 设备/告警：`services/device-service\...\{MqttEventListener.java:49-174, DoorEventDeduplicator, MqttCommandPublisher.java:126, DeviceInternalController.java:27-59}`；`{DevicePresenceService.java:70-238, DeviceSalesLockService.java:55-82, DeviceStableOnlineAutoUnlockService.java:77-83, DeviceTempPlanService.java:30-33, OtaService.java:29-235, DeviceAvailabilityKpiService.java:52-103, OpsAlertDispatcher.java:76-498, DisputeSlaAlertService}`；迁移 V151。
- 任务/对账：`{XxlJobManagedTasks.java:16-82, ScheduledTaskRegistry.java:78-152, ReconciliationService.java:108, reconciliation\PlatformBillProviderRegistry.java:14-28, DataConsistencyService.java:66,182-204}`。
- 小程序：`clients/consumer-mp\src\{pages.json:2-290, api\endpoints.ts:24-154, utils\consumer-api.ts:65-763, utils\recharge.ts:38-305, utils\soft-fallback.ts:9-17, composables\use-auto-refresh.ts:27-38, pages\index\index.vue（3641 行）}`；`clients/merchant-mp\src\{pages.json:2-338, config\merchant-nav.ts:16-152, composables\useMerchantMe.ts:29-131, composables\useReplenishmentFulfillment.ts:48-495, components\ReplenishStepBar.vue:3-18, components\ReplenishEvidenceSection.vue}`；债表 `docs/engineering/{consumer-mp-debt-tracker.md, merchant-mp-debt-tracker.md}`。

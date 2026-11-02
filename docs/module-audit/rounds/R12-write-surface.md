# R12 后端写面全量（静态）— 轮报告

> 镜头：trade-service 全部 Controller 的 POST/PUT/DELETE/PATCH 逐一四查——鉴权/权限注解、入参校验 400、幂等与重复提交、审计留痕。
> 方法：每批 2 个只读子代理并行 + 主会话汇总领号；每端点产出四查覆盖行。
> 发现锚：批 1 = `9400efae` 时代（含至 `d09e4865` 的修复后代码）。

## 五批分组（接手依据——后续批次照此执行，勿重排）

| 批 | 域 | Controllers（api/ 下文件名，省略 Controller 后缀） | 状态 |
|----|----|----------------------------------------------------|------|
| 1 | 身份/RBAC/商户/钱包/系统 | 1a: Account, Auth, OpsRbac, Department, OpsOrg, MerchantAdmin, MerchantOnboarding, SysDict, DictRuntime, SystemConfig, AdminDataManage；1b: MerchantPortal, MerchantWallet, MerchantWithdraw, LineManager, LineWithdraw, OpsStaffWallet, OpsPayoutAccount, LinePromoTask, Approval, ScheduledTask, PublicConfig, CabinetOpenLanding | ✅ 完成（137 写端点，P2×3/P3×11 → MA-068~081） |
| 2 | 交易/订单/支付/优惠/回调 | Session, Order, Payment, Dispute, ConsumerDispute, Coupon, Promotion, Marketing, OpsAd, ConsumerBalanceRefund, AdminBalanceRefund, WeChatNotify, AlipayNotify, PayScoreNotify, DevMockPayment, DevMockAlipayPayment, DevMockRecharge | ✅ 完成（45 写端点，P3×8 → MA-082~089） |
| 3 | 资金财务/增长/报表 | OpsFinance, OpsPlatformFund, OpsMerchantFeeLedger, OpsMachinePayeeBill, InvoiceAdmin, DeviceDataFeeBill, SiteRentBill, SiteRentSplit, Member, AdminGrowth, OpsRisk, OpsAnalytics, AdminDashboard, DeviceAvailabilityKpi, ReplenishmentReport | ✅ 完成（55 写端点，P2×1+P3×9 → MA-090~099） |
| 4 | 仓储/履约/补货/异常 | OpsWarehouse, WarehouseTransfer, OpsProcurement, OpsReplenishment, OpsLineRoute, MerchantWarehouseStocktake, SlotTemplate, OpsExternalImport, RepairTicket, UserFeedback, ConsumerFeedback, OpsException, MerchantOpsException, OpsCommercial | ✅ 完成（79 写端点，P2×2+P3×13 → MA-100~114） |
| 5 | 设备/将邑/内部/通知/杂项 | Device, DeviceMqttCredential, DeviceHttpCredential, DeviceInternal, OpsDeviceRental, OpsDeviceEnv, AdminJiangyi×2, JiangyiInternal, SessionInternal, VisionInternal, SmsInternal, OpsAlertInternal, DemoDataInternal, OpsDevOps, OpsOta, DataConsistency, Media, Announcement×3, Notification×2, OpsController | ✅ 完成（98 写端点，P2×2+P3×9 → MA-115~125；注：Ops 大杂烩已按域抽出，现存 OpsController 仅 2 端点） |

> 注意：批次表覆盖 95 个 Controller（PLAN 旧写 85 为过期数字）。OpsController 体量大，批 5 如超载可拆 5a/5b。

## 批 1 战果（2026-11-02 晚）

- **覆盖**：23 个 Controller / 137 个写端点，四查覆盖表见两个子代理报告（本文件存档于会话；覆盖表全量行已随 MA-068~081 台账引用）。
- **总体健康度**：无 P0/P1、**无一个裸奔写端点**（默认拒绝 + @RequiresPermissions 切面 + 服务层二次鉴权 + 审计的纵深防御扎实）；缺口集中在「非主链写路径的归属校验补漏」「资金参数无界」「权限码双轨」。
- **P2×3**：
  - MA-068 商户盘点凭证上传缺设备归属校验（跨商户挂照片/灌图）— MerchantPortalService.java:278
  - MA-069 线长佣金参数（rateBps/fixedCents）无界直落，持续性资金乘数 — LineManagerController.java:52
  - MA-070 「组织与点位」权限码双轨：Controller 用 ops:org:*、服务层强制 ops:device:*，且删除 OR、其余 AND（删比改松）— OpsOrgController.java:43
- **P3×11**（MA-071~081）：RBAC 分配端点重复 ID→500；运营改密/2FA 关闭无审计；提现「申请」动作全族无审计+代提不可归因；补货员钱包打款无操作者审计；线长换绑 userId 无审计；商户补货配置/开票税务资料两处无审计；收款账户 ownerType 无白名单且兜底落到只读权限；merchant.status 自由串直落库；字典类型硬删无内置保护（fail-open 联动）；组织节点无父级/环校验（子树静默消失）；bind-openid 重复绑定 500 而非 409。
- **判「不是缺陷」的定性记录**（防后续重查）：MerchantOnboarding review 对 list 码开放 OR（审批流 assignee 兜底）；AuthController 公开端点（WebConfig 白名单注释）；merchant-password-login 验证码不对称（G2-11⑧ 拍板）；DictRuntime 登录可读（javadoc 钉「展示用」）；AccountController 无注解（消费者自我范围面）；锁包装器 500（MA-053 已收口）。


## 批 2 战果（2026-11-03 凌晨，锚 `931f11d3`）

- **覆盖**：17 Controller / 45 写端点（2a 交易优惠 34 + 2b 支付回调 11）。
- **总体健康度**：无 P0/P1/P2。消费端 11 个写端点「JWT→资源归属→状态机→锁」四件套齐全（非本人 404/403 防探测），**客户端零金额入参**（全部服务端以分计算）；三个渠道回调验签先行+金额全比对+nonce 防重放；三个 DevMock 端点四重门禁（@ConditionalOnProperty+白名单不含+服务层 fail-closed+生产启动抛异常）无生产可达。
- **P3×8**（MA-082~089）：营销域运营写审计缺失（15 端点合并）、batch-issue DTO 零约束、素材 status 自由串、活动 activityType/budget 负数 fail-open、投放时间窗倒挂、DTO @Size 漏网、微信退款回调 refund_id 缺失幂等键塌缩（防御性）、余额退款 DTO 上限口径。
- **判「不是缺陷」定性**：回调对未知 out_trade_no 回 204（渠道查单自愈兜底 C12/H11）；prepay 幂等键跨用户统一 409（无信息泄露）；缺 mchid 放行（验签已是信任锚，比对属纵深）；广告 launch/stop 与 edit 共用 ops:ad:edit（menu.ts H51 注释钉明）；退款 restoreInventory 三态 Boolean（javadoc 钉死）。
- 未挣：无。


## 批 3 战果（2026-11-03 凌晨，锚 `d1ad4521`）

- **覆盖**：15 Controller / 55 写端点（3a 资金财务 21 + 3b 增长风控报表 34；OpsAnalytics/DeviceAvailabilityKpi/ReplenishmentReport 三者纯 GET 已逐一确认无写面）。
- **总体健康度**：无 P0/P1。资金主链纵深防御扎实（权限双闸/分布式锁+行锁/状态机短路/appendLog 齐备——SiteRentBill/DeviceDataFeeBill/SiteRentSplit 是全仓范本）；缺口集中在 **2026-10-14 新开的机器账单链**（shareBps 无界+零留痕+裸 parse，建议整链一次回补）与增长域「配置写校验+审计」两件套（与批 1/2 同构模式）。
- **P2×1**（MA-090）：机器账单分成比例无界（持续性资金乘数，V356 新链全仓唯一没闸的 bps）。
- **P3×9**（MA-091~099）：三处资金销账零操作者归因、财务面 5 端点无审计、平台资金池负数充值、billMonth 裸 parse、会员等级规则零校验（死档）、兑换商品负积分成本（积分铸造）、黑名单解除零留痕、增长域审计缺失、一键召回无幂等。
- **判「不是缺陷」定性**：平台资金池手工登记无幂等键（外部事实登记+月度核对兜底）；MachinePayeeBill 用 ops:device:edit（554353ba 拍板）；fee settle 权限 edit→adjust（类注释钉明）；platform_fund_flow 本身即带 operatorId 的不可删台账（域内审计完备）；机器账单 settle 无 VOID 态（域无作废功能）；SiteRent allocate 分摊口径 javadoc 大段钉明勿动；sku-review/run 挂 list 码（页面级设计自洽）；AdminDeviceOpsService 服务层码比注解松（控制器更严先拦）；min_spent 全链路元无混用。
- 未挣：无。

## 批 4 战果（2026-11-03 凌晨，锚 `b92ac251`）

- **覆盖**：14 Controller / 79 写端点（4a 仓储履约 55 + 4b 异常反馈 24）。
- **总体健康度**：无 P0/P1。核心库存链（盘点/出库/调拨/采购收货/应付付款）状态机+锁+三层账流水系统性在位；MA-038 修复在位且收货侧未回潮；分仓负责人多人集合铁律全端点核实。缺口集中在外围：**佣金/分成参数无界家族第三例**（MA-100，与 MA-069/090 建议一次同模式收口）、**外部导入重复灌数无闸**（MA-101）、月结处置无锁竞态、若干 400/500 口径与列宽家族新点位。
- **P2×2**（均经主会话亲自复核源码）：MA-100 线路成员佣金无界零审计（LineCommissionJob 直入补货员钱包）；MA-101 外部导入同账期多批次 commit（月结 sum 翻倍）。
- **P3×13**（MA-102~114）：调拨取消零审计、月结处置/效期建任务无锁双挂、PUT /inventory 零校验、PO review 注解死分支、调拨收货未知 lineId 静默全额入库、SlotTemplate 绕开门柜守卫、线路缺必填 500/幽灵线长、无主仓闸两旁路、工单列宽三级链断裂、反馈查看权限可硬删、retry 幂等 marker 错位、批量指派 "null"、@Size 家族补点。
- **判「不是缺陷」定性**：moveBetweenBins 不写流水（D28-A4 刻意）；cleanupStaleOutbounds 无外层事务（P1-4 刻意）；createTaskFromSuggestion 不防重（注释在位）；月结盘盈自动 SURPLUS；LineCommissionJob javadoc「只记台账」已过时随 MA-100 一并修；外部导入权限沿用仓库域（铁律 57）；CommercialFlow 演示闸双保险；retry 对将邑柜拦截刻意。
- 未挣：无。

## 批 5 战果（2026-11-03 凌晨，锚 `fe098186`）

- **覆盖**：24 Controller（去重 23）/ 98 写端点（5a 设备/将邑/内部 84 + 5b 杂项/OpsController 14）。
- **总体健康度**：无 P0/P1。**鉴权面全量收口**：84+14 端点零裸奔（/internal/** 全被 fleet key+CIDR 覆盖且路径一一对应、设备面 3 pattern 一机一密精确对齐、jiangyi-gateway 设备 token 链 HS256+BOUND+tokenVersion 实证在位、14/14 权限码全部可溯源）；一致性修复对 15 类高危类型显式拒绝自动修。
- **P2×2**（均主会话亲自复核）：MA-115 OTA 发布/下架零归因（operatorId 传而不用）；MA-116 一致性修复零归因+error_message 覆写证据。两者都是「改数据/推固件级」运维动作，与 MA-091/097/102 构成「高危写零归因」家族主链。
- **P3×9**（MA-117~125）：将邑运营写面 ~30 端点审计缺失、租赁月退参数静默吞错、凭据签发孤儿、心跳 NaN 500、internal 遗留入口绕守卫、公告自由串/零归因、fix 重放假成功、OTA 入参缺口。
- **判「不是缺陷」定性**：heartbeat 走 fleet key（device-service 转发面刻意）；registerUnknown 自动建档（信任面内）；anomaly-events 不校验设备存在（非结算驱动）；OTA 设备侧防降级+unpublish 回落语义自洽；sonar/scan 仅 log.info（最轻档）；SmsInternal latest-code 生产 403；DemoDataInternal 四重门禁（铁律 77 端点面成立）；LineCommissionJob javadoc 漂移已随 MA-100 记录。
- 未挣：无。

## R12 全轮总结（五批收官）

- **覆盖**：95 Controller / 395 写端点全量四查，产出逐端点覆盖矩阵（各批战果节）。
- **结论**：无 P0/P1、**零裸奔写端点**——鉴权纵深防御（默认拒绝+切面+服务层二次+拦截器路径精确对齐）经全量验证成立。资金面核心链（结算/退款/分账/提现/回调）此前六轨已深审，本轮补齐了外围全部写面。
- **发现**：58 条（MA-068~125）= **P2×7 / P3×51**。P2 家族归纳：①佣金/分成参数无界×3（MA-069/090/100，建议一次同模式收口）；②跨租户/污染写×2（MA-068 盘点凭证、MA-101 导入重灌）；③高危运维零归因×2（MA-115 OTA、MA-116 一致性修复）。P3 高频家族：审计留痕缺口（~15 端点组）、400/500 口径（~10 处）、@Size/列宽家族（~8 处）——建议修时分三个批次按家族扫。
- **判「不是缺陷」定性记录**：~40 条分散在各批战果节（防后续会话把刻意设计改坏）。

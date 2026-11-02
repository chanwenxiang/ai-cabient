# R12 后端写面全量（静态）— 轮报告

> 镜头：trade-service 全部 Controller 的 POST/PUT/DELETE/PATCH 逐一四查——鉴权/权限注解、入参校验 400、幂等与重复提交、审计留痕。
> 方法：每批 2 个只读子代理并行 + 主会话汇总领号；每端点产出四查覆盖行。
> 发现锚：批 1 = `9400efae` 时代（含至 `d09e4865` 的修复后代码）。

## 五批分组（接手依据——后续批次照此执行，勿重排）

| 批 | 域 | Controllers（api/ 下文件名，省略 Controller 后缀） | 状态 |
|----|----|----------------------------------------------------|------|
| 1 | 身份/RBAC/商户/钱包/系统 | 1a: Account, Auth, OpsRbac, Department, OpsOrg, MerchantAdmin, MerchantOnboarding, SysDict, DictRuntime, SystemConfig, AdminDataManage；1b: MerchantPortal, MerchantWallet, MerchantWithdraw, LineManager, LineWithdraw, OpsStaffWallet, OpsPayoutAccount, LinePromoTask, Approval, ScheduledTask, PublicConfig, CabinetOpenLanding | ✅ 完成（137 写端点，P2×3/P3×11 → MA-068~081） |
| 2 | 交易/订单/支付/优惠/回调 | Session, Order, Payment, Dispute, ConsumerDispute, Coupon, Promotion, Marketing, OpsAd, ConsumerBalanceRefund, AdminBalanceRefund, WeChatNotify, AlipayNotify, PayScoreNotify, DevMockPayment, DevMockAlipayPayment, DevMockRecharge | 未开工 |
| 3 | 资金财务/增长/报表 | OpsFinance, OpsPlatformFund, OpsMerchantFeeLedger, OpsMachinePayeeBill, InvoiceAdmin, DeviceDataFeeBill, SiteRentBill, SiteRentSplit, Member, AdminGrowth, OpsRisk, OpsAnalytics, AdminDashboard, DeviceAvailabilityKpi, ReplenishmentReport | 未开工 |
| 4 | 仓储/履约/补货/异常 | OpsWarehouse, WarehouseTransfer, OpsProcurement, OpsReplenishment, OpsLineRoute, MerchantWarehouseStocktake, SlotTemplate, OpsExternalImport, RepairTicket, UserFeedback, ConsumerFeedback, OpsException, MerchantOpsException, OpsCommercial | 未开工 |
| 5 | 设备/将邑/内部/通知/杂项 | Device, DeviceMqttCredential, DeviceHttpCredential, DeviceInternal, DeviceAvailability…(其余), OpsDeviceRental, OpsDeviceEnv, OpsJiangyi*, AdminJiangyi*, SessionInternal, VisionInternal, JiangyiInternal, SmsInternal, OpsAlertInternal, DemoDataInternal, OpsDevOps, OpsOta, DataConsistency, Media, Announcement×3, Notification×2, **OpsController（最大，单独）** | 未开工 |

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

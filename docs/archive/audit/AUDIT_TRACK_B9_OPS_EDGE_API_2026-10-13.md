# Track B-9 分报告 · 运维/边缘接口审计（2026-10-13）

> 范围：实审 44 个 Controller（91 总枚举减去 B-1~B-8 已归属域）——DevMock\*、\*Internal、监控/导出/调度、文件上传下载、通知/公告、OpsCommercial/OpsFinance/OpsPlatformFund、OTA、MQTT 凭据、落地页等边缘域。
> 方法：只读子代理初审 + **主会话复核定级**（commercial-flow 一条由子代理 P1 降档 P2，理由见行内）。
> 台账 ID：子代理未自编号，按主台账顺序立 AU-124~131。
> 路径缩写：T = `services/trade-service/src/main/java/com/aicabinet/trade/`

## 发现

### P1：无

### P2（含定案改判）

| 台账ID | 位置 | 发现 | 证据 | 定级说明/处置 |
|--------|------|------|------|--------------|
| AU-124 ✨ | `T api/OpsCommercialController.java:33` + `service/CommercialFlowService.java:61-173` | 「一键跑通演示」端点 `POST /api/v2/ops/admin/commercial-flow/run` prod profile 下仍可达：无条件造演示供应商/采购单/补货路线/出库，再走 `createSessionForDevTest`+`completeDevUploadRecognition`→**真实订单并结算**（`SessionSettleService:240`），最后跑当日对账 | 主会话复核：仅 `@RequiresPermissions("ops:admin")` 门（:33），无 `@ConditionalOnProperty`/mockEnabled 运行期判断 | **子代理判 P1→主会话降 P2 定案**：进入前提=已持 `ops:admin`（超管保留码不可下发，见 PermissionService ops:admin 语义+B-8 结论），超管本可造任意数据；残留风险=**生产脏数据**而非越权。处置：加 `mock-enabled` 条件装配或运行期拒绝（与 ProductionStartupValidator 拒 mock 语义对齐），随配置门禁批（AU-074/075）。**✅批八-8d 已修**：`CommercialFlowService.runFullFlow` 入口加运行期拒绝（`SecurityProperties.mockEnabled=false`⇒403，先于权限校验与一切造数；dev=true 不受影响，prod/staging 该开关恒 false 由 compose+validator 双钉），负向测试 CommercialFlowDemoStageGuardTest 先红 1/2（nothing-was-thrown）后绿 2/2） |
| AU-125 ✨ | `T api/AnnouncementController.java:36-39` + `service/AnnouncementService.java:57-59` | `/api/v2/ops/announcements/published` 无 `@RequiresPermissions`（该文件其余 5 mapping 全有，主会话 grep 实证），任何登录消费者可读**全部**已发布公告（含 MERCHANT/OPS 定向内容），且直接返回域实体（含 operatorId/targetScope/status） | clients/* grep 无调用方（疑似死端点） | 待修：删除或加 `ops:announcement:list`+audience 过滤+DTO |
| （并入） | `T api/OpsPlatformFundController.java:46-85`；`OpsDeviceRentalController.java:49-113` | 资金登记/押金写接口裸 `Map<String,Object>` 收参（Number 强转 CCE→500）；recharge 仅拒 ==0、**负数充值静默接受=变相出账** | 子代理报告（B-1 F-04 同款） | **不立新号**：与 AU-008（B-1 资金面 5×P2 批）完全重叠，修复随 AU-008 批 |

### P3（AU-126~131）

| 台账ID | 位置 | 发现（摘要） |
|--------|------|-------------|
| AU-126 | `ScheduledTaskController:142-145` | 任务执行失败内部异常 message 回显运营端（AU-113 同族） |
| AU-127 | `FileAttachmentService:86-119` | 争议证据上传 PENDING_DISPUTE 阶段无数量上限（MAX_EVIDENCE=5 仅 bind 时查 :130）→可堆积存储 |
| AU-128 | `NearbyDeviceService:40` | `/devices/nearby` 每请求全表 haversine，无空间索引/缓存（设备规模小可暂缓） |
| AU-129 | `NotificationService:204,252` | 通知列表 limit 未夹紧（消费者自作用域，影响小；对照 adminRecent :289 有 clamp） |
| AU-130 | `application.yml:362` + `ProductionStartupValidator` | XXL executor `dev-xxl-token` 默认值不在启动校验器清单，仅 compose `:?` 兜底；脱 compose 部署可带弱 token 上 prod |
| AU-131 | `OtaService:77-80` | OTA 仅 sha256 完整性校验，无固件代码签名——`ops:ota:publish` 即可向全部柜机推任意包（债务，接真硬件前评估） |

## 专项结论一：DevMock/mock 类端点生产不可达（三层防线，逐行核实）

1. **Bean 层**：三个 DevMock\* + DemoDataInternal 均 `@ConditionalOnProperty("aicabinet.security.mock-enabled"=true)`（`DevMockPaymentController:20` 等）。
2. **启动层**：`ProductionStartupValidator:118-135` 在 prod/staging 对 security/line-withdraw/merchant-withdraw/reconciliation/profit-sharing mock **硬抛异常拒启动**；`application-prod.yml:8`、`application-staging.yml:5` 字面 false。
3. **运行层**：`/api/v2/dev/**` 不在 WebConfig 白名单（:40-58）；`confirmRechargeMock` 带运行期 `!mockEnabled→404`+订单属主+锁（`PaymentService:321-334`）。
4. **部署层**：`docker-compose.production.yml:14-15` 双 env false；nginx 三份配置只代理 /api/v2。

唯一残留=以默认/dev profile 直接公网部署（validator 仅 warn）→ 已登记 AU-051/AU-079（部署门禁），不另立号。

## 专项结论二：内部端点（\*Internal\*）未公网暴露（证据链）

- 8 个内部控制器全部在 `/internal/**`；`InternalApiAuthInterceptor` fail-closed（key 未配⇒503 :28-31、常量时间比较 :84-88）。
- prod/staging 强制 CIDR 非空+key≥32 字符（`ProductionStartupValidator:83,143,187-196`）；compose `INTERNAL_API_ALLOWED_CIDRS` `:?` 必填。
- 网关三份路由全部 `location /internal/ { return 403; }`（nginx.conf:111-113、compose:137、full:147）；actuator 仅 health,info,prometheus；XXL-admin/EMQX dashboard/服务直连端口绑 127.0.0.1。
- device-service `DeviceInternalController` 指令白名单（:27-32,72）；`SmsInternalController:33` mock/staging 双闸。

## 干净面声明

- 全项目无 Spring Security，统一 AuthInterceptor(默认拒绝)+PermissionAspect+InternalApiAuthInterceptor；`requirePermission` 内嵌 requireOperator 硬界，消费者 ID 无法命中 ops 码；范围内 ops 控制器 mapping:注解 1:1（唯一例外=AU-125）。
- BOLA：消费者/商户读路径按 userId/merchantId 收敛；FileAttachment refType+refId 双核；H43 头像匿名枚举已修。
- 文件面：白名单+5MB+EXIF 剥离+UUID 对象键+normalize 防穿越。
- 状态机：ScheduledTask#run 只触发注册表内置描述符；LineWithdraw payout 双门+钱包锁。
- 信息泄漏：MQTT 凭据一次性返回、DevOps 不回显 GitHub token、公开配置键白名单、落地页 HtmlEscape+IP 限流。
- 幂等：JiangyiInternal upsert 契约、gather finishNotify 一次性、open-failed 非终态才迁移（AU-060 修复后含归属校验）。

## 存疑（③-4 立 DC-25，其余归并处理）

1. MerchantWallet/MerchantWithdraw 面 B-5「除外」与 B-1 覆盖边界——已在 B-1 审（AU-006/007 即出自此域），无遗漏。
2. OpsFinance/OpsPlatformFund 账务正确性深查归 B-1（AU-008 批）。
3. 无 profile 公网部署→ 归 AU-051/079 部署门禁 + F-8 部署仓缺口。
4. **OpsAnalytics footfall**：控制器传 operatorId 但 service 签名未接，任何持 `ops:analytics:footfall:view` 的线级管理员看全网聚合——立 **DC-25** 待拍板（有意全局聚合 vs scope 缺口）。
5. AdminDashboard adjustBalance/refund 与 B-1 交叠，权限标注 44/44 全覆盖，资金语义归 B-1。

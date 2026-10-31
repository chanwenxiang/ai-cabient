# R7 审计：增长风控（11 页）

- 执行 AI / 日期：Qoder（本会话）/ 2026-10-31 起
- 代码基线：HEAD `04d946c7`（起）
- 环境：本地 docker（trade `:18080`、nginx `:80`）；库 `aicabinet` @15433
- 账号：超管 `13900000001`；消费者 mock 登录（`SMS_MOCK_CODE`）
- 纪律：只读优先；写操作只验确认闸不提交；券/积分/红包涉及资金口径，数值对 SQL 真值

## 分批

| 批次 | 页面 | 状态 |
|------|------|------|
| 7a | `/phone-verify` `/risk` `/promotions` `/coupons` `/ad-assets` `/ad-campaigns` | ✅ 完成（前两页代码审查；后四页数值全对） |
| 7b | `/points-redeem` `/member-levels` `/marketing-roi` `/notifications` `/feedback` | ✅ 完成（数值全对；feedback 暴露字典重名 → MA-018） |

## 代码审查（用户指定先看 `/phone-verify` 与 `/risk` 的核心代码）

### 手机验证（consumer 短信登录链 + admin `/phone-verify` 日志页）

**做得对的部分（附代码锚点）**

| 控制点 | 实现 | 位置 |
|--------|------|------|
| 发码前图形验证码 | `captchaService.verifyOrThrow` 前置（captcha 关闭时自动跳过） | `AuthController:67-74` |
| 同号冷却 60s + 每小时 5 条 | Redisson 桶/计数 | `SmsCodeService:31-36,129-146` |
| 错码 5 次烧码 | 计数达 5 ⇒ 标记 `used_at` 并清计数 | `SmsCodeService:96-108` |
| 6 位随机码 | `SecureRandom` + `%06d` | `SmsCodeService:196-203` |
| 一次性 / TTL | `used_at` 单次消费；TTL 走配置 | 同上 |
| 日志不落明文 | 手机号 `139****0000`、码 `12****` | `maskPhone/maskCode` |
| 内部取码接口不外泄 | `/internal/v1/sms/latest-code` 仅 mock/staging，生产 403 | `SmsInternalController:31-35` |
| **防手机号枚举** | 未注册与密码错统一 `INVALID_CREDENTIALS`，并计入登录限流 | `AuthService.requireExistingUser:364-374` |
| dev 万能码边界 | `SMS_MOCK_CODE` 仅 mock 开启生效；生产启动校验禁止 123456/000000 | `application-dev.yml` + `ProductionStartupValidator` |

**发现并已修**

- **MA-017（P3，已修）**：`verifyCode` 原为「查出未用 → save(used_at)」，并发请求可同时消费同一码。改 `markUsedIfUnused(id)` 条件更新（`WHERE used_at IS NULL`）；抢不到判失败**但不计失败次数**。新增 `SmsCodeServiceTest` 4 条钉测全绿。

**观察项（不改，记录）**

1. `sendCode` 在 DB 事务内先写 Redis 冷却/计数再发短信 ⇒ 若事务回滚，用户被冷却 60s 却没收到码（体验，非安全）。
2. 发送限流只有「手机号」维，无 IP 维；有图形验证码前置 + 生产禁万能码，风险中低。

### 风控（`RiskControlService` / `RiskAutoDispositionService` / `OpsRiskAdminService`）

**做得对的部分**

| 控制点 | 实现 | 位置 |
|--------|------|------|
| 黑名单拦截（含过期判定） | 命中记 `BLACKLIST_HIT`(BLOCK) + 403 | `RiskControlService:68-78` |
| 欠款阶梯限制再开门 | CB-011 判据 = 累计欠款次数；**明确不跨商户、不上征信** | `:80-90` |
| 1 小时开门频控 | 超限记 `MALICIOUS_OPEN` + 429 | `:92-99` |
| 欠款事件**只记不拦** | 结算主流程调用，`try/catch` 保证不打断收尾（注释写明） | `:118-138` |
| 纠纷频次自动拉黑 | 7 天 ≥ N 次 ⇒ 自动拉黑 30 天 + 事件；分布式锁 + 已存在则跳过（幂等） | `:141-151,175-194` |
| 人工拉黑/解除 | 每用户锁 + `FOR UPDATE` + 记事件（含操作人） | `:153-173` |
| 自动处置 | INFO 72h→AUTO_CLEARED、WARN 168h→ACKED，小时数可配、0 关闭、每事件分布式锁 | `RiskAutoDispositionService:62-104` |
| 运营账号豁免 | `userId >= 100000000` 跳过消费者实名/余额/风控 | `UserValidationService:71-73` |
| 后台权限 | `ops:risk:list` / `ops:risk:blacklist`；分页 size 钳 ≤100 | `OpsRiskAdminService:40-50` |

**观察项（不改，记录）**

1. `onDisputeCreated` / `autoBlacklist` 未加 `@Transactional`：其中 `findByIdForUpdate` 在自动提交下**拿不到行锁**；「拉黑 + 记事件」也不是同一事务（有分布式锁兜底，影响低）。
2. `listRiskEvents` 无数据范围过滤（全局风控台语义）；`listBlacklist` 无分页（表小，可接受）。
3. `RISK_CONTROL_ENABLED` 默认 true、prod 频控默认 5 次/小时、纠纷 3 次/7 天（dev 放宽为 200/50）——上线前确认运营频次是否合适（属参数，不是缺陷）。

## 字典同名标签盘点 + 新门禁（用户 2026-10-31 要求「看看能不能加门禁」）

### 全量盘点（真库 + Flyway 全量迁移库，两处结果一致）

| dict_type | 标签 | 值 | 代码写的是哪个 | 建议规范值 |
|-----------|------|----|----------------|------------|
| enable_status | 停用 | `DISABLED` / `INACTIVE` | 两套词表（账号类 ENABLED/DISABLED、通用 ACTIVE/INACTIVE）| 按使用面拆类型 |
| feedback_status | 已回复 | `HANDLED` / `REPLIED` | **HANDLED**（UserFeedbackService:118） | HANDLED |
| ops_alert_type | 对账差异 | `RECON_MISMATCH` / `RECONCILIATION_MISMATCH` | **RECON_MISMATCH**（ReconciliationService:216/283） | RECON_MISMATCH |
| restock_line_type | 下架 | `PULL_OFF` / `PULL` / `REMOVE` | **PULL_OFF**（ReplenishmentService:82），读取端仍兼容三者 | PULL_OFF |
| session_kind | 消费 | `CONSUMER` / `SHOPPING` | **CONSUMER**（`SessionKind` 只有 4 类；SHOPPING 是 session_state 的值，误入） | 下线 SHOPPING 项 |
| split_status | 失败 / 已提交 | `WECHAT_FAILED`/`FAILED`、`WECHAT_SUBMITTED`/`SUBMITTED` | **WECHAT_\***（RevenueSplitService:27） | WECHAT_* |
| warehouse_movement_type | 手工入库 | `MANUAL_INBOUND` / `INBOUND_MANUAL` | **MANUAL_INBOUND**（WarehouseService:261 等） | MANUAL_INBOUND |

**根因**：8 组全部来自 `SysDictBootstrap` 的**兼容别名**（为历史数据保留两套词表），不是手工脏数据；表现为各页筛选下拉出现同名两项（首见 `/feedback` 的「已回复」）。

### 门禁（已上线）

`DictLabelUniquenessIT`（Testcontainers + Flyway 全量迁移后断言）：

- 判据：`SELECT dict_type, dict_label FROM sys_dict_data WHERE status='ACTIVE' GROUP BY … HAVING COUNT(*)>1` 必须为空；
- **棘轮**：白名单 `KNOWN_DUPLICATES` 与实跑集合**必须完全相等**——新增重名红；清理后的残留条目也会红（提示同步删）；
- 已登记 `check-docker-tests-guarded`（job=integration，`*IT.java` 走 failsafe），首次实跑即抓出全部 8 组；
- 为什么不用静态扫 SQL：迁移里字典插入有 ≥4 种写法（`VALUES`、`INSERT … SELECT … FROM (VALUES …)`、带 EXISTS 守卫的单行 SELECT 等），正则解析漏报率高，**全量迁移后的库**才是唯一真值。

### 清理方向（待拍板，见 ISSUES MA-018）

- A 清理式：每组定规范值、旧值停用/删除并迁历史数据（历史标签可能掉回裸码）；
- **B 选项层收敛（推荐）**：字典保留全部值用于历史渲染，只在「下拉选项」返回规范值（配 canonical 清单）——不动数据、不丢历史标签、立即消除重复项。

## 逐页结果

### 页面：营销活动（`/promotions`）

- 列表 3 = SQL `promotion_activity` 3 ✅；首行「新客开门礼」预算 **¥20000 / 已用 ¥2.00 / 剩余 ¥19998** —— 减法自洽 ✅；表头含 每人限次/适用柜/状态；筛选 关键词+状态（启用/停用）；行操作 停用（写，未提交）；工具栏含导入/导出/下载模板；console 0 error。

### 页面：优惠券（`/coupons`）

- 列表 6 = SQL `coupon_definition` 6 ✅；**发行数对真值**：券 1（满减券 ¥5）显 `0/5000` = 无 `user_coupon` 行；券 2（新人立减 ¥2）显 `2/5000` = `user_coupon` 恰好 2 行（1 张 UNUSED、1 张 USED），与 `issued_count=2` 一致 ✅。
- 表头含 面值/最低消费/有效期/绑定活动/说明；行操作 编辑/发券/更多操作；工具栏 新建优惠券/手动发券/批量发券（写，未提交）；console 0 error。

### 页面：素材库（`/ad-assets`）

- 列表 1 = SQL `media_asset` 1 ✅（演示屏保视频 · 视频 · 15 秒 · 在用）；工具栏 上传素材；行操作 编辑/删除；console 0 error。

### 页面：投放计划（`/ad-campaigns`）

- 列表 1 = SQL `ad_campaign` 1 ✅；首行「演示屏保投放」：素材 **1 个** = `ad_campaign_item` 1 ✅；**柜机屏 0 台定向 / 柜机数 0** = `ad_campaign_device` 0 ✅；**曝光 0 / 完播 0** = `ad_play_event` 0（`ad_revenue_daily` 0）✅；行操作 编辑/停止；console 0 error。
- 备注：本页关键词已按 **MA-012** 改服务端过滤（`?q=` 进 SQL，状态标签翻值再传 `status`），本批复测未出现「行数与总数不符」。

## 逐页结果（7b）

- **积分兑换管理**（`/points-redeem`）：6 = SQL `points_redeem_item` 6 ✅；首行「满 20 减 5 券 · 需 100 积分 · 库存/已兑 2000/0」；表头含 所需积分/兑换优惠券/券定义/可兑/排序/状态；console 0 error。
- **会员等级规则**（`/member-levels`）：4 = SQL `member_level_rule` 4 ✅；首行「普通会员 ¥0.00~¥1000.00 / 下一档积分门槛 0 / 倍率 1 / 折扣 无」；行操作 编辑/停用；console 0 error。
- **活动效果分析**（`/marketing-roi`）：3 = SQL `promotion_activity` 3 ✅；首行「新客开门礼 预算 ¥20000 / 已用 ¥2.00 / **发券 2 / 核销 1**」——与 `user_coupon` 2 张（1 张 USED）**完全一致** ✅；console 0 error。
- **消息记录**（`/notifications`）：**36** = SQL `notification_log` 36 ✅（页内 20 条为分页）；首行「订单支付成功 · 消费者 · 关联单号 1792572…」；工具栏 发送站内信/删除选中（写，未提交）；console 0 error。
- **用户反馈**（`/feedback`）：0 = SQL `user_feedback` 0 ✅（空态「暂无反馈」）；🔴 状态筛选出现两个「已回复」→ 已升级为字典级问题 **MA-018**（见上节「字典同名标签盘点」），门禁已上线。

## 发现汇总

| 级别 | 数量 | 编号 |
|------|------|------|
| P0 | 0 | — |
| P1 | 0 | — |
| P2 | 0 | — |
| P3 | 2 | MA-017（验证码并发双花，已修）· MA-018（字典 8 组同名标签，门禁已加、清理待拍板） |

## 给下一轮的交接注记

- 短信/风控是 security-sensitive 路径：改这里必须先读 `ProductionStartupValidator`（mock 相关配置）与 `SmsInternalController` 的生产闸。
- `SMS_MOCK_CODE` 在 dev 会短路校验（不走 DB 码），**验证码相关缺陷不能用万能码复现**，以钉测为准。

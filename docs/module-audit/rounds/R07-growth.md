# R7 审计：增长风控（11 页）

- 执行 AI / 日期：Qoder（本会话）/ 2026-10-31 起
- 代码基线：HEAD `04d946c7`（起）
- 环境：本地 docker（trade `:18080`、nginx `:80`）；库 `aicabinet` @15433
- 账号：超管 `13900000001`；消费者 mock 登录（`SMS_MOCK_CODE`）
- 纪律：只读优先；写操作只验确认闸不提交；券/积分/红包涉及资金口径，数值对 SQL 真值

## 分批

| 批次 | 页面 | 状态 |
|------|------|------|
| 7a | `/phone-verify` `/risk` `/promotions` `/coupons` `/ad-assets` `/ad-campaigns` | 🟡 进行中（前两页**代码审查**已完成） |
| 7b | `/points-redeem` `/member-levels` `/marketing-roi` `/notifications` `/feedback` | 未开始 |

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

## 逐页结果

（7a 其余四页 + 7b 待填）

## 发现汇总

| 级别 | 数量 | 编号 |
|------|------|------|
| P0 | 0 | — |
| P1 | 0 | — |
| P2 | 0 | — |
| P3 | 1 | MA-017（验证码并发双花，已修） |

## 给下一轮的交接注记

- 短信/风控是 security-sensitive 路径：改这里必须先读 `ProductionStartupValidator`（mock 相关配置）与 `SmsInternalController` 的生产闸。
- `SMS_MOCK_CODE` 在 dev 会短路校验（不走 DB 码），**验证码相关缺陷不能用万能码复现**，以钉测为准。

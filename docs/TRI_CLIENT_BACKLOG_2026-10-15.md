# 三端未做事项全景清单（2026-10-15 13:15）

> 来源：10-05 全仓审计（Explore 逐项核对修复状态）+ 10-12 新旧系统全量复扫 + 10-15 老系统差异对照与 dev 全面审计 + 竞品台账 backlog。已排除用户明确否决项（储值余额预警、拍照/断电/网络延迟下发）与已完成项。

## 一、今天（10-15）全面审计发现、抽查确认仍未修 —— 最高优先

| # | 端 | 项 | 一句话 | 抽查证据 |
|---|---|---|---|---|
| T1 | admin | **P0① 两页面全员不可达** | LineRoutesView/MachinePayeeBillsView 未登记 menu.ts、路由 meta 无 perm → fail-closed 守卫 403 | `config/menu.ts` 零命中；`router/index.ts:182,188` meta 只有 title |
| T2 | 后端 | **P0② 佣金冲正基数错** | 部分退款按整单全额冲回（多扣钱）+ 发佣前退款永久漏发；GMV/单数基数应为 `total−refunded` 净额 | 差异文档 §6.1，老系统口径佐证 |
| T3 | 后端 | **P0③ 押金负余额误锁全柜** | `debitIfAbsentAllowNegative` 负余额被当费用欠缴；`pendingFeeTotal` 写好零调用 | grep 全仓仅 `MerchantDailyIncomeNotifyScheduler:314` 定义 |
| T4 | 后端 | P1 簇 | 台账 SETTLED 与钱包不联动（结清不解锁）／负余额 doDebit 412 阻断消费者退款冲正／零销售日 early-return 跳过扣费催缴／staff 钱包无行锁／换绑线长同 device-day 双付 | PROJECT_KNOWLEDGE 608 |
| T5 | consumer | **争议结果零触达** | 模板已种、无调用方——两代系统都缺，只差接线 | grep notify/send 零命中 |
| T6 | admin | 打款按钮权限错配 | `ops:replenishment:edit` vs 后端 `ops:line-manager:*` + catch 空吞 | `LineRoutesView.vue:447` 仍在 |
| T7 | 后端 | 将邑争议自动免单永不命中？ | `DisputeTicketMapper:60` `likeRight(reason,"识别超时")`；但 `DisputeService:194` 生成的确是同前缀——**待核**：免单门控比对的是落库 reason 还是展示文案 | 两处并存，需场景实测 |

## 一·补记：今日审计 T1-T7 修复实录（2026-10-15 下午，本会话）

| # | 状态 | 修法 |
|---|---|---|
| T1 页面不可达 | ✅ 已修 | menu.ts 登记两页（perm=后端读门槛 ops:replenishment:list / ops:device:list）+ 路由 meta.perm 双保险 |
| T2 佣金冲正基数 | ✅ 已修 | 新 `CommissionBaseCalc`：净额（total−refunded）+ 含 PARTIAL_REFUNDED；发佣（LineCommissionJob 两处）与冲正重算（CommissionClawbackService）同源切换；+2 钉住用例（部分退款按净额差额、全额退款不计基数） |
| T3 押金负余额误锁 | ✅ 已修 | checkOverdueAndRemind 判据改为 `pendingFeeTotal`（费用口径债=PENDING 台账累计，与 MerchantFeeLedger 设计注释一致）；+3 钉住用例（无费用债不催不锁 / 超阈锁柜 / 结清只解前缀） |
| T5 争议结果零触达 | ✅ 已修 | DisputeService 接 `dispute_resolved`（V163 已种模板）；人工结案 + autoWaive 两处发站内信，@Autowired(required=false) 不动 4 个测试构造，try/catch 不阻断结案主链 |
| T6 打款按钮权限错配 | ✅ 已修 | LineRoutesView 钱包操作 perm 对齐 OpsStaffWalletController（payout=ops:line-manager:edit、ledger=list） |
| T7 免单前缀不命中 | ✅ 已修（定性=文案错位） | JiangyiRecognitionTimeoutService failReason 修正为「识别超时（将邑端识别结果超时未回传）…」——该清扫本无 class 映射动作，旧文案让将邑超时单永远进不了自动免单兜底；SessionExpire 路径原本就命中 |
| T4-2 负余额阻断退款冲正 | ✅ 已修 | RevenueSplitService.clawbackWalletDelta 改 debitIfAbsentAllowNegative（冲正=对账动作可挂负，不受余额闸 412 拦截；幂等键不变）+ 测试适配钉住 |
| T4-3 零销售日跳过扣费催缴 | ✅ 已修 | sendDailyIncomeNotify 的 early-return 只对通知生效——空收入日也调 chargeAndCheckAllMerchants（CB-041 修复面留下的洞：全员零销售日整批费用/催缴/解锁被跳过） |
| T4-4 staff 钱包无行锁 | ✅ 已修 | StaffWalletAccountMapper 加 findByIdForUpdateRaw（FOR UPDATE，XML 同 LineWalletAccountMapper），StaffWalletService.reload 写路径改行锁读=分布式锁+行锁双层 |
| T4-5 换绑线长双付 | ✅ 已修 | ①线长：doPostCommissionTx 查重升到 device-day 维度（existsByBizDateAndDeviceId，同柜同日只付一次）；②staff GMV 模式同 device-day 只付一次（RESTOCK_QTY 按件计酬不动） |
| T4-1 结清不解锁 | ✅ 已修 | scheduler.unlockFeeOverdueIfCleared 公开入口；MerchantFeeLedgerService.settle 实际置 SETTLED 后调用（结清即解锁，不等次日调度；幂等无副作用） |

验证：佣金 6 例、调度器 6 例、Dispute 32 例、免单/过期 10 例全绿；trade 容器重建。

## 二、10-05 大审计剩余（P0 4/6 关、P1 12/17 关后的遗留）

| # | 端 | 项 | 状态 |
|---|---|---|---|
| A1 | edge+后端 | ~~P0-2/P0-3（同根）~~ ✅ 主体落地（2026-10-15 拍板 A+B 全做） | P0-2：HTTP 设备凭据一机一密（V367+拦截器+APK 装机页，fleet key 退出设备面）；P0-3：将邑激活制（V366 auth_enabled+未激活告警+突发检测）。二期待真机：控件固件 per-device secret（JIANGYI_GATEWAY_DESIGN.md 7.6） |
| A2 | 基建 | ~~P1-8 Kafka PLAINTEXT 无 SASL + modelVersion 丢弃~~ ✅ 已完成 | 2026-10-15：监听器并轨 HTTP ingest 口径（modelVersion 必填+deviceId 绑定，9.4-1 一并收口）+ Redpanda SASL/SCRAM（三态实测） |
| A3 | edge | P1-6 残留：OTA checksum 与下载同通道；P1-7 残留：`usesCleartextTraffic` 未关 | 部分修复 |
| A4 | 基建 | P1-14 残留：postgres/redis/mysql 等有状态镜像仍浮动 tag | 部分修复 |
| A5 | 后端 | ~~P1-17 补货实收 qty≤0 静默丢行（应拒绝）~~ ✅ 已修（2026-10-15 实证：ReplenishmentService:1054 qty≤0 已 400 拒绝，注释带审计编号——backlog 行状态未同步而已） | 无剩余 |
| A6 | P2×9 | ✅ **8/9 已修（2026-10-16）**：①eventSeq 去重键可控→DoorEventDeduplicator 加同 session+state 60s×30 次滑动窗口限流（rate_limited 指标+2 例单测）；②EdgeRuntimeConfig 加密失败不再回落明文（抛异常拒绝保存）+allowBackup=false；③VISION CIDRS 全非法 fail-fast（parse_cidrs_strict）+VISION_TRUSTED_PROXY_CIDRS 网段化信任代理（废弃布尔 VISION_TRUST_PROXY，与 Java 侧对称，5 例新测试）；④admin 自建 yuan() 15 处收敛 display.ts（yuanTextZero/yuanTextNone 显式命名，MemberLevels 元口径保留）；⑤toLocaleString 4 处补 Asia/Shanghai；⑦privacy-consent 未同意拦全部 H5 请求（shared request 单点，同意后整页重载）；⑧check-compose-ports ALLOWLIST 须 compose 侧行尾 EXPOSE-ALLOW 双处留痕（负向自测 2 例）；⑨e2e 锁 finally/TOCTOU 批次4 已修无需动。**⑥商户登录明文存手机号 ✅ 已修（2026-10-16 用户拍板「删明文存储」）**：删「记住账号」开关与手机号持久化（登录体验由 30min token+24h 静默续期承担）；老版本 storage 残留键启动即清。配套调研 CB-048：C 端已对齐微信官方推荐形态不动；**B 端「微信绑定免密登录」（密码登录后显式绑定 openid，绑定后静默免密；对齐西陆运维端形态）列为二期建议**。 | 8/9 修复 + ⑥已闭环 |
| A7 | P3×5 | surrogate 截断、reasonKey 死代码、vision async stub、MQTT clientId 随机、OutboundMqttQueue 丢第 0 条 | 未修（低危） |

## 三、业务缺口（已采纳未实施 / 待拍板）

| # | 项 | 说明 |
|---|---|---|
| B1 | ~~退款重试 Job（G1）~~ ✅ **已完成（2026-10-15，00abda8c）** | REFUND_INITIATED 痕迹 + RefundRetryScheduler 分钟级闭环 + Registry/V364 登记；12 钉住用例，容器/CI 验证通过 |
| B2 | ~~渠道费实结化~~ ✅ **已完成（2026-10-09，a82fb0f9 V331）** | 微信账单手续费列解析 + 实结分摊尾差归集 + ACTUAL/ESTIMATED 标注 + 费率差异告警。**有意留尾**：①支付宝账单手续费列待渠道接入时补列位（AlipayBillCsvParser）；②逐单明细行实结（FundBillChannelFeeRateTest 钉住估算契约，日账单已实结优先） |
| B3 | 审计 backlog（CB-021） | ~~前后值/RBAC 留痕/权限点/报表化~~ ✅ 全部完成（前三项早前已落码，2026-10-15 核实；第 4 项敏感操作汇总报表本日补齐） |
| B4 | ~~客服触点~~ ✅ **已完成（2026-10-09，a82fb0f9）** | merchant.service_phone 配置键 +「我的」页帮助与客服（fail-closed）；商户端实机验收通过 |
| B5 | ~~押金撤机规则~~ ✅ 已完成 | 2026-10-15 拍板选项 1 并落地：撤机清算三件套参数化（V365 + registerDeposit 可配 + settle 三分支），CB-044 |
| B6 | ~~点位多收款人~~ ✅ **已拍板：维持单组（2026-10-15 方案 A，CB-043）** | 老存量 1027 点位实盘：93% ≤1 组收款人、99.6% ≤2 组、合作2/租金收款人均 <2% 死字段；新单组+电费独立行+线长独立佣金覆盖 99.6%，4 个 3 组点位迁移时线下合并；不为 0.4% 存量上子表工程 |
| B7 | ~~机器通道购买账单~~ ✂️ **不延续（2026-10-15 用户拍板「B7 不要」）** | 老「货道买断给个人」新系统不实现；留此行仅作决策留痕 |
| B8 | ~~券订单退款口径~~ ✅ 已完成 | 2026-10-15 拍板选项 2 并落地：全额退返券、部分退比例摊（券不返/不重算门槛），CB-045 |
| B9 | ~~台账文档纠偏~~ ✅ 已完成（2026-10-16） | 核查：台账中**不存在**「老系统无自动打款/佣金黑盒」两类表述（多重关键词+共现检索实证；CB-033/040/043/044 写作时已按亲验口径引用 文件:行号，E3 的 240 元硬编码等事实已在 CB-043 条目内）。唯一需精确化处=CB-040 结论的「ego-automat 佣金从不冲正」——已补 E1 勘误锚点（mis-server RyTask:151-187 / T+10 日结 / 支付宝代发，勘误只澄清实现位置与结算节奏，不改变「从不冲正」事实与缺陷判定）。PROJECT_KNOWLEDGE:609 待办标注因该文件由并发会话持有未同步更新。 |

## 四、功能对照剩余（easygo 硬缺口清单里还没做的）

| # | 端 | 项 | 说明 |
|---|---|---|---|
| C1 | ~~admin~~（**端口纠偏 2026-10-15**：原写 merchant-mp，10-14 拍板即「管理界面并入外部导入模块映射页」） | ~~外部商品库页面~~ ✅ **已完成** | admin `ExternalChannelImportTab.vue`「外部商品映射」tab（绑定/改绑/解绑+未映射预览）已上产（static/admin/assets chunk 已核） |
| C2 | admin | ~~理货记录台账~~ ✅ 已完成 | 2026-10-15：replenishment_task×line 行粒度只读聚合（不建新表），补货员效率页新增 tab |
| C3 | admin | ~~商品模板~~ ✅ 已完成 | 2026-10-15：V368 铺货模板（存当前设备陈列为模板/一键套用），CB-047 |
| C4 | 商业 | 支付宝准入门槛 | 外部资源/商务决策，非代码 |

已做（本轮确认关闭）：单柜 SKU 上限、商品排序/批量重置价、本月销售额、信号强度、SELF_TEST 入口、音量下发、商户账单页、缺货口径统一、结算单实体化、平台资金池+租赁押金、大屏下钻、品牌/单位字典化、admin 三层权限清零。

## 五、环境/收尾杂项

- 今日全部改动（账单页+将邑控制+三断点修复+巡柜口径）**未提交**——工作区有并发会话文件，提交须显式路径。
- dev 库走查/e2e 痕迹未还原：device 500909625160 仍 RENTAL+rent 30000（10-12）、replenishment task 3/4、出库单 2、今日主仓绑定已还原。
- Observability 页 6 个 Grafana iframe 401/404——待用户浏览器内确认 Basic 认证（10-12 遗留）。
- 将邑模拟器不响应补货开门指令——本地无法闭环补货完成段，如需要扩模拟器。

## 建议动手顺序（2026-10-15 17:40 复核更新）

1. ~~今天审计的 T1-T5~~ ✅ 全部完成（T1-T7，含 T4 五子项）。
2. ~~B1 退款重试 Job~~ ✅ 完成（00abda8c）。
3. **A1（P0-2/P0-3 fleet 密钥）**——接真硬件前的硬门槛，**当前唯一高优工程项**，建议提前立项（安全工程问题，不依赖竞品对照；参考微信/支付宝 IoT 官方设备鉴权范式：一机一密/动态凭证）。
4. ~~B5/B6/B7/B8 产品拍板~~ 全部已拍板并落地（B5=CB-044 选项1、B6=CB-043 方案A、B7 不延续、B8=CB-045 选项2）。
5. A2（P1-8 Kafka SASL）基建加固✅、A6（P2×9）8/9 已修（⑥手机号存储待拍板）、B3/C2/C3/B9 ✅；剩余 A7（P3×5 低危）未排期。

# O3 · 告警升级链（P0 短信/电话 + 值班表）证据

> 2026-09-19 第十九/二十轮。对应路线图条目 **O3**（`docs/COMPETITOR_BENCHMARK_AND_ROADMAP_2026-09-17.md`）。
> ⚠️ 本文**刻意不写行号锚点**（行号会随无关改动漂移，本仓已有 `ci.yml:297` 漂 147 行的前科）；
> 引用一律用**方法名 / 常量名**，它们被门禁真校验。

## 0. 目标与范围

原文：`钉钉/企微/Webhook → 增加 P0 告警电话/SMS 升级链、值班表联动（配合争议 SLA 值班表 P1 项）`。

**升级链**＝聊天渠道（群机器人）**没人收到**时，改用**个人**渠道（短信 / 电话）叫值班人。
本节交付的是**链路与语义**；真实发送需要短信/外呼网关凭据（外部），与飞书同一口径 ——
**未配则不打，消息落日志**。

## 1. 设计决策（每条都对应一个「不做就会静默失效」的场景）

| 决策 | 为什么 | 反例（不这么做会怎样） |
|---|---|---|
| 触发条件是「**一条都没真的送达**」，含「压根没配聊天渠道」 | P0 告警**没人收到**本身就该升级 | 若只在「配了但全失败」时升级，则**忘配飞书**的环境永远不会升级，且日志看不出来 |
| 收件人来自**值班表**，解析不到就 `NO_ONCALL` **不发送** | 升级打的是**个人**，猜错代价极高（半夜打错人 / 该叫醒的人没被叫醒） | 回退到「某个默认号码」⇒ 信号是绿的（"打过电话了"），但打给了错的人 |
| 两级：**先短信、短信不成再电话** | 电话是打扰性最强的渠道，只在短信这一级没成功时才用 | 一级成功还打二级 ⇒ 值班人被短信+电话各吵一次 |
| SMS/PHONE **判业务码**（`code == 0`） | 短信网关在「余额不足 / 号码黑名单 / 模板未报备」时**常回 HTTP 200** | 只看状态码 ⇒ 升级链自认为成功、值班人从没收到（本项目最忌的「信号在骗读者」） |
| 类型白名单**逐字相等**，不用前缀/包含 | 防「近亲类型」被顺带升级 | `WECHAT_REFUND_ABNORMAL` 的名单会命中 `WECHAT_REFUND_ABNORMAL_EXTRA` |
| 总开关 seed=`FALSE` ＋ 读取兜底 `false` | 新装环境**默认零行为变化** | 缺省即开启 ⇒ 半夜打电话没人知道为什么 |
| `send` 与 `trySend` **两条路径都**触发升级 | 二者都可能承载 P0 告警 | 只接一条 ⇒ 「有的告警会升级、有的不会」，两条路径日志都正常 |

## 2. 交付物

| 文件 | 内容 |
|---|---|
| `services/trade-service/.../service/OnCallRoster.java` | **新增**。值班表解析 + 「此刻谁在班」。ISO 星期、`[startHour,endHour)` 半开区间、**支持跨夜班**（`startHour > endHour`）。解析失败 / 非数组 / `days` 全是非法值 / `phone` 为空 ⇒ **退化为空表或跳过条目**（fail-closed），**不抛异常** |
| `services/trade-service/.../service/OpsAlertDispatcher.java` | `send`/`trySend` 抽出共用 `fanout(...)` → `Fanout(anyConfigured, anyDelivered)`；新增 `escalateIfNeeded` / `isEscalationType` / `escalateTo` / `escalationPayload` / `now()`；`deliveryError` 增 `case ESCALATION_SMS, ESCALATION_PHONE -> "code"`；类文档补升级链段落。**顺手删掉了只被 `send` 用过的 `post()`**（已无调用方） |
| `services/trade-service/.../service/SystemConfigService.java` | 新增 **5** 个配置常量 + seed（`ops.alert.escalation_enabled` / `_types` / `ops.alert.oncall_roster` / `_sms_webhook` / `_phone_webhook`）。键名刻意留在 `ops.alert.` 命名空间 ⇒ **自动被** `check-ops-alert-channels` 的 R5（seed）/R6（运营台可见）接管 |
| `clients/admin-vue/src/views/system/AlertRuleView.vue` | 新增「告警升级链」分组，把 5 个键露出来（运营台可改） |
| `scripts/check-ops-alert-escalation.mjs` | **新增门禁**（E1–E9，见 §3），已接进聚合链（`check:audit-gates` 第 6 位） |
| `docs/evidence/2026-09-19-o3-escalation/scripts/ab-drift-ops-escalation.mjs` | **新增**。门禁的负向对照：11 条漂移（D1–D11），逐条「注入 → 必红 → 还原 → sha256 逐字节一致 → 回绿」 |

单测：

| 文件 | 用例数 |
|---|---|
| `OnCallRosterTest` | **12** |
| `OpsAlertDispatcherEscalationTest` | **17** |
| `OpsAlertDispatcherTest`（既有，回归） | **30**（全绿 ⇒ 重构未破坏原语义） |

## 3. 门禁 E1–E9 与「怎么让它红」

| 规则 | 判据 | 让它红的方式（A/B 已实测） |
|---|---|---|
| E1 入口接线 | `send` 与 `trySend` 两条方法体内都必须出现 `escalateIfNeeded(` | D1 / D2：各删掉一处调用 |
| E2 默认关 | ①seed 值必须恰为 `FALSE`；②读取必须写成 `getBoolean(..., false)` | D3：seed 改 `"true"`；D4：兜底改 `true` |
| E3 不猜收件人 | `onCall == null` 分支必须 `return NO_ONCALL`，**且分支内不得出现** `tryPost`/`escalateTo`/`postJson` | D5：在该分支插一次投递 |
| E4 判业务码 | `deliveryError` 必须为 `SMS`/`PHONE` 声明看 `code` | D6：删掉该 case |
| E5 渠道绑定 + 禁硬编码 | 两级 URL 必须来自各自常量；文件内不得出现 `http(s)://` 字面量 | D7：一级错绑到电话的键 |
| E6 类型真实性 | 默认白名单 ≥3 个类型，且每个都必须能在 services 的 Java 源码里找到**带引号的字面量** | D8：把某个类型拼错一个字母 |
| E7 命名空间 | 升级相关键必须 `ops.alert.` 前缀（否则 R5/R6 管不到）＋必须在运营台分组里 | D9：把 `oncall_roster` 改成 `oncall.roster` |
| E8 不得豁免 | 升级键不得出现在 `INTERNAL_ALERT_KEYS` 里 | D10：加进去 |
| E9 值班表 fail-closed | `OnCallRoster.parse` 必须存在「返回 `EMPTY`」与「必须是 JSON 数组」两处 | D11：`return EMPTY;` 全改 `return null;` |

> 🔴 每条判据都**指出了让它红的方式**（上表右列），并由 A/B 实测；只写「存在性」不写「可红性」＝失效形态③。

## 4. 实跑验证（命令 + 输出）

```
node docs/evidence/2026-09-19-o3-escalation/scripts/ab-drift-ops-escalation.mjs
  → 基线绿 ✓ ；D1–D11 全部「红，还原字节一致且回绿」；11/11 用例符合预期        EXIT=0

node scripts/check-ops-alert-escalation.mjs
  → OK：两条路径均触发升级；总开关 seed=FALSE 且兜底 false；无值班人 fail-closed；
    SMS/PHONE 判业务码；两级绑定各自键且无硬编码地址；白名单类型均可在源码找到出处；
    键在 ops.alert. 命名空间且未被豁免                                        EXIT=0

node scripts/check-ops-alert-channels.mjs
  → OK: 4 条渠道；10 个 ops.alert.* 配置键全部 seed 且在运营台可见              EXIT=0

node scripts/check-audit-gates-wiring.mjs
  → OK：聚合链 31 个引用全部有定义；39 个 check-*.mjs 全部已接线；
        40 个 check:* 中 38 个可达 + 2 个本地豁免                             EXIT=0

node scripts/run-audit-gates.mjs
  → 聚合链 31 个门禁，失败 0 个                                                EXIT=0

mvn -B test -pl services/trade-service -Dtest='OnCallRosterTest,OpsAlertDispatcherEscalationTest,OpsAlertDispatcherTest'
  → EXITCODE=0；按 <testcase> 元素计数：12 + 17 + 30，failure/error 均为 0
    （⚠️ 不采信日志里的 "Tests run:" —— 本仓已记录它在 @Nested / IT 类会写 0）

prettier --check / eslint（直调 node_modules，不用 pnpm）
  → 全绿
```

## 5. 🔴 本轮自己踩的坑（比结论更该记）

1. **门禁 E6 第一版解析出 0 个类型**：默认白名单是 `"A,B," + "C"` 形式 —— **一个引号内含逗号**，
   而我按「一个引号一个类型」写正则 ⇒ 恒解析为 0。修法：**先把所有字面量并起来、再按逗号切**。
   （症状是门禁**红**、但红的原因是我自己写错，不是被测物坏 —— 「假红与假绿同害」。）
2. **A/B 全部「变异未生效」**：本仓 Java/脚本在磁盘上是 **CRLF**（`git ls-files --eol` → `i/lf w/crlf`），
   而我在 JS 里写的是 `\n` 模式 ⇒ 一条都匹配不上。修法：**按文件真实行尾归一**再匹配/替换。
   ⚠️ 若不查根因，很容易把这误读成「门禁没红」。
3. **`OnCallRoster` 自己埋过一个 fail-open**：`days` 写了但全是非法值（如 `[0,8]`）时，
   原实现会因 `days` 为空而**退化成「每天都算」** —— 把配置错误静默放宽成 7×24 值班。
   已改为**跳过该条目**，并留回归用例 `daysWithNoUsableValue_skipsEntry_insteadOfWideningToEveryDay`。
4. **prettier/eslint 各拦下一处**：门禁里读了 `alertView` 却没用（eslint `no-unused-vars`）。
   修法不是删掉读取，而是**真的用起来** —— 顺手断言「升级键必须在运营台分组里」，
   使本门禁即使将来 R6 被 `INTERNAL_ALERT_KEYS` 放宽也能独立断定。

## 6. 未覆盖（诚实边界）

- **真实发送未实测**：无短信/外呼网关凭据 ⇒ 两条升级通道的**真实投递**未端到端跑过；
  已实测的是「投递被业务码拒绝时会降级」与「未配置时视为该级不可用」。
- **升级链不在运营台「测试发送」覆盖内**：`probeChannels` 只遍历聊天渠道 `CHANNELS`（R7 要求如此），
  SMS/PHONE 没有试发入口 ⇒ 配错网关要等真告警才知道。
- 🔴 **`sms/WebhookSmsSender` 仍只判 HTTP 状态码**（`>= 400` 才算失败；见其 `dispatch`），
  与本次给 SMS/PHONE 立的「判业务码」标准不一致 —— 它是 **C 端**通知通道（登录码/通知短信），
  网关返回体契约未知，改动会影响登录链路，故**本批未动**，登记为待办。
- 值班表**没有「节假日 / 临时换班 / 生效期」**，也不校验号码真实性；它只是「此刻谁在班」的最小解析器。
- 升级链**没有去重/抑制窗口**（同一 P0 反复触发会反复打），也**没有回执/确认（ack）**机制 ——
  「升级到电话」只表示"已发起外呼"，不代表"值班人接听了"。真正的「未确认再升级」需要 ack 闭环。

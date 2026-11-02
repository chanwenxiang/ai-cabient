# R11 业务流转全链路（2026-11-02 起，段 1 已交付）

> 按 PLAN §R11：造数全链路剧本，三端点验落点，金额逐段对账。
> **段 1 = 消费下单 → 开门 → 端侧识别 → 结算 → 分账 → 商户钱包入账**（会员单形态，一并收掉 MA-029 的端到端腿）。

## 段 1 交付（锚 HEAD `270e22c3`，测试类 `MemberOrderChainE2ETest`）

### 为什么在 Testcontainers 里跑，而不是在活栈上跑（硬约束遵守记录）

值守期间探测到活栈 `:80` 上**有真实浏览器会话正在轮询 admin**：
`docker logs --since 25m ai-cabinet-gateway-1` 显示 `GET /api/v2/ops/admin/approvals/inbox` 每 60s 一发，
Referer `http://127.0.0.1/admin/feedback`，UA `QuarkPC/7.4.2.1020`，最后一条 18:32:36（本机时间 02:32）。
而 `:18080` 现跑镜像是 `f9905080`（**不含 MA-029**），活栈复走会员单必须先重建该容器 ⇒ 会打断他人在用的共享栈。
交接书 §4「不碰别的会话的容器/栈」优先，故本轮把这条链改成**仓内可重复、随 CI 跑**的 E2E 形态挣得，
活栈复走降级为段 2 的前置动作（须先确认无人在用再重建）。

### 剧本与判据（六层，逐层可对账）

| 层 | 判据 | 实跑数字（`R11-LAYER` 留痕） |
|----|------|------------------------------|
| 前置 | 消费用户必须真是 SILVER（档位 > 0） | pct 由 `member_level_rule` 读出并断言 > 0（为 0 则本用例退化，直接红） |
| 链 | 实名 → 充值 dev mock ¥200 → 开门 → 关门停 **RECOGNIZING** → 端侧 `edge-results` 采纳 | qty=2 上报被 `accepted=true`；关门态断言 `RECOGNIZING`（绿必须由端侧链挣得，禁 dev-cart 短路） |
| 落库 | `Σ 行金额 − 券 = total` 且 `gross − member − activity − coupon = total` | `gross=700 member=14 activity=0 coupon=200 total=486 lineSum=686` ⇒ 700−14−0−200=486 ✓、686−200=486 ✓ |
| 非零 | 会员档必须真的入账（MA-029 的核心） | `member=14`（=2×(350−343)），行单价 343 已是会员价 ⇒ 折扣含在单价里、**没有再被减第二次** |
| 读面 | consumer 与 merchant 从同一 DTO 读到同一组数 | 两端 `memberDiscountCents=14 / grossAmountCents=700 / totalAmountCents=486` 与落库逐字段相等 |
| 资金 | 分账闭合 + 账本型入账 + 台账↔账户 | `split[status=LEDGER_ONLY gross=486 platform=0 merchant=486]`、钱包 `SPLIT_CREDIT=486`、`walletBalance=50486`（种子 50000 + 486） |
| 对账 | 全库不得有「会员档 > 0 却被折后式判不一致」的单 | 违规 **0 行**（与巡检检测式同一条 SQL） |

🔴 这一单同时把 **会员 + 券** 两条优惠都跑了：券 ¥2.00 是订单级减项、会员 ¥0.14 含在单价里 ⇒
若 MA-029 的 ② 残留还在，`total` 会是 `486 − 14 = 472`（少收 14 分）——测试会当场红。

### 实跑结果

```
mvn -pl services/trade-service -am -Dtest=MemberOrderChainE2ETest -Dsurefire.failIfNoSpecifiedTests=false test
→ Tests run: 1, Failures: 0, Errors: 0  · 37.6s · BUILD SUCCESS（真 PG + 真 Redis，Flyway V1→V425 从零）
```

## 段 2+ 待做（下一会话的直接入口）

1. **采购入库 → 分仓 → 备货/补货任务 → 出库领货 → 上柜**：🔴 前置是 dev 只有 **1 个仓**
   （`warehouse` 实有 1 行 `WH-DEMO-001`、`manager_user_id=100000002`）⇒ 需要**新增**第二个仓当分仓
   （不删既有行），否则「本部仓→分仓」这一段是退化的（R9 M 批 5 已记）。
2. **争议 → 结案（CONFIRM/手工）→ 退款**，以及**月结 → 提现申请**（提现只到申请态，渠道走 dev mock；
   同时收掉 R9 未挣的「提现确认框是否披露 6‰ 手续费」）。
3. **公告内容腿**：`announcement` 0 行 ⇒ 走运营端正规写入口造 `AUDIT-` 公告，复验两端 4 页
   （并顺带给 MA-035 的缺参数文案一个「有数据」的正例）。
4. **活栈复走**：确认 `:80` 无人在用后重建 trade 镜像到含 MA-029 的 HEAD（顺带应用 V425），
   用模拟器柜 `500909625160` 真跑一笔会员单，复验 admin/商户端/消费端三处「会员优惠」行一致，
   并跑 `docs/module-audit/seeds/money-recheck.sql` 七段（7c 应 > 0 行 ⇒ 证明入账链真的在活库跑起来）。

## R11 段 2 · B3 公告读面（2026-11-02 完成）

- 造数：`announcement` 直插一条 `announce_id=9900000001 / status=PUBLISHED / publish_at=now`（表主键是 **bigint**，起初用字符串 id 插失败，已改正）。**留在 dev 库不删**，后续读面回归可复用。
- API：`GET /api/v2/announcements` 返回该条；`GET /api/v2/announcements/9900000001` 返回详情（公开读，无需鉴权）。
- 真端（automator 9421）：列表页显示「通知公告 / 系统 / 新 / 2026-11-02 10:07 / R11 造数公告」；详情页显示标题+正文+时间；**「请检查网络后重试」出现 false** ⇒ MA-035 的判据通过。
- 结论：公告读面在「有数据」时也正常，之前 0 行只是没数据，不是缺陷。

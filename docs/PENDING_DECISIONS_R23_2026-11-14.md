# 需拍板清单（R23 §四要求的正式清单）—— 2026-11-14

> **定位**：R23 计划 §四第 3 条「不把拍板题当缺陷……列『需拍板』清单」——R23 计划当时只写了要列清单，清单本身不存在（此前散落在各审计报告的「待拍板」句 + `.workbuddy` 日志里，换会话即丢）。本文档把**当前仍开着的拍板面**收拢成唯一权威清单；R23 各路审计发现新拍板项时**追加到本文档**，不得只在报告里留一句。
>
> **前置事实**：`docs/PENDING_DECISIONS_5_2026-11-13.md` 的 5 条（①争议改单券不返还 / ②对账日跑删除重建 / ③分账回退幂等 / ④Ops 重试落补货会话 / ⑤device_sku_price 行锁粒度勘误）经 2026-11-14 用户复核确认**全部收口**，本文档不重复收录。
>
> **纪律**：拍板项不是缺陷（修的人不自己选口径）；R23 审计期间保持只读，拍板项允许挂着进 L4 现状评估；每条拍板后在此标注「✅ 已拍板 YYYY-MM-DD：结论 → 落点提交」。

---

## ① R22 §8.4「两侧皆 0 ⇒ MATCHED」语义 —— 🔴 最高优先（漏登记已久）

- **现状**：`ReconciliationService.java:226-227` 终态判定只比较三个量：
  `diffCents == 0 && unmatched == 0 && ledgerOnly.isEmpty() ? "MATCHED" : "MISMATCH"`
  ——不区分「核对过且相等」与「两侧都没有数据」。
- **实库取证**（R22 报告 :395）：`WECHAT` 13 次对账里 **9 次两侧全 0** 却记 `MATCHED`。
- **拍板问题**：「两侧皆 0 ⇒ MATCHED」是产品认可（当日确实无交易就是平的），还是必须区分「未核对」（如记 `EMPTY`/`NOT_CHECKED` 并进告警面）？
- **关联**：R22-8 的静默面（空账单 fail-fast）已修（382c0769）；本条只剩**语义口径**。
- **报告出处**：`AUDIT_FOUR_END_R22_2026-11-12.md` :316 / §8.4（:393「不是待定，是拍板项」）。

## ② R19-2 赠送冲回 —— 改退款资格语义

- **现状**：R19 发现赠送（免费券/赠送金）在退款场景的冲回语义未实现；11-12 修复核查报告明列「未做待拍板」。
- **拍板问题**：赠送部分是否参与退款资格/冲回？改的是退款资格语义，修的人不能自己定。
- **报告出处**：`AUDIT_FIX_QUALITY_VERIFICATION_2026-11-12.md` §七「未做待拍板」。

## ③ R19-5 dev 80 端口暴露 —— 断真机联调的取舍

- **现状**：dev 栈 80 端口暴露给真机联调用（花生壳隧道链路依赖）；收紧会断将邑真机联调。11-12 修复核查报告明列「未做待拍板」。
- **拍板问题**：真机联调期结束后是否收回？还是维持到生产化迁移完成？
- **报告出处**：同上。

## ④ R22-7 厂商待定 —— 重复 start 的设备侧行为

- **现状**：§7.15 双摄重复 `start` 是拒绝还是幂等返回，需厂商答复或真机验证。
- **附**：R22 报告 :421 的现场动作「**同柜连点两次 start**」真机双点验证也未做（需要真机在手的人执行）。
- **报告出处**：`AUDIT_FOUR_END_R22_2026-11-12.md` §7.15 / :421。

## ⑤ OpsOta 审计双记合并 —— 4 处双记留产品拍板

- **现状**（2026-11-14 精确化）：publish/unpublish **各**双记——
  `OtaService.java:120`（OTA_PUBLISH）/:143（OTA_UNPUBLISH）与
  `OpsOtaController.java:43`（OTA_RELEASE_PUBLISH）/:58（OTA_RELEASE_UNPUBLISH）写的是同一动作的审计。
- **门禁事实**：S9 端点级归因下删控制器侧冗余审计仍绿（service 链可达），类级判定会红（1-hop 视野）——即「合并到 service 单点记」技术上可行，但动作码会变（OTA_RELEASE_* vs OTA_*），历史审计连续性归产品定。
- **⚠️ 记录位置教训**：本条此前只写在 `.workbuddy/memory/2026-11-14.md`，未进 docs 台账——本文档即修正（ OpsOta 双记自此以本文档为准）。

## ⑥ AccountController 6 写端点 0 归因 —— 豁免归类还是补留痕

- **现状**：S9 端点级基线 `AccountController {endpoints:6, attributed:0}`；`AccountService.updateNickname` 等为自助改昵称类低风险写（源码注释「无越权面」）。
- **拍板问题**：这 6 个端点归类为 `CONSUMER_WRITE` 语义豁免（消费者自主操作，业务单据即流水），还是给运营侧补 `appendLog` 留痕？台账现行口径「修真缺口是修复批的活」——本条决定它算不算真缺口。

## ⑦ movePoint(2) 单源例外 —— 已有归属，留档供 R23 L4 评估

- **现状**（2026-11-14 性质订正）：`common-core/UserBehaviorRowDto.java:34`
  `totalSpent.movePointRight(2).setScale(0, HALF_UP).longValue()` 是**元→分资金方向派生 + 静默四舍五入**（正是 `YuanFen.toCents` 拒 `scale>2` 要防的形态）；因 common-core 不能反向依赖 trade-service 的 `YuanFen`，登记为**已知单源例外**（输入来自 DB 聚合的 BigDecimal，风险低）。`UserBehaviorAnalyticsService.java:194` 的 `movePointLeft(2)` 才是分→元**显示**方向——两者勿混为一类。
- **拍板问题**：是否值得把 `YuanFen` 下沉到 common-core（或建 shared 单源）让该例外销账？还是维持登记例外？
- **关联**：`check-yuan-to-cents` movePoint(2) 棘轮在册 2 处。

## ⑧ S12 四键值域 —— ✅ 已拍板 2026-11-14（按建议值域开工）

- **拍板结论**：提现手续费率 0–1000 bps（老系统 6‰=60bps，含商户降档覆盖空间）/ `balance.refund.max_cents` 0–5,000,000 分（¥5万）/ `checkout.preauth_cents` 0–1,000,000 分（¥1万）/ 券折扣两族维持既有局部校验（`CouponService` discountPercent 1..99、`PromotionActivityValidator` discountBps 0..10000 开区间）并纳入门禁视野（SETTER_RE 加 `setDiscountBps|setDiscountPercent` + 入口校验形态白名单）。
- **落点**：`CommissionParamBounds` 常量单源 + `SystemConfigService` 写侧天花板 + 读侧回落双保险 + 门禁 SETTER_RE 扩面。✅ **已落地 2026-11-14：代码 `9d5c724f`（fix-batch 登记 `e2e54807`，33 Java 用例+门禁夹具 21 断言全绿）；本文档本体落盘 = `d0da52d5`**。

---

## 台账口径备忘（非拍板项，防止下轮误读）

- **登记制清零 ≠ 语义补齐**：S9 端点级 215 未归因（455/240，`maxUnattributed=215` 棘轮只减）、S10 回显字段/windowLabel 随修复批——数字清零的都是「分类/登记」维度。
- **fixture 三口径并存**（引用时标来源）：独立 `.test.mjs` 文件数（47/144）≠ wiring 夹具覆盖计数（76/144）≠ `check-gate-test-coupling`（diff 范围，推送后「改动集为空」是预期行为非故障）。
- **提交说明勘误**已在 `AUDIT_FOUR_END_R22_2026-11-12.md` :302-303（e49b3556/48ae2de4 说明与 --stat 不符、ddbfc438 豁免口径冲突）——追溯一律以 `git show --name-only` 为准。

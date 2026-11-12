# 通宵审查-修复循环 · 终版报告（第二段：R11–R17 + M01 批量修，2026-11-11 → 2026-11-12 07:30）

> **范围**：第一夜终报（`AUDIT_FOUR_END_OVERNIGHT_FINAL_2026-11-11.md`，HEAD=`4f9a31b3`）之后的延续段——R11 至 R17 共 7 轮四端审计 + M01 清列家族批量修。
> **区间**：`cc9e0852..9657f90c` 共 **52 提交**（全部 pre-push-ci-preflight 通过后推送；终态 HEAD=`9657f90c`）。
> **方法**：每轮先对齐前轮已修/豁免清单后仅报新增；主审逐条亲验关键发现；「守卫只修一半」「修实例不修模式」两科为多轮主根因，均已升级为机器门禁。
> **总账**：新发现 **69 条 = P1×2 + P2×21 + P3×46**；P0 为零。**已修 63 条 + 6 条登记不修**（理由见 §五）。

---

## 一、轮次总账

| 轮 | 基线 | 发现 | 修复 | 关键词 |
|---|---|---|---|---|
| R11 | cc9e0852 | **24** = P1×1 + P2×5 + P3×18 | 24/24 全修 | TDZ 第三类（onLoad 位次）；seq 守卫「只挂 catch 半边」；新门禁 `check-seq-success-path-guard` |
| R12 | 3ed314bf | **6** = P1×1 + P2×4 + P3×1 | 6/6 全修 | seq 门禁盲区扩三端；**M01 清列家族定性（75+ 处）**；consumer-mp seq 全清复核 |
| R13 | e9ca9e9b | **1** = P2×1 | 1/1 全修 | MA-174：`\|\| null` 在 UPDATE 表单扩散 6 视图 |
| R14 | d60d0b91 | **11** = P2×3 + P3×8 | 10 修（F2 随 R17 批补修）+ F3 登记 | G1 XFF 首跳伪造绕限流；MA-174 残余第 7/8 处；号段豁免补钉测 |
| R15 | 21f55938 | **26** = P2×8 + P3×18 | 21 修 + 5 登记 | 四路机制层首审（CI workflow/auth 横切/媒体链/infra）；H2 CVE 门禁「从未真正工作」 |
| R16 | eccccb20 | **1** = P3×1 | 1/1 全修 | MA-175 机具账单缺事务+行锁；九大机制域「已核对无问题」划界 |
| R17 | — | **0** 新发现 | 改进批 6 项 | 四角度复查零命中——收敛信号 |
| M01 批 | — | （R12 定性的执行批） | 三批 10 站点 | `setX(blankToNull)+updateById` 系统性清列缺陷收官 |
| **合计** | | **69** | **63 修 + 6 登记** | |

**收敛曲线**：R11→R13 在「已扫面」上 24→6→1 快速收敛；R14/R15 为「从未扫过的机制层」扩面（CI workflow 本体、认证/token 横切、媒体上传链、compose/依赖/e2e），各产出 11/26 条——扩面轮有爆发、面内即收敛（R15 同面复查 R16 仅 1 条、R17 零）。全部 P1/P2 清零，P3 除登记项外全修。

---

## 二、关键修复分主题

### 1. 守卫家族收官（TDZ + seq，三端门禁化）
- **TDZ 第三类**（声明晚于 onLoad/onShow 注册）：C-N1 order-detail.vue 修复；三类扫描伞（onShow/immediate-watch/注册位次）三端零余留。
- **seq 守卫「成功赋值不判 isCurrent」**：admin 15 处（4 缺陷 + 11 存量同批）+ merchant-mp/consumer-mp 全部补齐（M-N1~N10、C-N2~C-N4、jiangyi-capture）。
- **机器门禁**：`check-seq-success-path-guard` TARGET_DIRS 扩至三端（R12-P1-1），self-test 三态验证。

### 2. M01 清列家族（详见 §三）
- 后端显式清列累计 **16 站点**（R11 B-N1 + R12 批 3 + R14 A1/A2 + M01 批 10）；前端 `|| null`/`|| undefined` → `?? null/undefined` 收口 **15 字段**（useDeviceAsset 6 + MerchantSplitsView 3 + R13 六视图 6）。
- 门禁 `check-mybatis-null-clear` 在位；剩余扫描命中 2 处定性为 CREATE 路径非缺陷（OpsRbacService:230 / WarehouseStocktakeService:114）。

### 3. 认证横切对齐（R15 K 系列 + R11/R13 铺垫）
- **K1** 跨 tab 刷新互踢 → Web Locks 串行化（`doRefreshWithCrossTabLock`）；**K2** admin 刷新瞬断硬登出 → `refreshWithOutcome` 四态（仅明确 401/403 清会话，429/5xx 保会话）；**K3** merchant 登出补服务端吊销；**K4/K5/K6/K7** 同批收口（consumer 死锁风险/7 天宽限可达/下载 401 对齐/判域精确化）。

### 4. 安全修复
- **G1（P2·安全）** `/o/{deviceId}` 匿名限流 XFF 首跳伪造绕过 → 信任序倒置（X-Real-IP 优先）+ 4 钉测；**L1（P2）** SKU 图 sha 去重共享 fileId × release 误删 → 档案层存活判定（不排除自己）+ 2 钉测；**L2** 解码炸弹 → header 探测 8000px/32MP 上限；**L3** 争议凭证 PENDING 配额（20 张）。

### 5. CI 与基建韧性（R15 H/M 系列 + R17 收尾）
- **H2 深挖**：CVE 审计门禁 pnpm9 `--json` 输出为 `advisories` 老格式，`vulnerabilities` 键恒空 ⇒ **自落地起从未拦过任何漏洞**（24 条 advisory 全漏网）→ fail-closed + 双格式解析 + KNOWN_BASELINE_GHSAS 棘轮，三态验证。
- **H1** 五 job 补 timeout；**H4** concurrency（本次 M01 三连推即被其正确取消中间轮）；**M1** gateway/redpanda restart；**M2** admin-uat 假断言改结构化；**M3** 16 文件 76 服务 logging 轮转；**M6** nginx /admin/ 安全头；**MA-175** 机具账单 @Transactional + FOR UPDATE + 状态幂等闸。
- **性能**：F2 订单列表跨单批量取图（N+1 → 两次批查）+ OrderViewAssemblerTest 钉测 4/4。

---

## 三、M01 清列家族批量修（执行批收口）

**模式**：`entity.setX(blankToNull(request.x()))` + `save/updateById` → MyBatis-Plus NOT_NULL 策略跳 null ⇒ UPDATE 路径可清空字段永不可清；前端 `|| undefined` 丢键使「用户显式清空」信号也到不了后端。

**三批 10 站点**（后端显式 `lambdaUpdate().set(col, null)`，照 MA-169 模板）：

| 批 | 提交 | 站点 |
|---|---|---|
| 第一批 | `5d34d414`（R12 批 MA-168~171 后端） | MerchantDevicePortalService opsRemark / OpsCatalogAdminService operationScope / JiangyiClassMappingMapper textName（+前端 useDeviceAsset 6 字段、MerchantSplitsView 3 字段 `?? null`） |
| 第二批 | `0cbbab01` | DisputeService operatorNote / MerchantPortalService alertContactName + alertContactPhone / RepairTicketService remark / SysDictService type remark + data remark（6 站点，扫描模板 `.tmp/m01_scan.py`） |
| 第三批 | `b3ac0f88` | MerchantPortalService contactPhone（第二批只修 alertContact×2 漏 contactPhone 的收尾） |

> **台账勘误**：`AUDIT_OVERNIGHT_STATE` 曾把第一批记为「cf7edc08 内」——实为 `5d34d414`；cf7edc08 对 OpsCatalogAdminService 的改动是 R15-L1（SKU 图释放判定），非 M01。本表以 `git show --stat` 实证为准。

**非缺陷定性**：扫描余量 2 处为 CREATE 路径（创建时 null=默认值，语义正确）。

---

## 四、验证证据

| 类别 | 结果 |
|---|---|
| pre-push-ci-preflight | 52 提交每轮推送前全链通过 |
| **本地定向测试（M01 触及服务，本轮实跑）** | **114/114 绿**：Dispute×4 类 / MerchantPortal×2 / RepairTicket×2 / SysDict+DictGate×3 / SkuSceneLimit×2 / OpsCatalogAdmin / CompetitiveGap×3 / Settlement×2（18）。⚠️ 初跑 10 Error 系本地 `~/.m2` common-core 旧 JAR（`mvn -pl` 单模块不带 `-am` → NoSuchMethodError 假红），重装后清零——新踩坑已记 lessons |
| 各轮门禁实跑 | mybatis-null-clear / seq-success-path-guard（三端+self-test）/ mp-defect-classes / admin-artifact-pairing / flyway-seed-separation / xxl-job-wiring / ui-barehex / touch-target / merchant-me-gated-load / migration-safety 等，明细见各 R 报告 |
| 三端 vitest | merchant 258 / consumer 195 / admin 140 多轮全绿；type-check 每批全绿 |
| 钉测新增 | 分账清列 2 / 号段豁免 4 / XFF 4 / SKU 图释放 2 / 解码上限 2 / F2 批量取图 4 / OrderViewAssembler preloadedImages 等 |
| **CI（GitHub Actions）** | **`77553bac` 轮 5/5 全绿**（build / admin-artifacts / mini-programs / e2e-h5 / integration——通宵以来首次完整通过，TDZ/budget/产物配对/R15 批全验证）。M01 三连推中间轮被 concurrency 正确取消，**唯一有效轮 `9657f90c` 落报时点 in_progress，终态以 Actions 为准**（M01 改动已有本地 114/114 定向绿兜底） |
| 未跑（如实标注） | mvn verify 全量、真 Docker IT 套件（DictLabelUniquenessIT 真库已单跑绿）、真机弱网时序复现——与历轮口径一致 |

---

## 五、遗留与登记（全部有归属，无阻塞）

| 条 | 来源 | 性质 |
|---|---|---|
| H7 deploy-prod 校验目标 SHA 的 CI 结论 | R15 | 建议级（半自动档已有人工兜底） |
| K7 双 Cookie 各自续期 | R15 | 需 refresh 端点 realm 契约，跨端变更立项 |
| L3 孤儿凭证超龄巡检 | R15 | 保留期属产品决策 |
| M7 Spring Boot 3.5.16 EOL / resilience4j 双版本现状 | R15 | 现状确认（DM 版本已属性化），升级待排期 |
| F3 OpenAPI Merchant/Admin 变体缺 coverImageUrl | R14 | 存量模式，商户端做图时一并查根因 |
| OrgSitesView 日期字段 / R11 化妆类登记（HomeCartBar emit 沿袭、WarehouseView.vue 疑死文件等） | R13/R11 | 低危，按铁律 74 不盲动 |

---

## 六、教训（本轮新增，已入册）

1. **「守卫只修一半」必须机器判据化**：R11 的 P1/P2 ≈12 条同科（catch 判了、成功赋值没判；同文件只盖一个函数；跨端只改一端）——已立三端 seq 门禁 + CODE_FIX_CHECKLIST 三条硬规则。
2. **「修实例不修模式」的代价**：M01 家族 R11 修 1 处时同模式有 75+ 处；扫描脚本（`.tmp/m01_scan.py`）+ 批量模板才是解。
3. **判据类修复必须带构造侧正向用例**：号段豁免移除零钉测被 R14-D1 点名（4/4 补齐）。
4. **本地 `mvn -pl <module> test` 不带 `-am` 会用 `~/.m2` 过期公共模块 JAR** → NoSuchMethodError 假红；先 `install` 公共模块或带 `-am`。本轮 10 个假 Error 实证。
5. **并发组取消中间轮是设计行为**：多提交连推时只有 HEAD 轮有效，勿把 cancelled 当失败排查。

---

*报告落盘：2026-11-12 07:35。终态 HEAD=`9657f90c`；CI 锚=`77553bac` 5/5 全绿 + `9657f90c` 验证 M01 批（终态以 Actions 为准）。两段通宵合计：R3–R17 共 15 轮，发现 ≈164 条，修复率 96%（余 6 条登记不修均有书面理由）。*

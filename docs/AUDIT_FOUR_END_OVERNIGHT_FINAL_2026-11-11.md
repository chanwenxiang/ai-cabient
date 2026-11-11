# 通宵审查-修复循环 · 终版报告（2026-11-11）

> **范围**：2026-11-10 深夜起至 11-11 06:40 的连续「审查 → 修复 → 回归 → 推送」循环（R1–R9 + CI 追加轮）。
> **基线链**：起于 `893bb2ca`（R3/R4 报告入库时点）→ 终于 `4f9a31b3`（R10 CI 追加批），共 **24 提交**，全部已推送 origin/dev。
> **证据纪律**：本报告数字以 git log + 台账（`AUDIT_OVERNIGHT_STATE_2026-11-10.md`）+ 本会话主审逐条亲验为准；推送后 CI 实跑结果如实标注（第四轮 CI 在报告落盘时 build/e2e-h5 仍在跑）。
> **上游输入**：R3 报告（并发会话，`AUDIT_FOUR_END_R3_2026-11-10.md`）+ R4 报告（`AUDIT_FOUR_END_R4_2026-11-10.md`）= 修复清单定义；R5–R9 为通宵自产轮次。

---

## 一、发现总账（去重并集）

| 来源 | 条数 | P0 | P1 | P2 | P3/Info | 备注 |
|---|---|---|---|---|---|---|
| R3（并发会话，已入库） | 30 | 0 | 2（A-R3-1 / C-R3-1） | 14 | 14 | 与 R4 有 9 处交集，终账已并 |
| R4（本会话五路+亲验） | 22 独有 | 0 | 4（A19 / B-新1 / D1 / E5） | 7 | 11 | 含对 R3 撤账 1 条（D4 审批失真不成立） |
| R5（5 路审「R3/R4 修复批」） | 33 | 0 | 0 | 9（A1-A3/B1-B4/E1-E2） | 24 | 全部是「修复批自身的洞」——修一半/判据无构造侧用例/家族清单漏页 |
| R6（3 路再审+盲区） | 9 | 0 | 0 | 2（F1 / F2） | 7 | 含 H1 质检拒收死写、H2 券 EXPIRED 死写两个真死代码 |
| R7（主审直查） | 2 | 0 | 0 | 0 | 2（软形态收口） | B-新3 家族最后两处 |
| R8（单路终验） | 1 | 0 | 0 | 0 | 1（javadoc 悬空） | 终验结论：零未修洞 |
| R9（拍板批） | 12 项处置 | — | — | — | — | 待拍板 3 + 化妆 9，用户授权按推荐执行 |
| CI 追加轮（推送后实跑抓住） | 4 | 0 | 1（TDZ×2 计 1 族） | 1（admin bake 未生效） | 2 | messages.vue / marketing-ad-banner TDZ、JiangyiModelSync 测试缺 stub、bb2a5435 bake 静默未生效 |
| **去重总账** | **≈95 条 + 12 项处置** | **0** | **6** | **~30** | **~59** | **P0 全程为零** |

**关键定性**：①上轮（R3/R4）修复批自身带洞 9 条（R5/R6 抓出）——「修一半」「判据无构造侧用例」「家族清单漏页」三种根因各 3 条；②两处真死代码（H1/H2）为存量非新引入；③CI 追加轮 4 条全部是「本地定向测试没跑到/产物配对没生效」类流程洞，非逻辑缺陷。

---

## 二、逐条状态三分类

### 2.1 已修（全部有源码证据 + 回归）

**P1×6（R3/R4 清单核心）：**

| 条 | 状态 | 证据提交 | HEAD 抽验 |
|---|---|---|---|
| D1 备货预览 `@Select` 缺 `<script>` ⇒ prep-preview 必 500 | ✅ 已修 | 9d5f295e | `<script>` 开合标签 ×2 在位 |
| E5 「清除主图」被前端 `\|\| undefined` 架空（文件物理删除） | ✅ 已修 | 9d5f295e | 四字段显式携空串在位 |
| B-新1 备货改量重提 renew 分支不复查 ⇒ P0-3 复发 | ✅ 已修 | 9d5f295e + 855a517f/8fc6593b 前置 | renew fail-closed 三元在位 |
| A19 错误边界 FATAL_INFO_RE 生产永不命中 | ✅ 已修 | 9d5f295e | `error-boundary.ts` 双形态判据 + 3 例钉测 |
| C1(=R3-C-R3-1) 上传 401 判据死代码 | ✅ 已修 | 9d5f295e | `upStatus === 401 ? 'UNAUTHORIZED'` 构造侧在位 |
| A-R3-1 saveOpsConfig 保存响应双判守卫 | ✅ 已修 | 9d5f295e | isCurrent+商户比对在位 |

**P2/P3 大批（按提交归组，逐条明细见各报告与台账）：**

| 批 | 提交 | 内容 |
|---|---|---|
| R3/R4 P2 批 | bd5845bf | B9 根因 meFromServer+12 页 / C2 结算 toast 读 status / D2 采购回放闸+V454 / B-R3-1/2/3/5 盘点三件 / A20-A22 seq 族 / D4 提现终态闸 / D-R3-1 开机回执异步化 等 |
| R3/R4 P2c+P3 批 | 7e9d4f0b | D3 JOIN 回填 / D-R3-3 重读防撤销 / E6 三键看护 / C3 假按钮诚实化 / A29 文案 / A27 两页 CSV 等 |
| 门禁加固批 | 17b7a89e + 7e0d7961 | 规则 9 钉方法体+剥注释+双页锚+空心化判据 / 下拉刷新防锚+globalStyle / 触控棘轮 env 硬校验 / 裸色棘轮双向锁——三态验证 13 项注入全过 |
| A27 补 | 268fea2c | 三页 CSV 金额去 ¥ |
| R5 批 | 76c06c23 + 602afa24 | 24 处（A1-A9/B1-B8/D-新1-6/E1-E5） |
| R6 批 | 966bb46d + 985536be + V455 | F1/F2/F3 四处同改（IT 从零库绿）/ G1 / H1 REQUIRES_NEW / H2 删死写 |
| R7 批 | 085ac656 | cabinet-reports/tax 弱网文案分流 |
| R9 拍板批 | 02062bf3 + 281c75e5 + V456 | B7 盲盘总量遮蔽 / RETIRE 注释钉住 / CHECKLIST 三条 / 化妆 8 项 |

**CI 追加批：**

| 条 | 状态 | 证据提交 |
|---|---|---|
| R10-1 messages.vue loadSeq TDZ（H5 onShow 同步触发） | ✅ 已修 | 9e9a39b8 |
| R10-2 marketing-ad-banner immediate watch TDZ | ✅ 已修 | 4f9a31b3 |
| R10-3 admin bake 静默未生效（bb2a5435 只走了 118 处旧状态） | ✅ 已修 | a8ade8ec（227 处真实重烤）——**本轮 CI admin-artifacts 已转绿** |
| R10-4 JiangyiModelSyncServiceTest 缺 selectById stub | ✅ 已修 | 517d693a（37/37 绿） |

### 2.2 有意不修（豁免登记，均有理由）

| 条 | 原因 |
|---|---|
| B-新2（同量+COMPLETED 回放「假成功」提示） | V401 幂等契约保留——重复建任务风险 > 文案瑕疵；取消态已由后端 409+清键自愈 |
| A5（清空实盘行按旧值过账） | 与「盘点覆盖式语义」一致：空白=未录入不参与调账 |
| D4（提现回放终态窄窗） | R6 已加终态闸（REJECTED/CANCELLED→409），剩余 PAID/PAYING/FAILED 回放为诚实语义 |
| D-新4（D-R3-3 残余 TOCTOU 窗口） | 窗口缩至两条相邻 DB 读，fail-safe 方向（少确认一次），无资损 |
| D-新5（V454 竞态失败方通用 409 文案） | 设计内取舍：一张成单+显式 409+用户知情重提 |
| A8①（门禁切片字符串盲区） | 化妆1 实测会因 unmatched 引号吞余量 ⇒ 已回滚；现锚体内无字符串花括号，注释留痕可查 |
| E-R3-8（contract 门禁 import 副作用） | 顶层线性脚本包函数重构风险 > 收益（无 importer），正式登记不修 |
| 化妆-登记 ×2 | HomeCartBar emit 赋值沿袭（无运行时影响）；WarehouseView.vue 疑死文件（铁律 74 不盲删，待人工确认） |
| 门禁候选① | 「admin 事件处理禁裸 await」不实施——A1 修复后已转 toast，误报面 > 收益（写入 CODE_FIX_CHECKLIST） |

### 2.3 原「待拍板」→ 已按用户授权处置（2026-11-11 晨）

| 项 | 处置 |
|---|---|
| B7 盲盘头部/列表卡总量直出 | **修**：盲盘进行中账面→「隐」、实盘→「已录入 N/M」、差异 chip 不渲染（02062bf3） |
| RETIRE 柜不解绑 | **定为有意留痕**：注释钉进 `DeviceAssetService`，防顺手统一 |
| 门禁候选 ①②③⑤ | ①豁免 / ②三条硬约定落 CHECKLIST / ③既有门禁已覆盖 / ⑤部分覆盖（均书面化） |

---

## 三、回归证据

| 类别 | 结果 |
|---|---|
| pre-push-ci-preflight 全链（109 门禁） | **R3/R4 批、R5 批、R6 批、R7/R8 批、R9 批共 6 轮全绿**（每轮修完即跑） |
| 后端定向测试 | ProcurementServiceTest 17 / ScheduledTaskStaleMonitorTest 14 / MerchantWithdrawChannelLimit+LineWithdrawPayee+MerchantSelfReplenish+MerchantPrepPreview 45 / CouponServiceTest 26 / JiangyiModelSyncServiceTest 37（CI 修复后）——**均 0 失败** |
| DictLabelUniquenessIT（真库从零跑 V455） | 2/2 绿 |
| 三端 vitest | merchant 250+ / consumer 175+ / admin 133+ 多轮全绿 |
| 三端 type-check | 每轮修完即跑，全绿 |
| CI（GitHub Actions 实跑） | 第 1/2 轮红（build 测试 + admin-artifacts，即 R10-3/R10-4）→ 修复；第 3 轮 e2e-h5 红（R10-1/TDZ 漏网第二处）→ 修复；**第 4 轮（4f9a31b3）：mini-programs ✅ / admin-artifacts ✅（原阻塞项转绿）/ build + e2e-h5 报告落盘时仍在跑** |
| 主审 HEAD 抽验（报告落盘时） | 9/9 关键修复在位（6×P1 + H1 + B7 + F3） |
| 未跑（如实标注） | mvn verify 全量、真 Docker IT 套件、真机 H5/微信端点击链——与历轮口径一致 |

---

## 四、遗留与建议

**遗留（全部已登记，无阻塞）：**
1. 化妆-登记 ×2（HomeCartBar emit 沿袭 / WarehouseView.vue 疑死文件——铁律 74 需人工确认后删）。
2. e2e-h5 第 4 轮结果待 CI 出（build/e2e-h5 在跑；若 TDZ 仍有第三处，TC-QUAL-001 会再点名页面）。
3. B7 头部「账面 隐」遮蔽的产品语义请晨间复核一眼（已按保守方向：宁多遮不泄露）。

**建议（写进流程的三条，已落 CODE_FIX_CHECKLIST + 踩坑总册）：**
1. 判据类修复必须带构造侧正向用例（C1/A19/E5 三条同科，R5-A3 第四次复发后 CI 第四次抓）。
2. 注解 SQL 含 `<foreach>` 必须 `<script>`（D1 被全 mock 单测盖住，建议补绑定层静态门禁）。
3. 跨端契约修复两端同批改 + 门禁锚双侧（B-新1/R5-B5 连续两轮实证）。
4. **新增**：admin 保真重烤后必须 `git status` 确认产物 diff 非零再提交（bb2a5435 的 bake 静默未生效连续骗过两轮 preflight——CI admin-artifacts 才是真锚）。

---

*报告落盘：2026-11-11 06:45 前后。HEAD=`4f9a31b3` 已推送；CI 第 4 轮 build/e2e-h5 进行中，结果以 Actions 为准（admin-artifacts 与 mini-programs 已绿）。*

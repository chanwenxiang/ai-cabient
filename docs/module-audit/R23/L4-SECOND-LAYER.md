# R23 · L4 第二层结构化现状评估（S9–S15 + S8 机制自评）

> 主审镜头稿（阶段 2 并入 R23-FINAL）。数据采集时点：2026-11-15，快照 a95a2fa3；门禁健康前提 = R23-B 负向注入 13 条全红零假绿。

## 逐族评估（现状 / 缺口 / 建议 / 代价）

### S9 写动作归因
- **现状**：类级注册表 26 条全分类、maxTbd=0；端点级 455/240，maxUnattributed=215 冻结只减；拒绝路径黑名单语义派生（类名 denied OR 写 ACCESS_DENIED 自动纳入）；AccountController {6,0} 已拍板 CONSUMER_WRITE（b72a8d0b，登记不落码）。
- **缺口**：215 分解（执行方 2026-11-14 交叉分解为准）= 语义豁免 81（DEVICE_FACING 68 + CONSUMER_WRITE 6 + CALLBACK 4 + DEV_ONLY 3，37.7%）+ **134 两级判据口径差区**（类级判可达、端点级未归因：MerchantPortal 21 / DeviceCompat 17 / DeviceReport 16 / JiangyiInternal 15 / Auth 14 / AdminDashboard 7 / AdminJiangyi 7…共 59 控制器）。134 非已证真缺口，也非噪音。〔R23 汇审订正：主审初稿按 215−74=141 口径，漏计 CALLBACK 4+DEV_ONLY 3。〕
- **建议**：主审汇审时对 134 区做「运营侧写端点」过筛（半机械：按控制器语义分类 × 端点方法名），产出三种去向——真缺口（排修复批补 appendLog，范式现成）/ 语义豁免（登记）/ 确认低风险（记账）。**不从基线抹数，棘轮只许减。**
- **代价**：评估本身半天；若筛出真缺口，补审计每控制器 ~0.5h。

### S10 取数窗口诚实性
- **现状**：截断 10 + 窗口 34 全登记（BATCH 12 / PARAM_OVERRIDABLE 9 / KPI_FIXED 10 / TOP_N 1 / DATE_ARITHMETIC 1 / ARCHIVE 4），maxTbd=0；LinePromoTask 分页化=已落地样板。
- **缺口**：登记制清零 ≠ 语义补齐——回显字段（total/truncated/windowLabel）消费面未补。
- **建议**：维持登记棘轮；语义补齐按「用户面优先」随修复批排 P3（BATCH/PARAM_OVERRIDABLE/KPI_FIXED 不急——内部批消费无回显需求）。
- **代价**：小/条（PageResult 惯法现成）。

### S11 回调入口契约
- **现状**：8 外部回调全登记（4 SIGNATURE_BYPASS+白名单锚点双向绑定 / 2 DEV_ONLY / 1 ONE_TIME_TOKEN / 1 DEVICE_TOKEN）；MA-047 本体+mock 裸奔防线双向判据。
- **缺口**：无现行缺口；新增回调受门禁咬。
- **建议**：维持。R23-C 若发现未登记新回调即 MA 新条。

### S12 资金乘数限幅
- **现状**：逐调用点判定（括号计数扫描器）+ 佣金族 require* + 四键天花板扩面（9d5c724f：费率≤1000bps 含按商户前缀键、refund.max≤5M、preauth≤1M 入守卫族）+ 折扣两族 setter 入视野（入口校验形态白名单）+ MIN_WRITERS=7 锚点（80febfeb）。
- **缺口**：折扣族依赖 validator 入口闸（白名单登记）——防线成立（绕闸新增写侧会因计数红），但判据形态是「白名单+计数」不是逐点 require*，比佣金族弱半档。
- **建议**：维持现状；R23-L1 深读 9d5c724f/80febfeb 邻面后，若发现折扣族有绕 validator 路径再升级为逐点 require*。
- **代价**：升级=小（原语现成）。

### S13 并发声明
- **现状**：diff 级强制（资金/库存/额度写路径触碰须条件 UPDATE/乐观锁/锁 key 或 CONCURRENCY 锚点）；R18-2 收口验证过。
- **缺口**：只拦增量不扫存量（设计决定，误报面大）；DeviceSkuLot 无 version 字段的结构性弱点由原子 UPDATE 兜住已知路径。
- **建议**：维持；R23-A 若在库存/资金面发现新的读改写再立条。
- **代价**：-。

### S14 迁移形态无关
- **现状**：逐表状态机（DROP→重建免疫）+ SCRIPT_TABLE_SET_EXEMPT 头 + MIGRATION_IMMUTABLE_EXEMPT（须 to_regclass 实测证据）；**空库 V1→V481 实跑 474 迁移全 success（R23-D 2026-11-15 复跑 5.6s）**。
- **缺口**：V421:24 的 recognition-demo「去留属 R8 待拍板」漂移出拍板清单（=R23-B MA-200 关联）。
- **建议**：随 MA-200 一并收（豁免空转三处同根）。
- **代价**：小。

### S15 巡检规则夹具
- **现状**：24 规则全登记、正负夹具 24/24、maxMissingFixtures=0。
- **缺口**：dev 演示数据存量 19 条 FAIL（R23-D 记录，FIXED=154 证明闭环在动）——非代码缺陷。
- **建议**：维持。

### S8 机制自评（①②③④）
- ①fix-batch：27 批 + 19 豁免（B 路抽 3 批双向零差异）；②fixtures：四口径 49/194/78-145/豁免 67/69（B 路实测，豁免余量 2）；③coupling：diff 口径按批正常；④夹具棘轮：新门禁自带 .test.mjs 已成惯性（80febfeb 三门禁全带）。
- **缺口**：MA-200（recognition-demo 豁免空转 ×2 门禁 + 拍板漂移）/ MA-201（feature-flags R2 判据未剥注释，潜在死开关假绿形态、现行无实伤）。
- **建议**：两条 P3 进下一修复批小刀（MA-201 换 maskForScan 范式；MA-200 删豁免或拍板回填）。
- **代价**：各 ~0.5h。

## 汇总结论
第二层 15 族全部结构化落地且本轮门禁健康（13 注入全红）；实质欠账收敛为三件：
1. **S9-134 区过筛**（评估任务，半天，产出真缺口清单 or 确认无）——主审阶段 2 亲做
2. **MA-200/201 两条 P3 小刀**（随下一修复批）
3. **S10 用户面回显**（P3 随批，非独立批）

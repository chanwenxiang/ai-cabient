# 通宵连续审查-修复循环 · 状态台账（2026-11-10 深夜启动）

> 供 7:00 终报会话与任何接手会话读取。**本文件是唯一实时状态源**（聊天上下文不可跨会话）。
> 循环模式：R{n} 审查（5 路只读子代理）→ 逐条亲验 → 修复 → 回归 → 提交 → R{n+1}。
> 停止条件：2026-11-11 07:00（届时由定时任务汇总终报）。

## 固定输入

- 基线起点：`893bb2ca`（R3/R4 报告的 HEAD）。
- 待修清单（两轮并集）：`docs/AUDIT_FOUR_END_R4_2026-11-10.md`（§1 洞表 + §2/§3）∪ `docs/AUDIT_FOUR_END_R3_2026-11-10.md`（§3–§5）。
- 已知待拍板两项（低风险顺手收口，终报标注）：D4 提现回放终态窄窗 → 按仿 B1 加 409；RETIRE 柜不解绑 → 不动代码，仅台账留痕。

## 轮次账（进度更新 2026-11-11 01:20 前后）

### 修复批进度（R3+R4 并集）
- ✅ P1×6 已提交（D1 script包装 / E5 携空串 / B-新1+2 renew实时复查 / A19 双形态判据 / C1 构造侧status / A-R3-1 保存守卫）
- ✅ P2 Wave A 10 条已提交（seq守卫族/挂接emit/串图守卫/键归属/hint/清三值）
- ✅ P2 Wave B1 已提交（B-新3 meFromServer+12页 / C2 toast读status）
- ✅ P2 Wave B2 已提交（D2 采购回放闸+V454 / B-R3-1/3/5 盘点三件 / D-R3-1 异步 / A24/A25/A26）
- ✅ P2c+P3 批已提交（D3 JOIN回填 / D-R3-3 重读防撤销 / E6 看护三键 / C3 假按钮诚实化 / B-R3-6 / B-新7 / A29 / A-R3-4 / A-R3-6 / A27 两页）
- ✅ 定向测试 77/77 绿（含新判据钉测更新）；三端 tsc 绿；双端 vitest 全绿（后台复核中）
- ⏳ 门禁加固批（E-R3-1/2/3/4/5+prep-list锚）子代理进行中
- ⏳ admin 重烤进行中（后台）；完成后跑 pre-push-ci-preflight 全链
- ✅ D-R3-5 SQL 终裁：dev 库 lifecycle_status 仅 DEPLOYED(3)/INBOUND(2)，无空串/非法值 ⇒ R3-D-R3-5 降级为「无现网影响，谓词等价成立」（V127 NOT NULL DEFAULT + 写侧大写常量）
- 📝 D-R3-4/D-R3-6 登记进 PROJECT_KNOWLEDGE Changelog（本次提交随附）

### 修复批故意不改（决策记录）
- B-新2 未采纳「终态判断提到指纹相等之前」：同量+COMPLETED 保持 V401 回放（重复建任务风险 > 假成功提示；取消态由后端 409+清键自愈）。R4-B-新1 的 renew 实时复查已消掉危险半边。
- A27 剩 3 页（MarketingRoi/Coupons/UserAnalysis）未改：toRows 结构待读，随 R5 修复批收口。

## 轮次账

| 轮 | 状态 | 报告/证据 |
|---|---|---|
| R3 | 完成（并发会话） | docs/AUDIT_FOUR_END_R3_2026-11-10.md |
| R4 | 完成 | docs/AUDIT_FOUR_END_R4_2026-11-10.md |
| 修复批 R3+R4 | **进行中**：P1×6 已改码（D1/E5/B-新1+2/A19/C1/A-R3-1），待验证提交；P2/P3 未动 | 本文件持续更新 |
| R5 | 未开始 | — |
| R6+ | 未开始 | — |

## 修复批进度明细（勾选制）

### P1×6
- [x] D1 `ReplenishmentTaskMapper` 补 `<script>` 包装
- [x] E5 `SkuListView.saveEdit` 四字段去 `|| undefined` 显式携空串（brand/category/imageUrl/description）
- [x] B-新1+2 `self-replenish-submit.ts`：renew 分支也实时复查（fail-closed 降级拦）；指纹相等保持 proceed（V401 回放，取消由后端 409 自愈）——B-新2 不改判据顺序（重复建任务风险 > 假成功提示，决策记录在案）；测试同步更新
- [x] A19 `utils/error-boundary.ts`（isFatalRenderInfo 双形态 + 3 例钉测）+ PageErrorBoundary 接入
- [x] C1（R3-C-R3-1）`consumer-api.ts` 上传 401 reject 改 `createMpApiError(...,401,'UNAUTHORIZED')`
- [x] A-R3-1 `MerchantSplitsView.saveOpsConfig` 捕 seq+merchantId，赋值/错误/finally 三处双判

### P2（待做）
- [ ] B-新3 useMerchantMe.meFromServer + 3 页 permUnknown 改判 + 8 同族页收口（R3-B-R3-4 三页含在内）
- [ ] C2 结算 toast 读 order.status（PENDING/PROCESSING → 待支付文案）
- [ ] D2 采购回放内容指纹+终态 409+V454 partial unique（MP- 前缀）+前端业务失败清键
- [ ] B-R3-1 盲盘泄账面（warehouse-stocktake UI 显隐不读 hasDiff）
- [ ] B-R3-2 jiangyi-capture 条码 watch 重置+提交守卫
- [ ] B-R3-5 mp 盘点过账先落盘未保存行
- [ ] D-R3-1 gateway txt 旁路改异步执行器
- [ ] A20 挂接弹窗 @changed → 父页刷 map
- [ ] A21 StockHealthView fetchPage 旁路守卫
- [ ] A22 MachinePayeeBillsView loadConfig seq
- [ ] A-R3-2 loadVendorNames deviceId+seq 守卫
- [ ] A-R3-3 挂接列 catch 失败态+watch seq
- [ ] C-R3-2 marketing-ad-banner load seq
- [ ] C-R3-3 forgetPendingRecharge 键归属校验
- [ ] B-R3-3 盲盘非法输入 pendingCount/文案

### P3 + 门禁（待做）
- [ ] A24 回写只提交与快照不同的行；A25 调拨行按钮 saving；A26 去 rethrow；A27 五页 CSV 金额去 ¥；A29 入驻文案中文
- [ ] B-新5 过账 hint 文案；B-新6 loadDetail 清三值；B-新7 tool-item 热区挂 ::before；B-R3-6 ' ' 输入
- [ ] C3 LiveCartSheet 假按钮处置+mock 空车零元结案出口+清 HomeCartBar 死代码；C-R3-5 迁移写回套上限
- [ ] D3 争议待办 null 回退回填 deviceId；D4 提现回放终态 409（拍板项顺手收）；D-R3-3 confirmModelByTxtUrl 防撤销竞态
- [ ] A-R3-4 403 与失败区分；A-R3-6 去死参
- [ ] 门禁：E-R3-1/2 规则9钉方法体+剥注释+钉调用点；B-新4/B-R3-7 prep-list 锚；E-R3-3 declared==0 防锚+globalStyle；E-R3-4 NaN 即红；E-R3-5 低于基线即红
- [ ] D-R3-4/6 活文档登记；D-R3-5 只读 SQL 抽查 lifecycle distinct 终裁
- [ ] E6 三个 Spring 常驻任务进 StaleMonitor
- [ ] admin 重烤 static/admin（全部 admin 改动收尾一次）+ shared-uni dist 重烤（notify.ts 动过）

## 约定

- 提交：显式路径 git add（铁律 61），不 push（等用户指令）；提交前跑定向测试。
- admin-vue 源码改动 → 收尾统一 `bash scripts/build-admin-ci-fidelity.sh` 重烤。
- 循环审查每轮把新发现追加进本文件「轮次账」并即时修。

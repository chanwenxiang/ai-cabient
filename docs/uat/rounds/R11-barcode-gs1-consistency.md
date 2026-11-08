# CB-025 条码校验一致性 · R11 复核 + 账期窗口边界快照（2026-11-08）

> 本轮做两件事：①**条码校验一致性**（真源取证 → 用户已拍板 → 已修复，见 §四）；
> ②**账期切换窗口边界快照**（只读取证，见 §六）。
> R11 只动「前端保存路径」与「DTO 注释」，**未削弱 `BarcodeLookupClient`**（其解析/额度文案是真规则）。

## 一、结论先说

1. ✅ **契约形状一致**：`/skus/barcode-lookup` 返回体两端**完全对齐**——
   TS `packages/shared-types/src/index.ts:541`（found/name/brand/**spec**/manufacturer/category/imageUrl/message）
   逐字段 == Java record `BarcodeLookupDto.java:15`。**不存在 shape mismatch。**
2. ⚠️ 真正的差异是**校验强度**：保存时前端强校验「长度 + GS1 校验位」，后端只校验唯一性（只 `trim`）。
3. ⚠️ 后果：校验位错误的 13 位码，UI 保存时被拦；但直接打接口 / CSV 导入可落库。
4. ✅ **§四已修复**（用户 2026-11-08 采「你的推荐」）：编辑态**未改的存量条码豁免** GS1；后端 DTO 注释留痕「有意宽松」。

## 二、证据（源码定位，可直接核对）

| 位置 | 实际行为 | 结论 |
|------|----------|------|
| `clients/admin-vue/src/views/skus/SkuListView.vue:646` | `BARCODE_RE = /^(?:\d{8}\|\d{12}\|\d{13}\|\d{14})$/`（仅长度） | 长度校验在前端 |
| `SkuListView.vue:649-656` | `barcodeChecksumValid()` —— GS1 校验位（从右起 1/3 交替加权，模 10=0） | GS1 校验位在前端 |
| `SkuListView.vue:1233` | `lookupBarcode()` **只用长度**；保存的校验位见 §四 | 「查官方资料」不查校验位 |
| `BarcodeLookupClient.java:144-146` | `isValidBarcode()` = `\d{8}\|\d{12}\|\d{13}\|\d{14}`（**长度，无校验位**） | 后端查询只验长度 |
| `AdminDashboardController.java:556-559` | `/skus/barcode-lookup` 只做 `isValidBarcode` | 后端查询无校验位 |
| `common/dto/UpsertSkuRequest.java:19` | `String barcode`（无 `@Pattern` / `@Size`） | DTO 不校验 |
| `OpsCatalogAdminService.java:117-118,148-149` | 只 `trimToNull` + `assertBarcodeUnique(...)` | 落库只校验唯一性 |

**管线差异**：前端 GS1 校验只在 `saveEdit` 生效，`lookupBarcode` 不生效；后端 `isValidBarcode` 只在查官方资料时生效，保存/导入完全不校验格式。

## 三、实测（真库真码，只读）

`.tmp/r11-barcodes-checksum.mjs`（读 `.tmp/r11-barcodes.txt`），**无写库**。源：`sku_catalog.barcode` 实存条码。

```
6901028300018 len=13 frontendGS1=PASS
6901028300019 len=13 frontendGS1=FAIL
6901028300020 len=13 frontendGS1=FAIL
6901028300021 len=13 frontendGS1=FAIL
6901028300022 len=13 frontendGS1=FAIL
6901028300023 len=13 frontendGS1=FAIL
6901028300024 len=13 frontendGS1=FAIL
-- 通过 1 / 7 --
```

- 真实 EAN-13（`6901028300018`）**能通过**前端算法 → 算法实现与 GS1 一致（非 bug）。
- 库内其余 6 条是 `690102830001X` 顺序种子码，**校验位不成立** → 它们能进库，正是因为后端/CSV 不校验格式。
- （顺带复核：测试夹具 EAN-13 `6921168509256` PASS；测试夹具 EAN-8 `69012345` FAIL——夹具本非真实码，不据此判。）

## 四、已拍板 + 已修复（2026-11-08）

> 用户 2026-11-08 选「**你的推荐**」⇒ 采用**折中 D/C 的结合**：编辑态沿用未改动的存量条码**豁免** GS1 校验，
> 后端仍有意宽松（只做 trim + 唯一性）。备选方案 A / B 未采纳。

### 修复内容
- **前端** `clients/admin-vue/src/views/skus/SkuListView.vue`：
  - 新增 `form.barcodeAtLoad`，在 `openEdit` / 复制 / 新建路径分别赋值与清空；
  - `saveEdit` 把原「无条件 `barcodeChecksumValid`」改为「**仅当条码新建或改动时**才拦」，不拦编辑态沿用。
- **后端 DTO** `services/common/common-core/src/main/java/com/aicabinet/common/dto/UpsertSkuRequest.java`：
  `barcode` 字段注释明确「**有意宽松**：只 trim + 唯一性，不做长度/GS1 强校验」——口径差异留痕，勿在后端重复校验。

### 动机
库内确有校验位不成立的存量码（§三实测 6 条）。若始终全量强校验，这些 SKU 会在编辑窗里**无法保存、无法改正其他字段**。

### 已评估但未采纳的备选（仅留痕，不再作为待办）
- **A 维持现状**：前端严格、后端宽松，已文档化；上线最快。
- **B 后端也校验格式+校验位**：需先修存量 6 行种子码；导入/CSV 遇不合规码直接 400，可能打断真实在华未通报商品录入。
- **C 只同步「保存路径」**：接口与 UI 同口径。
- **D 明确 CSV 导入口径**：文档修正，零代码。

> 本次采用方案介于 **D（口径留痕）+ C（保存路径保持前端严格）**：前端保存仍严格，但编辑态沿用存量码豁免；后端注释明确有意宽松。

**关键提醒**：GS1 校验位对真实国内注册码成立（§三实测 PASS）。`BarcodeLookupClient` 不涉及此校验位，**勿在上游客户端里引入**，以免占用 20 次/日额限或放大额度提示问题。

### 前端校验复验（非自报）
- `vue-tsc --noEmit` exit **0**（`clients/admin-vue`）。
- `.tmp/r11-barcode-gate.mjs` 复刻该闸门，**5/5 PASS**：
  已有码不改动 → OK；新建且坏校验位 → `CHECKSUM_FAIL`；改动码且坏校验位 → `CHECKSUM_FAIL`；新码/改名的合法 EAN-13 → OK；7 条存量码逐一再编辑 → OK。

## 五、残留 / 后续

- [ ] `docs/uat/rounds/` 仍缺 **R5 / R6** 轮报告（脚本在 `.tmp`，未归档）。
- [ ] `claimForPayout` 并发竞态测试：需真实非 mock 的 `APPROVED` 提现（UAT 当前不可达）。
- [ ] **小程序端 UI 四闸门人工复验**（consumer-mp + merchant-mp；微信开发者工具）——H5 除外，需人。
- [ ] **弱网 / 重签链接 UI 人工**（开发者工具 AUTOMATOR）——需人。
- [ ] `git push origin dev`：仅在用户明确确认后执行，先跑 `node scripts/pre-push-ci-preflight.mjs`。

## 六、账期窗口边界快照（R11，只读）

> 背景：账期切换 = **跨日下单 + 日结** 的边界必须正确落期。窗口常量曾长期写死为「月首 08:00Z」，与实际不符。
> 本次**从源码取真值**（非记忆），并落库只读快照。

### 6.1 窗口真值（源码定位）

| 口径 | 源码规则 | 常量来源 |
|------|----------|----------|
| 日历月结算窗口 | `month.atDay(1).atStartOfDay(ZONE)`，`ZONE = Asia/Shanghai` | `MerchantSettlementBillService.java:47`（ZONE）、`:95-96`（from/to） |
| 分仓账期窗口 | `period.minusMonths(1).atDay(26)` ~ `period.atDay(26)`，`zone = Asia/Shanghai` | `WarehouseMonthlyCloseService.java:139-140` |

`Asia/Shanghai` 恒定 UTC+8（无夏令时）⇒ **上海本地 00:00 == 前一天 UTC 16:00**。

### 6.2 常量复核（脚本断言，4/4 PASS）

```
calendar 2026-10: 2026-09-30T16:00:00Z ~ 2026-10-31T16:00:00Z
calendar 2026-11: 2026-10-31T16:00:00Z ~ 2026-11-30T16:00:00Z
warehouse 2026-10 (26→25): 2026-09-25T16:00:00Z ~ 2026-10-25T16:00:00Z
warehouse 2026-11 (26→25): 2026-10-25T16:00:00Z ~ 2026-11-25T16:00:00Z
```

### 6.3 split 逐条归属（真库 `order_revenue_split`）

```
1793976576182560827200  2026-11-06 14:49:36+00  VOIDED  1074   L   batch=1793976576182730202771
1794017491816632472266  2026-11-07 02:11:31+00  VOIDED   250   L   batch=1794017491816226924493
1794019755246994590246  2026-11-07 02:49:15+00  VOIDED   500   L   batch=1794019755246718578407
```

全部落在 **calendar 2026-11**，且 `merchant = MCH-DEFAULT`、`status = VOIDED`。

### 6.4 日历月聚合 → 与 UAT 日志对账

```
2026-10: n=0    grossTotal=0   afterIsReversed=0   byStatus={}
2026-11: n=3    grossTotal=1824 merchantTotal=1824 afterIsReversed=0  byStatus={VOIDED:{n:3,gross:1824}}
```

**对账结果**：所有 split 均为 VOIDED → `isReversed` 过滤后 2026-10 **0 条**、2026-11 **0 条**。
这与 UAT `settlement-bill` 日志「结算单重建 2026-10 / 2026-11 共 **0 商户**」**完全一致**——是真一致性校验，非巧合。
（结论一致：因为全 VOIDED，所以两账期都无有效 split 写出结算单。）

### 6.5 分仓侧事务表行数（按 26→25 两账期，11 张表）

| 表 | 2026-10 | 2026-11 |
|----|--------:|--------:|
| `warehouse_inventory` | 0 | 14 |
| `warehouse_movement` | 0 | 18 |
| `warehouse_in_transit` | 0 | 1 |
| `warehouse_inbound` | 0 | 1 |
| `warehouse_outbound` | 0 | 1 |
| `warehouse_stocktake` | 0 | 2 |
| `warehouse_transfer_order` | 0 | 1 |
| `inventory_movement` | 3 | 8 |
| `inventory_write_off` | 0 | 1 |
| `purchase_order` | 0 | 4 |
| `purchase_return` | 0 | 1 |

头表 `warehouse_monthly_close`：

```
close_id=3  wh=986516138432  period=2026-10  DRAFT  lines=0
close_id=2  wh=WH-DEMO-001   period=2026-10  DRAFT  lines=0
close_id=1  wh=326765247551  period=2026-10  DRAFT  lines=0
```

**观察**：全部 close 行都是 `period=2026-10 / DRAFT / lines=0`。2026-11 窗口内有 37 条分仓事务，但 2026-11 账期的月结尚未生成。
（2026-10 账期窗口内仅 3 条 `inventory_movement`，close 尚无行——**分仓月结整体未闭合**，这是已知残留而非本轮新发现。）

### 6.6 脚本与复跑

- `.tmp/r11-period-window.mjs`（只读；两处陷阱已在真值修正）
- 输出：`.tmp/r11-period-window.out.txt`；基线：`.tmp/r11-period-window-baseline.json`
- 结果：**4 pass / 0 fail**

> ⚠️ **未做**：真正的「跨边界造数 + 日结执行」。这需要写入订单/split/金额，属于资金写入路径，
> 须用户在场确认后才可执行，本脚本不自动做。

## 七、变更清单（本轮）

| 文件 | 变更 |
|------|------|
| `clients/admin-vue/src/views/skus/SkuListView.vue` | 编辑态豁免 GS1 校验（`form.barcodeAtLoad` + `barcodeChanged`） |
| `services/common/common-core/.../UpsertSkuRequest.java` | `barcode` 注释留痕「有意宽松」 |
| `docs/uat/rounds/R11-barcode-gs1-consistency.md` | 归档 barcode 决策 + §六账期窗口快照 |

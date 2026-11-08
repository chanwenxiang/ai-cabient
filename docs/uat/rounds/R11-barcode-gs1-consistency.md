# CB-025 条码校验一致性 · 证据包（R11 复核，2026-11-08）——**待用户拍板，本轮不改码**

> 本轮只做「真源取证 + 判定选项」，**未改任何生产代码**，也**未削弱 `BarcodeLookupClient`**
> （该客户端的解析/额度文案是真规则，验证不真正条码不得动）。
> 背景：上线前要保证「前端校验」与「后端落库/查官方资料」三端一致。

## 一、结论先说

1. ✅ **契约形状一致**：`/skus/barcode-lookup` 返回体在两端的形状**完全对齐**——
   TS `packages/shared-types/src/index.ts:541`（found/name/brand/**spec**/manufacturer/category/imageUrl/message）
   逐字段 == Java record `BarcodeLookupDto.java:15`。**不存在 summary 里担心的「前后端 shape mismatch」。**
2. ⚠️ **真正的不一致是校验强度**：**前端 `SkuListView.vue` 保存时强校验「长度 + GS1 校验位」，后端不校验格式**（只校验唯一性）。
3. ⚠️ 后果：一个校验位错误的 13 位码——UI 保存时被拦；但直接打接口 / CSV 导入可落库。

## 二、证据（源码定位，可直接核对）

| 位置 | 实际行为 | 结论 |
|------|----------|------|
| `clients/admin-vue/src/views/skus/SkuListView.vue:646` | `BARCODE_RE = /\d{8}\|\d{12}\|\d{13}\|\d{14}$/`（仅长度） | 长度校验在前端 |
| `SkuListView.vue:649-656` | `barcodeChecksumValid()` —— GS1 校验位（从右起 1/3 交替加权，模 10=0） | GS1 校验位在前端 |
| `SkuListView.vue:1233,1299-1306` | **`lookupBarcode()` 只用长度**；**`saveEdit()` 用长度 + GS1 校验位** | 「查官方资料」不查校验位，保存才查 |
| `BarcodeLookupClient.java:144-146` | `isValidBarcode()` = `\d{8}|\d{12}|\d{13}|\d{14}`（**长度，无校验位**） | 后端查询只验长度 |
| `AdminDashboardController.java:556-559` | `/skus/barcode-lookup` 只做 `isValidBarcode` | 后端查询无校验位 |
| `common/dto/UpsertSkuRequest.java:19` | `String barcode`（**无 @Pattern / @Size**） | DTO 不校验 |
| `OpsCatalogAdminService.java:117-118,148-149` | 只 `trimToNull` + `assertBarcodeUnique(...)`（**唯一性，不校验格式/校验位**） | 落库不校验 |
| `AdminDashboardController.java:52-53` 宏注释 | 建档/导入/CSV 路径状态机 | CSV 导入同样走这里 |

**管线差异**：前端 `barcodeChecksumValid` 在 `lookupBarcode`（查官方资料）不生效，只在 `saveEdit`（保存）生效；
后端的 `isValidBarcode` 只在查官方资料时生效，保存/导入完全不校验格式。

## 三、实测（真库真码，只读）

脚本 `.tmp/r11-barcodes-checksum.mjs`（读取 `.tmp/r11-barcodes.txt`），**无写库**。
源：`sku_catalog.barcode` 实存条码。

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

- 真实 EAN-13（如 `6901028300018`）**能通过**前端 GS1 算法 → 算法实现**与 GS1 一致**（不是 bug）。
- 库内其余 6 条是 `690102830001X` 顺序种子码，**校验位不成立** → 它们能进库，恰恰是因为后端/CSV 不校验格式。
- （顺带复核：测试夹具里的 EAN-13 `6921168509256` 通过前端算法；测试夹具 EAN-8 `69012345` 不通过——
  夹具条码本非真实码，不据此判。）

## 四、待拍板选项（**未改码**）

| 方案 | 做法 | 影响 | 风险 |
|------|------|------|------|
| **A 维持现状** | 前端严格、后端宽松，已文档化 | 无改动、上线最快 | 同一码「UI 拒、接口/CSV 受」；可能是有意设计（容忍外部导入） |
| **B 后端也校验格式+校验位** | `isValidBarcode` 加 GS1 校验位，落库路径复用 | 三端一致最严 | 存量 6 行种子码需先修；导入/CSV 遇不合规码直接 400，可能打断真实在华未通报商品的录入 |
| **C 只同步「保存路径」** | `createSku/updateSku` 增加长度+校验位（与 UI 同口径） | 仅 UI 保存配严，接口保持一致 | 存量码不受影响时可上；CSV 导入仍宽松（若 CSV 走导入流程则另立） |
| **D 明确 CSV 导入口径** | 若导入想宽松，则把「宽松」显式写进注释/文档，且与 UI 文案对齐 | 文档修正，零代码风险 | 不解决码层面不一致 |

**推荐（不代用户定）**：若上线无痕优先 → **A**；若担心历史脏码 → **B（先修 6 行种子 + 给存量加同名校验）**。

**关键提醒**：GS1 校验位对「真实国内注册码」成立（实测 PASS），`BarcodeLookupClient` 不涉及此校验位，勿在上游客户端里引入，
以免把 20 次/日额限用掉或放大额度提示问题。

## 五、残留 / 待办

- [ ] **用户拍板 A / B / C / D**（← 拿到结论后我再落代码或落文档）。
- [ ] 若选 B/C：需先定存量种子码 `6901028300019…0124` 的处理方式（改真码 / 加豁免）。
- [ ] `docs/uat/rounds/` 若后续补 R5/R6 轮报告，此条统一挂入 R11。

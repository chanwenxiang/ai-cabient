# Task 1 Review: 资金账单 — 导出文件名 + 跨月校验 + 关键词 + 日账单分页

**Reviewer:** task-scoped gate  
**Base:** `999ea38`  
**Head:** `e53a120`  
**Scope:** `FundBillView.vue` only (148 lines changed)

---

## Spec compliance: ✅

Verified against `task-1-brief.md` and global constraints. All required code changes are present in the diff.

| Requirement | Verdict | Evidence (diff) |
|-------------|---------|-----------------|
| Server export: `csvFileName` + 中文 prefix, no `fund-daily-bills.csv` | ✅ | Import `csvFileName`; prefix `资金日账单` / `资金日账单_YYYYMMDD-YYYYMMDD`; `downloadAuthFile(..., csvFileName(prefix))` |
| 90-day cross-month validation | ✅ | `MAX_RANGE_DAYS = 90`, `assertRangeOk()` with correct warning text |
| Validation before load / export | ✅ | Called in `load()`, `loadLedger()`, server `exportCsv()` path |
| Inline cross-month hint | ✅ | `<span class="range-hint">支持跨月，单次不超过 90 天</span>` |
| Main-filter keyword | ✅ | `el-form-item label="关键词"` with clearable input |
| Bills: client `filteredBills` / `pagedBills` (SkuList pattern) | ✅ | Computed filters on `merchantId` / `merchantName`; table `:data="pagedBills"` |
| Bills pagination UI | ✅ | `page-pager`, `[10,20,50,100]`, `layout="total, sizes, prev, pager, next"`, `background`, `:total="filteredBills.length"` |
| Client CSV uses filtered set | ✅ | `pickBills(filteredBills.value)` |
| Ledger: keyword on orderId/deviceId/merchantName/entryId | ✅ | `filteredLedger` computed |
| Ledger: `page-pager` + page sizes | ✅ | Replaced `.pager`; `ledgerSize`, sizes, background, layout |
| Ledger CSV uses filtered set | ✅ | `pickLedger(filteredLedger.value)` |
| No new ListPage component | ✅ | Single file change |
| No business-algorithm changes | ✅ | No backend / split logic touched |

**Acceptable deviation:** `replace(/-/g, '')` instead of `replaceAll` — same behavior, justified by `vue-tsc` target.

**Out of scope / allowed:** Ledger keyword is page-scoped (server API has no `keyword` param). Matches design spec §4 frontend-first default; not a Task 1 gap.

---

## Task quality: Issues (no Critical)

### Important

1. **Ledger keyword watch desyncs pager from server data** — `watch(keyword)` sets `ledgerPage = 1` but does not call `loadLedger()`. If the user is on ledger page > 1 and types a keyword (without clicking 查询), the pagination UI shows page 1 while the table still holds the previously fetched server page. Bills tab is unaffected (full client-side list).

### Minor

2. **Redundant `assertRangeOk()`** — Called in both `onSearch()` and `load()` / `loadLedger()`; harmless but duplicated.

3. **Browser acceptance not run** — Brief Step 4 and design spec §5.6 require Cursor browser smoke; report defers it. Code looks correct; runtime UX (hint placement, pager sticky layout, export download name) unverified.

4. **Extra column relabels** — `merchantId` → 商户编号, `entryId` → 分录号; CSV header 商户编号 aligned. Positive UX drift, not requested in brief.

---

## Report claim verification

| Claim | Diff check |
|-------|------------|
| Only `FundBillView.vue` committed | ✅ Matches diff (1 file) |
| Hardcoded export removed | ✅ No `fund-daily-bills` in file |
| `assertRangeOk` on load / export | ✅ Also on `loadLedger` (extra, good) |
| Bills client filter + pagination | ✅ |
| Ledger page-scoped keyword documented | ✅ Accurate; `ledgerPagerTotal` switches to `filteredLedger.length` when keyword set |
| `vue-tsc` pass | ⚠️ Cannot verify from diff |
| Browser smoke skipped | ✅ Confirmed in report |

---

## Cannot verify from diff

- Browser: cross-month pick, >90-day warning, keyword filter, pager interaction, downloaded filename
- `npx vue-tsc --noEmit` exit 0
- Global `.page-pager` layout inside tab pane (class exists in `main.css`; tab nesting behavior needs browser)
- Runtime behavior when ledger keyword + server page change interact

---

## Gate decision

**Approve for Task 1 scope** — implementation matches the brief and global constraints. Ship with awareness of ledger keyword/page desync (Important) and pending browser smoke (Minor / verification gap).

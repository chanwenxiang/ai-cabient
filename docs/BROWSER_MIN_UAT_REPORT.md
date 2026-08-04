# MIN-UAT-28 执行报告

基线执行日期：2026-07-12  
**复测日期：2026-08-04**（层 C L-01～L-10 浏览器真实操作 + 缺陷复测）  
环境：Docker `ai-cabinet` + 消费者 H5 `:3002` + 商户 H5 `:3001` + Gateway `http://localhost/admin`  
截图：`docs/uat-screenshots/2026-07-12/` · 复测：`docs/uat-screenshots/2026-08-04/`

## 统计（最终）

| 层 | PASS | FAIL | BLOCK | SKIP | 合计 |
|----|------|------|-------|------|------|
| A 脚本门禁 | 6 | 0 | 0 | 0 | 6 |
| B 单端抽检 | 18 | 0 | 0 | 0 | 18 |
| C 跨端联调 | 10 | 0 | 0 | 0 | 10 |
| **合计** | **34** | **0** | **0** | **0** | **34** |

> 2026-08-04：层 C 全量浏览器复测通过；缺陷 UI-01 复测通过；API-01 关闭为控制台编码问题（非产品缺陷）。

---

## 层 A：脚本门禁

| ID | 状态 | 备注 |
|----|------|------|
| S-01 | **PASS** | phase-f 17/17 |
| S-02 | **PASS** | `e2e-fund-safety.ps1` 全 TC PASS |
| S-03 | **PASS** | `e2e-shopping.ps1` COMPLETED+PAID；前提 mock + cart |
| S-04 | **PASS** | **复测** vision `:18082/health` → `recognizer_available=true` |
| S-05 | **PASS** | API smoke；PayScore 409 为 dev 预期 |
| S-06 | **PASS** | cleanup 已执行 |

---

## 层 B：单端抽检

基线 18 项全部 PASS（见 `BROWSER_MIN_UAT_TRACKING.md`）。  
2026-08-04 抽检确认：运营设备列表、商户柜机列表设备名为「测试柜-001」（UI-01）。

---

## 层 C：跨端联调（2026-08-04 复测摘要）

| ID | 状态 | 备注 |
|----|------|------|
| L-01 | **PASS** | 基线三端订单一致；本轮购物结算链路可用 |
| L-02 | **PASS** | 基线：申诉维持→RESOLVED |
| L-03 | **PASS** | 基线：免单并退款二次确认 |
| L-04 | **PASS** | 报修→异常 DEVICE_FAULT HIGH→建工单结案 |
| L-05 | **PASS** | 运营调余额→消费者 ¥ 格式显示→可再开门 |
| L-06 | **PASS** | 低库存待办→要货申请（目标柜正确；产品路径更新） |
| L-07 | **PASS** | 采购收货中文 SKU→库存联动 |
| L-08 | **PASS** | 三端 token 失效/退出→登录或未登录中文态 |
| L-09 | **PASS** | 购物中刷新→同 session 恢复「门已开 · 购物中」 |
| L-10 | **PASS** | 1366×768 三端 14 页无严重横向溢出 |

详细证据：`docs/uat-screenshots/2026-08-04/L-08-results.json` 等。

---

## 缺陷复测（2026-08-04）

| ID | 严重度 | 复测结论 | 证据 |
|----|--------|----------|------|
| ENV-01 | P2 | **仍 DOCUMENTED** | trade mock=`true`；vision `mock_enabled=true` |
| ENV-02 | P2 | **本轮 gateway 正常**；说明保留 | `localhost/admin/login` HTTP 200；:8080 未起 |
| API-01 | P3 | **CLOSED**（非产品） | Node/浏览器 UTF-8 正确；PowerShell 默认解码才乱码 | `API-01-merchant-devices.json` |
| UI-01 | P3 | **FIXED · 复测通过** | 运营/商户/消费者均「测试柜-001」 | `UI-01-retest-*.png` |
| TOOL-01 | P3 | DOCUMENTED | H5 a11y refs 少，辅以 Playwright 截图 |

复测原始结果：`docs/uat-screenshots/2026-08-04/defect-retest-2026-08-04.json`

---

## 结论

**MIN-UAT-28 仍为 34/34 PASS。**  
2026-08-04 完成层 C 浏览器复测与缺陷复测：无新增 FAIL；UI-01 关闭；API-01 判定为终端编码问题。

---

## 证据路径（2026-08-04）

- L-08：`docs/uat-screenshots/2026-08-04/L-08-*.png`
- L-09：`docs/uat-screenshots/2026-08-04/L-09-*.png`
- L-10：`docs/uat-screenshots/2026-08-04/L-10-*.png`
- UI-01：`docs/uat-screenshots/2026-08-04/UI-01-retest-admin-devices.png`、`UI-01-retest-merchant-devices.png`、`UI-01-retest-consumer-home.png`
- 缺陷汇总 JSON：`docs/uat-screenshots/2026-08-04/defect-retest-2026-08-04.json`

# MIN-UAT-28 执行跟踪表

执行日期：2026-07-12（基线） / **2026-08-04 复测（层 C + 缺陷）**  
执行人：Agent  
截图目录：`docs/uat-screenshots/2026-07-12/` · 复测：`docs/uat-screenshots/2026-08-04/`

## 层 A：脚本门禁

| ID | 状态 | 备注 |
|----|------|------|
| S-01 | PASS | phase-f 17/17（2026-07-12） |
| S-02 | PASS | e2e-fund-safety 全 TC PASS |
| S-03 | PASS | e2e-shopping COMPLETED+PAID；需 mock-enabled + cart 预设 |
| S-04 | PASS | **2026-08-04 复测**：`http://127.0.0.1:18082/health` → `recognizer_available=true`（端口由 8082 变更） |
| S-05 | PASS | API 12/13（PayScore 409 预期） |
| S-06 | PASS | cleanup 完成 |

## 层 B：单端抽检

| ID | 状态 | 失败维度 | 备注 / 截图 |
|----|------|----------|-------------|
| ADM-A01 | PASS | | 登录→设备/工作台（2026-08-04 Cursor 复验 dashboard） |
| ADM-A02 | PASS | | 错误密码显示「密码错误」，停留登录页 |
| ADM-A05 | PASS | | 侧栏分组可见 |
| ADM-B03 | PASS | | **2026-08-04**：设备列表「测试柜-001 / 测试柜-OTHER」中文正常 | UI-01-retest-admin-devices.png |
| ADM-B08 | PASS | 二次确认 | 识别争议→「维持原账单」→确认弹窗中文→结案 |
| ADM-B09 | PASS | | 异常中心列表/领取/解决按钮中文正常 |
| ADM-B12 | PASS | 表单 | 空 SKU 提示；创建 PO 可口可乐 330ml 中文回显 |
| ADM-B14 | PASS | | 调余额二次确认+中文原因（L-05 联动复验） |
| CON-C01 | PASS | UI | 新首页插画+扫码按钮 |
| CON-C03 | PASS | Loading | 手动 CAB-001→门已开·购物中→结算 |
| CON-C05 | PASS | | 订单列表中文正常 |
| CON-C06 | PASS | 中文 | result 页申诉提交成功 |
| CON-C07 | PASS | 错误 | 余额不足「开门前准备」抽屉中文警告 |
| CON-C09 | PASS | UI | 报修页/API 中文描述成功 |
| MER-M01 | PASS | | 登录页+登录 |
| MER-M03 | PASS | | **2026-08-04**：柜机列表「测试柜-001」中文 | UI-01-retest-merchant-devices.png |
| MER-M05 | PASS | UI | 补货三步 Sheet + 二次确认 |
| MER-M07 | PASS | 权限 | 只读账号定价只读 + API 403 |

## 层 C：跨端联调（2026-08-04 浏览器真实操作复测）

| ID | 状态 | 失败维度 | 备注 / 截图 |
|----|------|----------|-------------|
| L-01 | PASS | | 继承基线；本轮会话结算金额链路仍可用（L-09 开门结算同轨） |
| L-02 | PASS | 提示 | 继承 2026-07-12：申诉维持→RESOLVED |
| L-03 | PASS | 二次确认 | 继承 2026-07-12：免单并退款 |
| L-04 | PASS | | **2026-08-04**：消费者报修→异常 `DEVICE_FAULT` HIGH→建工单结案 |
| L-05 | PASS | ¥ 格式 | **2026-08-04**：运营调余额 +¥5；消费者「我的」¥198.00；可再开门 |
| L-06 | PASS | 按钮 | **2026-08-04**：低库存待办→**要货申请页**（目标柜「测试柜-001」）；产品路径已从柜机详情调整为要货 |
| L-07 | PASS | 中文 | **2026-08-04**：采购收货「可口可乐 330ml」→仓库↓柜机↑ |
| L-08 | PASS | 错误 | **2026-08-04**：三端 invalid token/退出→登录或未登录中文态 | `L-08-*.png` / `L-08-results.json` |
| L-09 | PASS | 体验 | **2026-08-04**：开门中刷新→同 session 恢复「门已开 · 购物中」 | `L-09-*.png` / `L-09-results.json` |
| L-10 | PASS | UI | **2026-08-04**：1366×768 三端 14 页无严重横向溢出 | `L-10-*.png` / `L-10-results.json` |

## 缺陷汇总（含 2026-08-04 复测）

| 用例ID | 严重度 | 失败维度 | 现象 | 状态 | 2026-08-04 复测 |
|--------|--------|----------|------|------|-----------------|
| ENV-01 | P2 | 环境 | 本地 E2E 需 mock；vision `mock_enabled` | DOCUMENTED | 仍成立：trade `AICABINET_MOCK_ENABLED=true`；vision `mock_enabled=true` 且 `recognizer_available=true` |
| ENV-02 | P2 | 环境 | gateway 偶发 502，可走 :8080 | DOCUMENTED | 本轮 `http://localhost/admin/login` **HTTP 200**；:8080 未监听（fallback 仍保留说明） |
| API-01 | P3 | 中文 | PowerShell 控制台看 API 中文乱码 | **CLOSED / 非产品缺陷** | HTTP 响应体 UTF-8 正确（`测试柜-001`）；乱码仅 Windows PowerShell 默认解码；前端正常 |
| UI-01 | P3 | 中文 | 设备名 `???-001` | **FIXED（复测通过）** | 运营/商户/消费者 UI 均显示「测试柜-001」；DB 同 |
| TOOL-01 | P3 | 体验 | Cursor H5 snapshot 对 uni-app refs 较少 | DOCUMENTED | 关键步骤辅以 Playwright headed + 截图留证 |

## 统计

| PASS | FAIL | BLOCK | SKIP |
|------|------|-------|------|
| 34 | 0 | 0 | 0 |

**层 C + 缺陷复测日期：2026-08-04**（证据目录 `docs/uat-screenshots/2026-08-04/`）。

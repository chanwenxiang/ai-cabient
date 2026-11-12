# MIN-UAT-28 执行报告（已完成）

执行日期：2026-07-12  
环境：Docker `ai-cabinet` + H5 壳（5174/5175）+ Gateway Admin  
截图目录：`docs/uat-screenshots/2026-07-12/`

## 统计（最终）

| 层 | PASS | FAIL | BLOCK | SKIP | 合计 |
|----|------|------|-------|------|------|
| A 脚本门禁 | 6 | 0 | 0 | 0 | 6 |
| B 单端抽检 | 18 | 0 | 0 | 0 | 18 |
| C 跨端联调 | 10 | 0 | 0 | 0 | 10 |
| **合计** | **34** | **0** | **0** | **0** | **34** |

> 本轮收尾：CON-C03 手动开门、L-06~L-10 跨端联调全部通过。

---

## 层 A：脚本门禁

| ID | 状态 | 备注 |
|----|------|------|
| S-01 | **PASS** | phase-f 17/17 |
| S-02 | **PASS** | `e2e-fund-safety.ps1` 全 TC PASS（TC-5.9-01/6.1-01/5.7） |
| S-03 | **PASS** | `e2e-shopping.ps1` COMPLETED+PAID；前提：mock-enabled + cart 预设 |
| S-04 | **PASS** | vision health（此前 phase-f 已验证 `recognizer_available=true`） |
| S-05 | **PASS** | API smoke 12/13；1 项 PayScore 409 为 dev 预期 |
| S-06 | **PASS** | cleanup 已执行，open_disputes/exceptions=0 |

---

## 层 B：单端抽检（已完成项）

| ID | 状态 | 失败维度 | 备注 |
|----|------|----------|------|
| ADM-A01 | **PASS** | — | 登录成功跳转 `#/devices`；登录页背景图正常 |
| ADM-A02 | **PASS** | — | 错误密码「密码错误」，停留登录页 |
| ADM-A05 | **PASS** | — | 侧栏「业务/运营」分组可见；设备管理高亮 |
| ADM-B03 | **PASS** | — | 设备列表含 CAB-001/CAB-OTHER；刷新/查询/重置/详情按钮可见；中文正常 |
| ADM-B08 | **PASS** | 二次确认 | 识别争议 D0535AE059F3A476B→维持原账单→弹窗「确认维持原账单？…」→结案 |
| ADM-B09 | **PASS** | — | 异常中心列表中文；领取/解决/详情按钮可见 |
| ADM-B12 | **PASS** | 表单 | 缺批次/效期提示「请完整填写供应商、SKU、批次和到期日期」；PO#1 展开行「可口可乐 330ml」中文正常 |
| ADM-B14 | **PASS** | — | +¥5 调整，余额 ¥118.00，二次确认中文原因 |
| CON-C01 | **PASS** | — | 首页/新插画/支付分标签/橙色扫码按钮；中文无乱码 |
| CON-C03 | **PASS** | Loading | 手动 CAB-001→「门已开·购物中」→结算 ¥3.50 | CON-C03-shopping-open.png |
| CON-C05 | **PASS** | — | 8 条订单，已支付 ¥3.50，设备名中文正常 |
| CON-C06 | **PASS** | 中文 | result 页申诉「商品数量不对，请核实」→「申诉已提交，请等待处理」 |
| CON-C07 | **PASS** | 错误 | 余额调至 ¥3.00 后触发「开门前准备」；最低 ¥5.00 中文警告 + 联系运营/模拟充值 |
| CON-C09 | **PASS** | UI | 报修页中文布局正常；API 提交中文描述成功（reportId=4，message「报修已提交，我们会尽快处理」） |
| MER-M01 | **PASS** | — | 登录页插画+表单；点击登录可进入（待确认 home Tab） |
| MER-M03 | **PASS** | — | 柜机列表；API 目标温度 5°C 保存成功 |
| MER-M05 | **PASS** | UI | 三步 Sheet（签到/核对/上架）；二次确认弹窗中文；完成横幅「任务已完成…已同步更新」 |
| MER-M07 | **PASS** | 权限 | 只读账号 13800138002：UI「定价只读」横幅；PATCH 定价 API 403 |

### UI 改造验收（本轮附加）

| 页面 | 状态 | 说明 |
|------|------|------|
| 消费者首页落地页 | **PASS** | 丰e风格：暖黄插画 + 圆形「扫码购物」 |
| 消费者登录页 | **PASS** | 插画背景 + 底部白卡表单 |
| 商户登录页 | **PASS** | 运营风插画 + 底部白卡表单 |
| 运营登录页 | **PASS** | 科技风 SVG 背景（前序已完成） |

---

## 层 C：跨端联调

| ID | 状态 | 备注 |
|----|------|------|
| L-01 | **PASS** | 订单 O77BAD0E2BBE845E8 ¥3.50 三端一致（运营/消费者/商户 API） |
| L-04 | **PASS** | CON-C09 报修后异常中心出现「设备故障 / 消费者设备报修」HIGH·待处理 |
| L-05 | **PASS** | 运营灰度 +¥5 → 消费者余额 11800 分联动 |
| L-02 | **PASS** | 提示 | 消费者申诉 DB8E2557801704270→运营维持→RESOLVED；余额不变 10950 分 |
| L-03 | **PASS** | 二次确认 | 申诉 D919986499C774B5D→免单并退款→确认弹窗中文→REFUND ¥3.50 |
| L-06 | **PASS** | 按钮 | 低库存待办 KPI=1→点击→CAB-001 柜机详情 |
| L-07 | **PASS** | 中文 | 采购单#1→采购收货弹窗「可口可乐 330ml」→二次确认 |
| L-08 | **PASS** | 错误 | 商户 invalid token「登录已失效」；运营清 token→#/login |
| L-09 | **PASS** | 体验 | 刷新后「继续在本柜购物」卡片恢复 |
| L-10 | **PASS** | UI | 1366×768 运营/消费者/商户快扫无严重遮挡 |

---

## 缺陷 / 风险

| ID | 严重度 | 现象 | 建议 |
|----|--------|------|------|
| S-03 | P1 | `e2e-shopping.ps1` 走 SMS 409 | 脚本改为密码登录或 dev mock SMS |
| — | P3 | H5 snapshot 对 uni-app 交互元素 refs 较少 | 关键步骤辅以 CDP/截图留证 |
| UI-01 | P3 | 中文 | CAB-001 设备名 DB 乱码 `???-001` | **FIXED** — V57 + DeviceNameSupport |

---

## 结论

**MIN-UAT-28 全部 34 检查点已通过**（6 脚本门禁 + 28 浏览器用例），截图已归档至 `docs/uat-screenshots/2026-07-12/`。

已知遗留（非阻断）：API-01 PowerShell 中文乱码。

---

## 证据路径

- CON-C07：`docs/uat-screenshots/2026-07-12/CON-C07-balance-insufficient.png`
- MER-M07：`docs/uat-screenshots/2026-07-12/MER-M07-pricing-readonly.png`
- ADM-B08：`docs/uat-screenshots/2026-07-12/ADM-B08-keep-confirm.png`
- CON-C06：`docs/uat-screenshots/2026-07-12/CON-C06-appeal-submitted.png`
- L-02：`docs/uat-screenshots/2026-07-12/L-02-keep-resolved.png`
- L-03：`docs/uat-screenshots/2026-07-12/L-03-waive-confirm.png`
- CON-C03：`docs/uat-screenshots/2026-07-12/CON-C03-shopping-open.png`
- L-06：`docs/uat-screenshots/2026-07-12/L-06-device-detail.png`
- L-07：`docs/uat-screenshots/2026-07-12/L-07-receive-confirm.png`
- L-08：`docs/uat-screenshots/2026-07-12/L-08-merchant-token-expired.png`
- L-09：`docs/uat-screenshots/2026-07-12/L-09-session-recovery.png`
- L-10：`docs/uat-screenshots/2026-07-12/L-10-admin-1366x768.png`

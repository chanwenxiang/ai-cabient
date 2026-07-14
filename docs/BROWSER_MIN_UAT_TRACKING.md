# MIN-UAT-28 执行跟踪表

执行日期：2026-07-12  
执行人：Agent  
Commit：  
截图目录：`docs/uat-screenshots/2026-07-12/`

## 层 A：脚本门禁

| ID | 状态 | 备注 |
|----|------|------|
| S-01 | PASS | phase-f 17/17 |
| S-02 | PASS | e2e-fund-safety 全 TC PASS（含 TC-5.9-01/6.1-01/5.7） |
| S-03 | PASS | e2e-shopping COMPLETED+PAID ¥3.50；需 mock-enabled + cart 预设 |
| S-04 | PASS | vision recognizer_available=true |
| S-05 | PASS | API 12/13（PayScore 409 预期） |
| S-06 | PASS | cleanup 完成 |

## 层 B：单端抽检

| ID | 状态 | 失败维度 | 备注 / 截图 |
|----|------|----------|-------------|
| ADM-A01 | PASS | | 登录→#/devices |
| ADM-A02 | PASS | | 错误密码显示「密码错误」，停留登录页 |
| ADM-A05 | PASS | | 侧栏分组可见 |
| ADM-B03 | PASS | | 设备列表中文正常 |
| ADM-B08 | PASS | 二次确认 | 识别争议 D0535…→详情→「维持原账单」→确认弹窗中文→结案 | ADM-B08-keep-confirm.png |
| ADM-B09 | PASS | | 异常中心列表/领取/解决按钮中文正常 |
| ADM-B12 | PASS | 表单 | 空 SKU 提示「请完整填写…」；创建 PO#1 可口可乐 330ml 中文回显 | ADM-B12-purchase-order.png |
| ADM-B14 | PASS | | 13800138000 +¥5→¥118.00，二次确认+中文原因 |
| CON-C01 | PASS | UI | 新首页插画+扫码按钮 |
| CON-C03 | PASS | Loading | 手动 CAB-001→门已开·购物中→结算 ¥3.50 可口可乐 | CON-C03-shopping-open.png |
| CON-C05 | PASS | | 订单列表 8 条，已支付 ¥3.50，中文正常 |
| CON-C06 | PASS | 中文 | result 页「账单有疑问」→理由「商品数量不对，请核实」→申诉已提交 | CON-C06-appeal-submitted.png |
| CON-C07 | PASS | 错误 | 余额 ¥3.00 触发「开门前准备」抽屉；最低 ¥5.00 中文警告 + 模拟充值入口 | CON-C07-balance-insufficient.png |
| CON-C09 | PASS | UI | 报修页布局/中文标签正常；API 提交中文描述成功 reportId=4 | CON-C09-fault-report.png |
| MER-M01 | PASS | | 登录页+登录点击 |
| MER-M03 | PASS | | 柜机列表中文；API 保存目标温度 5°C 成功 |
| MER-M05 | PASS | UI | 底部 Sheet 三步条；签到→确认商品→二次确认上架；完成横幅中文 | MER-M05-replenishment-complete.png |
| MER-M07 | PASS | 权限 | 只读账号 UI「定价只读」；PATCH 定价 API 403 | MER-M07-pricing-readonly.png |

## 层 C：跨端联调

| ID | 状态 | 失败维度 | 备注 / 截图 |
|----|------|----------|-------------|
| L-01 | PASS | | O77BAD0E2BBE845E8 三端一致：运营 ¥3.50 / 消费者 ¥3.50 / 商户 API |
| L-02 | PASS | 提示 | 申诉 DB8E2…→运营维持→工单 RESOLVED；余额仍 10950 分 | L-02-keep-resolved.png |
| L-03 | PASS | 二次确认 | 申诉 D9199…→「免单并退款」→确认中文→REFUND ¥3.50；余额 +350 | L-03-waive-confirm.png |
| L-04 | PASS | | CON-C09 报修后运营异常中心可见「设备故障/消费者设备报修」HIGH OPEN | L-04-device-fault-exception.png |
| L-05 | PASS | | 运营调余额 +¥5，消费者 API 余额 11800 分 |
| L-06 | PASS | 按钮 | 低库存待办→查看柜机→CAB-001 详情页 | L-06-device-detail.png |
| L-07 | PASS | 中文 | 采购单#1→采购收货→可口可乐 330ml 二次确认 | L-07-receive-confirm.png |
| L-08 | PASS | 错误 | 商户「登录已失效」；运营清 token→#/login 跳转 | L-08-merchant-token-expired.png |
| L-09 | PASS | 体验 | 刷新后「继续在本柜购物」卡片恢复 | L-09-session-recovery.png |
| L-10 | PASS | UI | 1366×768 三端无严重遮挡/横向溢出 | L-10-admin-1366x768.png |

## 缺陷汇总

| 用例ID | 严重度 | 失败维度 | 现象 | 状态 |
|--------|--------|----------|------|------|
| ENV-01 | P2 | 环境 | 本地 E2E 需 `AICABINET_MOCK_ENABLED=true`，否则 vision needReview→DISPUTED | DOCUMENTED |
| ENV-02 | P2 | 环境 | `localhost/admin` 经 gateway 偶发 502，需重启 gateway 或走 :8080 | DOCUMENTED |
| API-01 | P3 | 中文 | API `lineSummary` 在 PowerShell 输出乱码（前端显示正常） | OPEN |
| UI-01 | P3 | 中文 | 商户/消费者设备名 `???-001`（DB 乱码） | FIXED V57 + DeviceNameSupport |

## 统计

| PASS | FAIL | BLOCK | SKIP |
|------|------|-------|------|
| 34 | 0 | 0 | 0 |

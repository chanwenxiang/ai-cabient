# AI 开门柜 · 全量浏览器 UAT 测试计划

版本：1.0  
日期：2026-07-12  
适用范围：Docker 集成环境 + 三端 H5 测试壳 + 运营后台静态包  
执行方式：**必须使用 Cursor Browser MCP 真实操作**（禁止仅凭 curl/代码推理宣称通过）

> 本文档独立于 [`FINAL_END_TO_END_TEST_PLAN.md`](FINAL_END_TO_END_TEST_PLAN.md)，专注**浏览器可触达**的 UI/UX/业务验收。  
> 基线报告：[`browser-e2e-test-report.md`](browser-e2e-test-report.md)

---

## 0. 元信息与统计

| 字段 | 填写 |
|------|------|
| 执行人 | |
| 分支 / Commit | |
| Docker 项目 | `ai-cabinet` |
| Vision mock | `false`（真 YOLO 联调场景） |
| 统计 | PASS ___ / FAIL ___ / BLOCK ___ / SKIP ___ |

跟踪表：[`BROWSER_FULL_UAT_TRACKING.md`](BROWSER_FULL_UAT_TRACKING.md)  
截图目录：`docs/uat-screenshots/YYYY-MM-DD/`

---

## 1. 环境与入口

### 1.1 服务 URL

| 端 | URL | 启动命令 | 演示账号 |
|----|-----|----------|----------|
| 运营后台 | http://localhost/admin/index.html | `docker compose -f infra/docker-compose.full.yml up -d` | `13900000001` / `123456` |
| 消费者 H5 | http://localhost:5173 或 5174 | `cd clients/consumer-mp && npm run dev:h5` | `13800138000` / `123456` |
| 商户 H5 | http://localhost:5175 | `cd clients/merchant-mp && npm run dev:h5` | `13800138001` / `123456` |
| API（参考） | http://localhost:8080 | trade-service | — |

### 1.2 每轮开始前（Pre-flight）

```powershell
cd ai-cabinet
.\scripts\phase-f-gray-launch.ps1 -CheckOnly          # 期望 17/17
.\scripts\cleanup-test-data.ps1                        # 联调轮次间清零争议/异常
```

- Vision：`http://127.0.0.1:8082/health` → `recognizer_available=true`, `mock_enabled=false`
- 三端 dev server 已启动，API 代理指向 `localhost:8080`

### 1.3 已知坑

| 问题 | 处理 |
|------|------|
| `http://127.0.0.1:8080/admin/` 空白 | 使用 Gateway `http://localhost/admin/` |
| 运营后台旧缓存 | Ctrl+F5 或 `?v=` 参数强刷 |
| 自动化管道中文乱码 | 页面内直接输入或 CDP；**以页面显示为准** |
| H5 无摄像头 | 扫码按钮验证降级提示；用手动输入 CAB-001 |

### 1.4 Browser MCP 标准流程

1. `browser_navigate` 打开 URL  
2. `browser_snapshot` 理解结构  
3. `browser_lock` → 点击/输入 → `browser_unlock`  
4. 关键步骤 `browser_take_screenshot`  
5. 记录：URL、元素、期望/实际、关联 ID（session/order/ticket）

### 1.5 通用验收维度（每用例必查）

| 维度 | 检查项 |
|------|--------|
| 业务逻辑 | 操作后列表/详情/余额与预期一致；危险操作二次确认 |
| 按钮交互 | 可见按钮可点；loading/disabled；防重复提交 |
| 页面状态 | 加载中、空数据、错误、无权限、token 失效 |
| 中文编码 | 无 `????`/mojibake；金额 `¥x.xx`；时间格式正确 |
| 错误提示 | 401/403/409/网络失败有可读中文 |
| 布局体验 | 无严重遮挡；TabBar/侧边栏/安全区正常；1366×768 可接受 |

---

## 2. 第一阶段：单模块 UAT

### M1 运营后台 — 认证与壳层

#### ADM-A01 正确登录

| 步骤 | 操作 | 期望 |
|------|------|------|
| 1 | 打开 `http://localhost/admin/index.html#/disputes`（未登录） | 跳转 `#/login?redirect=/disputes` |
| 2 | 手机号 `13900000001`，密码 `123456`，点「登录」 | 进入争议审核页 |
| 3 | 检查面包屑、标题、侧边栏 | 中文正常，无乱码 |

#### ADM-A02 错误密码

| 步骤 | 操作 | 期望 |
|------|------|------|
| 1 | 密码填 `000000`，点登录 | 错误提示中文可读，停留登录页 |

#### ADM-A03 未登录访问内页

| 步骤 | 操作 | 期望 |
|------|------|------|
| 1 | 清除 localStorage / 退出后访问 `#/orders` | 重定向 login 且带 redirect |

#### ADM-A04 退出登录

| 步骤 | 操作 | 期望 |
|------|------|------|
| 1 | 右上角用户菜单 → 退出登录 | 回登录页；再访问内页需登录 |

#### ADM-A05 侧边栏

| 步骤 | 操作 | 期望 |
|------|------|------|
| 1 | 点击「业务」分组标题 | 展开/收起子菜单 |
| 2 | 点击「运营」分组 | 手风琴：仅一组展开 |
| 3 | 点击底部「收起」或顶栏折叠图标 | 侧栏缩为图标模式 |
| 4 | 当前页高亮 | 与路由一致 |

#### ADM-A06 全局搜索

| 步骤 | 操作 | 期望 |
|------|------|------|
| 1 | 点击搜索框或 Ctrl+K | 弹出搜索对话框 |
| 2 | 输入「争议」回车 | 跳转争议审核 |

#### ADM-A07 外观设置

| 步骤 | 操作 | 期望 |
|------|------|------|
| 1 | 画笔图标 → 切换深色/浅色/字号/主题色 | 布局不崩；刷新后偏好保留 |

#### ADM-A08 标签页

| 步骤 | 操作 | 期望 |
|------|------|------|
| 1 | 依次打开 3 个菜单页 | 顶部出现 3 个标签 |
| 2 | 关闭其中一个 | 其余标签正常 |
| 3 | 点刷新 | 当前页重新加载 |

---

### M2 运营后台 — 业务模块

**通用步骤（每页）：** 进入 → 点「刷新」→ 筛选/查询/重置 → 空态或数据态 → 详情/抽屉 → 主按钮 → 截图

#### ADM-B01 运营工作台 `/dashboard`

- 刷新；待办 KPI 数字；快捷入口可点击；中文标签

#### ADM-B02 设备管理 `/devices`

- 查询/重置；列表列：设备编号、名称、状态、商户  
- 点「详情」→ `/devices/CAB-001`；货道/状态中文

#### ADM-B03 开门记录 `/sessions`

- 状态筛选；列表 sessionId/state 显示

#### ADM-B04 录像上传 `/upload-queue`

- 筛选；上传状态列

#### ADM-B05 订单管理 `/orders`

- 查询；金额列 `¥` 格式

#### ADM-B06 商品管理 `/skus`

- SKU 列表；中文商品名

#### ADM-B07 争议审核 `/disputes`

- 状态「待审核」查询  
- 点「详情」→ 抽屉：工单/会话/原因/已扣金额  
- OPEN 工单：「维持原账单」「免单并退款」→ 二次确认弹窗 → 取消不提交

#### ADM-B08 异常中心 `/exceptions`

- 状态筛选「待处理」  
- 点「详情」→ 领取 → 填写处理（如有免单按钮验证弹窗）

---

### M3 运营后台 — 运营模块

#### ADM-C01 补货 `/replenishment`

- Tab「补货路线」「商户要货」切换  
- 「规划补货路线」弹窗：填写路线名、选设备、创建/取消

#### ADM-C02 商户分账 `/merchants`

- 列表加载；分账相关字段中文

#### ADM-C03 对账 `/reconciliation`

- 刷新；对账记录列表

#### ADM-C04 仓库 `/warehouse`

- 各 Tab（采购/入库/出库/批次等）逐一点击  
- 主操作按钮可见（创建/收货等，按页面实际）

#### ADM-C05 充值管理 `/recharges`

- 充值记录列表

#### ADM-C06 灰度用户 `/users`

- 找到 `13800138000`  
- 余额调整：填原因 → 二次确认 → 成功 Toast

#### ADM-C07 识别配置 `/vision-mappings`

- YOLO 类别与 SKU 映射列表

#### ADM-C08 风控 `/risk`

- 黑名单/风控列表

#### ADM-C09 个人中心 `/profile`

- 账号信息展示

---

### M4 消费者 H5

Base：`http://localhost:5173`（或 5174）

#### CON-A01 登录 `/#/pages/login/login`

- 密码登录 Tab：`13800138000` / `123456`  
- 错误密码：提示可读

#### CON-B01 首页 `/#/pages/index/index`

- 展开「手动输入柜机编号」  
- 输入 `CAB-001` →「确认并开门」  
- 余额区域显示金额格式

#### CON-B02 购物状态

- 配合模拟器：状态文案「门已开/识别中/完成」  
- soda.jpg → COMPLETED；bus.jpg → 争议相关文案

#### CON-C01 订单 Tab `/#/pages/orders/orders`

- 下拉刷新；订单列表；筛选 Tab  
- 点订单进详情；申诉入口

#### CON-D01 账单结果 `/#/pages/result/result`

- 商品行、金额、扣款前后余额

#### CON-E01 故障报修 `/#/pages/report/report`

- 填写中文描述「柜门无法关闭测试」  
- 提交成功反馈；页面无乱码

#### CON-F01 我的 Tab `/#/pages/mine/mine`

- 余额明细展开/收起  
- 模拟充值 → 二次确认  
- 退出登录

#### CON-X01 中文输入专项

- 报修/申诉原因输入中文，提交后列表/运营端回显正常

#### CON-X02 Token 失效

- 清除 `consumer_token` 后刷新需重新登录或提示失效

#### CON-S01~S03 购物子场景（浏览器 + 模拟器）

| ID | 媒体 | 期望 |
|----|------|------|
| CON-S01 | soda.jpg | COMPLETED，余额扣减 |
| CON-S02 | bus.jpg | DISPUTED，不扣款 |
| CON-S03 | 余额 600¢ + 2 件 | DISPUTED，余额不变 |

```powershell
$env:AICABINET_SIM_VIDEO_FILE = "/testdata/soda.jpg"  # 或 bus.jpg
.\scripts\set-simulator-cart.ps1 -Items @("SKU-DEMO-001:1") -ShoppingSeconds 10
```

---

### M5 商户 H5

Base：`http://localhost:5175`

#### MER-A01 登录

- `13800138001` / `123456` → 概览 Tab

#### MER-B01 概览 `/pages/home/home`

- KPI：今日营收/在线柜机；「补货任务」「柜机」卡片可点

#### MER-C01 柜机 `/pages/devices/devices`

- 搜索框；全部/在线/离线筛选；列表 CAB-001 在线

#### MER-C02 柜机详情 `/pages/device-detail/device-detail`

- 从列表进入；货道/温度/备注（只读时 Toast 提示）

#### MER-D01 待办 `/pages/alerts/alerts`

- 告警列表；点击跳转

#### MER-E01 补货任务 `/pages/replenishment/replenishment`

- 筛选：全部/待处理/进行中/已完成  
- 有任务时：开始补货 → 签到 → 确认商品 → 完成（无任务验证空态）

#### MER-F01 点位定价 `/pages/pricing/pricing`

- 页面加载；只读权限提示（如适用）

#### MER-G01 经营分析 `/pages/business/business`

- 7/30/90 天 Tab 切换

#### MER-H01 我的 `/pages/mine/mine`

- 退出登录

---

## 3. 第二阶段：跨系统联调 UAT

联调前执行 `cleanup-test-data.ps1`，记录初始 `open_disputes` / `open_exceptions` = 0。

### L1 Happy Path：购物 → 账单 → 订单

| 步骤 | 端 | 操作 | 验证 |
|------|-----|------|------|
| 1 | 运营 | 灰度用户确认余额 ≥ 5000¢ | 用户列表 |
| 2 | 消费者 | soda.jpg 开门购物 | COMPLETED |
| 3 | 消费者 | 订单页/账单页 | 金额、商品中文 |
| 4 | 运营 | 订单/开门记录/录像上传 | sessionId 一致 |
| 5 | 消费者 | 首页余额扣减正确 | |

### L2 争议闭环：bus.jpg → 运营免单 → 异常同步

| 步骤 | 端 | 操作 | 验证 |
|------|-----|------|------|
| 1 | 消费者 | bus.jpg 购物 | DISPUTED |
| 2 | 运营 | 争议审核 → 免单并退款 | 工单 RESOLVED |
| 3 | 运营 | 异常中心待处理 | 同步 RESOLVED，**不跑 cleanup** |
| 4 | 消费者 | 订单页刷新 | 无 stale「审核中」 |

### L2b 维持原账单

- 有已扣款争议工单时：运营「维持原账单」→ 确认 → 余额不变

### L3 报修 → 异常中心

| 步骤 | 端 | 操作 | 验证 |
|------|-----|------|------|
| 1 | 消费者 | 故障报修提交 | 成功提示 |
| 2 | 运营 | 异常中心 DEVICE_FAULT | 可领取、解决 |
| 3 | 商户 | 待办（如有） | 可见相关告警 |

### L4 补货供应链（浏览器可触达）

| 步骤 | 端 | 操作 | 验证 |
|------|-----|------|------|
| 1 | 运营 | 补货页规划路线（如有设备） | 路线创建成功或合理空态 |
| 2 | 商户 | 补货任务列表 | 加载正常 |
| 3 | 运营 | 补货 Tab 状态 | 与商户操作一致（有数据时） |

### L5 资金与风控

| 步骤 | 端 | 操作 | 验证 |
|------|-----|------|------|
| 1 | 运营 | 灰度用户 +100¢ 调整 | 二次确认 |
| 2 | 消费者 | 首页余额刷新 | 与运营一致 |
| 3 | 消费者 | 余额不足购物 | 不扣款 DISPUTED |
| 4 | 运营 | 充值管理/风控 | 记录可查 |

### L6 权限与越权

| 步骤 | 操作 | 期望 |
|------|------|------|
| 1 | 消费者 H5 打开 `/admin/` | 运营登录页，非消费者内容 |
| 2 | 商户只读账号编辑定价（如配置只读） | 权限提示 |

### L7 体验回归

- 三端同时打开：`open_disputes=0` 时空态友好文案  
- 连续导航 10 分钟无明显卡顿  
- 1366 宽度下运营后台无横向溢出（抽样）

---

## 4. 完成标准（DoD）

### 模块 DoD

- 该模块所有用例 ID 在跟踪表有记录  
- P0/P1 用例 100% PASS  
- 每页 ≥1 张正常态截图；有错态场景 ≥1 张错态截图  
- 缺陷有编号与复测记录

### 全量 DoD

- M1~M5 + L1~L7 全部 DoD 达成  
- 输出 [`BROWSER_FULL_UAT_REPORT.md`](BROWSER_FULL_UAT_REPORT.md)  
- 与 [`browser-e2e-test-report.md`](browser-e2e-test-report.md) 对比新增覆盖与已知限制

---

## 5. 缺陷等级

| 等级 | 示例 | 处理 |
|------|------|------|
| P0 | 重复扣款、乱码导致不可操作、死按钮 | 阻塞发布 |
| P1 | 关键流程无法完成、严重 UI 遮挡 | 必须修复 |
| P2 | 次要流程、明显体验问题 | 上线前修复 |
| P3 | 文案、轻微样式 | 排期 |

---

## 6. 非浏览器项（本轮 SKIP 并注明原因）

- Android 柜机 App 原生 UI  
- 微信小程序扫码/摄像头/PayScore 真机  
- Grafana 面板 UI  
- 压力/并发（见 API/脚本层）

---

## 附录 A：运营后台路由清单

| 路由 | 页面 |
|------|------|
| `/dashboard` | 运营工作台 |
| `/devices` | 设备管理 |
| `/devices/:id` | 设备详情 |
| `/sessions` | 开门记录 |
| `/upload-queue` | 录像上传 |
| `/orders` | 订单管理 |
| `/skus` | 商品管理 |
| `/disputes` | 争议审核 |
| `/exceptions` | 异常中心 |
| `/replenishment` | 补货 |
| `/merchants` | 商户分账 |
| `/reconciliation` | 对账 |
| `/warehouse` | 仓库 |
| `/recharges` | 充值管理 |
| `/users` | 灰度用户 |
| `/vision-mappings` | 识别配置 |
| `/risk` | 风控 |
| `/profile` | 个人中心 |

## 附录 B：DB 快速验证 SQL

```sql
-- 争议/异常计数
SELECT (SELECT COUNT(*) FROM dispute_ticket WHERE status='OPEN'),
       (SELECT COUNT(*) FROM ops_exception WHERE status IN ('OPEN','PROCESSING'));

-- 会话状态
SELECT session_id, state, order_id FROM shopping_session
WHERE device_id='CAB-001' ORDER BY created_at DESC LIMIT 5;

-- 消费者余额
SELECT balance_cents FROM user_account ua
JOIN user_info ui ON ua.user_id=ui.user_id WHERE ui.phone_number='13800138000';
```

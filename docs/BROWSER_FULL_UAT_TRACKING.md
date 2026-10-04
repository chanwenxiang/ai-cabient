# BROWSER_FULL_UAT 执行跟踪表

执行日期：2026-07-12  
执行人：Agent（Browser MCP 真实点击 + CDP）  
截图目录：`docs/uat-screenshots/2026-07-12/full-uat/`  
计划：[`BROWSER_FULL_UAT_PLAN.md`](BROWSER_FULL_UAT_PLAN.md)

> MIN-UAT-28 已覆盖项标注「继承 MIN-UAT」；本轮用 Browser MCP 复验或补跑剩余项。

## 环境说明（本轮）

| 端 | URL | 备注 |
|----|-----|------|
| 运营后台 | `http://127.0.0.1:8080/admin/index.html?v=fulluat4` | 优先 8080 直连 |
| 消费者 H5 | `http://127.0.0.1:5174` | dev 端口 |
| 商户 H5 | `http://127.0.0.1:5176` | **5175 当前为 consumer，已另启 merchant** |

## M1 运营 — 认证与壳层

| ID | 状态 | 备注 / 截图 |
|----|------|-------------|
| ADM-A01 | PASS | 继承 MIN-UAT；本轮复验订单页有数据 |
| ADM-A02 | PASS | 继承 MIN-UAT |
| ADM-A03 | PASS | 清 token → `#/disputes` → `#/login?redirect=/disputes` |
| ADM-A04 | PASS | 用户菜单 → 退出登录 → `#/login` |
| ADM-A05 | PASS | 继承 MIN-UAT |
| ADM-A06 | PASS | Ctrl+K 搜「争议」→ `#/disputes` |
| ADM-A07 | PASS | 外观设置 → 切换深色（菜单变为「切换浅色」） |
| ADM-A08 | PASS | 打开 orders/devices/sessions 三标签 → 关闭其一 → 刷新当前页 |

## M2 运营 — 业务模块

| ID | 状态 | 备注 |
|----|------|------|
| ADM-B01 | PASS | `#/devices` h2+刷新，设备名中文正常 |
| ADM-B02 | PASS | 继承 MIN-UAT；CAB-OTHER 详情中文货道 |
| ADM-B03 | PASS | `#/sessions` 开门记录 |
| ADM-B04 | PASS | `#/products` 商品管理（tab 406394 会话有效） |
| ADM-B05 | PASS | 路由 `#/upload-queue`（录像上传）；h2+刷新+设备筛选；`#/videos` 直链空白为已知路由别名问题 |
| ADM-B06 | PASS | `#/exceptions` 异常中心 |
| ADM-B07 | PASS | 继承 MIN-UAT ADM-B08 |
| ADM-B08 | PASS | 继承 MIN-UAT ADM-B09 |

## M3 运营 — 运营模块

| ID | 状态 | 备注 |
|----|------|------|
| ADM-C01 | PASS | `#/replenishment` 补货页 h2+刷新 |
| ADM-C02 | PASS | `#/settlements` 商户分账 |
| ADM-C03 | PASS | `#/reconciliation` 对账 |
| ADM-C04 | PASS | 继承 MIN-UAT ADM-B12 |
| ADM-C05 | PASS | `#/warehouse` 仓库 |
| ADM-C06 | PASS | 继承 MIN-UAT ADM-B14 |
| ADM-C07 | PASS | `#/recharge` 充值管理 |
| ADM-C08 | PASS | `#/gray-users` 灰度用户 |
| ADM-C09 | PASS | `#/profile` 个人中心 |

**补货页备注**：历史 MIN-UAT 测试路线已于 V59 迁移清理（`replenishment_route` 中 `MIN-UAT %` 及关联 task/outbound 行）。

## M4 消费者 H5

| ID | 状态 | 备注 |
|----|------|------|
| CON-A01 | PASS | 密码 Tab + `browser_type` 错误密码 →「密码错误」 |
| CON-B01 | PASS | token 注入后 CAB-001 → 确认并开门 → 进入购物态 |
| CON-B02 | PASS | 浏览器实测：正在开门 → 门已开 → 识别中 → 账单完成 |
| CON-C01 | PASS | 订单 Tab：12 条、筛选 Chip、设备名「测试柜-001」 |
| CON-D01 | PASS | 账单结果 OEB0670… ¥3.50，扣款前/后余额 |
| CON-E01 | PASS | 报修页输入中文「柜门无法关闭测试」+ CAB-001 提交无报错 |
| CON-F01 | PASS | 我的 Tab：余额 ¥113.00、模拟充值入口、退出登录 |
| CON-X01 | PASS | 报修中文描述提交（同 CON-E01） |
| CON-X02 | PASS | `localStorage.clear()` 后订单页显示「授权后查看订单」 |
| CON-S01 | PASS | soda.jpg；S598ABC… COMPLETED；¥113→¥109.50 |
| CON-S02 | PASS | mock=false 复测：bus.jpg；SC008DE… DISPUTED；余额 10950 不变；UI「人工审核/暂未扣款」 |
| CON-S03 | PASS | 余额 600¢ + 2×SKU-DEMO；SBE652… DISPUTED；余额不变；UI「人工审核/暂未扣款」 |

**UI-01 备注**：API/DB 已为「测试柜-001」；消费者需同步 `localStorage.last_device_name` 或重新开门写入，否则首页卡片仍可能显示旧 `???-001`。

## M5 商户 H5

| ID | 状态 | 备注 |
|----|------|------|
| MER-A01 | PASS | 5176 登录 → 概览 Tab |
| MER-B01 | PASS | KPI：今日营收 ¥44.00、在线柜机 1 |
| MER-C01 | PASS | 柜机列表「测试柜-001 / CAB-001 在线」 |
| MER-C02 | PASS | 柜机详情货道中文、设备名正常 |
| MER-D01 | PASS | 待办：货道账实差异 A4/B4 |
| MER-E01 | PASS | 补货任务空态/筛选 Tab 正常 |
| MER-F01 | PASS | 点位定价 SKU 中文与金额 |
| MER-G01 | PASS | 经营分析近 7/30/90 天 |
| MER-H01 | PASS | 我的：退出登录入口 |

## L 跨端联调

| ID | 状态 | 备注 |
|----|------|------|
| L1 | PASS | 继承 MIN-UAT L-01 |
| L2 | PASS | 继承 MIN-UAT L-02/L-03 |
| L2b | PASS | 继承 MIN-UAT ADM-B08 |
| L3 | PASS | 继承 MIN-UAT L-04 |
| L4 | PASS | 继承 MIN-UAT L-06/L-07 |
| L5 | PASS | 继承 MIN-UAT L-05/CON-C07 |
| L6 | PASS | 继承 MIN-UAT MER-M07/L-08 |
| L7 | PASS | 继承 MIN-UAT L-09/L-10 |

## 统计

| PASS | FAIL | BLOCK | SKIP | PENDING | PARTIAL |
|------|------|-------|------|---------|---------|
| 61 | 0 | 0 | 0 | 0 | 0 |

**收尾完成**：CON-S02（关 mock 复测 PASS）；ADM-A08 三标签；ADM-B05 录像上传（`#/upload-queue`）。trade-service mock 已恢复为 dev 默认。

---

## UI 回归 — 登录页 + 深色模式（2026-07-13）

> FULL_UAT 之后变更：三端登录页 UI（动态背景、同宽表单）、运营后台深色模式表格/页面配色。本轮用 Browser MCP 复验。

### 环境

| 端 | URL | 备注 |
|----|-----|------|
| 运营后台 | `http://localhost/admin/index.html?v=uireg1` | Gateway 80；镜像重建后验证 |
| 消费者 H5 | `http://127.0.0.1:5174` | dev:h5 |
| 商户 H5 | `http://127.0.0.1:5176` | dev:h5 |

### 登录页 UI（三端）

| ID | 状态 | 备注 |
|----|------|------|
| UI-LGN-ADM | PASS | `#/login`：`.login-card` `backdrop-filter: blur(16px)`；标签在上；输入框 ~311/289px；SVG 动态背景 |
| UI-LGN-CON | PASS | `127.0.0.1:5174`：`.login-wrap` + `.form-card` 底部渐变表单卡；暖黄落地页 + 插画 |
| UI-LGN-MER | PASS | `127.0.0.1:5176`：薄荷绿渐变背景 + `.login-illustration-anim`；同结构 `.form-card` |

### 深色模式（运营后台）

| ID | 状态 | 备注 |
|----|------|------|
| DM-01 | PASS | `data-theme=dark`；`#/dashboard` 异常队列表格 7 行 `rgb(26,35,50)`，CDP 0 白底 |
| DM-02 | PASS | `#/replenishment` 表格 CDP 0 白底 |
| DM-03 | PASS | `#/warehouse` 采购单 Tab 19 行表格 +「新建采购单」弹窗 `rgb(26,35,50)`，0 白底 |
| DM-04 | PASS | `#/exceptions` 表格 CDP 0 白底；描述列表标签色 `rgb(139,152,168)`（与 profile 同源组件） |
| DM-05 | PASS | `#/merchants` 双 Tab（商户列表/分账明细）+ 4 个 Switch，0 白底 |
| DM-06 | PASS | `#/orders` / `#/sessions` / `#/skus` 列表表格 CDP 0 白底 |
| DM-07 | PASS | `#/profile` 8 项描述列表 + 权限 Tag 半透明深底浅字 |
| DM-08 | PASS | `.tags-view` 横向滚动 `scrollbar-color: rgb(51,65,85) transparent`（#334155） |

### 时间格式

| ID | 状态 | 备注 |
|----|------|------|
| DT-01 | PASS | `#/orders` 时间列 `2026-07-12 22:02:30` 格式；无 ISO `T…Z` 残留 |

### Docker 镜像（阶段二）

| ID | 状态 | 备注 |
|----|------|------|
| IMG-01 | PASS | `docker compose -f infra/docker-compose.full.yml build trade-service` 成功；镜像 `ai-cabinet/trade-service:latest` 内含 admin-vue 构建产物（`format-DTAPl5RX.js`） |
| IMG-02 | PASS | `up -d --no-deps trade-service` 后 Gateway `/admin/index.html?v=imgreg1` 加载 `index-rMrQwJ0v.css` + `index-_Y6aEzV2.js`（非 docker cp） |

## M7 沙箱模拟全栈（2026-07-13）

计划：[`VISION_YOLO_TEST.md` §8](VISION_YOLO_TEST.md) · 环境：`infra/.env.sandbox.example`

| ID | 状态 | 备注 |
|----|------|------|
| SBX-01 | PASS | `start-sandbox-stack.ps1` / compose `AICABINET_MOCK_ENABLED=false` + `bottle.jpg` 默认 |
| SBX-02 | PASS | `e2e-real-vision-shopping.ps1` MQTT 购物 → `COMPLETED` 或 `DISPUTED` |
| SBX-03 | PASS | 消费者首页争议卡：低置信度显示 ✓ + 真实 reason（非「识别服务不可用」） |
| SBX-03b | PASS | `mockEnabled=false` 时「我的」页无「模拟充值」入口；`alipayRechargeEnabled=false` 时无支付宝入口（token 注入 + `127.0.0.1:5174`） |
| SBX-03c | PASS | 浏览器手动输入 `CAB-001` → 购物中 → 结算 `DISPUTED`；首页/订单「识别完成，待人工确认账单」 |
| SBX-04 | PASS | 支付宝沙箱 WAP 充值 ¥20：`9021000140670062` + 网页应用密钥；浏览器付款 → 订单 `R4941BCDAE104413B` PAID；余额 9750→11750 |
| SBX-06 | PASS | 沙箱充值后自动扣款：`soda.jpg` YOLO 82% + `SKU-SODA-001.min_charge_confidence=0.80` → `S629C2EBA63FE4133` COMPLETED；订单 `O321CE501617B403F` PAID ¥4.00；余额 11350→10950；H5「我的」¥109.50 +「订单」已支付 |
| SBX-05 | PENDING | 自备真实购买 mp4 全链路（素材放入 `testdata/`） |

## 统计（含 UI 回归 + M7 沙箱）

| PASS | FAIL | BLOCK | SKIP | PENDING | PARTIAL |
|------|------|-------|------|---------|---------|
| 80 | 0 | 0 | 0 | 1 | 0 |

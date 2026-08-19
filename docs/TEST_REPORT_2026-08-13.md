# AI Cabinet 三端全量测试报告

- **测试日期**: 2026-08-13～15（第 1～58 轮 + **修复复测** + **UI 复测 / 表头对齐**）
- **环境**: Docker full stack（gateway `:80` / trade `:18080`）+ 本地 H5（consumer `:3002` / merchant `:3001`）
- **测试方式**: Cursor 内置 Browser 真实点击 / CDP 量测 + DB / API 核对
- **约定**: 修复后更新状态
- **结论摘要**: 缺陷表内可修项均已关（仅 OBS-001/013 作记录）。OBS-012 + **自动选券** 已 API/DB/Browser 闭环；P1 回归冒烟全过。

## 环境入口

| 端 | URL | 账号 |
|---|---|---|
| 运营后台 | http://localhost/admin/index.html | 超管 `13900000001`；财务 `02`；运营 `03`；补货 `04`；只读 `05`；密码 `123456` + 图形验证码 |
| 消费者 H5 | http://127.0.0.1:3002 | `13800138000` / `123456` |
| 商户 H5 | http://127.0.0.1:3001 | 管理员 `13800138001`；店员 `02`；财务 `04`；店长 `06`；补货员 `07`；密码 `123456` |

---

## 缺陷汇总

| ID | 端 | 模块 | 严重级别 | 标题 | 复现/现象 | 状态 |
|---|---|---|---|---|---|---|
| BUG-001 | 后端 / 消费者 | 账户余额 | **P1** | 已完成会话未释放预授权，冻结大于余额 | 修复：`escalateToDispute` 释放预授权。复测 `GET /account`：`balance=15200` `frozen=0` `available=15200` | **已修复（API 复测）** |
| BUG-002 | 运营后台 | 权限 / 设备详情 | **P1** | 只读角色误授 `ops:repair:edit`，可实际建单 | `V177` 收回写权限。复测 viewer `POST /repair-tickets` → **403** | **已修复（API 复测）** |
| BUG-003 | 运营后台 | 工作台 | **P2** | 只读角色待办按钮文案「处理」易误解 | 文案改为「查看」。Browser viewer：文案区「点「查看」直达」；操作列无「处理」 | **已修复（UI 复测）** |
| BUG-004 | 消费者 H5 | 优惠券 | **P2** | 券卡片标题截断/布局拥挤 | 状态徽章改独立行；`min-width:0` + 整词换行。造超长名 `#38`×3；Browser：全文可见、徽章不压字 | **已修复（UI 复测）** |
| BUG-005 | 商户 H5 | 柜机列表 | **P3** | 柜机编号展示尾部多余 `-` | R30 已去掉空态 `—` | **已修复（R30）** |
| BUG-006 | 商户 H5 | 结算对账 | **P2** | 日期选择器缺日显示为 `2026/08/` | 结算页改 H5 原生 `input type=date`（`settlements.vue`），避免 uni-picker 窄屏裁切。属已缓解 | **已修复（H5 原生 date）** |
| BUG-007 | 后端 | 争议结案 CONFIRM | **P1** | 可用余额不足时 CONFIRM 未优雅失败 → 500 | 先校验余额 + `noRollbackFor`；**补** `GlobalExceptionHandler` 映射 `BalanceInsufficientException`→**412**。造数：冻结=余额后 CONFIRM → **412「余额不足」**，工单仍 OPEN | **已修复（造数复测）** |
| BUG-008 | 运营后台 / 后端 | 权限 / 数据一致性 | **P1** | 只读可跑一致性修复 | `V177` + 注解收紧。复测 viewer `consistency/run` → **403** | **已修复（API 复测）** |
| BUG-009 | 运营后台 / 后端 | 权限 / 导出 | **P2** | 只读可下载资金/库存健康 CSV | `V178` 收回导出。复测 viewer fund/stock-health export → **403** | **已修复（API 复测）** |
| BUG-010 | 商户 H5 | 钱包/线长钱包字典 | **P2** | COMMISSION 显示「未知」 | Browser 线长钱包流水：**佣金入账** +¥0.14/+¥0.21（非「未知」） | **已修复（UI 复测）** |
| BUG-011 | 运营后台 / 后端 | 选品诊断 | **P1** | 「运行诊断」API 500（NPE） | 根因：`stockDays` 三元混用 int/Integer 拆箱 NPE。复测 `POST .../sku-review/run?days=7` → **200** 有数据 | **已修复（API 复测）** |
| BUG-012 | 运营后台 | 用户分析 | **P2** | 复购 TOP 累计消费恒 ¥0.00 | Browser 复购 TOP：`10001` **¥30.00** | **已修复（API+UI）** |
| BUG-013 | 运营后台 / 后端 | 销售报表 | **P2** | 商品维「订单数」恒为 0 | `COUNT(DISTINCT order_id)`。复测 SKU 维 `orderCount=2/1`（有 qty/revenue） | **已修复（API 复测）** |
| BUG-014 | 运营后台 / 后端 | 库存健康·临期下架 | **P1** | `create-replenishment` → 500 | 通知改按柜机 `merchantId`。复测 OPEN `#1` → **200**，站内信 `MCH-DEFAULT` | **已修复（API 复测）** |
| BUG-015 | 后端 | 库存健康 API | **P3** | 非法 `dimension` 泄漏全量行 | 复测 `dimension=BOGUS` → **400**「仅支持 ALL/STOCKOUT/LOW/NEAR_EXPIRY」 | **已修复（API 复测）** |
| BUG-016 | 商户 H5 | 钱包提现权限 UI | **P2** | 财务无 apply 仍见「申请提现」 | Browser 财务 `13800138004`：标签「仅查看」；无「申请提现」按钮 | **已修复（UI 复测）** |
| BUG-017 | 后端 / 运营后台 | 离线锁机恢复 | **P2** | 在线后锁机不自动解除 | 自动解锁接受 OPEN `DEVICE_OFFLINE`。造数：CAB-OTHER ONLINE+锁机+稳定在线，触发 `device-auto-unlock` → `sales_locked=f`，OFFLINE/FAULT **RESOLVED** | **已修复（造数复测）** |
| BUG-018 | 后端 / 消费者 | 优惠券核销 | **P1** | 可对已支付订单核销且不改金额 | 校验订单状态。复测 PAID 单 → **409**「订单当前状态不可用券」 | **已修复（API 复测）** |
| BUG-019 | 后端 | 优惠券核销 | **P2** | 非法 orderId → FK 500 | 校验订单存在。复测假单 → **404**「订单不存在」 | **已修复（API 复测）** |
| BUG-020 | 商户 H5 | 经营分析 / AI 洞察 | **P3** | 档位英文枚举直出 | Browser：**正常 / 滞销 / 无销量**；无 NORMAL 等英文 | **已修复（UI 复测）** |
| BUG-021 | 运营后台 | 固件版本 OTA | **P3** | 状态/渠道英文枚举 | Browser：渠道「稳定版」、状态「已发布」 | **已修复（UI 复测）** |
| BUG-022 | 运营后台 | 列表默认排序 | **P2** | ID 默认升序 | Browser 维修工单 ID **18→…→1** desc | **已修复（UI 复测）** |
| BUG-023 | 运营后台 | 维修工单 | **P3** | `CLOSED` 显示「未知」 | Browser `#1/#2` 状态「**已关闭**」；筛选项含已关闭 | **已修复（UI 复测）** |
| BUG-024 | 消费者 H5 | 我的 | **P3** | 已登录仍显示游客文案 | Browser 登录后：可用 ¥152 / 已实名 / 可开门；**无**「登录后可查看…」 | **已修复（UI 复测）** |
| BUG-025 | 三端 | 全局字体 | **P2** | 页面字体栈/字号/等宽字体不统一，视觉不一致 | **R47** 发现。**已修**：`theme.css` 对齐 `--el-font-family`；两 H5 绑 `html/body/#app/page` + 根 28rpx/14px；等宽统一 `--app-font-mono`；admin 已 rebuild。**R47-fix** Browser：商户/消费者 body+page 同栈 PingFang、14px；订单号 mono 栈；运营 `--el-font-family`=`--app-font` | **已修复（R47-fix）** |
| BUG-026 | 运营后台 / 三端 | 列表布局 / 分页·底栏 / 表头对齐 | **P2** | Tabs 盖分页；放大错位；横滚/表头与表体错位 | R50 分页/横滚已关。**再修**：去掉 `.table-scroll table{width:auto!important}`（会废掉 `table-layout:fixed`，表头/表体按内容分叉）。抽查订单/设备/仓库流水/维修工单列 left **maxAbs=0** | **已修复（含表头对齐跟进）** |
| OBS-001 | 商户 H5 | 钱包提现 | **观察** | H5 `uni-input` 与截图不同步，但 a11y/model 可驱动校验 | `v-model` 绑定正确；Browser/CDP 截图常空、a11y 有值；toast/API 校验已通。属验收工具与 uni-input H5 绘制不同步，非业务绑定缺陷 | **记录（非缺陷）** |
| OBS-002 | 运营后台 | 公告字典 | **观察** | 「存档」与「归档」文案不一致 | 筛选项/字典改为「**已归档**」，操作仍为「归档」。`V179` + shared-dict/bootstrap；Browser 状态选项见「已归档」 | **已修复（UI 复测）** |
| OBS-003 | 商户 H5 | 补货任务 | **观察** | 「已完成」与「今日完成率」口径曾不一致 | 列表「已完成」为累计，完成率为今日。文案改为「**累计已完成**」。Browser 补货员页可见 | **已修复（UI 文案）** |
| OBS-004 | 运营后台 | 营销活动 | **观察** | 新建活动 datetime 面板确认易被遮挡/中断 | Dialog `append-to-body` + picker `teleported`/`z-index=5000`/`close-on-click-modal=false`。Browser：点面板「确定」无 overlay 拦截；`OBS004-panel-verify` 落库 DRAFT | **已修复（UI 复测）** |
| OBS-005 | 消费者 H5 | 故障报修 | **观察** | uni-app「提交报修」按钮 a11y/坐标点击常落父级 | 提交按钮改为独立 `view role=button` + `@tap.stop`。Browser：a11y 见「提交报修」；点击可登录后提交成功 toast「报修已提交」 | **已修复（UI 复测）** |
| OBS-006 | 运营后台 | 活动效果分析 | **观察** | ROI 仅含有券定义的活动；类型字典缺项；已用≠核销面额 | 列改「预算已用/订单优惠」并加口径 hint；无券活动也入表。Browser：6 行含 R58-ui-promo；类型「新客」 | **已修复（UI+API）** |
| OBS-007 | 商户 H5 | 线长钱包 | **观察** | （已升级 BUG-010）流水类型键名错配 | 见 BUG-010 | **关闭→BUG-010** |
| OBS-008 | 运营后台 | 角色权限树 | **观察** | `viewer` 拥有根权限 `ops`，全选联动下 UI 易显「全勾」误导 | `V181` 删除 viewer 的 `ops` 并再收紧写/导出；`RoleManageView` 加载时跳过目录(M) checkedKeys。DB：viewer **无** `ops` | **已修复（DB+UI）** |
| OBS-009 | 运营后台 | 字典管理 | **观察** | 左侧类型搜索后右侧未自动切到选中类型 | 搜索后若当前类型不在结果中，自动选中首项。Browser 搜 `wallet` → 右侧「字典数据 · 钱包流水类型」 | **已修复（UI 复测）** |
| OBS-010 | 测试数据 | 投放/补货名称乱码 | **观察** | 列表见 `R10????` 等非产品展示坏 | `V182` 清理：投放「R10演示投放计划」；路线 R4/R5 演示名。DB 已无 `????` | **已修复（数据清理）** |
| OBS-011 | 运营后台 | 服务时限监控 | **观察** | 开门成功率在 0/0 时显示 100%；在线峰值与当前在线率口径不一致 | 空分母：rate **0**/UI「—」。峰值：`max(快照, 当前 ONLINE)`。Browser：成功率 **—**、0/0、在线率 50%、**在线峰值 1** | **已修复（UI 复测）** |
| OBS-012 | 消费者 / 识别 | 开门→支付 | **观察** | 纯 mock 识别曾一律 DISPUTED，无法演示 PAID | mock+重力/demo-close 注入 → **PAID**。跟进：发 UNUSED 新人立减#44 → 模拟器关门 → 原价¥3.50 / 优惠¥2.00 / 实付¥1.50；券 **USED** | **已修复（含自动选券）** |
| OBS-013 | 运营后台 / 数据 | 运营大屏在售 | **观察** | 大屏在售/锁机随锁机态变化 | 与销售锁机态一致属预期口径；OBS-019 解锁宽限后在售含离线已解锁柜。非缺陷 | **记录（预期行为）** |
| OBS-014 | 运营后台 | 投放地图坐标 | **观察** | 两台柜机坐标完全相同，地图聚成一点「2」 | `V181` 偏移 CAB-OTHER→`(22.565,114.12)`。API map-points 两坐标不同；Browser「共 **2** 个柜机落点」 | **已修复（数据+UI）** |
| OBS-015 | 商户 H5 | 财务工作台 CTA | **观察** | 财务无补货权限，工作台仍展示「扫码到柜 / 开始补货」 | `home.vue` 扫码卡仅 `canReplenishment` 时展示。Browser 财务 `13800138004`：无「扫码到柜」，仅柜机/待办 | **已修复（UI 复测）** |
| OBS-016 | 商户 H5 | 结算对账口径 | **观察** | 「待分账」取全局 overview，与区间营收并列易误解 | 文案改为「**全局待分账**」。Browser 店长结算页：区间营收 ¥11 / 全局待分账 ¥17.55 | **已修复（UI 文案）** |
| OBS-017 | 运营后台 | 出库发运货道满 | **观察** | 拣货成功后货道已满则发运 400，单据停在 PICKED | `markPicked` 同步 `clampLinesToSlotHeadroom`；全满则 **400**「无可拣货数量」且保持 DRAFT。复测 OB#13 A2 满→pick **400**，status=DRAFT | **已修复（API）** |
| OBS-018 | 消费者 H5 | 积分兑换门槛 | **观察** | 最低兑换项 100 积分，演示账号仅 12 分无法正向兑换 | `V182` 演示用户 `10001` 积分补至 **120**。API：available=120，最低档 100，可兑 | **已修复（演示数据）** |
| OBS-019 | 后端 / 设备 | 离线柜人工解锁 | **观察** | OFFLINE 柜人工解锁后，`device-presence` 会再次锁机并建 OPEN 异常 | `V183`+宽限 45 分钟。API+Browser：CAB-OTHER 离线且操作区为「锁机停售」（已解锁）；工作台/大屏在售含该柜 | **已修复（UI 复测）** |
| OBS-020 | 运营后台 / 数据 | 用户反馈 contactInfo | **观察** | 历史反馈 `contactInfo` 存有 XSS 样例载荷；当前 UI/导出**不展示**该字段 | V183 清脏+sanitize+列表不回传。Browser 反馈列表仅 content，无 contactInfo/脚本渲染 | **已修复（UI 复测）** |
| OBS-021 | 商户 H5 | 补货任务距离 | **观察** | 已完成任务 notes/展示 `dist=1209196m` 异常偏大 | 根因：无起点时默认上海→深圳柜 ≈1209196m。`RoutePlanningService` 改用首台有坐标柜机作起点；`V181` 清脏 notes/distance。两柜直线约 **1753m** | **已修复（代码+数据）** |
| OBS-022 | 运营后台 | 库存健康·临期 | **观察** | 临期行「容量」恒为 0 | `stockHealth` NEAR_EXPIRY 容量取自 `device_sku_inventory`。API：`SKU-SNACK-001` qty=8 **capacity=16**；Browser 临期行可见库存 8 | **已修复（API+UI）** |
| OBS-023 | 运营后台 | 设备运维详情 | **观察** | 事件「详情」列含英文键值原文 | `DeviceOpsMonitorView.formatEventDetail` 映射键/字典值。Browser：`在线状态：离线`、`生命周期：已投放`（非 `onlineStatus=OFFLINE`） | **已修复（UI 复测）** |
| OBS-024 | 运营后台 | 运营大屏 | **观察** | 识别自动结算耗时曾展示「平均 —586ms」 | 指标实为开门时长（close−open）。文案改为「开门时长/开门均时长」。Browser：大屏「开门时长 12112ms」；SLA「开门均时长」 | **已修复（UI 文案）** |
| OBS-025 | 运营后台 | 素材库 | **观察** | 测试素材预览「加载失败」 | `previewUrl` 改为同源 `/api/v2/media/ad-assets/{id}` 流式。API **200** image/png；Browser 预览列可出图（非「加载失败」；演示图本身极小呈色块） | **已修复（API+UI）** |
| OBS-026 | 后端 | 全局异常 | **观察** | 不支持的 HTTP 方法未映射为 405，统一落成 **500**「系统繁忙」 | `GlobalExceptionHandler` 映射 `HttpRequestMethodNotSupportedException`→**405**「不支持的请求方法」。复测 `DELETE /api/v2/ops/announcements` → **405** | **已修复（API 复测）** |
| OBS-027 | 运营后台 | 商户提现 | **观察** | 商户钱包状态列直出英文 `ACTIVE` | 状态列用 `displayLabel('merchant_status')`。Browser：MCH-DEFAULT/OTHER 显示「**正常**」 | **已修复（UI 复测）** |
| OBS-028 | 运营后台 | 数据一致性 | **观察** | `COUPON_ISSUED` 类型列显示「未知」；该类不可自动修复 | 字典「发券数量」；造数 FAIL 后操作列见「**需人工**」（非 —/修复） | **已修复（UI 复测）** |
| OBS-029 | 后端 / 仓库导出 | 库存流水 | **观察** | API `warehouse/export?tab=movements` **400** unsupported；UI 走前端本地导出成功 | `OpsCsvExportService` + `serverTabs` 含 movements。复测 `GET .../warehouse/export?tab=movements` → **200** CSV；Browser 库存流水 Tab 可点导出 | **已修复（API+UI）** |

---

## 字体专项（R47）

共享令牌：`packages/shared-uni/src/theme.css` 定义 `--app-font`（PingFang SC → … → Noto Sans SC）与 `--app-font-mono`。三端均 `@import`，但落地不一致：

| 端 | 根节点绑定 | Browser 实测 | 问题 |
|---|---|---|---|
| 运营 `admin-vue` | `html,body,#app { font-family: var(--app-font) }` | 内容区为 `--app-font`；`--el-font-family` 仍为 Element 默认 **Helvetica Neue 优先** | EP 令牌未对齐 |
| 消费者 H5 | 仅 `page`/`uni-page-body` | 内容 `--app-font` + **14px**；`html/body` 默认 **Noto Sans SC 16px** | 壳层与页面分裂 |
| 商户 H5 | 仅 `page`/`uni-page-body` | 同分裂；`uni-page-body` **无根 font-size** → **16px** | 与消费者基准字号不一致 |
| 等宽 | 各页硬编码 | 消费 `monospace`；商户 `ui-monospace`；运营混用 `monospace` / `--app-font-mono` / 自建栈 | 三端四套 |

**建议（待开始修）**：① `--el-font-family: var(--app-font)`；② H5 统一绑 `html,body,#app,page` + 根字号 28rpx；③ 等宽一律 `var(--app-font-mono)`。

**R47-fix 已落地**：①③ 已做；② 已绑 `html,body,#app,page,uni-page-body`（H5 的 `html` 字号可能仍被 uni rem 设为 16px，body/page 为 14px 且字体栈一致）。Admin 已 `npm run build` 写入 Docker 静态目录。

---

## BUG-007 / BUG-017 造数复测（2026-08-15 20:18～）

| 项 | 步骤 | 结果 |
|---|---|---|
| BUG-007 | 用户 10001 `frozen=balance`；OPEN 争议挂 DISPUTED 单 `O-BUG007-TEST`（100 分）；CONFIRM 改单至 SKU-DEMO-001×1（350） | **412** `余额不足`；票仍 OPEN；订单仍 DISPUTED/100 |
| BUG-017 | CAB-OTHER→ONLINE+锁机+`online_since` 早于阈值；OPEN DEVICE_OFFLINE/FAULT；`POST .../scheduled-tasks/device-auto-unlock/run` | `sales_locked=f`；相关异常 **RESOLVED** |

补丁：`GlobalExceptionHandler` 增加 `BalanceInsufficientException` → HTTP 412（此前仅 `noRollbackFor` 仍可能落到通用 500）。

---

## UI 复测 + 表头对齐（2026-08-15 19:45～20:06）

同类型表头错位：全局 `.table-scroll .el-table table` 曾设 `width:auto !important`，导致 `table-layout:fixed` 不生效、表头/表体按内容各排各的。已去掉该规则并 rebuild；CDP 抽查：

| 页面 | maxAbs(表头 left−表体 left) |
|---|---|
| 维修工单 | 0 |
| 订单管理 | 0 |
| 设备管理 | 0 |
| 仓库·库存流水 | 0（分页无覆盖） |

| 项 | 结果 |
|---|---|
| BUG-003 | viewer 工作台无「处理」文案；提示「点「查看」直达」 |
| BUG-010 | 线长钱包「佣金入账」 |
| BUG-012 | 复购 TOP ¥30.00 |
| BUG-016 | 财务钱包「仅查看」、无申请提现 |
| BUG-020 | 正常/滞销/无销量 |
| BUG-021 | 稳定版 / 已发布 |
| BUG-022/023 | 工单 desc；CLOSED=已关闭 |
| BUG-024 | 登录后无游客提示 |
| BUG-004 | 超长标题完整换行；已使用/已过期徽章独立行不压字（种子 `coupon_def_id=38`） |

---

## BUG-004 修复复测（2026-08-15 20:22～）

| 项 | 结果 |
|---|---|
| 根因 | 状态徽章 `position:absolute` 叠在标题右上角；flex 子项缺 `min-width:0`，长标题易挤/压字 |
| 修复 | `coupons.vue`：徽章改 `coupon-status-row` 独立行；标题 `word-break`/`overflow-wrap`；右侧 `min-width:0` |
| 造数 | `coupon_def_id=38` 超长名；`user_coupon` UNUSED/USED/EXPIRED 各 1 |
| Browser | `/pages/coupons/coupons`：长标题三行全文可见，徽章不压字；短标题布局正常 |

---

## OBS-002 / 026 / 027（2026-08-15 20:30～）

| ID | 修复 | 复测 |
|---|---|---|
| OBS-026 | `HttpRequestMethodNotSupportedException` → HTTP **405** | `DELETE /api/v2/ops/announcements` → **405**「不支持的请求方法: DELETE」 |
| OBS-002 | `ARCHIVED` 标签「存档」→「已归档」（shared-dict + bootstrap + `V179`） | Browser 公告状态选项「已归档」；操作仍「归档」 |
| OBS-027 | 商户钱包状态列 `displayLabel('merchant_status')` | Browser：`ACTIVE` →「**正常**」 |

---

## OBS-011 / 022 / 023 / 029（2026-08-15 21:00～）

| ID | 修复 | 复测 |
|---|---|---|
| OBS-011 | 空分母成功率改为 0；UI 0 次尝试显示「—」；峰值 floor 对齐当前在线 | API：peak=**1**/rate=0.5；开门成功率「—」/0/0 |
| OBS-022 | NEAR_EXPIRY 容量取货道库存 capacity | API `capacity=16`（qty=8）；Browser 临期行 `SKU-SNACK-001` 库存 8 |
| OBS-023 | 运维详情键值中文化 | Browser：`在线状态：离线` / `生命周期：已投放` |
| OBS-029 | warehouse export 支持 `tab=movements` | API **200** CSV；Browser 库存流水 Tab 可导出 |

---

## OBS-008 / 014 / 021 / 025（2026-08-15 21:35～）

| ID | 修复 | 复测 |
|---|---|---|
| OBS-008 | `V181` 删 viewer 根 `ops`；权限树加载跳过目录(M) | DB viewer 无 `ops`；RoleManageView 过滤 `permType!==M` |
| OBS-014 | `V181` 偏移 CAB-OTHER 坐标 | map-points 两坐标不同；Browser **2** 个独立红标 +「共 2 个柜机落点」 |
| OBS-021 | 规划起点改首台有坐标柜机；清脏 dist notes | 脏 `dist=1209196` **0** 条；两柜直线 ~1753m（非 1209km） |
| OBS-025 | 素材预览同源 `/api/v2/media/ad-assets/{id}` | 流式 **200**；Browser 预览列出图（非「加载失败」） |

---

## OBS-003 / 010 / 016 / 018（2026-08-15 22:12～）

| ID | 修复 | 复测 |
|---|---|---|
| OBS-003 | 补货统计「已完成」→「累计已完成」 | 与「今日完成率」口径区分 |
| OBS-010 | `V182` 清理 `????` 投放/路线名 | DB：R10演示投放计划；R4/R5 演示补货路线 |
| OBS-016 | 结算「待分账」→「全局待分账」 | Browser 店长页可见全局待分账 ¥17.55 |
| OBS-018 | `V182` 演示用户积分补至 120 | API available=120，最低兑 100，可兑 |

---

## 第 29 轮执行明细（2026-08-14 16:07～）

| 用例 | 结果 | 备注 |
|---|---|---|
| 风控 | **通过** | 风险事件 38 条（用户发起争议为主）；可切换黑名单 Tab |
| 销售报表 | **有缺陷** | 区间 08-01～08-14：可乐/雪碧有销量营收，订单数 **0** → **BUG-013** |
| 用户分析 | **有缺陷** | KPI ¥30.00；复购 TOP 累计消费 **¥0.00** → **BUG-012** |
| 选品诊断 | **有缺陷** | 空态；「运行诊断」API 仍 500 → **BUG-011** |
| 组织与点位 | **通过** | R12测试组织启用、1 台设备 |
| 线长钱包 | **有缺陷** | ¥99.35；佣金流水「未知」→ **BUG-010** |
| API 复确认 | **仍开放** | BUG-009 fund CSV 200；BUG-014 create-replenishment 500；BUG-015 `FOOBAR` 6 行；BUG-001 当前余额 15100/冻结 0（根因仍开放） |

环境快照：不变；**仍不改代码。请发「开始修」进入修复。**

---

## 第 30 轮执行明细（2026-08-14 16:22～）

| 用例 | 结果 | 备注 |
|---|---|---|
| 文案清理（用户授权改前端） | **已改** | 去掉商户订单/要货/争议/补货 ID 前缀 `#`；运营打印/仓库/补货文案 `#`；大屏识别自动结算错误 `—`；柜机空生命周期 `—`；补货隐藏 `seq=`/`dist=` 技术备注 |
| 商户柜机/订单/要货/补货 Browser | **通过** | `CAB-001` 无尾缀（**BUG-005 关**）；订单/要货/补货无 `#`；补货无 `seq/dist` |
| 消费者消息 | **通过** | 无无意义 `#` |
| 运营维修工单 | **有缺陷** | ID 升序最新在底 → **BUG-022**；CLOSED→「未知」→ **BUG-023**（字典无 CLOSED，`dictLabel` 缺项回「未知」） |
| 运营订单列表 | **有缺陷** | `orderId` 字典序，创建时间乱序 → **BUG-022** |

本轮改动：`merchant-mp` orders/request/disputes/replenishment/devices；`admin-vue` Print/Warehouse/BigScreen/Replenishment/DeviceDetail。Docker 静态 admin 需 rebuild 后大屏等才生效；商户 H5 `:3001` 已热更新验收。

---

## 第 50 轮执行明细（2026-08-15 15:50～）

| 用例 | 结果 | 备注 |
|---|---|---|
| 仓库库存流水 + 浏览器放大 | **有缺陷→已修** | 用户截图「20条/页」压在表格上；CDP `zoom` 100%～200% 重叠 → **BUG-026** |
| 补货调度放大 | **有缺陷→已修** | 同构 `tabs+table` |
| 订单/设备/争议/异常/一致性 | **通过（对照）** | 直挂 `.table-scroll` + `PagePager`，150% **无**重叠 |
| 资金账单 / 商户（少行） | **通过/弱显** | 同为 tabs，但表矮时未必压到分页 |

### R50-fix（2026-08-15 16:04～）

| 项 | 结果 | 备注 |
|---|---|---|
| admin 布局 | **已落地** | Tabs `flex:0` 自然撑高；卡片 `flex-shrink:0`；`PagePager` z-index/隔离/不透明底 |
| 三端同类 | **已落地** | `theme.css` `.app-footer-bar`；消费者 `cart-bar` + dvh；商户 filters/action-dock isolation |
| Browser 仓库 150% | **通过** | 重叠 **0**；滚底见「共 33 条 20条/页」；末行与分页 gap=13 |
| Browser 补货 150% | **通过** | 重叠 **0** |
| admin rebuild | **完成** | `clients/admin-vue` → static/admin |

**BUG-026 已关。请发「开始修」修其余 P1，或「下一轮」。**

---

## 第 51 轮执行明细（2026-08-15 16:22～）

### A. 同类型 Tabs/表横滚·分页抽查（BUG-026 跟进）

| 页面 | 横滚 maxAbs | 分页 overlap/gap | 结论 |
|---|---|---|---|
| 仓库·库存流水 | **0**（先前跟进） | — | 通过 |
| 补货·路线 | 无超宽 | overlap **0** | 通过 |
| 补货·商户要货 | **0**（max≈577） | overlap **0** | 通过 |
| 资金账单 | **0**（max≈159） | overlap **0** | 通过 |
| 商户·分账明细 | **0**（max≈110） | 活跃 pane gap 正；勿用隐藏 pane 的 pager 误测 | 通过 |
| 风控·风险事件 | 无超宽 | gap≈**13** | 通过 |
| 仓库·采购单 | **0**（max≈97） | gap≈**27** | 通过 |
| 订单管理（直表） | **0**（max≈835） | gap≈**27** | 通过 |
| 固件 OTA | **0**（max≈229） | —；少行时 `.table-scroll` 仍 flex 撑高属布局策略 | 通过 |

**本轮无新增布局缺陷。**

### B. 缺口深测

| 用例 | 结果 | 备注 |
|---|---|---|
| **BUG-007** CONFIRM | **仍开放** | `frozen=20000` + CONFIRM → **500** rollback-only；票仍 OPEN；已清理 |
| 选品诊断 run | **仍开放** | `sku-review/run?days=7` → **500**（BUG-011） |
| 库存健康非法维 | **仍开放** | `dimension=FOOBAR` → **200** 多行（BUG-015） |
| 营销活动页 | **通过/观察** | 列表 4 条；类型列仍见英文枚举（OBS-006）；未再深挖 UI 新建（OBS-004） |
| 固件版本 | **有缺陷** | `PUBLISHED` / `stable` → **BUG-021**；横滚对齐正常 |
| CAB-001 | **观察** | ONLINE 仍 `sales_locked=t`（BUG-017）；R51 曾临时解锁开门后已恢复锁机 |

测后清理：争议 `D047802…` RESOLVED；`user_id=10001` `frozen=0`；`CAB-001` `sales_locked=t`。

**请发「开始修」优先 P1（001/002/007/008/011/014/018），或「下一轮」。**

---

## 第 52 轮执行明细（2026-08-15 16:36～）

缺口深路径（OTA / 仓库在途 / 临期下架 / 营销新建 / viewer 权限 / H5）。**仍不改代码。**

| 用例 | 结果 | 备注 |
|---|---|---|
| OTA「发布版本」空提交 | **通过（校验）** | toast「请填写版本号与下载地址」；状态列英文仍 **BUG-021** |
| Browser 仓库·在途 | **通过** | `?tab=transit` 见出库 11 / 薯片×7 / IN_TRANSIT；分页「共 1 条」；横滚可见 |
| API 在途 | **通过（OBS-026）** | 正确 `GET .../warehouse/in-transit` **200**；误路径/方法 → **500** |
| 库存健康页 | **通过（有缺陷）** | 断货 1 / 临期 1；临期「下架」路径与 API 同源 **BUG-014** |
| **BUG-014** create-replenishment | **仍开放+根因** | 超管 POST → **500**；日志：`merchant:portal:access` /「平台管理员请使用运营后台…」→ rollback-only；`pull_off#1` 仍 OPEN |
| 营销「新建活动」 | **通过/观察** | 对话框可开（OBS-004 datetime 落库未本轮强测） |
| viewer consistency/run | **仍开放** | **200** → **BUG-008** |
| viewer fund export | **仍开放** | **200** CSV → **BUG-009** |
| 财务 withdraw | **仍开放** | `13800138004` → **403**（UI 仍可点）→ **BUG-016** |
| 假单核销 `#36` | **仍开放** | `O-FAKE-R52` → **500** FK → **BUG-019** |
| `dimension=FOOBAR` | **仍开放** | **6** 行 → **BUG-015** |
| Browser 消费者「我的」 | **仍开放** | ¥151.00 / 已实名 / 可开门 +「登录后可查看…」→ **BUG-024** |
| Browser 商户线长钱包 | **仍开放** | 可用 ¥98.35；佣金「**未知**」+¥0.14/+¥0.21 → **BUG-010** |

**请发「开始修」优先 P1（001/002/007/008/011/014/018），或「下一轮」。**

---

## 第 53 轮执行明细（2026-08-15 16:47～）

缺口：柜端签收闭环、P1 002/011、大屏/投放/可用性/手机验证。**仍不改代码。**

| 用例 | 结果 | 备注 |
|---|---|---|
| 商户 task#22 签到→开门→完成 | **通过** | check-in OK；`open-door` 会话 `SCF712…`（空补货）→ COMPLETED 无争议；complete → task **COMPLETED** |
| 在途签收 | **通过** | `transit#28` IN_TRANSIT→**RECEIVED**（×7）；在途列表空；CAB-001 薯片 lot qty **1→8** |
| **BUG-002** viewer 建单 | **仍开放** | `POST repair-tickets` → `#14` `R53-viewer-repair` OPEN；Browser 列表可见 |
| **BUG-011** 选品诊断 | **仍开放** | API/Browser「运行诊断」**500** NPE @ `runSkuReview`；列表仍空 |
| Browser 运营大屏 | **通过（OBS）** | 在售 0 / 锁机 2 → **OBS-013**；识别「平均 12053ms」→ **OBS-024** 破折号未见 |
| Browser 投放计划 | **通过（OBS-010）** | `#2` R11-UI投放计划 投放中；`#1` 仍 `R10????` |
| Browser 设备可用性 | **通过** | `/admin/device-kpi`：设备总数 2 / 离线 1 / 自动锁机 1；人工介入率「—」 |
| Browser 手机验证 | **通过** | 列表见 `13800138000`；登记验证按钮可用 |
| 识别映射 API | **通过** | `GET .../vision-mappings` 200（含 YOLO can→SKU-SODA） |

测后残留：viewer 工单 `#14` OPEN；`CAB-001` `sales_locked=t`；task#22 COMPLETED；在途已签收。

**请发「开始修」优先 P1（001/002/007/008/011/014/018），或「下一轮」。**

---

## 第 58 轮执行明细（2026-08-15 17:44～）

缺口：反馈/黑名单/Mock 充值、营销 UI datetime 落库、字典 OBS-009、导出/帮助。**仍不改代码。**

| 用例 | 结果 | 备注 |
|---|---|---|
| API/Browser 用户反馈 | **通过** | 消费者 `POST /feedback` → `#13`；超管 reply OK；Browser 列表可读；contactInfo XSS **未渲染**（OBS-020） |
| API 风控黑名单 | **通过** | add user `10001` → list 见 `R58-test` → DELETE 清空 |
| API Mock 充值 | **通过** | WECHAT prepay `RFC430…` → mock-success **PAID**；余额 15100→**15200** |
| Browser 营销 UI 新建 | **通过（OBS 细化）** | 名称 `R58-ui-promo` + datetime 写入后落库 `#37` DRAFT；点选面板路径未纯手工 → **OBS-004** |
| **OBS-009** 字典搜索 | **再确认** | 搜 `wallet` 左侧「钱包流水类型」，右侧仍「设备类型」 |
| API 导出抽样 | **通过** | orders/sessions/risk CSV **200**；user-analysis/roi export 路径 **404**（UI 可能本地导出） |
| Browser 帮助中心 | **通过** | FAQ/客服热线/公告/反馈/报修入口可读 |

测后残留：feedback `#13` 已回复；promo `#37` DRAFT；充值 +¥1（余额 15200）；黑名单已清。

**请发「开始修」优先 P1，或「下一轮」。**

---

## 第 57 轮执行明细（2026-08-15 17:34～）

缺口：素材上传预览、公告发布、用户分析/销售报表、ROI、线长钱包字典、组织/消费者消息。**仍不改代码。**

| 用例 | 结果 | 备注 |
|---|---|---|
| API 素材上传 | **通过（OBS）** | multipart → `assetId=2` `R57-asset` ACTIVE；Browser 预览「加载失败」×2 → **OBS-025** |
| API/Browser 公告 | **通过（OBS）** | 建 `#2` DRAFT→publish **PUBLISHED**；列表见标题；筛选项「存档」→ **OBS-002** |
| **BUG-012** 用户分析 | **仍开放** | Browser 复购 TOP 累计消费 **¥0.00**（KPI ¥30.00；API `totalSpent=30.00`） |
| **BUG-013** 销售报表 | **仍开放** | API SKU 维 `orderCount=0`；Browser 默认今日空 |
| Browser ROI | **有观察** | 「新客开门礼」类型「未知」→ **OBS-006** |
| **BUG-010** 线长钱包 | **仍开放** | H5 佣金流水「未知」×2（+¥0.14/+¥0.21）；余额 ¥98.35 |
| Browser 组织与点位 | **通过** | 组织树见「R12测试组织」启用·1 台 |
| Browser 消费者消息 | **通过** | 充值/支付消息可读；关联单号为纯数字 |
| P1 软确认 | **仍开放** | viewer consistency **200**；sku-review/expiry create **500** |
| BUG-001 余额快照 | **记录** | `user_id=10001` `balance=15100` `frozen=0`（根因仍开放） |

测后残留：ad asset `#2`；announce `#2` PUBLISHED；`CAB-001` 仍解锁。

**请发「开始修」优先 P1（001/002/007/008/011/014/018），或「下一轮」。**

---

## 第 56 轮执行明细（2026-08-15 17:22～）

缺口：营销/投放写路径、P1 软确认、风控、维修工单、消费者券 H5。**仍不改代码。**

| 用例 | 结果 | 备注 |
|---|---|---|
| API 营销新建+启用 | **通过** | `POST /ops/promotions` → `#36` DRAFT；launch → **ACTIVE** |
| Browser 营销活动 | **通过（OBS）** | 见 `R56-promo`；「新建活动」对话框字段齐全 → **OBS-004**；列表类型 `NEW_USER`/`POINTS` → **OBS-006** |
| API/Browser 投放 | **通过** | `POST .../ad/campaigns` → `#3` DRAFT→launch **RUNNING**；Browser「R56-campaign / 投放中」 |
| **BUG-002** viewer 建单 | **仍开放** | viewer `POST .../repair-tickets` → `#15` OPEN；Browser 列表可见 |
| **BUG-008/009** | **仍开放** | viewer `consistency/run` **200**；`fund/.../export` **200 CSV** |
| **BUG-011/014** | **仍开放** | sku-review/run **500**；expiry create-replenishment **500** |
| **BUG-015** FOOBAR | **仍开放** | `dimension=FOOBAR` → **6** 行；`LOW` → 0；默认 → 1 |
| **BUG-018** 券核销 PAID | **仍开放** | 新发 `#39` 对 `O97FB…` → USED ¥5；订单金额不变 |
| **BUG-019** 假单号 | **仍开放** | `#40` + `O-FAKE-R56` → **500**；券仍 UNUSED |
| Browser 风控 | **通过** | 风险事件列表可读（「用户发起争议」中文） |
| Browser 维修工单 | **有缺陷** | `#15` 可见；CLOSED 行「未知」×3 → **BUG-023** |
| Browser 消费者券 | **通过** | 登录后见未使用 `#40` + 多张已使用；满减文案可读 |

测后残留：promo `#36` ACTIVE；ad `#3` RUNNING；repair `#15` OPEN；券 `#39` USED / `#40` UNUSED；`CAB-001` 仍解锁。

**请发「开始修」优先 P1（001/002/007/008/011/014/018），或「下一轮」。**

---

## 第 55 轮执行明细（2026-08-15 17:05～）

缺口：离线锁机自动恢复（BUG-017）、大屏在售口径、viewer 工作台、财务提现 UI、经营分析枚举、维修工单「未知」、定时任务页。**仍不改代码。**

| 用例 | 结果 | 备注 |
|---|---|---|
| **BUG-017** 自动解锁 | **仍开放（细化）** | 配置 `auto_unlock_enabled=true`；定时 `device-auto-unlock` enabled/SUCCESS，ONLINE+锁机未自动解。人工 `UNLOCK` → **200**，`CAB-001` `sales_locked=f` |
| **OBS-013** 大屏在售 | **口径变化** | 解锁后 Browser 大屏：在售柜 **1** / 锁机 **1**（原 0/2） |
| Browser 定时任务 | **通过** | 约 22 项可读，含 unlock/presence |
| Browser 维修工单 | **有缺陷** | 仍见 Tag「未知」→ **BUG-023** |
| 商户财务钱包 | **有缺陷** | UI「可自主提现/申请提现」；withdraw API **403** → **BUG-016** |
| 商户经营分析 | **有缺陷** | `NORMAL` / `SLOW_MOVER` / `NO_SALES` → **BUG-020** |
| Browser viewer 工作台 | **有缺陷** | `13900000005` +「只读」；待办「处理」×3 → **BUG-003** |

测后残留：`CAB-001` ONLINE **已解锁**（`sales_locked=f`）；`CAB-OTHER` OFFLINE 仍锁；大屏 在售 1 / 锁机 1。未为恢复旧基线再锁机。

**请发「开始修」优先 P1（001/002/007/008/011/014/018），或「下一轮」。**

---

## 第 54 轮执行明细（2026-08-15 16:55～）

缺口：货道满发运（OBS-017）、P1/P2 再确认、OTA 真发布、打印/录像/识别入驻。**仍不改代码。**（本轮中断后已补完报告）

| 用例 | 结果 | 备注 |
|---|---|---|
| **OBS-017** 货道满发运 | **再确认** | 造 A2 缺口 → plan route#26 / OB#12 DRAFT→PICKED；填满货道后 ship → **400**「货道已满…」；cancel → CANCELLED |
| **BUG-018** 券核销 PAID | **仍开放** | `#36` 对 `O97FB…` → USED ¥5；订单仍 400 分 / `coupon_discount_cents=0` |
| **BUG-012** 用户分析 | **仍开放** | Browser 复购 TOP 累计消费 **¥0.00**（API `totalSpent=30.00`） |
| **BUG-013** 销售报表 | **仍开放** | API SKU 维 `orderCount=0`（有 qty/revenue） |
| P1 软确认 | **仍开放** | BUG-008 viewer run **200**；BUG-011/014 **500**；BUG-015 FOOBAR 多行 |
| API OTA 发布 | **通过** | `POST .../ota/releases` → `#35` `1.0.54-R54` **PUBLISHED** |
| Browser OTA | **有缺陷** | 见 `1.0.54-R54`；状态/渠道仍英文 → **BUG-021** |
| Browser 录像上传 | **通过** | 队列空「暂无待上传录像」（正常空态） |
| Browser 打印标签 | **通过** | `?type=labels&ids=SKU-DEMO-001,SKU-SODA-001` 见「共 2 个商品」 |
| Browser 识别入驻 | **通过** | 在售商品列表可读（可乐/苹果/雪碧等「生产」）；流程说明完整 |

测后残留：券 `#36` USED（测 BUG-018）；OTA `#35` PUBLISHED；OB#12/route#26 CANCELLED；A2 雪碧 lot 两行各 qty=4（满仓态）。

**请发「开始修」优先 P1（001/002/007/008/011/014/018），或「下一轮」。**

---

## 第 49 轮执行明细（2026-08-15 15:42～）

| 用例 | 结果 | 备注 |
|---|---|---|
| API 采购创建+收货 | **通过** | `PO#5` `R49-PO-154337` CREATED→RECEIVED；水批次 `B-R49-WATER-154337` 入库 +5 |
| API 补货规划→拣货→发运 | **通过** | 造账面缺口（B1 薯片 lot qty 8→1）→ `route#22`/`OB#11` DRAFT→PICKED→SHIPPED（7 件 IN_TRANSIT）；Browser 在途可见 |
| Browser 仓库采购/流水/在途 | **通过** | 采购见 PO#5；流水 #38 收货 +5、#39 发运 -7；在途 1 条薯片×7 |
| 一致性 fix | **通过（有观察）** | `INVENTORY_MISMATCH#43` fix → `fixed=true`；再 run `failCount=1` 仅剩券；`COUPON_ISSUED#40` 不可自动修 → **OBS-028** |
| 导出抽样 | **通过（OBS-029）** | orders/sessions/risk/routes/requests/revenue-splits/warehouse(purchase|inventory|outbounds) **200**；`tab=movements` API **400**，UI 本地导出 OK |
| OBS-021 | **再确认** | 新路线 notes/`totalDistanceM=1209196` |
| BUG-007 | **未硬复现** | 仍未造可用余额不足场景 |

测后残留：`PO#5` 已收货；`OB#11` 在途；CAB-001 B1 lot qty=1 + 在途 7。**仍不改代码。请发「开始修」。**

---

## 第 48 轮执行明细（2026-08-15 15:30～）

| 用例 | 结果 | 备注 |
|---|---|---|
| API/Browser 运营 `13900000003` | **通过（有权限瑕疵）** | 菜单裁剪正确（无仓库/补货调度/角色/公告）；订单/争议/异常/设备/投放/资金账单可用；仓库/公告/角色深链回工作台且 API **403** |
| 运营·资金账单 | **通过** | `/admin/fund-bills` 4 条日账单；导出 API **200**（角色含 `ops:fund:list/export`） |
| 运营·数据一致性 | **有缺陷** | 含 `consistency:run/fix`；「立即巡检」+ API `run` **200** `failCount=2` → 扩记 **BUG-008** |
| API/Browser 补货 `13900000004` | **通过** | 仅库存健康/补货调度/仓库/补货员效率；设备/订单/资金深链回补货调度；PO API **403** |
| Browser 补货调度 | **通过（OBS-010）** | 路线 10 条已完成；KPI 已履约 10 / 要货待审 3 / 临期 1；见 `R4????`/`R5??????` |
| Browser 仓库（补货） | **通过** | 概览 WH-DEMO-001；批次库存 7 行可读；出库默认「待处理」空（全量 8 SHIPPED+1 CANCELLED） |
| Browser 库存健康/补货员效率 | **通过** | 临期 1 行；效率 2 人完成率 100% |
| API P1 软确认 | **仍开放** | viewer consistency/fund CSV → **008/009**；sku-review **500** → **011**；expiry create **500**（超管+补货）→ **014**；FOOBAR **6** → **015** |
| BUG-007 | **未硬复现** | `user_id=10001` 仍 `balance=15100`/`frozen=0`；无 OPEN 争议可供 CONFIRM |

**仍不改代码。请发「开始修」。**

---

## 第 47 轮执行明细（2026-08-15 15:01～）

| 用例 | 结果 | 备注 |
|---|---|---|
| 三端字体代码+Browser | **有缺陷** | 见「字体专项」→ **BUG-025** |
| Browser 财务·资金账单 | **通过** | `13900000002`：4 条日账单；CSV 抽样与 UI 对齐 |
| Browser 财务·对账 | **通过** | 批次 1 / 差异 0 / 已平账 |
| Browser 财务·商户提现 | **有观察** | ¥650；状态 **ACTIVE** → **OBS-027** |
| Browser/API 财务权限 | **通过** | devices/warehouse **403**；无设备菜单 |
| Browser 打印 purchase/labels | **通过** | PO#4；雪碧/薯片标签 2 张 |
| API fund/stock-health 导出 | **通过（联动 OBS-022）** | stock CSV 临期容量列仍 **0** |

**仍不改代码。请发「开始修」。**

---

## 第 46 轮执行明细（2026-08-15 14:49～）

| 用例 | 结果 | 备注 |
|---|---|---|
| Browser 设备列表 | **通过** | 2 台；CAB-001 在线/停售；与 BUG-017 锁机态一致 |
| Browser 设备详情深 Tab | **通过** | 概览/温控与环境（空读数提示）/货道 12 道/投放流水空态/关联单据会话+订单可读；二维码链接可见；工单迷你表可见 |
| Browser 打印拣货单 | **通过** | `/print?type=picking&outboundId=10`：4 行商品/批次/货道；缺 `type` 时空态「暂无打印内容」属预期 |
| Browser/API 手机验证 | **通过** | 流水 2 条（13800138000 / 微信 / MCH-DEFAULT）；登记入口可见 |
| Browser 商户「我的」 | **通过** | 资料/现场/经营入口齐全；订阅模板未配置提示可读 |
| Browser 商户订单详情 | **通过** | PAID ¥4.00 雪碧；订单号/会话纯数字；支付方式「余额」 |
| Browser 消费者协议页 | **通过** | agreement/privacy/refund/billing 正文分区完整（硬导航） |
| Browser 消费者开通支付 | **通过** | 实名/免密已勾选，「可以开门购物了」 |
| Browser 购物视频（无 url） | **通过（空态）** | 无 `url` 时「缺少视频地址」；演示单无 `videoUri` 不展示入口 |
| API merchant/profile PATCH | **通过** | 更新 remark OK；误用 GET → 500 → **OBS-026** |
| API device commands GET | **有观察** | 仅 POST 下发；GET → 500 → **OBS-026** |

**仍不改代码。请发「开始修」。**

---

## 第 45 轮执行明细（2026-08-15 14:42～）

| 用例 | 结果 | 备注 |
|---|---|---|
| Browser/API 积分兑换管理 | **通过** | 3 项 ACTIVE；停用/启用 POST status 往返 OK；列表中文可读 |
| Browser/API 会员等级规则 | **通过** | NORMAL/SILVER/GOLD/PLATINUM；停用/启用往返 OK |
| Browser/API 线长钱包·提现 | **通过** | 成员余额 ¥98.35（提现后）；提现审核见 `#2` `LW-60B94F…` **已打款**（自动过阈）；流水后台「佣金入账」正常 |
| API 线长提现创建 | **通过** | `POST .../line-wallet/withdraw` `amountCents=100` → **PAID** Mock；`<100` → `400 最低提现 1.0 元` |
| Browser 商户待办 alerts | **通过** | 故障(锁机) + 临期 SKU-SNACK-001×7；角标待办 2 |
| Browser 商户分账 splits | **通过** | 「全部」见待分账/已冲正；订单号纯数字展示；商户所得金额正确 |
| Browser 商户线长钱包 | **有缺陷** | 可用 ¥98.35；佣金流水仍「**未知**」→ **BUG-010** |
| Browser 消费者营销 | **通过** | 4 活动（含 R4 测试）；「积分兑好礼」CTA「已下线」 |
| Browser 帮助/公告 | **通过** | 帮助 FAQ/客服 400-888-0018；公告「全量测试公告-商户可见」 |
| Browser/API 积分兑换 | **有观察** | 积分 12；点兑换 toast「积分不足」/API 409 → **OBS-018** |
| Browser 消费者「我的」 | **有缺陷** | 已登录仍显示「登录后可查看订单与余额」→ **BUG-024** |
| API merchant/me · expiry-alerts · revenue-splits | **通过** | me 权限齐全；alerts 1；splits total=9 |

**仍不改代码。请发「开始修」。**

---

## 第 44 轮执行明细（2026-08-15 14:35～）

| 用例 | 结果 | 备注 |
|---|---|---|
| Browser 客流坪效 | **通过** | 近 7 天：开门 28 / 支付订单 3 / 转化 10.7% / 客单价 ¥3.67；时段热区与排行区块可见 |
| Browser 设备报表 | **通过** | 设备数 2、离线 1；API `reports/devices` 2 行 |
| Browser 数据分析 | **通过** | 今日营收/订单快照与入口文案正常 |
| Browser 财务毛利 | **通过** | 今日营收/成本/毛利卡 +「固化昨日毛利」入口可见 |
| Browser 商品管理 | **通过** | 页可开；API `skus` 16 条 |
| Browser 识别入驻 | **通过** | 管线态草稿/映射中/已测试计数可见；API rows 16 |
| Browser 运营账号 | **通过** | 筛选/新增/导入导出入口可见 |
| Browser 菜单管理 | **通过** | 运营侧栏 / 商户权限树 Tab 与说明文案正常 |
| Browser 商户点位定价 | **通过** | 6 SKU 可见；薯片覆盖价 ¥6.68（基准 ¥6.50） |
| Browser 消费者意见反馈 | **通过** | 提交表单（类型/内容/联系方式）可读；API `feedback/mine` 12 条 |

**仍不改代码。请发「开始修」。**

---

## 第 43 轮执行明细（2026-08-15 14:26～ / 中断后续）

| 用例 | 结果 | 备注 |
|---|---|---|
| API viewer 权限码 | **有观察/缺陷联动** | roleKey=`viewer` 34 项含根 `ops` + `ops:repair:edit` / `consistency:run` / `fund:export` / `stock-health:export` → **OBS-008** + BUG-002/008/009 |
| Browser 投放计划 | **有观察** | `R11-UI投放计划` / `R10????` 均「投放中」→ **OBS-010** |
| Browser 素材库 | **有观察** | `R10测试素材` 可读；预览「加载失败」→ **OBS-025** |
| Browser 商户提现 | **通过** | 钱包 Tab 见 `MCH-DEFAULT`；审核列表 API 5 条（PAID/REJECTED） |
| Browser 角色管理 | **通过** | 10 角色列表含「只读」 |
| Browser 参数配置 | **通过（有联动）** | 24 项可读；`device.offline.auto_unlock_enabled=true` 仍锁机 → **BUG-017** |
| Browser 风控 | **通过** | 风险事件可见（用户发起争议等） |
| Browser 通知公告 | **有观察** | 状态筛选项「存档」vs 更多操作「归档」→ **OBS-002** |
| Browser 消费者报修 | **通过** | 表单/类型/提交按钮可见（提交点击限制见 OBS-005） |
| 余额快照 | **记录** | `user_id=10001` balance=15100 frozen=0（BUG-001 根因仍开放） |

**仍不改代码。请发「开始修」。**

---

## 第 42 轮执行明细（2026-08-15 14:17～）

| 用例 | 结果 | 备注 |
|---|---|---|
| API/Browser viewer 建工单 | **有缺陷** | `POST repair-tickets` → `#13` OPEN `R42-viewer-repair`；列表可见 → **BUG-002** |
| Browser viewer 工作台 | **有缺陷** | 只读账号仍有待办「处理」按钮 → **BUG-003** |
| Browser 手机验证 | **通过** | 2 条流水（微信渠道）可读 |
| Browser 用户反馈 | **有观察** | 12 条内容可读；无 contactInfo XSS 渲染 → **OBS-020** |
| Browser 字典管理 | **有观察** | 搜 `wallet` 左侧「钱包流水类型」14，右侧仍「设备类型」→ **OBS-009** |
| Browser/API 会员积分兑换 | **有观察** | 银卡、积分 12；最低 100 分仍显示「立即兑换」；API **409** → **OBS-018** |
| Browser 商户争议 | **通过** | 待处理空态文案正常 |

**仍不改代码。请发「开始修」。** 脏数据：viewer 工单新增 `#13`。

---

## 第 41 轮执行明细（2026-08-15 14:07～）

| 用例 | 结果 | 备注 |
|---|---|---|
| API P2 导出/库存 | **仍开放** | viewer fund CSV **200** + stock-health export **200** → **BUG-009**；`dimension=FOOBAR` 6 行 → **BUG-015** |
| API/Browser 用户分析 | **有缺陷** | KPI ¥30.00；复购 TOP 累计消费 **¥0.00** → **BUG-012** |
| API/Browser 销售报表 | **有缺陷** | SKU 维 `orderCount=0`；Browser 今日「暂无数据」→ **BUG-013** |
| Browser 维修工单 | **有缺陷** | 状态「未知」×3 → **BUG-023** |
| Browser OTA | **有缺陷** | `PUBLISHED` / `stable` → **BUG-021** |
| Browser 财务钱包 | **有缺陷** | 「可自主提现 / 申请提现」可见；API withdraw **403** → **BUG-016**；首页「扫码到柜」→ **OBS-015** |
| Browser 线长钱包 | **有缺陷** | 佣金流水「未知」+¥0.14/+¥0.21 → **BUG-010** |
| Browser 经营分析 | **有缺陷** | AI 洞察 `NORMAL`/`SLOW_MOVER`/`NO_SALES` → **BUG-020** |

**仍不改代码。请发「开始修」。**

---

## 第 40 轮执行明细（2026-08-15 13:44～ / 中断后续写报告）

| 用例 | 结果 | 备注 |
|---|---|---|
| API P1 点检 | **仍开放** | viewer `consistency/run` **200** failCount=2 → **BUG-008**；sku-review **500** → **BUG-011**；expiry create-replenishment **500** → **BUG-014** |
| 锁机 / 余额 | **仍开放** | `CAB-001` ONLINE+锁机 → **BUG-017**；`user_id=10001` balance=15100 frozen=0（BUG-001 根因仍开放） |
| Browser 运营大屏 | **有观察** | 在售 0 / 锁机 2 → **OBS-013**；「平均 —586ms」→ **OBS-024** |
| Browser 审计日志 | **有缺陷** | 310 条可读；默认 ID 升序、首行最早 → **BUG-022** |
| Browser 补货员效率 | **通过** | 近 7 天：商户测试 9/9、完成率 100% |
| Browser 识别映射 | **通过** | apple→苹果（生产）、bottle→雪碧等可读 |
| API BUG-018/019 | **仍开放** | `#38` 对 PAID `O97FB…` → USED 且订单金额未变 → **BUG-018**；`#36`+假单 → **500** 仍 UNUSED → **BUG-019** |
| Browser 商户公告 | **通过** | 「全量测试公告-商户可见」可读 |
| Browser 消费者优惠券 | **通过（BUG-004 未复现）** | 未使用 Tab 见「满减券 ¥5」完整标题；本轮未见「新人立…」截断 |

**仍不改代码。请发「开始修」。** 脏数据：券 `#38` 已 USED（测 BUG-018）；`#36` 仍 UNUSED。

---

## 第 39 轮执行明细（2026-08-15 13:27～）

| 用例 | 结果 | 备注 |
|---|---|---|
| Browser 开门记录 | **有缺陷** | 列表可加载（47 条）；默认按会话号升序 → **BUG-022**；Docker 包仍见十六进制会话号（源码已有 `displayBizNo`，待 rebuild） |
| Browser 录像上传 | **通过** | 空队列「暂无待上传录像」 |
| Browser / API 服务时限 | **有观察** | 快照 0/0→100%、在线峰值 0 / 在线率 50% → **OBS-011**；实时识别均耗时 586ms |
| Browser 运营消息记录 | **通过（有备注）** | 充值/补货站内信可读；业务列直出 `RECHARGE`/`REPLENISHMENT`；关联单号 Docker 仍为十六进制 |
| Browser 商户消息 | **通过（有备注）** | 标题仍为模板「新补货任务 #16」；关联单号为纯数字 `16` |
| Browser 商户团队 | **通过** | 管理员/店员/财务/店长/补货员 5 人可见 |

**仍不改代码。请发「开始修」。**

---

## 第 38 轮执行明细（2026-08-15 13:15～）

| 用例 | 结果 | 备注 |
|---|---|---|
| Browser 订单管理 | **有缺陷** | 默认按订单号字典序升序，时间线颠倒 → **BUG-022**；Docker 包仍见十六进制单号 |
| Browser 告警规则 | **通过** | 离线锁机/自动解锁/SLA/Webhook 等配置项可见（`auto_unlock` 与 BUG-017 相关） |
| Browser 定时任务 | **通过（有联动）** | `device-auto-unlock` / `device-presence` 均 SUCCESS，但柜机仍锁机 → **BUG-017** |
| Browser 对账 | **通过** | 批次 1 已平账、差异 0 |
| Browser 优惠券 | **通过** | 7 条券定义可读，发行量可见 |
| Browser 商户要货 | **通过** | 发起页 SKU 列表 OK；「我的申请」见 `#34/#36` 无 `#` 前缀 |
| Browser 消费者充值 | **通过** | 余额 ¥151.00；记录单号为纯数字片段 |

**仍不改代码。请发「开始修」。**

---

## 第 37 轮执行明细（2026-08-15 13:01～）

| 用例 | 结果 | 备注 |
|---|---|---|
| API P1 点检 | **仍开放** | sku-review **500** → **BUG-011**；expiry create-replenishment **500** → **BUG-014**；viewer consistency/run **200** failCount=2 → **BUG-008** |
| 锁机状态 | **仍开放** | `CAB-001` ONLINE+锁机 → **BUG-017** |
| Browser 仓库 | **通过** | WH-DEMO-001 正常，多 Tab 入口可见 |
| Browser 运营大屏 | **有观察** | 在售 0 / 锁机 2 → **OBS-013**；识别自动结算「平均 —586ms」显示怪异 |
| Browser 设备可用性 | **通过** | 当日 KPI：离线事件 1 / 自动锁机 1 |
| Browser 投放地图 | **有观察** | 共 2 落点、Leaflet 1 簇 → **OBS-014** |
| Browser 补货调度 | **通过（有观察）** | 路线 10 条；临期 Tab OPEN + 原因 `NEAR_EXPIRY`；`R4????` 路线名（测试数据乱码） |
| Browser 补货员 H5 | **通过（有观察）** | 已完成 10 / 今日完成率 0% → **OBS-003**；任务 16 明细无 `seq=`/`dist=`；`CAB-001` 无尾缀 |

**仍不改代码。请发「开始修」。**

---

## 第 36 轮执行明细（2026-08-15 12:53～）

| 用例 | 结果 | 备注 |
|---|---|---|
| Browser 店长结算对账 | **有观察** | 区间营收 ¥11 / 待分账 ¥17.55 → **OBS-016**；日期完整（BUG-006 H5 仍缓解） |
| Browser 消费者订单 | **通过** | 纯数字单号（如 `738105086360`），无 `@` 批次码 |
| Browser 消费者消息 | **通过** | 充值/订单通知可读；关联单号为纯数字 |
| Browser 手机验证 | **通过** | 登记流水 #1/#2 可见 |
| Browser 用户反馈 | **通过（有观察）** | 列表仅 content；无 contactInfo → **OBS-020** |

承接 R35 结算/消费者缺口。**仍不改代码。请发「开始修」。**

---

## 第 35 轮执行明细（2026-08-15 12:48～）

| 用例 | 结果 | 备注 |
|---|---|---|
| API 假单核销 | **有缺陷** | `#38` + `O-FAKE-R35` → **500**；券 UNUSED → **BUG-019** |
| viewer 导出 | **有缺陷** | fund + stock-health CSV 均 **200** → **BUG-009** |
| Browser 资金账单 | **通过** | 日账单 4 条已固化，金额可读 |
| Browser 风控 | **通过** | 风险事件列表（用户发起争议）可见 |
| Browser 营销/ROI | **有观察** | 活动类型 `NEW_USER`/`POINTS`；ROI「未知」→ **OBS-006** |
| Browser 通知公告 | **有观察** | 筛选项「存档」vs 操作「归档」→ **OBS-002** |
| Browser 设备运维 | **有观察** | 详情 `onlineStatus=OFFLINE` 等 → **OBS-023** |

结算/消费者 Browser 顺延第 36 轮。**仍不改代码。请发「开始修」。**

---

## 第 34 轮执行明细（2026-08-15 12:38～）

| 用例 | 结果 | 备注 |
|---|---|---|
| Browser 固件 OTA | **有缺陷** | 渠道 `stable` / 状态 `PUBLISHED` → **BUG-021** |
| Browser 用户分析 | **有缺陷** | KPI ¥30.00；复购 TOP 累计消费 **¥0.00** → **BUG-012** |
| Browser/API 销售报表 | **有缺陷** | 默认今日空态；API 区间 SKU 仍 `orderCount=0` → **BUG-013** |
| Browser 数据一致性 | **通过（有 FAIL）** | FAIL 2：库存汇总可「修复」；券定义类型列显示「未知」（观察） |
| Browser 线长钱包(H5) | **有缺陷** | 佣金流水「未知」+¥0.14/+¥0.21 → **BUG-010** |
| Browser 经营分析 | **有缺陷** | AI 洞察 `NORMAL`/`SLOW_MOVER`/`NO_SALES` → **BUG-020** |
| Browser/API 财务钱包 | **有缺陷** | UI「可自主提现/申请提现」；withdraw **403** → **BUG-016** |
| 锁机状态 | **仍开放** | `CAB-001` ONLINE+锁机、工作台停售 2 → **BUG-017** |

本轮以 **Cursor 内置 Browser** 为主。**仍不改代码。请发「开始修」。**

---

## 第 33 轮执行明细（2026-08-15 12:24～）

| 用例 | 结果 | 备注 |
|---|---|---|
| viewer 建维修工单 API | **有缺陷** | `POST repair-tickets` → `#12` OPEN → **BUG-002** |
| viewer consistency/fund | **有缺陷** | `consistency/run` **200** failCount=2 → **BUG-008**；fund CSV **200** → **BUG-009** |
| sku-review / expiry 下架 | **有缺陷** | 均 **500** → **BUG-011/014** |
| stock-health FOOBAR | **有缺陷** | `dimension=FOOBAR` 仍 **6** 行 → **BUG-015** |
| 优惠券核销 PAID | **有缺陷** | 新发 `#37` 对 PAID `O97FB…` → USED；订单金额不变 → **BUG-018** |
| Browser 工作台（viewer） | **有缺陷** | 待办操作 `aria-label=处理` → **BUG-003**；在售 0/停售 2 → OBS-013 |
| Browser 争议/异常 | **通过（空态）** | 待审核争议 0；异常中心 OPEN 2（CAB-001/CAB-OTHER 离线超时） |
| Browser 维修工单 | **有缺陷** | ID 升序含 `#12` → **BUG-022**；CLOSED「未知」→ **BUG-023** |
| Browser 库存健康 | **有观察** | 临期薯片容量 0 → **OBS-022** |
| Browser 用户余额 | **通过** | `10001` ¥151.00（frozen=0；BUG-001 根因仍开放） |
| Browser 消费者券 | **通过** | 券列表可读；本轮未见明显标题截断 |
| Browser 商户争议/柜机 | **通过** | 争议空态；`CAB-001` 无尾缀 `-`（BUG-005 仍关） |

本轮以 **Cursor 内置 Browser** 为主验收；H5 `:3001/:3002` 本轮现场拉起。**仍不改代码。请发「开始修」。**

---

## 第 32 轮执行明细（2026-08-14 18:02～）

| 用例 | 结果 | 备注 |
|---|---|---|
| 线长钱包 Browser | **有缺陷** | 佣金流水仍「**未知**」+¥0.14/+¥0.21 → **BUG-010** |
| 用户分析 Browser/API | **有缺陷** | KPI 有营收；复购 TOP 累计消费 **¥0.00**；API `totalSpent`（元）vs 前端 `totalSpentCents` → **BUG-012** |
| 销售报表 API/Browser | **有缺陷** | `dimension=SKU` 区间 08-01～08-14 仍 `orderCount=0` → **BUG-013**；页面默认今日区间常空态 |
| 财务钱包 Browser/API | **有缺陷** | UI「可自主提现 / 申请提现」可见；`POST .../wallet/withdraw` **403** → **BUG-016** |
| 消费者优惠券 | **部分** | 券均 USED，标题全文可见；无未使用券，**BUG-004** 截断难再复现 |
| 消费者会员中心 | **通过** | `/pages/member/index`：银卡、累计消费 ¥1,306、积分 12、我的券 0 张可用 |

本轮聚焦 P2 复确认 + 消费者券/会员冒烟。**仍不改代码。请发「开始修」。**

---

## 第 31 轮执行明细（2026-08-14 17:33～）

| 用例 | 结果 | 备注 |
|---|---|---|
| API 权限/P1 | **仍开放** | viewer `consistency/run` **200** failCount=2 → **BUG-008**；`fund/daily-bills/export` **200 CSV** → **BUG-009**；`sku-review/run` **500** → **BUG-011**；`expiry/.../create-replenishment` **500** → **BUG-014**；`stock-health?dimension=FOOBAR` **6 行** → **BUG-015** |
| 财务提现 API | **仍开放** | `13800138004` withdraw → **403** → **BUG-016** |
| 商户经营分析 Browser | **有缺陷** | AI 洞察仍 `NORMAL`/`SLOW_MOVER`/`NO_SALES` → **BUG-020** |
| 运营 OTA Browser | **有缺陷** | `stable` / `PUBLISHED` → **BUG-021** |
| 维修工单 Browser | **有缺陷** | ID 升序 `1,2,5…` → **BUG-022**；CLOSED「未知」→ **BUG-023** |
| 消费者订单展示 | **通过** | 纯数字单号、无 `@` 批次码（H5 热更新；运营 Docker 包未 rebuild 时订单页仍可能见旧十六进制） |

环境：JWT 曾过期，本轮经 captcha+redis 刷新 admin/viewer；财务 withdraw 403 再确认。**仍不改代码。请发「开始修」。**

---

## 第 28 轮执行明细（2026-08-14 15:58～）

| 用例 | 结果 | 备注 |
|---|---|---|
| 设备运维 | **通过（有观察）** | 离线/锁机/无销售事件列表可见；详情英文键 → **OBS-023** |
| 服务时限监控 | **通过（有观察）** | 开门 0/0→100%、在线峰值 0 → **OBS-011** 仍在 |
| 参数配置 | **通过** | 预授权 2000、离线锁机 10、解锁开关 true 等可见 |
| 个人中心 | **通过** | 超管 13900000001、权限 165、主题浅色 |
| P1 复确认 | **仍开放** | BUG-002 建单 `#11`；BUG-008 `run` 200；BUG-011 `sku-review/run` 500；BUG-018 券 `#35` 对 PAID `O97FB…` 核销成功且订单金额不变 |
| 消费者优惠券 | **通过** | 列表仅「已使用」（含 R28 误核销的新人券）；BUG-004 无未使用券本轮难复现布局 |

环境快照：viewer 探针工单 `#9/#10/#11` OPEN；券 `#34/#35` 均 USED 挂同一 PAID 单；**仍不改代码。建议下一指令「开始修」。**

---

## 第 27 轮执行明细（2026-08-14 15:48～）

| 用例 | 结果 | 备注 |
|---|---|---|
| 固件版本 OTA | **有缺陷** | Browser：v1.0.0 / `stable` / **`PUBLISHED`** → **BUG-021**；可下架 |
| 识别映射 | **通过** | 24 条端侧类名→SKU；入驻状态「生产」；阿里云类目映射 3 条 |
| 录像上传队列 | **通过** | 空队列「暂无待上传录像」 |
| 告警规则 | **通过** | 15 条规则；输入框可见值（如离线锁机 10、稳定解锁 15、SLA 48）；开关 `auto_unlock=true` |
| 识别入驻 | **通过** | 可乐/苹果/雪碧等「生产」在售；端侧类名正常 |
| 三端冒烟收口 | **通过** | 运营工作台（在售 1/停售 1）；商户工作台（待办 1/临期）；消费者首页「开门购物」+「我的」¥151 可开门 |

环境快照：消费者 ¥151；CAB-OTHER 仍离线停售（OBS-013/019）；**仍不改代码**。

---

## 第 26 轮执行明细（2026-08-14 15:38～）

| 用例 | 结果 | 备注 |
|---|---|---|
| 商户要货申请 | **通过** | Browser 发起页 6 SKU；「我的申请」见 #36/#34/#1 待审核；API 建单 `requestId=36`（矿泉水×1）SUBMITTED |
| 商户待办 | **通过** | 临期 1：SKU-SNACK-001 · NEAR_EXPIRY · CAB-001 |
| 商户消息中心 | **通过** | 补货任务通知 #16/#10/#8…可见 |
| 商户公告 | **通过** | 「全量测试公告-商户可见」 |
| 商户分账明细 | **通过** | 「全部」含待分账/已冲正多笔；失败 Tab 空态正常 |
| 商户我的 | **通过（有说明）** | 菜单齐全；微信订阅提示「未配置订阅模板」——演示 H5 预期，非产品缺陷 |
| 运营仓库 | **通过** | 仓库概览 WH-DEMO-001；批次库存 7 条（含 R16 水批次） |
| 运营定时任务 | **通过** | 多任务近期 SUCCESS（补偿/一致性/离线巡检等） |
| 运营库存健康 | **通过（有观察）** | 临期行 1；容量 0 → **OBS-022**；BUG-014/015 未本轮复测写路径 |

环境快照：要货 #36 待审核；OPEN 争议 0；**仍不改代码**。

---

## 第 25 轮执行明细（2026-08-14 15:23～）

| 用例 | 结果 | 备注 |
|---|---|---|
| 商户财务工作台 | **通过（有观察）** | Browser「商户财务」；仍有「扫码到柜」CTA → **OBS-015**；经营工具含结算/争议/经营分析 |
| 财务钱包 | **有缺陷** | 可用 **¥650.00**；文案「可自主提现」+「申请提现」仍在；`POST .../withdraw` → **403** → **BUG-016** 再确认 |
| 财务结算/订单/争议 | **通过（有观察）** | 结算：区间营收 ¥11 / 待分账 ¥17.55 → **OBS-016**；订单列表多笔 PAID/退款可见；争议「暂无待处理」 |
| 店长工作台/团队 | **通过** | Browser「演示店长」；团队 5 人；`wallet` API **403**（符合） |
| 店长点位定价 | **通过** | 只读提示正确；6 SKU 基准价可见 |
| 店长经营分析 | **有缺陷** | 毛利/TOP/柜机报表正常；AI 洞察档位英文枚举 → **BUG-020** |
| 消费者我的/订单 | **通过** | Browser 可用 **¥151.00**、可开门；订单页见 PAID/退款；开门冒烟后顶栏「账单待人工确认」 |
| 消费者开门冒烟 | **观察** | `POST /sessions` → `S368E…` → `demo-close` → **DISPUTED**（`reviewCode=MOCK`）→ **OBS-012**；测后 `action=WAIVE` 结案 |

环境快照：商户钱包 ¥650；消费者 ¥151；OPEN 争议 0；**仍不改代码**。

---

## 第 24 轮执行明细（2026-08-14 15:14～ / 中断后续）

| 用例 | 结果 | 备注 |
|---|---|---|
| viewer 维修工单 | **有缺陷** | Browser「只读演示」页可见「**新建工单**」；API `POST .../repair-tickets` → **200** → `#9`/`#10` OPEN（`created_by=100000010`）→ **BUG-002** |
| viewer 数据一致性 | **有缺陷** | Browser 可见「立即巡检」+ FAIL 行「修复」；`POST .../consistency/run` → **200** `failCount=2` → **BUG-008**（未点修复） |
| viewer 资金导出 | **有缺陷** | `GET .../fund/daily-bills/export` → **200** `text/csv` → **BUG-009** |
| 商户店员 H5 | **通过** | Browser「商户店员」工作台；待办 1 / 临期 SKU；团队页见 5 人（含本人店员）；`wallet` API **403**（符合） |
| 商户补货员 H5 | **通过（有观察/缺陷）** | Browser「演示补货员」；经营工具收敛（要货/柜机概况）；补货任务：待处理 0 / 已完成 10 / 今日完成率 0%；`dist=1209196m` → **OBS-021**；柜机列表 `CAB-001-` → **BUG-005**；`wallet` **403** |

环境快照：viewer 探针工单 `#9`/`#10` 仍 OPEN；一致性 FAIL 2（库存汇总 + 券发放）；**仍不改代码**。

---

## 第 23 轮执行明细（2026-08-14 15:09～）

| 用例 | 结果 | 备注 |
|---|---|---|
| 商户补货任务 | **通过（有观察）** | Browser：待处理 0 / 已完成 10 / 今日完成率 100%；任务 #16 等可见；`dist=1209196m` → **OBS-021** |
| 商户柜机列表 | **通过（有缺陷）** | 在线 1；显示 `CAB-001-` → **BUG-005** 仍在 |
| 柜机详情 | **通过** | `?id=CAB-001`：在线、货道 A1～B4、动销；货道「只读」。误用 `deviceId=` 参数会「柜机不存在」（参数名应为 `id`） |
| 线长钱包 | **通过（有缺陷）** | Browser 可用 **¥99.35**；提现 R9 已打款；佣金流水显示「**未知**」→ **BUG-010** 再确认 |
| OBS-020 反馈 XSS | **确认无当前渲染面** | 回复弹窗仅 `content`；CSV 不含 contactInfo；无 v-html |

环境快照：线长 ¥99.35；商户钱包 ¥650；**仍不改代码**。

---

## 第 22 轮执行明细（2026-08-14 14:58～）

| 用例 | 结果 | 备注 |
|---|---|---|
| 商户 H5 登录 | **通过** | 内置 Browser：`13800138001` / `123456` → `/pages/home/home` |
| 商户工作台 | **通过** | 默认直营商户；待办 1；临期 SKU-SNACK-001；在线柜 1/1 |
| 商户钱包 | **通过** | Browser 可用 **¥650.00**；可申请提现；最近提现含 R11 已打款/已驳回；与 API `availableCents=65000` 一致 |
| 结算对账 | **通过（有观察）** | 区间营收 ¥11 / 商户所得 ¥9.90；「待分账」¥17.55 仍为全局 overview → **OBS-016** |
| 手机验证登记 | **通过** | Browser「登记验证」保存成功；列表见 `#1/#2`（13800138000 / 微信 / MCH-DEFAULT）；API total=2 |

环境快照：商户钱包 ¥650；手机验证流水 2 条；OPEN 争议 0；**仍不改代码**。

---

## 第 21 轮执行明细（2026-08-14 14:38～）

| 用例 | 结果 | 备注 |
|---|---|---|
| OPEN 争议清理 | **通过** | 4 张 OPEN（含 R20 `DEFCE…` MOCK + gravity mismatch）全部 **WAIVE** → OPEN=**0** |
| 用户余额 Browser | **通过** | `/admin/users`：`10001` **¥151.00** / 白银 / 已实名；与 API/DB `15100` 一致 |
| 消费者 H5「我的」 | **通过** | 内置 Browser：可用 **¥151.00**、已实名、可开门 |
| 会员等级规则 | **通过** | Browser `/admin/member-levels`：NORMAL/SILVER/GOLD/PLATINUM 四档启用；API 4 条一致。`10001` total_spent=1305.66 → 银卡符合规则 |
| 积分兑换管理 | **通过** | Browser `/admin/points-redeem`：3 项启用（100/300/800 分）；已兑均为 0（与 OBS-018 演示分不足一致） |
| 手机验证 | **通过（空数据）** | Browser `/admin/phone-verify`「暂无验证记录」；API total=0；可「登记验证」 |
| 用户反馈 | **通过** | Browser 列表 12 条；#12 回复后 **已回复**；API reply OK。UI 首次提交曾短暂卡住后关闭。contactInfo XSS → OBS-020 |
| 消息记录 | **通过** | Browser `/admin/notifications` 可见充值成功（R19 ¥1）、补货任务、订单支付等站内信 |

环境快照：消费者 `balance=15100 frozen=0`；OPEN 争议 **0**；券 #35 UNUSED；**仍不改代码**。

---

## 第 20 轮执行明细（2026-08-14 12:08～ / 中断后续）

| 用例 | 结果 | 备注 |
|---|---|---|
| 开门结算自动选券 | **阻断（OBS-012）** | 会话 `S5FDC…` / `S42FAF…`：关门 → **DISPUTED**（MOCK）；附 cart+gravity 仍进审；券 **#35 UNUSED**。代码路径：`SettlementService.finalizeOrder` → `selectBestCoupon`+`markUsed` 仅在成功 PAID；争议结案 `DisputeService` **不走**自动选券。对比 BUG-018 手动词核销无法本轮 E2E 打通。**（2026-08-16 已解除：见 OBS-012 自动选券行）** |
| 充值管理 Browser | **通过** | `/admin/recharges` 共 **6** 条，均用户 `10001` / 微信 / 已支付；含 R19 `R39F5BB2903C54534` ¥1.00（11:49） |
| 用户余额对齐 | **通过（API+DB）** | `user_id=10001`：API `balanceCents=15100`、DB `user_account=15100/frozen=0`；充值累计与余额口径一致（历史多笔充值后净额 ¥151）。Browser「用户余额」本轮未完整截图；**R21** Browser 已补齐 ¥151.00 |
| 消费者券状态 | **通过** | UNUSED 仅 #35（新人立减¥2）；#34 仍为 R19 BUG-018 误核销 USED |

环境快照：消费者 `balance=15100 frozen=0`；OPEN 争议约 4；券 #35 UNUSED；**仍不改代码**。

---

## 第 19 轮执行明细（2026-08-14 11:48～ / 中断后续）

| 用例 | 结果 | 备注 |
|---|---|---|
| 维修工单状态机 | **通过** | OPEN→DONE **409**；OPEN→IN_PROGRESS→DONE OK（#5/#6/#7）；Browser `/repair-tickets` 可见已完成 |
| 工单解锁设备 | **通过（有观察）** | #8 CAB-OTHER DONE + `unlockDevice=true` → 当时解锁；随后 presence 重锁 → OBS-019 |
| CAB-OTHER 离线锁 | **观察** | 工作台「在售 1 / 停售 1」「待处理异常 1」；与 OBS-019 一致 |
| 消费者充值 | **通过** | `prepay` ¥1 + `mock-success` → PAID；余额 15000→**15100**；Browser 充值页 ¥151.00 + 11:49 记录 |
| 优惠券列表 UI | **通过（有缺陷）** | Browser「未使用」新人立减¥2；「已使用」含被误核销的¥5 券；BUG-004 截断仍在 |
| 优惠券核销 API | **失败 BUG-018/019** | 已付订单可核销不改价；假订单 500 FK |

---

## 第 18 轮执行明细（2026-08-14 11:33～）

| 用例 | 结果 | 备注 |
|---|---|---|
| 异常中心离线锁机 | **有缺陷** | Browser 待处理 0；resolve EX931→RESOLVED；**不解锁** → BUG-017；policy 人工解锁 OK；auto-unlock 被维修工单挡住 |
| 采购退货 | **通过** | `POST .../purchase-returns` returnId=3（PO#4 退 1）；批次 `B-R17-COLA` 3→2；Browser「采购退货」可见 #3 |
| 应付付款 | **通过** | 退货后应付 570→380；`/payables/1/pay` 380→**PAID**；Browser 应付账款行「已付 ¥3.80」 |
| 在途签收闭环 | **通过** | 盘点 A1 腾空→plan route#16/OB#10→pick→ship→4 条 IN_TRANSIT→task check-in/complete→在途清空；A1/A2/B1 book=8 |
| 消费者会员中心 | **通过** | Browser 登录后银卡、累计 ¥1,306、积分 12、等级说明 |
| 积分兑换页 | **通过（有观察）** | 三档券可见；API 积分不足 409；OBS-018 |
| 消息中心 | **通过** | 充值/支付通知列表 + 通知偏好开关 |

---

## 第 17 轮执行明细（2026-08-14 11:21～）

| 用例 | 结果 | 备注 |
|---|---|---|
| 店长权限矩阵 | **通过** | `13800138006`：devices/orders/disputes/replenishment/settlements/team/inventory/slots **200**；wallet **403**（符合无 `wallet:view`） |
| 店长结算/分账 UI | **通过（有观察）** | Browser 登录「演示店长」；`/settlements` 汇总/按日/批次正常；日期完整（BUG-006 H5 仍缓解）；「全部」分账明细可见 ACCRUED/VOIDED；见 OBS-016 |
| 补货员权限矩阵 | **通过** | `13800138007`：devices/replenishment/inventory/slots **200**；orders/disputes/settlements/wallet/team **403** |
| 补货员工作台/我的 | **通过（有缺陷）** | Browser「演示补货员」；经营工具无结算/钱包入口；要货申请 API `POST .../replenishment/requests` → `requestId=34` SUBMITTED；见 **BUG-017** 待办仍显示离线停售 |
| 采购单创建+收货 | **通过** | `POST .../purchase-orders` → PO#4 CREATED；`/receive` → RECEIVED；批次 `B-R17-COLA-112319` qty=3；Browser 采购单筛 `R17-PO` 可见「已收货」 |
| 出库拣货/发运 | **部分通过** | OB#2 pick→PICKED OK；ship 因货道满 **400**（OBS-017）；cancel-unreceived→CANCELLED OK |

---

## 第 16 轮执行明细（2026-08-14 10:01～）

| 用例 | 结果 | 备注 |
|---|---|---|
| 商户店员权限矩阵 | **通过** | `13800138002`：devices/orders/disputes/replenishment/settlements/slots **200**；wallet/line-wallet **403**；Browser 钱包页「无权限」 |
| 商户财务权限矩阵 | **有缺陷** | `13800138004`：wallet/settlements/orders **200**；replenishment/team/inventory/slots/pricing **403**；提现 API 403 但 UI 仍可点 → **BUG-016**；OBS-015 |
| 仓库其他入库 | **通过** | `POST .../warehouse/inbound` 批次 `B-R16-WATER` qty=2；Browser「批次库存」可见第 7 行 |
| 货道盘点 | **通过** | `POST .../devices/CAB-001/slots/stocktake` B1 physical=7；discrepancies 9→8；UI 货道陈列 B1 账面/实盘 7/8 |
| 设备货道陈列 UI | **通过** | `/admin/devices/CAB-001`「货道陈列」A/B 行有 SKU，C 行未配置；填充率 96% |

---

## 第 15 轮执行明细（2026-08-14 09:40～，重启后）

| 用例 | 结果 | 备注 |
|---|---|---|
| 环境重启 | **通过** | Docker 全栈已起；`pnpm dev:consumer-h5` `:3002`、`dev:merchant-h5` `:3001`；gateway captcha/trade health OK；超管重登 |
| 运营大屏 | **通过（有观察）** | `/admin/big-screen` KPI/趋势可加载；见 OBS-013（在售 0 / 锁机 2） |
| 投放地图 | **通过（有观察）** | `/admin/device-map` Leaflet 落点 2；API `map-points` 200；见 OBS-014 同坐标簇 |
| 库存健康列表 | **通过** | 造数临期 lot 后 UI「临期行 1」可见薯片 2026-08-16；ALL 含临期 |
| 临期报损 | **通过** | `POST .../inventory/write-off` → `writeOffId=1`，lot qty 8→7，成本 360 |
| 临期下架任务 | **失败 BUG-014** | ensure OK；create-replenishment **500** rollback-only；Browser「下架」同源 |
| 非法维度 | **BUG-015** | API 探活确认未知 dimension 泄漏 |

---

## 第 14 轮执行明细（2026-08-13 17:55～）

| 用例 | 结果 | 备注 |
|---|---|---|
| 用户分析 | **有缺陷** | `/admin/user-analysis` KPI 正常（活跃1/复购率100%/营收¥30）；**BUG-012** 复购累计消费 ¥0.00 |
| 客流坪效 | **通过** | `/admin/footfall`：开门 32、支付 3、转化 9.4%、客单 ¥3.67；柜机排行 CAB-001 有数 |
| 设备报表 | **通过** | `/admin/reports`：设备 2、离线 1、累计营收 ¥30 / 今日 ¥4；表见 CAB-001 ONLINE |
| 销售报表 | **有缺陷** | `/admin/sales-reports` 商品维当日雪碧有销量；**BUG-013** 订单数=0。四维 API 均 200 |
| 数据分析 | **通过** | `/admin/analytics`：今日营收 ¥4、订单 1、开门/识别成功率 100%；趋势图可切 |
| 设备可用性 | **通过** | `/admin/device-kpi` + API `device-availability-kpi`：离线事件1、自动锁机2、人工介入率0% |
| 消费者开门→支付 | **受限 OBS-012** | H5 登录后开门/API 建会均落 MOCK 争议；无法验证自动扣款 PAID |

---

## 第 13 轮执行明细（2026-08-13 17:47～）

| 用例 | 结果 | 备注 |
|---|---|---|
| 服务时限监控 | **通过（有观察）** | `/admin/sla` 指标卡与实时表可加载；见 OBS-011 |
| 补货员效率 | **通过** | `/admin/replenishment-staff`：商户测试 8/8、运营测试 1/1，完成率 100%；API `replenishment-report/staff` 200 |
| 选品诊断 | **失败 BUG-011** | 页可开；「运行诊断」→ API **500 NPE**；GET list 空数组 |
| 识别入驻 | **通过** | UI `/sku-vision` 列表正常；`SKU-APPLE-001` enroll(`apple`)→advance→**PRODUCTION**（mappingEffective=true）；管线仍 `WAITING_EDGE_PROVIDER`（预期说明） |
| 组织设备归属 | **通过** | `PUT .../org/nodes/1/devices` 绑定 `CAB-001`；UI 组织树可见操作按钮 |
| 场地合同 | **通过** | 新建 `contractId=1` 点位「R13测试点位」/ 月费 ¥5000 / ACTIVE；Browser「场地合同」Tab 可见 |

---

## 第 11 轮执行明细（2026-08-13 16:20～17:25）

| 用例 | 结果 | 备注 |
|---|---|---|
| 商户钱包提现（阈值下自动打款） | **通过** | 调账后提现；低于阈值 Mock 直接 PAID |
| 商户提现审核驳回 | **通过** | `requestId=4` → **已驳回**；Browser「提现审核」可见 |
| 商户提现审核通过 | **通过** | `requestId=5` → **已打款** ¥500 |
| 投放计划 UI 新建选素材 | **部分通过** | 对话框可开、素材下拉可见 `R10测试素材`；Browser 点选 el-select 选项不稳定，API 建 `campaignId=2` 并 launch→RUNNING；列表 UI 名 `R11-UI投放计划` 正常 |
| 审计日志 | **通过** | `/admin/audit` 列表有数据（操作人/动作中文正常）；API `audit-logs`/`recent` 200 |
| 参数配置 | **通过** | `/admin/system-configs` 键值表可见（含 `checkout.preauth_cents=2000` 等） |
| 告警规则 | **通过** | `/admin/alert-rules` 与参数同源编辑 UI（离线锁机/争议 SLA/webhook/卡点扫描） |
| viewer 鉴权抽检（OBS-008） | **有缺陷** | 订单 list **200**；退款/审计/参数/RBAC/商户提现审核 **403**（正确）；`consistency/run` **200**、repair 建单 **200**、fund export **200** → 再证实 BUG-002/008/009 |

---

## 第 12 轮执行明细（2026-08-13 17:38～）

| 用例 | 结果 | 备注 |
|---|---|---|
| 定时任务列表 | **通过** | `/admin/scheduled-tasks`：补偿/券过期/一致性/设备/争议 SLA 等任务均启用；API `GET .../scheduled-tasks` 200 |
| 组织与点位 | **通过** | 初始空树；UI「新增顶级组织」`R12测试组织` 落库 `nodeId=1`；场地合同仍空（种子无合同） |
| 通知公告 | **通过** | `/admin/announcements`：`全量测试公告-商户可见` **已发布**；商户 H5 工作台可见同标题公告条 |
| 固件版本 OTA | **通过** | `/admin/ota`：`1.0.0` / `stable` / `PUBLISHED` / 灰度 100%；API `ota/releases` 200 |
| 录像上传队列 | **通过** | `/admin/upload-queue` 空队列文案正常（当前无滞留会话） |
| 识别映射 | **通过** | `/admin/vision-mappings`：YOLO 类名→SKU（`bottle`/`can`/…）生产/草稿态与白名单标签可见；阿里云映射区空 |
| 商户提现后余额对齐 | **通过** | DB `balance=65000`；后台钱包 **¥650.00**；商户 H5 **可用 ¥650.00**；流水含 `WITHDRAW_PAID(-50000)`/`WITHDRAW_RELEASE`/`ADJUST` 与 R11 审核一致 |

---

## 多角色菜单矩阵（第 2 轮）

| 角色 | 账号 | 可见分组 | 关键可见菜单 | 深链拒绝 | 结果 |
|---|---|---|---|---|---|
| 超管 | 13900000001 | 全部 7 组 | 全量含系统/公告/营销 | — | 通过 |
| 财务 | 13900000002 | 概览/设备商品/财务商户/增长风控 | 财务毛利、销售报表、资金账单、商户分账/提现、对账、数据一致性等；**无**交易履约/系统 | `/orders`、`/menus` → 回退工作台；`/finance` 可进 | **通过** |
| 运营 | 13900000003 | 含交易履约/设备/仓储(部分)/增长；系统仅「组织与点位」 | 订单/争议/异常/设备/识别/素材投放/会员积分等；**无**补货调度/仓库/财务毛利/角色菜单 | `/menus`、`/replenishment` → 回退；`/disputes` 可进 | **通过** |
| 补货 | 13900000004 | 仅概览+履约仓储 | 库存健康、补货调度、仓库、补货员效率；登录后默认落 `/replenishment?tab=routes` | — | **通过** |
| 只读 | 13900000005 | 裁剪后业务只读菜单 | 见第 1 轮；BUG-002 写入口残留 | `/menus`、`/announcements` 回退 | 部分失败 |

### 商户子账号权限（第 3 轮 API）

| 角色 | 账号 | 钱包 | 设备 | 补货任务 | 结果 |
|---|---|---|---|---|---|
| 商户财务 | 13800138004 | **可读** `/merchant/wallet` | — | **403** | **通过**（财务只读钱包、无履约） |
| 商户店员 | 13800138002 | **403** | **可读** devices | — | **通过**（店员无钱包） |

---

## 用例执行明细

### A. 运营后台

#### A1 登录与鉴权 / A2 菜单与权限

（第 1～2 轮结论不变；A2-06 仍失败，根因见 BUG-002。）

#### A3 通知公告 / 营销 / 优惠券 / 系统

| 用例 | 类型 | 结果 | 备注 |
|---|---|---|---|
| A3-01~03 公告列表/校验/发布 | 混合 | **通过** | |
| A3-04 营销活动页 / 启用 / 新建 | 正常 | **部分通过** | 第 3 轮启用「积分兑好礼」。第 4 轮 API 建 `#34`。第 5～6 轮 UI 新建（R5/「R6 UI满减测试」）均因 OBS-004 未落库；R6 校验「请选择活动时间」通过 |
| A3-05 优惠券创建→启用→发券 | 正常 | **通过** | 新建「全量测试满减券-5元」`coupon_def_id=37` ACTIVE；发给 `userId=10001` |
| A3-06 菜单管理 | 正常 | **通过** | |
| A3-07 空券名校验 | 异常 | **通过** | 「请填写名称」 |
| A3-08 活动效果分析 | 正常 | **通过（有观察）** | 第 6 轮 Browser：近 30 天仅 2 行（新客开门礼类型「未知」、已用 0.00/核销率 100%；夏日冰饮满减周）。API/代码根因见 OBS-006 |

#### A4 业务主流程

| 用例 | 类型 | 结果 | 备注 |
|---|---|---|---|
| A4-01~05 | — | **通过** | 见第 2 轮 |
| A4-06 报修异常→建工单结案 | 正常 | **通过** | 异常「消费者设备报修」→「建工单结案」→ `repair_ticket#5` OPEN；异常 RESOLVED |
| A4-07 争议 CONFIRM→PAID | 正常 | **条件通过** | 第 4/6 轮可用=0 → BUG-007。第 5 轮释放冻结后 CONFIRM→订单 `O97FB096C48F64198` **PAID**、余额 15300→14900。R6 新票 `D8A2988F16E6642A3` 故意留 OPEN 供余额不足路径复现 |
| A4-08 争议 FREE/WAIVE | 正常 | **通过** | 第 7 轮：`D8A2988F16E6642A3` → `resolutionType=FREE` → 映射 **WAIVE**，消息「已免单，无需扣款」；票 **RESOLVED**；会话 **COMPLETED**；关联异常 **RESOLVED**。UI 结案需勾选「已对照录像核对」（无录像时先勾「无录像仍结案」） |
| A4-09 财务毛利页 | 正常 | **通过** | 第 7 轮 Browser：今日营收 **¥4.00**、今日订单 **1**；API `/finance/stats` 一致 |
| A4-10 异常中心 | 正常 | **通过** | 第 7 轮：待处理 **0**；已解决 **59**（含 `EX95E8E60EA25446EC9D`） |

### B. 消费者小程序 H5

| 用例 | 类型 | 结果 | 备注 |
|---|---|---|---|
| B1-01~13 | — | 见第 1～2 轮 | B1-07 失败 BUG-001；B1-08 布局 BUG-004 |
| B1-14 关门结算环路 | 正常 | **部分通过** | 第 3 轮开门→关门落 DISPUTED。第 5 轮人工 CONFIRM 在余额可用时闭环 **PAID**（见 A4-07） |
| B1-15 新发券生效 | 正常 | **通过** | 「我的优惠券」可见「全量测试满减券-5元」 |
| B1-16 报修提交联动 | 正常 | **通过** | 见第 3 轮 |
| B1-17 PAID 订单可见性 | 正常 | **通过** | 第 6 轮登录后「我的订单」：全部 9；置顶 `O97FB0…4198` **已支付** ¥4.00（余额）；顶部「需要关注」可见 R6 待审争议「账单待人工确认」；toast「审核完成，已扣款 ¥4.00」（历史 session 提示） |
| B1-18 我的余额展示 | 异常 | **失败（BUG-001 造数态）** | R6 造数 `frozen=20000` 时 UI：可用 ¥0 / 冻结 ¥200 / 总 ¥149，且仍显示「可开门」；测后已恢复 `frozen=0` |
| B1-19 模拟充值 | 正常 | **通过** | 第 7 轮：`prepay(channel=WECHAT,¥1)` → `mock-success` 订单 `R0C3C16D93E7342BF` **PAID**；余额 14900→**15000** |
| B1-20 消息中心 | 正常 | **通过** | Browser 见「充值成功」¥1（关联 `R0C3…`）与「订单支付成功」¥4（`O97FB0…`） |
| B1-21 会员/积分 | 正常 | **通过** | API：银卡、可用积分 **12**；兑换商品列表可读 |
| B1-22 热门活动 | 正常 | **通过** | Browser：轮播「夏日冰饮满减周」、我的优惠券「1 张可用」、「新客开门礼」去领券入口 |
| B1-23 积分不足兑换 | 异常 | **通过** | `POST /member/redeem` itemId=3 → **409**「积分不足」 |

### C. 商户小程序 H5

| 用例 | 类型 | 结果 | 备注 |
|---|---|---|---|
| C1-01~10 | — | 见第 2 轮 | |
| C1-11 补货现场流程 | 正常 | **通过** | 第 4 轮种子 task#9。第 5 轮：**真实账面缺口**（`device_sku_lot` A2 qty 8→1）→ `plan` route#10/task#10+outbound#9 → pick→ship → 签到→开门→RESTOCK×7→完成；H5 见 task#10「已完成」 |
| C1-12 财务/店员权限 | 权限 | **通过** | 见第 3 轮 |
| C1-13 提现超限 | 异常 | **通过** | 见第 4 轮 |
| C1-14 结算日期显示 | 边界 | **H5 通过（BUG-006 跟踪）** | 第 7 轮 a11y：`2026-08-06`～`2026-08-13`；结算 overview/daily API 正常 |
| C1-15 团队成员 | 正常 | **通过** | API 5 人：管理员/店员/财务/店长/补货员均为 ACTIVE |
| C1-16 商户公告 | 正常 | **通过** | 可见「全量测试公告-商户可见」 |

---

## 跨端一致性观察

1. **公告联动正常**（运营发布 → 消费者列表 / 商户首页条）。
2. **设备范围隔离正常**（超管多柜 vs 商户仅 CAB-001）。
3. **充值消息联动正常**（模拟充值 → 消息中心「充值成功」+ 余额增加）。
4. **优惠券联动正常**（运营创建发券 → 消费者券包可见）。
5. **报修联动正常**（消费者报修 → 异常中心 → 建工单结案）。
6. **账户冻结异常仍在**（历史 COMPLETED+FROZEN 会话未释放）。
7. vision mock / DISPUTED 属环境预期；**余额可用时 CONFIRM→PAID 已验证**；余额不足时见 BUG-007；**FREE/WAIVE 免单结案已验证**。
8. **补货规划缺口**依赖货道 **lot 账面**（`loadBookQtyBySlot`），改 lot 数量后 `plan` 可出 outbound 明细。

---

### 第 8 轮（2026-08-13 15:00+）

| 项 | 结果 | 备注 |
|---|---|---|
| BUG-002 UI+API | **失败加剧** | 只读进 `CAB-001` 见「新建工单」；API 建单成功 `ticket_id=6` |
| BUG-008 一致性 | **新缺陷** | 只读可见「立即巡检」；`run`/`fix` API 成功；误修 `recordId=5` |
| 资金账单 | **通过** | `/admin/fund-bills` 日账单+账务明细可开；API ledger total=12 |
| 用户余额 | **通过** | 查 `13800138000` → ¥150.00 / 白银；只读无调账按钮 |
| 对账页 | **跳过/无权限** | 只读 `GET /reconciliation` → **403**；深链回工作台属预期 |
| 数据一致性页 | **有缺陷** | 页可开，FAIL 含券发放/订单金额；写能力见 BUG-008 |
| vision→PAID | **未打通** | demo-close 仍落 **DISPUTED**（`D5EAFA733C0164B80` OPEN）；mock 无成功识别直达 PAID |
| 线长钱包 | **通过（OBS-007）** | ¥100.35 可提现；流水 COMMISSION 显示「未知」 |
| 分账明细 | **通过** | 默认「失败」空；「全部」见待分账/已冲正列表（含当日单） |
| BUG-003 | **再确认** | 只读工作台待办仍「处理」 |

环境快照：消费者 `balance=15000 frozen=0`；待审争议 1；只读误建工单 6 仍 OPEN。

---

### 第 9 轮（2026-08-13 15:10+）

| 项 | 结果 | 备注 |
|---|---|---|
| 超管对账 | **通过** | `/admin/reconciliation`：微信 `2026-08-05` 已平账、差异 0；有「执行对账/导出」 |
| 超管风控 | **通过** | `/admin/risk` 风险事件 Tab 可开；API total=31；只读导风险事件 **403**（正确） |
| 充值管理 | **通过** | `/admin/recharges` 共 5 条，用户 `10001`，渠道微信，状态已支付 |
| 投放计划 | **通过（空数据）** | `/admin/ad-campaigns` 可开、「新建投放」可见，表「暂无数据」 |
| 只读写权限审计 | **失败项扩容** | `viewer` 写类权限：`consistency:run/fix`、`repair:edit`、`fund:export`、`stock-health:export` → **BUG-009** |
| 争议 D5EAFA… | **通过** | 超管 API `FREE` → `WAIVE`；票 **RESOLVED**；会话 `S4AD8D0B…` **COMPLETED**；OPEN 列表空 |
| 线长提现正向 | **通过** | API ¥1 → `PAID` Mock；H5 余额 **¥99.35**，最近提现「已打款」`R9-27d983c14b7e` |
| 商户子账号线长钱包 | **通过（拒绝）** | 店员 `02` / 财务 `04` / 店长 `06` → **403** |
| 最低提现校验 | **通过** | `amountCents=1` → `400 最低提现 1.0 元` |

环境快照：消费者 `balance=15000 frozen=0`；线长可用 **9935**；OPEN 争议 **0**。

---

### 第 10 轮（2026-08-13 16:00+）

| 项 | 结果 | 备注 |
|---|---|---|
| 素材库上传 | **通过** | UI「上传素材」表单可开；API multipart 上传 `assetId=1` ACTIVE（MinIO） |
| 投放计划创建/上线 | **通过** | API 建 `campaignId=1` → launch **RUNNING**；Browser 表见「投放中 / 全部设备 / 1 个素材」。名称乱码 `R10????` 为本轮 PowerShell JSON 编码造数问题，非产品缺陷 |
| 超管线长钱包 | **通过** | `/line-managers`：成员余额 ¥99.35；提现审核见 `R9-27d983…` **已打款** MOCK |
| 商户提现 | **通过** | `/merchant-withdraw`：MCH-DEFAULT 可用 ¥500.01；MCH-OTHER ¥0；提现审核 Tab 存在 |
| 角色 viewer | **有缺陷/观察** | 搜 `viewer`→分配权限对话框可开；API/DB 写权限与 BUG-002/008/009 一致；根 `ops` → OBS-008 |
| 字典 wallet | **BUG-010** | 仅有 `wallet_entry_type`；H5 误用 `wallet_ledger_type`；UI 搜 wallet 可见类型（OBS-009） |

---

## 仍建议下一轮

1. **请发「开始修」** — 优先 P1（001/002/007/008/011/014/018）  
2. P2/P3：BUG-003/009/010/012/013/016/017/019/020～023…  
3. 清理测后脏数据：viewer 工单 `#9～#13`、要货 `#34/#36`、券 `#34/#35/#37/#38`（`#38` 已在 R40 误核销为 USED）  
4. 运营 Docker admin rebuild：纯数字单号 + **OBS-024** 大屏耗时文案  
5. CAB-001 ONLINE+锁机 与 BUG-017/OBS-013 一并处理

### 尚未充分测试 / 缺口（R58 盘点）

| 类别 | 缺口 | 说明 |
|---|---|---|
| **演示结算** | 开门→demo-close→PAID（mock） | **OBS-012 已修**：mock+重力 / demo-close 注入演示取货 → PAID；真实视觉精度仍需生产模型 |
| **端形态** | 微信/支付宝**原生**小程序 | 全程仅 H5；原生能力未测 |
| **支付通道** | 微信/支付宝**真沙箱**（非 Mock） | R58 Mock 充值已通；真通道未接 |
| **安全/合规专项** | 越权矩阵全量、审计完整性、2FA 强制流 | 仅抽样 viewer 写权限；2FA 挑战流未完整走 |
| **并发/压测** | 同柜多开、库存竞态、限流 | **未做** |
| **营销 UI** | datetime **面板点选确认** | R58 强制写值可落库 `#37`；纯面板点选遮挡（历史 OBS-004）未复现 |
| **导入导出** | 部分模块 export API 404 | orders/sessions/risk OK；user-analysis/roi export **404**（或走前端本地） |
| **脏数据清理** | viewer 工单 `#9～#15`、测试券/活动/素材 | 建议修前或修后统一清 |
| **已充分可修** | 开放 23 BUG（P1 优先） | 001/002/007/008/011/014/018 等；不必再为「找新路径」拖延开修 |

页面主路径与仓库/投放/OTA/签收等写操作多轮已覆盖；**剩余缺口以环境限制 + 开修回归为主**。

---

## 变更记录

| 轮次 | 时间 | 内容 |
|---|---|---|
| 第 1 轮 | 2026-08-13 上午 | 超管/只读/消费者开门订单公告/商户工作台 |
| 第 2 轮 | 2026-08-13 11:18+ | 财务/运营/补货角色矩阵；BUG-001 API 核实；充值/会员/消息/报修；商户结算/争议/补货/团队 |
| 第 3 轮 | 2026-08-13 11:40+ | 优惠券创建发券生效；营销启用；报修→异常→工单；关门环路(DISPUTED)；BUG-001/002 DB 根因；商户子账号权限/补货页 |
| 第 4 轮 | 2026-08-13 12:37+ | 营销活动#34+ROI；商户 task#9 签到开门履约；提现超限 toast/API；争议 CONFIRM→BUG-007；OBS-001/003/006 更新 |
| 第 5 轮 | 2026-08-13 13:00+ | 真实 lot 缺口 plan+pick+ship+task#10 履约；释放冻结后 CONFIRM→PAID；澄清 BUG-007；UI 营销新建仍失败(OBS-004)；ROI API 仅 1/2 |
| 第 6 轮 | 2026-08-13 13:05+ | BUG-007 再复现；OBS-004/006 根因补齐；消费者订单 PAID 可见 + 待审争议；造数余额 UI 复核后恢复 frozen=0 |
| 第 7 轮 | 2026-08-13 14:10+ | FREE/WAIVE 结案；财务/异常中心；充值+消息+会员+活动+积分不足；商户结算日期 H5 缓解跟踪；团队/公告；**仍不改代码** |
| 第 8 轮 | 2026-08-13 15:00+ | BUG-002 写证实；**BUG-008** 一致性误修；资金/用户/线长/分账冒烟；vision→PAID 仍无；OBS-007；**仍不改代码** |
| 第 9 轮 | 2026-08-13 15:10+ | 对账/风控/充值/投放冒烟；**BUG-009** 只读导出；争议 FREE 结案；线长提现 ¥1；子账号 403；**仍不改代码** |
| 第 10 轮 | 2026-08-13 16:00+ | 素材+投放 RUNNING；线长/商户提现后台；角色 viewer；**BUG-010** 字典键；OBS-008/009；**仍不改代码** |
| 第 11 轮 | 2026-08-13 17:20+ | 商户提现驳回/打款；投放 UI+API；审计/参数/告警；viewer 再证实 002/008/009；OBS-010；**仍不改代码** |
| 第 12 轮 | 2026-08-13 17:40+ | 定时任务/组织新建/公告；OTA/录像/识别映射；钱包三端 ¥650 对齐；**仍不改代码** |
| 第 13 轮 | 2026-08-13 17:50+ | SLA/补货员效率；苹果入驻→生产；场地合同+设备归属；**BUG-011** 选品诊断 NPE；OBS-011；**仍不改代码** |
| 第 14 轮 | 2026-08-13 18:00+ | 用户分析/客流/设备·销售报表/数据分析/设备可用性；**BUG-012/013**；开门→MOCK 争议 OBS-012；**仍不改代码** |
| 第 15 轮 | 2026-08-14 09:40+ | 重启后起服务；大屏/地图/库存健康；报损 OK；**BUG-014/015**；OBS-013/014；**仍不改代码** |
| 第 16 轮 | 2026-08-14 10:01+ | 商户店员/财务深路径；仓库入库+货道盘点/陈列；**BUG-016**；OBS-015；**仍不改代码** |
| 第 17 轮 | 2026-08-14 11:21+ | 店长/补货员；结算分账；采购收货+出库拣货；**BUG-017**；OBS-016/017；**仍不改代码** |
| 第 18 轮 | 2026-08-14 11:33+ | 异常 resolve 不解锁；退货/付款/在途闭环；消费者会员/积分/消息；BUG-017 根因；OBS-018；**仍不改代码** |
| 第 19 轮 | 2026-08-14 11:48+ | 维修工单闭环；CAB-OTHER 重锁 OBS-019；充值 OK；**BUG-018/019** 券核销；**仍不改代码** |
| 第 20 轮 | 2026-08-14 12:08+ | 自动选券受 OBS-012 阻断；充值管理 Browser + 余额 API/DB ¥151 对齐；**仍不改代码** |
| 第 21 轮 | 2026-08-14 14:38+ | 用户余额/H5 ¥151 Browser；争议 WAIVE；会员/积分/反馈/消息/手机验证；OBS-020；**仍不改代码** |
| 第 22 轮 | 2026-08-14 14:58+ | 商户 H5 工作台/钱包¥650/结算；手机验证登记 #1/#2；OBS-016；**仍不改代码** |
| 第 23 轮 | 2026-08-14 15:09+ | 补货/柜机/线长钱包 Browser；BUG-005/010 再确认；OBS-020/021；**仍不改代码** |
| 第 24 轮 | 2026-08-14 15:14+ | viewer 写权限 UI/API 再证实（#9/#10、consistency、fund CSV）；店员/补货员 H5；**仍不改代码** |
| 第 25 轮 | 2026-08-14 15:23+ | 财务/店长深路径；消费者开门→OBS-012；**BUG-020** AI 洞察英文枚举；BUG-016 再确认；**仍不改代码** |
| 第 26 轮 | 2026-08-14 15:38+ | 商户要货/待办/消息/公告/分账/我的；运营仓库·批次/定时任务/库存健康；**OBS-022**；**仍不改代码** |
| 第 27 轮 | 2026-08-14 15:48+ | OTA/识别映射/录像/告警/入驻；三端首页冒烟；**BUG-021** OTA 英文状态；**仍不改代码** |
| 第 28 轮 | 2026-08-14 15:58+ | 设备运维/SLA/参数/个人中心；P1 复确认 002/008/011/018；OBS-023；**建议开始修** |
| 第 29 轮 | 2026-08-14 16:07+ | 风控/销售报表/用户分析/选品/组织/线长钱包；BUG-009～015/010/012/013/014 再确认；**请开始修** |
| 第 30 轮 | 2026-08-14 16:22+ | UI 排序审计→**BUG-022/023**；清理三端无意义 `#`/技术备注；**BUG-005 已关**；商户 H5 Browser 验收 |
| 第 31 轮 | 2026-08-14 17:33+ | P1/P2 API 再确认 008/009/011/014/015/016；Browser 复核 020/021/022/023；消费者纯数字单号 OK；**请开始修** |
| 第 32 轮 | 2026-08-14 18:02+ | P2 复确认 BUG-010/012/013/016；消费者券（均 USED）/会员中心通过；**请开始修** |
| 第 33 轮 | 2026-08-15 12:24+ | 内置 Browser：P1 002/008/011/014/018 + 003/022/023/OBS；H5 券/争议/柜机；**请开始修** |
| 第 34 轮 | 2026-08-15 12:38+ | 内置 Browser：010/012/013/016/020/021 + 一致性/锁机；**请开始修** |
| 第 35 轮 | 2026-08-15 12:48+ | 资金/风控/营销/公告/设备运维；BUG-009/019 + OBS-002/006/023；**请开始修** |
| 第 36 轮 | 2026-08-15 12:53+ | 店长结算 OBS-016；消费者订单/消息 OK；手机验证/反馈；**请开始修** |
| 第 37 轮 | 2026-08-15 13:01+ | 仓库/大屏/可用性/地图/补货；P1 008/011/014；OBS-003/013/014；**请开始修** |
| 第 38 轮 | 2026-08-15 13:15+ | 订单 BUG-022；告警/定时任务/对账/券；要货/充值通过；**请开始修** |
| 第 39 轮 | 2026-08-15 13:27+ | 开门记录 BUG-022；录像/SLA OBS-011；消息/团队通过；**请开始修** |
| 第 40 轮 | 2026-08-15 13:44+ | P1 008/011/014/018/019；大屏 OBS-013/024；审计 BUG-022；映射/公告/券；**请开始修** |
| 第 41 轮 | 2026-08-15 14:07+ | P2 009/010/012/013/015/016/020/021/023 + OBS-015；**请开始修** |
| 第 42 轮 | 2026-08-15 14:17+ | viewer BUG-002/003；手机验证/反馈/字典/会员兑换/商户争议；OBS-009/018/020；**请开始修** |
| 第 43 轮 | 2026-08-15 14:26+ | 投放/素材/提现/角色/参数/风控/公告/报修；OBS-002/008/010/025；BUG-017；**请开始修** |
| 第 44 轮 | 2026-08-15 14:35+ | 客流/报表/分析/毛利/商品/入驻/账号菜单；点位定价/意见反馈；补未测清单；**请开始修** |
| 第 45 轮 | 2026-08-15 14:42+ | 积分/等级/线长提现；商户待办·分账·线长钱包；消费者营销/帮助/公告/兑换；**BUG-024**；BUG-010/OBS-018；**请开始修** |
| 第 46 轮 | 2026-08-15 14:49+ | 设备详情深 Tab/打印/手机验证；商户我的·订单详情；协议/开通支付/视频空态；**OBS-026**；**请开始修** |
| 第 47 轮 | 2026-08-15 15:01+ | 三端字体专项→**BUG-025**；财务资金/对账/提现；打印 purchase/labels；导出抽样；**OBS-027**；**请开始修** |
| 字体修复 | 2026-08-15 15:23+ | **BUG-025 已关**：theme/`--el-font-family`、H5 壳层、等宽 mono；admin rebuild + Browser 抽查 |
| 第 48 轮 | 2026-08-15 15:30+ | 运营/补货角色深路径；BUG-008 扩至运营；P1 008/009/011/014/015 再确认；OBS-010 补货乱码；**请开始修** |
| 第 49 轮 | 2026-08-15 15:42+ | 仓库 PO收货→plan→pick→ship；一致性 fix；导出扩样；**OBS-028/029**；OBS-021 再确认；**请开始修** |
| 第 50 轮 | 2026-08-15 15:50+ | 用户反馈缩放错位；仓库/补货分页压表 → **BUG-026**；直表页对照通过 |
| BUG-026 修复 | 2026-08-15 16:04+ | Tabs 自然撑高 + PagePager 隔离；H5 底栏/sticky 加固；admin rebuild；Browser 150% 重叠 0 |
| BUG-026 横滚对齐 | 2026-08-15 16:18+ | Tabs `.table-scroll` 横滚收口 + fit 测宽；Browser 拖横滚 maxAbs=0、无行上大空白 |
| 第 51 轮 | 2026-08-15 16:22+ | 同类型 Tabs/表横滚抽查通过；**BUG-007** 再确认；OTA/营销冒烟；**请开始修** |
| 第 52 轮 | 2026-08-15 16:36+ | OTA 校验/在途/库存健康/营销对话框；**BUG-014** 根因（商户门户鉴权）；008/009/010/015/016/019/024；**请开始修** |
| 第 53 轮 | 2026-08-15 16:47+ | 柜端签收闭环（transit#28 RECEIVED）；**BUG-002/011**；大屏/投放/可用性/手机验证；OBS-013/024；**请开始修** |
| 第 54 轮 | 2026-08-15 16:55+ | OBS-017 货道满；**BUG-018/012/013**；OTA#35 发布；打印/录像/识别入驻；**请开始修** |
| 第 55 轮 | 2026-08-15 17:05+ | 人工 UNLOCK 解 CAB-001；自动解锁仍坏→**BUG-017**；OBS-013 变为 1/1；**BUG-003/016/020/023**；定时任务；**请开始修** |
| 第 56 轮 | 2026-08-15 17:22+ | 营销#36+投放#3 写通；**BUG-002/008/009/011/014/015/018/019/023**；风控/工单/券 H5；**请开始修** |
| 第 57 轮 | 2026-08-15 17:34+ | 素材#2 上传仍预览失败→**OBS-025**；公告#2；**BUG-010/012/013**；ROI 未知；组织/消息/线长钱包；**请开始修** |
| 第 58 轮 | 2026-08-15 17:44+ | 反馈#13+黑名单+Mock充值；营销UI `#37`；OBS-009；导出/帮助；更新未测盘点；**请开始修** |
| 修复复测 | 2026-08-15 19:16+ | trade rebuild；**BUG-011** 真因=stockDays 拆箱 NPE 已修→200；**014** create-replenishment→200；002/008/009/013/015/018/019/012 API OK；001 余额对齐；安全脚本 **pass=31**；UI 项待启用 Browser 点验 |
| UI 复测 + 表头对齐 | 2026-08-15 19:45～20:06 | 去掉 table `width:auto` 错位根因；抽查订单/设备/仓库/工单对齐；关 **003/010/012/016/020～024**；当时 **007/017/004** 仍开放 |
| BUG-007/017 造数复测 | 2026-08-15 20:18+ | Handler 映射余额不足→412；CONFIRM 造数 **412**；`device-auto-unlock` 解 CAB-OTHER；**007/017 已关**；余 **BUG-004** |
| BUG-004 修复复测 | 2026-08-15 20:22+ | 券卡徽章独立行 + 长标题换行；Browser 超长名全文可见不压字；**BUG-004 已关**；缺陷表 BUG 均关闭/缓解 |
| OBS-002/026/027 | 2026-08-15 20:30+ | 405 映射；公告 ARCHIVED→已归档（V179）；商户钱包状态「正常」；Browser+API 复测通过 |
| OBS-006/009/015/028 | 2026-08-15 20:41+ | 字典搜索自动选中；财务隐藏扫码卡；promotion/consistency 字典补项（V180）；ROI「新客」 |
| OBS-011/022/023/029 | 2026-08-15 21:16+ | 空分母开门成功率→「—」；临期 capacity=16；运维详情中文；movements 导出 200；Browser+API 复测 |
| OBS-008/014/021/025 | 2026-08-15 22:08+ | V181 viewer 去 ops + 坐标偏移 + 清脏距离；规划起点修复；素材同源预览；Browser 地图 2 标 / 素材可出图 |
| OBS-003/010/016/018 | 2026-08-15 22:18+ | 累计已完成/全局待分账文案；V182 乱码清理+演示积分120；API/Browser 复测 |
| OBS-011/019/020 | 2026-08-15 22:33+ | 峰值 floor 对齐当前在线；V183 解锁宽限+清 XSS；presence 不重锁 CAB-OTHER；API 复测 |
| Browser + OBS-005/017 | 2026-08-15 22:43+ | Browser 复测 011/019/020；报修 a11y 可点并提交；拣货满道 400 保持 DRAFT |
| OBS-004/006/024/028 | 2026-08-15 22:55+ | datetime 面板 teleported+z-index；ROI 预算已用/订单优惠+无券入表；开门时长文案；一致性「需人工」 |
| OBS-012 | 2026-08-15 23:16+ | mock+重力证据 → finalize PAID；无重力仍 DISPUTED；OBS-001/013 标记录；BUG-006 关 |
| OBS-012 demo-close | 2026-08-15 23:20+ | demo-close 无重力注入 SKU-DEMO-001；纯关门 → COMPLETED/PAID ¥3.50 |
| OBS-012 sim mismatch | 2026-08-15 23:50+ | mock 下 gravity-mismatch 亦按重力 PAID；模拟器自动关门 → PAID；Browser 订单已支付 |
| 自动选券 E2E | 2026-08-16 00:01+ | OBS-012 解除后：发 #44 新人立减 → 开门→sim 关门 → PAID 折后¥1.50；券 USED（解第20轮阻断） |
| 闭环 API 回归 | 2026-08-16 00:05+ | P1/关键 OBS 冒烟全过；缺陷表无开放可修项 |
| 自动选券 Browser | 2026-08-16 00:18+ | H5 订单列表/详情见 ¥1.50 + 券 -¥2.00；与 API/DB 一致 |

## 闭环 API 回归（2026-08-16 00:05～）

| 项 | 期望 | 结果 |
|---|---|---|
| BUG-002 viewer `POST .../repair-tickets` | **403** | PASS |
| BUG-008 viewer `POST .../consistency/run` | **403** | PASS |
| BUG-009 viewer `GET .../fund/daily-bills/export` | **403** | PASS |
| BUG-011 `POST .../sku-review/run?days=7` | **200** | PASS |
| BUG-015 `stock-health?dimension=FOOBAR` | **400** | PASS |
| BUG-018 `POST /coupons/use` 对 PAID | **409** | PASS |
| BUG-019 假单号用券 | **404** | PASS |
| OBS-026 `DELETE /ops/announcements` | **405** | PASS |
| BUG-001 `GET /account` | `frozen=0` | PASS（bal=13750） |
| OBS-012 折后单 `1786809698587954983` | PAID / 150 / disc=200 | PASS |

## OBS-012（2026-08-15 23:10～）

| 路径 | 结果 |
|---|---|
| mock + 重力 `SKU-DEMO-001` delta=-1 → demo-close | session **COMPLETED** / order **PAID** ¥3.50 |
| mock + 无重力 → demo-close（注入前） | session **DISPUTED** / `reviewCode=MOCK` |
| demo-close 自动注入演示重力后 | 纯 demo-close → **COMPLETED/PAID** ¥3.50 |
| 模拟器自动关门（视觉 mock ≠ 重力，曾 mismatch） | mock 下按重力 → **COMPLETED/PAID**；Browser 订单「已支付」¥3.50 |
| 单测 | `mockModelVersion_withGravityEvidence_settlesInMockMode`；`gravityMismatch_withGravityCart_settlesInMockMode` |
| **自动选券（原第20轮阻断）** | 会话 `1786809686018444123` / 订单 `1786809698587954983`：**PAID**；`original=350` `couponDiscount=200` `payable=150`；券 **#44 USED**；余额 13900→13750。**Browser**：订单列表首条 ¥1.50 +「券 -¥2.00」；详情「优惠券抵扣 -¥2.00 / 实付 ¥1.50」 |

## OBS-004 / 006 / 024 / 028（2026-08-15 22:50～）

| ID | 修复 | 验证 |
|---|---|---|
| OBS-004 | Dialog append-to-body；picker teleported + z-index 5000 | Browser 点「确定」成功；`OBS004-panel-verify` 落库 |
| OBS-006 | 列名区分预算已用/订单优惠；无券活动入 ROI | Browser 6 行 + hint；API rows=6 |
| OBS-024 | 误标「识别耗时」→「开门时长」 | 大屏「开门时长 12112ms」；SLA「开门均时长」 |
| OBS-028 | 不可修类型显示「需人工」 | 造 COUPON_ISSUED FAIL：类型「发券数量」、操作「需人工」 |

## OBS-005 / 017（2026-08-15 22:40～）

| ID | 修复 | 验证 |
|---|---|---|
| OBS-005 | 报修提交改为独立 `view role=button` + stop | Browser a11y 有「提交报修」；点击→登录→再提交 toast「报修已提交」 |
| OBS-017 | `markPicked` 先 clamp 货道余量，全满拒绝 | OB#13 A2 满：pick **400**「无可拣货数量」；status 仍 **DRAFT** |

## OBS-011 / 019 / 020（2026-08-15 22:30～）

| ID | 修复 | 验证 |
|---|---|---|
| OBS-011 | `SlaMetricsService.current` 峰值 = max(快照, 当前 ONLINE) | API `/ops/admin/sla`：**peak=1** / rate=0.5；开门 0/0 仍 rate=0 |
| OBS-019 | `sales_unlocked_at` + 配置 `manual_unlock_grace_minutes=45`；presence 宽限内跳过 | CAB-OTHER policy 解锁后打戳；触发 `device-presence` 仍 **sales_locked=f** |
| OBS-020 | V183 清脏；sanitize 入库；运营 list 省略 contactInfo | DB XSS 行 **0**；`/ops/feedback` contact 全空 |

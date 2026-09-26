# 概览 UAT 执行日志 · 2026-09-26（用户视角）

> 工具：Playwright MCP（后台）+ 微信开发者工具 CLI/automator（双端 mp-weixin）  
> 视口 1366×768 · 运营号 `13900000001` · 消费 `13800138000` · 商户 `13800138001`  
> 环境：全栈 Docker UP · `http://localhost/admin/` · mp dist `clients/*/dist/dev/mp-weixin`  
> 姿态：当运营人员日常使用；小程序验收以 **mp-weixin 为准，不认 H5 UI**

截图目录：`docs/uat-screenshots/2026-09-26/overview/`

---

## 总览（截至本轮）

| 模块 | 状态 | 摘要 |
|------|------|------|
| 运营工作台 | **按钮级清点完成** | 见 `BUTTONS.md` B.1；零计数快捷 SKIP+直链 |
| 运营大屏 | **按钮级清点完成** | 刷新/返回 ✓；全屏自动化 SKIP |
| 数据分析 | **按钮级清点完成** | KPI 深链 + 图类型/今天 ✓ |
| 客流坪效 | **按钮级清点完成** | 下拉近30天 39→55 开门；刷新 ✓ |
| 设备报表 | **按钮级清点完成** | 离线筛选 + link-cell→详情 ✓ |
| 财务毛利 | **按钮级清点完成** | KPI 深链；固化确认取消 ✓ |
| 销售报表 | **按钮级清点完成** | 五维度切换 ✓ |
| 库存健康 | **按钮级清点完成** | 四维度 ✓；投放空态 |
| 用户分析 | **按钮级清点完成** | 天数切换；召回 disabled SKIP |
| 三端口径 | 抽样完成 | 订单 ¥3.50 四端一致 |

**Phase 1 结案**：附录 B 见 [`BUTTONS.md`](./BUTTONS.md)。残余：大屏全屏须人工；导出未验文件内容；§7 仅抽样单。

---

## 已确认问题（按严重度）

### P0 · 工作台「缺货柜/SKU 4」点进去是空的 — **已修**

（略，见上轮 lessons #186）

### P1 · 数据分析「今日」渠道却有近 7 天金额 — **已修 2026-09-26**

| 项 | 内容 |
|----|------|
| 现象 | 顶 KPI 今日营收 ¥0；点「今天」后渠道块写「近 1 天」却有余额 ¥7 |
| 根因 | `OpsAnalyticsQueryService.normalizeTrendDays(1)` **强制返回 7**；文案又写「近 N 天」 |
| 修复 | days=1 保持 1；UI `rangeLabel`：1→「今天」；单测更新 |
| 回归 | `?days=1`：当前范围=今天，渠道「暂无」；`?days=7`：余额 ¥7 仍在 |
| 证据 | lessons #190（截图未留档；以回归文字 + 单测为准） |

### P1 · 销售报表默认「今日」整页空 — **已修**

| 项 | 内容 |
|----|------|
| 现象 | 默认今日：订单 0 / 暂无数据；设备报表累计仍有 ¥33.50 |
| 修复 | 默认快捷改为「近7天」（重置同口径） |
| 回归 | 打开页即见近7天营收 ¥7 / 1 行 SKU |
| 证据 | 回归文字（截图未留档） |

### P1 · 进件待办「正常」+「无权限或暂无入口」 — **已修文案**

| 项 | 内容 |
|----|------|
| 现象 | 有权限、0 待办时仍写「无权限或暂无入口」；双「进件工作台」按钮仍在 |
| 修复 | 有 extra 入口权限时空态改为「暂无待办」 |
| 残留 | 区卡「进件工作台」保留；告警头重复按钮已删 |

### P2 · 争议筛选项英文 `OPEN` — **本轮未再复现**

| 项 | 内容 |
|----|------|
| 复测 | 工作台「待审争议」深链 → 状态筛选项显示「待审核」，主文无 `OPEN` |
| 证据 | `ov-dsp-deeplink-status.png` |

### P2 · CAB-001 污染离线告警 — **已修 2026-09-26**

| 项 | 内容 |
|----|------|
| 现象 | 告警明细含「门店一号柜 / CAB-001」离线；与「勿用 CAB-001 演示」冲突 |
| 根因 | 离线计数/待办未限制 lifecycle，INBOUND 柜也进工作台 |
| 修复 | `collectOfflineDeviceItems` / `countOfflineDevices` 仅 `DEPLOYED` |
| 回归 | 离线设备=1；告警仅「浏览器自动发号柜」；无 CAB-001 |
| 证据 | lessons #193 |

### P2 · 在线率 KPI 含 INBOUND（1/3） — **已修 2026-09-26**

| 项 | 内容 |
|----|------|
| 现象 | 设备在线率 33.3%（1/3）；分母含入库柜 |
| 根因 | `globalStats`/`stats` 用全量 `count`/`countByOnlineStatus` |
| 修复 | 分子分母仅 `isDeployedDevice`；深链 `lifecycleStatus=DEPLOYED&online=…` |
| 回归 | API/UI `0/1`、`0.0%`；深链落地筛选「投放」+「离线」，行仅 777740024057 |
| 证据 | lessons #194 |

### P2 · 客流「开门 0 / 订单 2」转化 0% — **已修展示 2026-09-26**

| 项 | 内容 |
|----|------|
| 现象 | 「浏览器自动发号柜」开门 0、订单 2、转化 0.0%、营收 ¥7 |
| 根因 | 开门按窗内 `shopping_session.created_at`；两单关联会话在 9/12–13，订单在 9/24 |
| 修复 | opens=0 且 orders>0 时转化显示「—」+ title；节标题注明口径 |
| 回归 | 行文案 `0 2 — ¥7.00` |
| 证据 | lessons #192 |

### P3 · 大屏地图点数 vs KPI — **已修文案 2026-09-26**

| 项 | 内容 |
|----|------|
| 现象 | 图例「在线 0 离线 1」vs KPI「在线率 33.3% / 离线 2」 |
| 根因 | 图例只数**有坐标**点位；KPI 用全量货柜 |
| 修复 | 「点位在线/离线」+「有坐标 1/3」 |
| 证据 | lessons #191 |

### P3 · 争议列表在 1366 宽下操作列贴右缘 — **已缓释 2026-09-26**

| 项 | 内容 |
|----|------|
| 现象 | 操作图标易贴容器右缘 |
| 修复 | 操作列宽 220→168；`.col-action` cell `padding-right: 12px` |
| 回归 | sticky 仍生效；padRight=12px；按钮距视口右缘有余量 |

---

## 争议详情抽屉（OV 续测）

| 项 | 结果 |
|----|------|
| 打开首行 | PASS · 抽屉可见 |
| 状态/金额中文 | PASS ·「待审核」·¥0.00 / 建议 ¥3.50 |
| 录像区 | 诚实空态「暂无录像或加载失败」+ 无录像结案勾选（演示数据预期） |
| 截图 | 未留档（抽屉打开与金额以本轮 Playwright 记录为准）；同域见 `ov-dsp-deeplink-status.png` |

---

## H5 vs mp-weixin（已核实，勿混用结论）

| 维度 | 结论 |
|------|------|
| 验收权威 | **mp-weixin**（活文档 / debt-tracker）；H5 UI **不**当小程序 PASS |
| 同源否 | 同 `src/`，但大量 `#ifdef H5` / `MP-WEIXIN`（登录、支付、下载 withCredentials 等） |
| 构建链 | `dev:mp-weixin` 会跑 `sync-consumer-mp-api` + `patch-uni-mp-workspace`；`dev:h5` **不跑** |
| 鉴权 | H5：URL auth_code / wx H5 / mock；MP：`wx.login` code |
| dist | 本轮已重启 watcher；mtime ≈ 13:10（与 src 对齐） |
| DevTools | `cli open` 拒 `touristappid`；本机用 `MP_WEIXIN_APPID_CONSUMER` 写入产物 `project.config.json` 后方可 open/auto |

---

## 三端口径抽样（订单 `1790242775512664013757`）

| 端 | 金额 | 状态 | 取证 |
|----|------|------|------|
| 运营后台抽屉 | ¥3.50 | 已支付 | Playwright 订单详情 |
| DB `cabinet_order` | 350 分 | PAID | psql |
| 消费者 mp-weixin | ¥3.50 | 已支付 | DevTools automator · `ov-mp-c-orders.png` |
| 商户 mp-weixin | ¥3.50 | 已支付 | DevTools automator · `ov-mp-m-orders.png` |
| 差 | 0 分 | 一致 | **PASS** |

CLI 要点：`cli.bat auto --project <dist> --auto-port 9420` 后再 `automator.connect({ wsEndpoint:'ws://127.0.0.1:9420' })`（缺 `--auto-port` 时 9420 不开）。

---

## 按钮生效抽样（工作台）

| ID | 结果 | 落地 URL / 备注 |
|----|------|-----------------|
| OV-DASH-02 刷新 | PASS | 待审争议仍为 8，未闪 0 |
| OV-DASH-03 补货调度 | PASS | `/replenishment?tab=routes` |
| OV-DASH-04 争议审核 | PASS | `/disputes?status=OPEN`，中文「待审核」 |
| OV-DASH-05 设备管理 | PASS | `/devices` |
| OV-DASH-06 设备可用性 | PASS | `/device-kpi` |
| OV-DASH-07 在售货柜 | PASS | `/devices?salesLocked=false`；可购买=1 与 KPI 一致 |
| OV-DASH-08 在线率 | PASS（修后） | UI/API `0/1`·`0.0%`；深链 `lifecycleStatus=DEPLOYED&online=OFFLINE` |
| OV-DASH-09 今日营收 | PASS | `/finance`；页内今日营收 ¥0.00 与工作台一致 |
| OV-DASH-10 待处理异常 | PASS | `/exceptions?status=OPEN`；本页 12 与 KPI 一致 |
| OV-DASH-12 待审争议 | PASS | query 生效；筛选项「待审核」（英文 OPEN 未再复现） |
| OV-DASH-14 停售 | PASS | `salesLocked=true` |
| OV-DASH-15 离线 | PASS | `online=OFFLINE` |
| OV-DASH-17 缺货 | PASS（已修） | 深链带 `lifecycleStatus=DEPLOYED`；投放空态与计数一致 |
| OV-DASH-recon 对账 | PASS | `status=MISMATCH`；本页差异 1 |
| OV-DASH-split 分账 | PASS | `/merchants?tab=splits` |
| OV-DASH-19 告警「查看」 | PASS | 带 ticketId/keyword 进争议 |
| OV-DASH-20 进件 | PASS | 区卡「进件工作台」单入口（告警头重复已删） |
| 大屏刷新/返回 | PASS | 回到 `/dashboard` |

### 数据三角（工作台 ↔ API `workbench-bundle`）

| UI | API | 判 |
|----|-----|----|
| 在售 1 / 停售 2 | devicesOnSale=1, devicesSalesLocked=2 | ✓ |
| 在线率 0.0% (0/1) | deviceOnline=0, deviceTotal=1（仅投放） | ✓ |
| 今日营收 ¥0.00 | revenueTodayCents=0 | ✓ |
| 待处理异常 12 | openExceptionCount=12 | ✓ |
| 待审争议 8 | openDisputes=8（overdue=7） | ✓ |
| 对账差异 1 / 分账 21 | reconciliationMismatches=1, splitExceptions=21 | ✓ |
| 缺货 0（修后） | lowStockItems=0 | ✓ 仅投放柜 |

---

## Phase 2 订单按行退款 / 异常免单 · 2026-09-26

| 步骤 | 结果 |
|------|------|
| 异常「免单/全额退回」 | 确认框出 → 取消；仍待处理 |
| 按行退款（旧） | **无二次确认**直接退成功（误退演示单 179024277…）→ **已修 #197** |
| 按行退款（新） | 确认框「会写入资金」→ 取消；订单仍 PAID |

---

## 下一轮计划

1. （可选）人工验大屏全屏；导出文件内容抽检  
2. 一致性巡检脏数据调账（财务 Phase5 债）  
3. 其它分册按钮级深测（履约写路径已部分覆盖）

---

## Phase 2 索引

详见 `docs/uat/FULFILLMENT_FULL_BROWSER_UAT.md` 与 `docs/uat-screenshots/2026-09-26/fulfillment/FINDINGS.md`（含订单 lines JsonView #196）。

---

## Phase 2 争议写路径（点到确认框，不提交资金）· 2026-09-26

| 步骤 | 结果 |
|------|------|
| 打开 OPEN 列表 → 详情抽屉 | PASS；有「认领工单 / 按调整明细落账 / 免单并退款」 |
| 认领工单 | PASS；处理人 →「运营超管」 |
| 无勾选直接落账 | 拦截 toast：须勾「无录像仍结案」+「已对照录像核对」 |
| 勾选后「免单并退款」 | 弹出「确认争议处理」含资金警示 → **取消**，未落账 |
| 「按调整明细落账」 | 填充明细后出确认框 → **取消**；OPEN 仍 8 |

---

## Phase 2 订单 / 开门 / 异常冒烟 · 2026-09-26

| 页 | 判定 | 证据 |
|----|------|------|
| 订单列表 | PASS | 10 行有数；金额 ¥3.50；API total=22 |
| 订单 overdue 深链 | PASS（空诚实） | `?status=PENDING&overdue=1` → 0 行；`pendingUnpaidOrders` 无逾期单 |
| 开门记录 | PASS | API total=55；表内可横滑（列多，预期） |
| 「仅滞留」 | **FAIL→已修** | 曾把 55 条历史单当滞留；现 API stuck=0 / 全量 55；空态「当前无超过 30 分钟的滞留会话」 |
| 异常中心 OPEN | PASS | UI 12 行 ↔ API total=12；Tab「待处理 (12)」 |

### P1 · 「仅滞留」含已完成历史单 — **已修 2026-09-26**

| 项 | 内容 |
|----|------|
| 现象 | `?stuck=1` / 勾选「仅滞留」横幅「共 55 条滞留」，行内大量「已完成」 |
| 根因 | `stuckOnly` 只加 `updatedBefore`，未限制活跃态 |
| 修复 | 滞留 = `STUCK_ACTIVE_STATES` ∩ `updated_at` 早于阈值（对齐工作台） |
| 回归 | stuckTotal=0；empty「当前无超过 30 分钟的滞留会话」（截图未留档；见 lessons #195） |
| 证据 | lessons #195 |

---

## 判定备注

- 「点得开」的深链大多生效；**不能**据此宣称概览 PASS。  
- P0 缺货深链空页足以让运营不信任工作台数字。  

# 设备商品附录 B · 按钮清点 · 2026-09-26（复测）

> 工具：Playwright MCP · 账号 `13900000001` · 视口 1366×768  
> 分册：[`DEVICE_SKU_FULL_BROWSER_UAT.md`](../../../uat/DEVICE_SKU_FULL_BROWSER_UAT.md)  
> 规则：锁机 / 下架 / 新建写路径一律 **确认框或弹层取消**，禁止真锁机/真下架。  
> 截图：本目录 `ds-*.png`

---

## B.1 设备运维 `/device-ops`

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| OPS-01 | 列表加载 | ✓ | 共 64 条；`ds-ops-01.png` |
| OPS-02 | 刷新 | ✓ | 可点 |
| OPS-03 | 导出 | ✓ | 下载 `设备运维事件_*.csv` |
| OPS-04 | 列设置 | ✓ | popover 可开 |
| OPS-05 | 重置 | ✓ | 可点 |
| OPS-06 | 升序/降序 | ✓ 可见 | |

---

## B.2 设备管理 `/devices`

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| DEV-01 | 列表加载 | ✓ | 共 3 条；KPI 在线1/离线2；`ds-dev-01.png` |
| DEV-02 | Tab 在线 | ✓ | `?online=ONLINE` · 共 1 |
| DEV-03 | Tab 离线 | ✓ | `?online=OFFLINE` · 共 2 |
| DEV-04 | Tab 可购买 | ✓ | `?online=ONLINE&salesLocked=false` · 共 1 |
| DEV-05 | Tab 已锁机 | ✓ | `?salesLocked=true` · 共 2 |
| DEV-06 | KPI「离线」点击 | ✓ | → `?online=OFFLINE`；`ds-dev-06-kpi-offline.png` |
| DEV-07 | 深链 DEPLOYED+OFFLINE | ✓ | 共 1 条（777740024057）；筛选含「投放」；`ds-dev-02-offline.png` |
| DEV-08 | 勾选后批量解锁 → 取消 | ✓ | 「将对 1 台设备执行「解锁」」；`ds-dev-03-batch-unlock.png` |
| DEV-09 | 未勾选批量锁机/解锁 | SKIP | disabled 合理 |
| DEV-10 | 行「详情」 | ✓ | → `/devices/330449777078`；`ds-dev-04-detail.png` |
| DEV-11 | 详情「锁机停售」→ 取消 | ✓ | 「确认执行「锁机停售」？请填写原因」；`ds-dev-05-unlock-confirm.png` |
| DEV-12 | 查询/重置/导出/刷新/列设置 | ✓ 可见 | |
| DEV-13 | 批量投放/未投放/退役/新建 | ✓ 可见 | 本轮未提交 |

---

## B.3 投放地图 `/device-map`

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| MAP-01 | 地图+落点 | ✓ | Leaflet；共 3 个柜机落点；`ds-map-01.png` |
| MAP-02 | 点位「详情」 | ✓ | 首条 → `/devices/CAB-001`（入库演示柜，与 KPI 投放口径分离，已知） |
| MAP-03 | 查询 / 刷新 | ✓ | 可点 |

---

## B.4 设备可用性 `/device-kpi`

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| KPI-01 | 页加载 | ✓ | 设备总数 3；平均锁定/恢复「暂无样本」；`ds-kpi-01.png` |
| KPI-02 | 刷新 | ✓ | 可点 |
| KPI-03 | 单页深测 | ✓ | 见 [`DEVICE_KPI_FULL_BROWSER_UAT.md`](../../../uat/DEVICE_KPI_FULL_BROWSER_UAT.md) · `device-kpi/` |

---

## B.5 维修工单 `/repair-tickets`

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| REP-01 | 列表 | ✓ | 共 1 条「已完成」；`ds-rep-01.png` |
| REP-02 | 新建工单 → 取消 | ✓ | 弹层「新建维修工单」→ 取消；`ds-rep-02-new.png` |
| REP-03 | 查询/刷新/列设置/批量指派 | ✓ 可见 | 未勾选时批量合理 disabled |
| REP-04 | 单页深测 | ✓ | 见 [`REPAIR_TICKETS_FULL_BROWSER_UAT.md`](../../../uat/REPAIR_TICKETS_FULL_BROWSER_UAT.md) · `repair-tickets/` |

---

## B.6 商品管理 `/skus`

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| SKU-01 | 列表 | ✓ | 共 6 条；可乐 ¥3.50；`ds-sku-01.png` |
| SKU-02 | 编辑 → 取消 | ✓ | 编辑弹层可开可关；`ds-sku-02-edit.png` |
| SKU-03 | 勾选后批量下架 → 取消 | ✓ | 「确认将 1 个商品下架？」；`ds-sku-03-offline.png` |
| SKU-04 | 「识别入驻」 | ✓ | → `/sku-vision` |
| SKU-05 | 新建/导出/导入/下载模板/刷新/查询/重置 | ✓ 可见 | |
| SKU-06 | 单页深测 | ✓ | 见 [`SKUS_FULL_BROWSER_UAT.md`](../../../uat/SKUS_FULL_BROWSER_UAT.md) · `skus/` |

---

## B.7 选品诊断 `/sku-review`

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| REV-01 | 列表 | ✓ | 共 6 条；可乐销量 9 / ¥31.50；`ds-rev-01.png` |
| REV-02 | 运行诊断 | ✓ | toast「诊断完成」 |
| REV-03 | 勾选后批量下架 → 取消 | ✓ | 「确认批量下架 1 个 SKU？」；`ds-rev-02-offline.png` |
| REV-04 | 批量下架/保留（未勾选） | SKIP | disabled 合理 |
| REV-05 | 导出/刷新/查询/重置 | ✓ 可见 | |
| REV-06 | 单页深测 | ✓ | 见 [`SKU_REVIEW_FULL_BROWSER_UAT.md`](../../../uat/SKU_REVIEW_FULL_BROWSER_UAT.md) · `sku-review/`（#210 total） |

---

## B.8 识别入驻 `/sku-vision`

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| VIS-01 | 列表 | ✓ | 共 6 条；识别状态「草稿」；`ds-vis-01.png` |
| VIS-02 | 入驻配置 → Esc/取消 | ✓ | 配置弹层可开可关；`ds-vis-02-cfg.png` |
| VIS-03 | 商品管理链 | ✓ 可见 | |
| VIS-04 | 导出/导入/刷新/查询 | ✓ 可见 | |

---

## B.9 识别映射 `/vision-mappings`

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| MAPG-01 | 列表 | ✓ | 共 4 条（bottle/bowl/can/cup）；`ds-mapg-01.png` |
| MAPG-02 | 行编辑/映射弹层 | ✓ | 弹层可开；`ds-mapg-02-row.png` |
| MAPG-03 | 新增映射 → 取消 | ✓ | 「新增识别映射」→ 取消；`ds-mapg-03-new.png` |
| MAPG-04 | 查询/重置/刷新/导出/列设置 | ✓ | 导出 CSV 触发 |
| MAPG-05 | 阿里云类目映射空态 | ✓ | 「暂无阿里云类目映射」中文 |

---

## B.10 录像上传 `/upload-queue`

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| UP-01 | 列表空态 | ✓ | 共 0 + 中文空；`ds-up-01.png` |
| UP-02 | `?stuck=1` | ✓ | URL 回显；`ds-up-02-stuck.png` |
| UP-03 | 查询/导出/刷新/列设置 | ✓ 可见 | |

---

## Phase 3 Done（复测）

- [x] 侧栏十页附录 B 勾生效或合法 SKIP  
- [x] 锁机/批量解锁/SKU 下架/选品下架/新建工单/新增映射均取消未提交  
- [x] 深链 query 回显（DEPLOYED+OFFLINE、online=OFFLINE、upload stuck）  
- [x] 日志见 [`FINDINGS.md`](./FINDINGS.md)

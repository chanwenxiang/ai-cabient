# Phase 3 设备商品 UAT 执行日志 · 2026-09-26（复测）

> 工具：Playwright MCP · 视口 1366×768 · 运营号 `13900000001`  
> 分册：[`DEVICE_SKU_FULL_BROWSER_UAT.md`](../../../uat/DEVICE_SKU_FULL_BROWSER_UAT.md)  
> 按钮清点：[`BUTTONS.md`](./BUTTONS.md)（概览同款附录 B 编号）  
> 环境：全栈 Docker · `http://localhost/admin/`  
> 姿态：侧栏「设备商品」十页；写路径确认后取消

截图目录：`docs/uat-screenshots/2026-09-26/device-sku/`

---

## 总览（本轮复测）

| 模块 | 状态 | 摘要 |
|------|------|------|
| 设备运维 | **完成** | B.1；64 条；导出 CSV |
| 设备管理 | **完成** | B.2；Tab/KPI/深链；批量解锁取消；详情锁机取消 |
| 投放地图 | **完成** | B.3；3 落点 Leaflet；详情→设备 |
| 设备可用性 | **完成** | B.4；总数 3；「暂无样本」诚实 |
| 维修工单 | **完成** | B.5；新建弹层取消 |
| 商品管理 | **完成** | B.6；编辑/下架取消；链识别入驻 |
| 选品诊断 | **完成** | B.7；诊断完成；批量下架取消 |
| 识别入驻 | **完成** | B.8；入驻配置取消 |
| 识别映射 | **完成** | B.9；4 映射；新增取消；阿里云空态 |
| 录像上传 | **完成** | B.10；空态 + stuck |

**Phase 3 结案（复测）**：附录 B 见 [`BUTTONS.md`](./BUTTONS.md)。写路径均未提交。

---

## 用例结果

| ID | 判定 | 证据 |
|----|------|------|
| DEV-OPS-01 | PASS | `/device-ops` 共 64；导出运维事件 CSV；`ds-ops-01.png` |
| DEV-LIST-01 | PASS | 共 3；含投放+入库；`ds-dev-01.png` |
| DEV-LIST-02 | PASS | `lifecycleStatus=DEPLOYED&online=OFFLINE` 共 1；`ds-dev-02-offline.png` |
| DEV-LIST-tabs | PASS | 在线1/离线2/可购买1/已锁机2 |
| DEV-LIST-batch | PASS | 批量解锁确认 → **取消** |
| DEV-DETAIL-lock | PASS | 锁机停售确认 → **取消**；`ds-dev-05-unlock-confirm.png` |
| DEV-MAP-01 | PASS | 3 落点；Leaflet；`ds-map-01.png` |
| DEV-KPI-01 | PASS | 总数 3；暂无样本；`ds-kpi-01.png` |
| DEV-REP-01 | PASS | 1 条已完成；新建「新建维修工单」→ 取消 |
| SKU-01 | PASS | 6 商品；¥3.50；编辑/下架取消 |
| SKU-review | PASS | 诊断「诊断完成」；批量下架取消 |
| SKU-vision | PASS | 6 行草稿；入驻配置 Esc |
| SKU-mapping | PASS | `/vision-mappings` 4 条；新增映射取消；`ds-mapg-*.png` |
| SKU-upload | PASS | 共 0；`?stuck=1` 回显 |

---

## 本轮操作要点（与概览对齐）

1. 开测前列附录 B，测完勾生效。  
2. 写路径：`.el-message-box` `opacity===1` 再取消（#200）。  
3. 深链验 query + 行数，不只验跳转。  
4. 未勾选时批量按钮 disabled → **SKIP（合理）**，勾选后再测取消。

---

## 本轮缺陷

| 严重度 | 摘要 | 状态 |
|--------|------|------|
| — | 本轮复测 **无新增 FAIL** | — |

已知（非本轮新开）：地图落点含 CAB-001 入库柜，与「投放 KPI」口径分离——点详情可进 CAB-001，勿当投放主路径（分册已注明）。

---

## 下一轮

侧栏下一组（履约仓储）或用户指定模块。

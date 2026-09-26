# 设备管理 · FINDINGS · 2026-09-26

> [`BUTTONS.md`](./BUTTONS.md) · [`DEVICES_FULL_BROWSER_UAT.md`](../../../uat/DEVICES_FULL_BROWSER_UAT.md)

## 结论

**PASS · FAIL 0 · BLOCK 0**

列表/看板/六态 Tab 与 API 对齐；深链 DEPLOYED+OFFLINE=1；批量锁机解锁投放退役与新建/退款设置均确认取消；详情深链与导出通过。结束视口 **1366×768**。

## 数据对照

| 项 | UI | API `GET /api/v2/ops/admin/devices` |
|----|-----|-------------------------------------|
| 全部 | 共 3 · 看板「全部设备」3 | total=3 |
| 在线 | 共 1 | online=ONLINE → 1 |
| 离线 | 共 2 | online=OFFLINE → 2 |
| 可购买 | 共 0 | ONLINE+未锁机 → 0（三台均已锁机） |
| 已锁机 | 共 3 | salesLocked=true → 3 |
| DEPLOYED+OFFLINE | 共 1 · 发号柜 | total=1 |

## 口径说明（非 FAIL）

| # | 说明 |
|---|------|
| 1 | **CAB-001**「门店一号柜」生命周期=**入库**，出现在全量列表合理；**勿当投放主路径**（与 DEVICE_SKU / 地图已知一致）。投放离线深链只命中 `777740024057`。 |
| 2 | 「可购买」= 在线且未锁机；本轮三台均锁机 → 可购买 0，空态「暂无设备」诚实。 |
| 3 | 「未锁机」≠ 可购买：离线未锁机仍不可买（页头 hint 已写明）。 |
| 4 | 锁机单台操作在**设备详情**；本页批量路径须确认取消。 |

## UAT 操作注意

| # | 说明 |
|---|------|
| 1 | MessageBox 须等 overlay opacity 就绪再点取消（#200）。 |
| 2 | 看板文案是「全部设备」不是「全部」。 |
| 3 | EP Select 回显用可见文案断言（如「投放」），勿只扫 `.el-option`。 |

## 证据

`dm-01`…`dm-17` · `dm-ux-*` · `dm-api-list.json` · `dm-99-end`
|

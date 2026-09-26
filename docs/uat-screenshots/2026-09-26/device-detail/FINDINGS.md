# 设备详情 · FINDINGS · 2026-09-26

> 柜机 `330449777078` + 锁机柜 `777740024057` · [`BUTTONS.md`](./BUTTONS.md) · [`DEVICE_DETAIL_FULL_BROWSER_UAT.md`](../../../uat/DEVICE_DETAIL_FULL_BROWSER_UAT.md)

## 结论

**PASS · FAIL 0 · BLOCK 0** · **按钮清点 100%** · UX 说明 2

第二轮补齐缺口：保存资产/解析坐标/保存退款规则、策略开关×4 拨回、温控启用/添加/删除/保存并应用/立即应用、一键规划补货（造缺货后点）、货道弹窗仅记实盘/调账面/保存配置、解锁营业（锁机柜）、关联订单查看。

## 视口

固定 **1366×768 · DPR=1.0**。

## UX / 产品说明（非 FAIL）

| # | 现象 | 说明 |
|---|------|------|
| 1 | 「套用模板」无二次确认，点击即 POST | 本柜幂等「与模板一致」；建议加 confirm |
| 2 | 「保存并应用 / 立即应用」温控无 confirm | 100% 清点已点击；演示柜收到「已按当前时段下发」。建议加确认框 |

## 条件隐藏（N/A，非漏测）

| 控件 | 原因 |
|------|------|
| 解绑硬件 | 无 IMEI 不渲染 |
| 重新生成编号 | `canRegenerateDeviceId=false` |
| 解绑 / 撤回未投放 | 入库态 disabled（状态机） |

## 造缺货说明

为露出「一键规划补货」，曾将货道 A1 实盘调账面为 0 → 点击跳转 shortage → **已还原账面 2**。属演示数据短暂变更，非残留。

## 导航落地摘要

| 起点 | URL |
|------|-----|
| 关联·会话查看 | `/sessions?deviceId=330449777078&sessionId=…` |
| 关联·订单查看 | `/orders?deviceId=330449777078` |
| 缺货建议 / 一键规划 | `/replenishment?tab=shortage&deviceId=` |
| 补货调度 | `/replenishment?tab=routes&deviceId=` |
| 工单列表 | `/repair-tickets?deviceId=` |
| 返回 | `/devices` |

## 证据文件

首轮：`dd-01`…`dd-04` · `dd-B-set-temp` · `dd-tab-*` · `dd-F/H/I` · `dd-mp-m-detail` · `dd-99`  
缺口轮：`dd-G-save-asset` · `dd-G-remote-policies` · `dd-G-temp-plan` · `dd-G-slot-dialog` · `dd-G-unlock` · `dd-G-plan-replenish`
|

# 待办交接 — 代码审查跟进

> 更新时间：2026-09-04  
> 来源：管理后台 + 小程序 + 三端之外审查  
> 分支：`dev`

---

## 已合入主线

| 范围 | 状态 |
|------|------|
| 管理后台 / 小程序审查核心 | ✅ |
| 三端之外 B-2/4/7/8/9/11–13/21/23/24 | ✅ |
| B-20 / B-22 / B-25/B-28 | ✅ |
| B-11 JWT / B-1 柜机鉴权 | 📝 设计备忘 |
| **柜机离线/停售即时通知 + 冷却** | ✅（本轮实现，待 commit） |

---

## 本轮：柜机意外通知

- 新增 `MerchantDeviceIncidentNotifyService`：离线 / 停售事件即时推送
- 设备级 Redis 冷却（默认 30 分钟，`merchant.notify.incident_cooldown_minutes`）
- 商户：订阅偏好 `DEVICE_OFFLINE` / `SALES_LOCKED` + 柜机权限 → 微信订阅消息
- 运营：`OpsAlertDispatcher` 钉钉/企微/Webhook
- 触发点：`DevicePresenceService` 标记离线 & 自动锁机；`DeviceSalesLockService` 首次锁机
- Flyway `V259` + 商户小程序订阅选项 / 告警页文案

---

## 仍开放（专项 / 运维）

- B-1 / B-3 实现、JWT B-11 实现  
- I-1 本机密钥轮换  
- A 类上线切换清单  
- N-13/N-15、零星 P3  
- 低库存仍走工作台汇总调度（未改）  

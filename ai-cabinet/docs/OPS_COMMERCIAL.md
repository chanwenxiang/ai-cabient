# 商业运营模块

本文描述设备 OTA、断网续传、多摄融合、风控、对账、补货、SLA 监控及 RBAC 权限管理的设计与 API，对应 Flyway `V8` / `V9` 迁移。

---

## 1. 模块总览

| 模块 | 职责 | 主要表 / API |
|------|------|----------------|
| **设备 OTA** | 远程升级工控机 Android App | `ota_release`、`ota_device_report` |
| **断网续传** | 离线本地存视频，有网再上传结算 | `shopping_session.upload_status`、`WAITING_UPLOAD` 状态 |
| **多摄像头融合** | 顶摄 + 侧摄，减少遮挡 | `video_clips` JSON、`camera_fusion_mode` |
| **风控** | 恶意开门、频繁申诉、黑名单 | `risk_event`、`user_blacklist` |
| **对账** | 微信/支付宝日账单核对 | `payment_reconciliation` |
| **补货** | 库存、补货员任务、路线 | `device_sku_inventory`、`replenishment_*` |
| **SLA 监控** | 识别耗时、开门成功率、在线率 | `sla_daily_snapshot` + 实时计算 |
| **RBAC** | 角色权限（借鉴 RuoYi） | `ops_role`、`ops_permission`、`ops_user_role` |

运营后台入口：http://localhost:8080/admin/index.html（侧栏新增 SLA / OTA / 风控 / 对账 / 补货 / 权限）

---

## 2. 设备 OTA

### 流程

```text
柜机心跳/启动 → 上报 appVersion
       ↓
GET /internal/v1/devices/{deviceId}/ota/check?currentVersion=1.0.0&channel=stable
       ↓
返回 downloadUrl / mandatory / checksum
       ↓
Android 下载安装 → 下次心跳上报新版本
```

### 运营 API

| 方法 | 路径 | 权限 |
|------|------|------|
| GET | `/api/v2/ops/admin/ota/releases` | `ops:ota:list` |
| POST | `/api/v2/ops/admin/ota/releases` | `ops:ota:publish` |

---

## 3. 断网续传

关门时柜机可上报：

```json
{
  "doorState": "CLOSED",
  "uploadStatus": "LOCAL_QUEUED",
  "videoUri": "file:///data/cabinet/clips/session.mp4"
}
```

会话进入 `WAITING_UPLOAD`，不触发识别。网络恢复后：

```http
POST /internal/v1/sessions/video
{
  "sessionId": "...",
  "deviceId": "CAB-001",
  "videoUri": "minio://cabinet-videos/...",
  "uploadStatus": "UPLOADED",
  "videoClipsJson": "[{\"camera\":\"TOP\",\"videoUri\":\"...\"},{\"camera\":\"SIDE\",\"videoUri\":\"...\"}]",
  "cameraFusionMode": "MULTI"
}
```

上传完成后自动 `RECOGNIZING` → 结算。

---

## 4. 多摄像头融合

- `video_clips`：JSON 数组，每项含 `camera`（`TOP` / `SIDE`）、`videoUri`、`capturedAt`
- `camera_fusion_mode`：`SINGLE` | `MULTI`
- vision-service 生产可扩展为读取多 URI 融合识别（当前 trade 侧已持久化，识别仍用主 `video_uri`）

---

## 5. 风控

| 规则 | 阈值 | 动作 |
|------|------|------|
| 黑名单 | - | 禁止开门 |
| 恶意开门 | 1 小时内 ≥5 次创建会话 | HTTP 429 |
| 频繁申诉 | 7 天内 ≥3 次争议 | 自动拉黑 30 天 |

开门前：`UserValidationService` → `RiskControlService.validateCanOpenDoor`

运营 API：`/api/v2/ops/admin/risk/events`、`/risk/blacklist`

---

## 6. 对账

每日定时或手动触发，对比账本订单/充值与支付渠道账单（本地 dev 为账本自洽 mock）：

```http
POST /api/v2/ops/admin/reconciliation/run?date=2026-07-06&channel=WECHAT
```

生产需对接微信/支付宝账单下载 SDK，填入 `platform_total` 与明细 `detail` JSON。

---

## 7. 补货系统

| 能力 | API |
|------|-----|
| 柜内库存 | `GET/PUT /api/v2/ops/admin/inventory` |
| 补货路线 | `GET/POST /api/v2/ops/admin/replenishment/routes` |
| 补货员任务 | `GET /api/v2/ops/admin/replenishment/my-tasks` |
| 完成任务 | `POST .../tasks/{id}/complete` |

补货员使用运营账号（`replenisher` 角色）登录小程序或独立 App 拉取任务。

---

## 8. SLA 监控

| 指标 | 说明 |
|------|------|
| 开门成功率 | 完成+争议 / 总会话 |
| 识别耗时 | `close_time - open_time` 均值 / P95 |
| 设备在线率 | ONLINE 设备 / 总设备 |

- 实时：`GET /api/v2/ops/admin/sla` → `realtime` 字段（近 24h）
- 日快照：每日 00:05 `SlaMetricsService.snapshotDaily()` 写入 `sla_daily_snapshot`

---

## 9. RBAC（借鉴 RuoYi）

表结构对齐 RuoYi 思路：

- `ops_role`：角色（admin / operator / replenisher / finance / viewer）
- `ops_permission`：菜单 + API 权限码（`ops:device:list` 等）
- `ops_user_role` / `ops_role_permission`：多对多

本地测试运营号 `13900000001`（userId `100000001`）默认绑定 **admin** 角色。

未配置角色的运营账号：向后兼容，视为拥有全部权限（`PermissionService.hasPermission` 空集返回 true）。

### API

| 方法 | 路径 |
|------|------|
| GET | `/api/v2/ops/admin/rbac/roles` |
| GET | `/api/v2/ops/admin/rbac/permissions` |
| GET | `/api/v2/ops/admin/rbac/users/{userId}/roles` |
| PUT | `/api/v2/ops/admin/rbac/users/{userId}/roles` |
| GET | `/api/v2/ops/admin/rbac/me/permissions` |

---

## 10. 迁移与启动

重启 trade-service 后 Flyway 自动执行 `V8`、`V9`：

```bash
cd services/trade-service && mvn spring-boot:run
```

验证：

```bash
curl -H "Authorization: Bearer <token>" http://localhost:8080/api/v2/ops/admin/sla
curl -H "X-Internal-Api-Key: dev-internal-key-change-me" \
  "http://localhost:8080/internal/v1/devices/CAB-001/ota/check?currentVersion=0.9.0"
```

### 模拟器联调（device-simulator）

```powershell
# 断网续传：关门 LOCAL_QUEUED → 5s 后 HTTP 上传并结算
$env:AICABINET_SIM_OFFLINE_UPLOAD="true"
$env:AICABINET_SIM_VIDEO_FILE="..\..\testdata\apple.jpg"

# 多摄融合：顶摄 + 侧摄
$env:AICABINET_SIM_MULTI_CAMERA="true"
$env:AICABINET_SIM_VIDEO_FILE="..\..\testdata\apple.jpg"
$env:AICABINET_SIM_SIDE_VIDEO_FILE="..\..\testdata\soda.jpg"

mvn -pl edge/device-simulator exec:java -Dexec.args="CAB-001"
```

---

## 11. 生产待办

- [ ] OTA：CDN 签名 URL、灰度 channel、回滚版本
- [ ] 对账：接入微信/支付宝账单 API
- [ ] 多摄：vision-service 多 URI 融合识别
- [ ] 补货：地图路线优化（第三方路径规划）
- [ ] RBAC：前端按钮级 `v-permission`、数据权限（按区域/设备）

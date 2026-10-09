# 将邑开门柜接入网关（jiangyi-gateway）设计 — CB-022

> 2026-10-09 实施。权威方案：WorkBuddy 计划 `electric-forging-tesla-n6eohtlS.md`（已批准）。
> 本文是落码后的**现状速查**，与方案冲突时以方案 §10 待确认项为准。

## 0. 核心判断（复述）

- **枢纽**：将邑 `orderNo` = 我方 `sessionId`（V16 §4.3.2.7「商户服务器生成、需唯一」）——免映射。
- **金额不采信设备侧**：识别上报只带 classId+quantity（无金额字段）⇒ 结算金额 = Σ(映射 SKU 现价 × qty)（分）；映射未命中 fail-closed 转 DISPUTED。
- **模式一无关门上报**：识别上报到达时 gateway 先合成 door-event CLOSED（SHOPPING→RECOGNIZING）再转发 edge-results；`uploadDoorState`（拉门成功）→ door-event OPEN（OPENING→SHOPPING）。
- **零侵入**：device-info / trade 业务服务 / common-core / 前端不动；唯一现有 Java 改动 = `DeviceServiceClient`（路由分流）。

## 1. 模块与文件

| 模块 | 文件 | 职责 |
|---|---|---|
| trade | `V332__jiangyi_device.sql` / `V333__jiangyi_class_mapping.sql` | 设备目录（SN/identifier/token_version）+ class 映射 |
| trade | `domain/JiangyiDevice(·ClassMapping)` + `mapper/*Mapper` | MyBatis-Plus 实体（house style：@TableName + BaseTradeMapper） |
| trade | `service/JiangyiDeviceDirectory` | isJiangyi(deviceId)=BOUND 才路由；TTL 缓存 60s/负 10s |
| trade | `service/JiangyiClassMappingService` / `JiangyiRecognitionTimeoutService` | 映射解析 / C09 即时版（释放冻结→DISPUTED→争议单） |
| trade | `client/JiangyiMerchantClient` / `service/JiangyiOnboardingService` | 商户云 login2/setDomain/getIdentifier/绑租户（env-only） |
| trade | `api/JiangyiInternalController` | `/internal/v1/jiangyi/**`：设备/映射/超时/入驻（internalApiAuthInterceptor 自动覆盖） |
| trade | `client/DeviceServiceClient`（●改） | isJiangyi→gateway（**不重试**，双开门风险）；operatorMode→501；else→device-service（编程式 Retry） |
| gateway | `auth/DeviceTokenService` / `DeviceAuthInterceptor` | JWT HS256 签发/验签；Bearer 拦 /jiangyi/api/**（排除 token 签发端点）；吊销=claims.ver<表 ver |
| gateway | `ws/DeviceHandshakeInterceptor` / `DeviceWebSocketHandler` | WS 握手校验 query token+identifier 一致；openDoor 下发；单设备单连接顶替 |
| gateway | `api/DeviceTokenController` | `POST /jiangyi/api/token/openDoorDeviceStatus`（+`/jiangyi/api/token` 别名），envelope `{status,msg,data:{token,identifier}}` |
| gateway | `api/DeviceReportController` | 文档路径照抄：uploadLockState/uploadDoorState/addRecognitionGoodsToOrder/uploadOrderError/openDoubleDoorError/uploadFaultOrder |
| gateway | `api/OpenDoorController` | `/internal/v1/jiangyi/devices/{id}/open-door`：WS 下发 + tracker 登记 |
| gateway | `tracker/CommandTracker` / `SessionWatchdog` | Redis hash+ZSET 三组 pending；15s 开门 / 300s 识别 / 10min doing 兜底 |
| gateway | `normalize/DoorStateNormalizer` / `RecognitionNormalizer` | success|fail 与 bigModel/BigModel 大小写归一；forms→items；modelVersion 截断 64 |
| 部署 | `infra/docker-compose.full.yml`、`infra/gateway/nginx*.conf` ×3 | jiangyi-gateway `127.0.0.1:18084:8083`；`/jiangyi/` + `/websocket/`（proxy_read_timeout 3600s） |
| 模拟器 | `simulator/jiangyi/JiangyiDeviceSimulator` | `java -jar … jiangyi`：取 token→WS→收 openDoor→锁/门/识别上报全链路 |

## 2. 端点契约（设备面路径照抄 V16 文档，domain 尾段=/jiangyi/api）

| 设备动作 | 端点 | 关键字段 |
|---|---|---|
| 换 token | `POST /jiangyi/api/token/openDoorDeviceStatus` | `{deviceSn}` → `{status:200,data:{token,identifier}}` |
| 开锁状态 | `POST /jiangyi/api/device/uploadLockState` | `{orderNo, lockStatus}` fail→trade open-failed |
| 拉门状态 | `POST /jiangyi/api/device/uploadDoorState` | `{orderNo, doorStatus}` success→door-event OPEN |
| 识别结果 | `POST /jiangyi/api/orderProduct/addRecognitionGoodsToOrder` | `{orderNo, forms:[{classId,quantity,specialId}]}` → 先合成 CLOSED → 归一化 → edge-results |
| 异常/大模型 | `POST /jiangyi/api/device/pokerOrder/uploadOrderError` | `bigModel:"doing"`→挂起 10min 兜底；否则→DISPUTED |
| 双门占用/故障单 | `/jiangyi/api/order/openDoubleDoorError`、`/order/uploadFaultOrder` | 仅审计 |

## 3. 鉴权（两把钥匙 + 设备 JWT）

- `JIANGYI_TENANT_ID` / `JIANGYI_SECRET`：仅 setDomain（入驻）。
- `JIANGYI_MERCHANT_MOBILE` / `JIANGYI_MERCHANT_PASSWORD`：商户云 login2 → Bearer（缓存 25min，401 重登一次）。
- `JIANGYI_JWT_SECRET`（≥32 字节，无默认值→503 fail-closed）：设备 token；claims sn/deviceId/identifier/ver/exp(7d)；吊销=表 token_version+1（`/token-revoke`），签发只记 `token_issued_at` 不 bump。

## 4. 部署要点

- compose：`docker compose -f infra/docker-compose.full.yml build jiangyi-gateway && docker compose -f infra/docker-compose.full.yml up -d --no-deps --force-recreate jiangyi-gateway`（拆两步，铁律 #41）。
- 内网穿透（联调）：frp/cpolar → 本机 nginx 80/443 → gateway `127.0.0.1:18084`；setDomain 的 domain=`https://<穿透域名>/jiangyi/api`、socketUrl=`wss://<穿透域名>/websocket`。
- 验证顺序（联调日 1）：入驻 register→bindDomain（setDomain）→ 设备通电 → token 签发 → WS 在线（`/internal/v1/jiangyi/devices/{id}/routable`）→ 扫码开门 → 模拟器/真机上报 → 结算 COMPLETED。

## 5. 已知边界与待办

- 一期只开放购物开门；强制开门空 msgContent 结构性拒绝；operatorMode 501。
- specialId 不采信（V16 §4.2.10 唯一出口、来源未定义）。
- 二期：视频（getTempUploadToken/uploadVideoUrl 逗号拼接容错）、模型同步预生成映射、补货开门真路由。
- 待办：`pnpm gen:api-types` 重跑（无新增对外 API，暂无影响）、真机联调回填识别率、商户端月度结算单 UI（另任务）。

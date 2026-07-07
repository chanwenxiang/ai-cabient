# Phase 2 指南

## 新增能力

| 模块 | 内容 |
|------|------|
| Android 真机 | Kotlin + MQTT + 门锁 HAL |
| 用户登录 | 手机验证码 + JWT |
| 小程序 | 登录页、扫码、充值 |
| 微信支付 | 充值预下单骨架（mock） |

## 推荐联调顺序

### 1. 后端（同 Phase 1）

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
cd ai-cabinet/infra && docker compose up -d
cd .. && mvn install -DskipTests
# 启动 vision / device / trade 三个服务
```

### 2. 设备端（二选一）

**A. 桌面模拟器（开发）**
```powershell
mvn exec:java -pl edge/device-simulator -Dexec.args="CAB-001"
```

**B. Android 真机**
- Android Studio 打开 `edge/android-app`
- 修改 `MQTT_BROKER` 为电脑 IP
- 安装到设备

### 3. 小程序

1. 微信开发者工具导入 `clients/miniapp`
2. `utils/api.js` 中 `BASE_URL` 改为 trade-service 地址
3. 勾选「不校验合法域名」

**登录测试账号：**
- 手机号：`13800138000`（种子用户，已实名，余额 100 元）
- 验证码：`123456`

### 4. 完整流程

```
登录 → 扫码/输入 CAB-001 → 开门
  → 设备 MQTT 收到指令 → 开门 → 3秒后关门
  → 识别 → 扣款 → 小程序展示账单
```

## 新 API

| 接口 | 说明 |
|------|------|
| `POST /api/v2/auth/sms-code?phoneNumber=` | 发送验证码 |
| `POST /api/v2/auth/login` | 登录，返回 JWT |
| `POST /api/v2/sessions` | 需 Bearer Token |
| `POST /api/v2/payment/recharge/prepay` | 充值预下单 |

## curl 示例

```powershell
# 登录
curl -X POST http://localhost:8080/api/v2/auth/login `
  -H "Content-Type: application/json" `
  -d '{"phoneNumber":"13800138000","code":"123456"}'

# 开门（替换 TOKEN）
curl -X POST http://localhost:8080/api/v2/sessions `
  -H "Authorization: Bearer TOKEN" `
  -H "Content-Type: application/json" `
  -d '{"deviceId":"CAB-001"}'
```

## 下一步 Phase 3

- 微信真实支付对接
- CameraX 录像 + 视觉识别
- 运营补货 App

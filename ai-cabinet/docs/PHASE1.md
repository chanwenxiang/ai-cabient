# Phase 1 联调指南

## 完整链路

```
小程序/ curl  →  trade-service  →  device-service  →  MQTT  →  device-simulator
                                                                    ↓
                                                              door OPEN/CLOSED
                                                                    ↓
                         trade-service  ←  device-service  ←  MQTT evt
                              ↓
                         vision-service (mock 识别)
                              ↓
                         扣款 + 生成订单
```

## 启动步骤

```powershell
# 0. JDK 17（按本机路径设置 JAVA_HOME）
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

# 1. 基础设施
cd ai-cabinet/infra
docker compose up -d

# 2. 编译
cd ..
mvn clean install -DskipTests

# 3. vision-service（终端 1）
cd vision-service
pip install -r requirements.txt
uvicorn app.main:app --port 8082

# 4. device-service（终端 2）
cd services/device-service
mvn spring-boot:run

# 5. trade-service（终端 3）
cd services/trade-service
mvn spring-boot:run

# 6. 设备模拟器（终端 4）
cd edge/device-simulator
mvn exec:java -Dexec.args="CAB-001"
```

## 测试开门

```powershell
# 创建会话
curl -X POST http://localhost:8080/api/v2/sessions `
  -H "Content-Type: application/json" `
  -d '{"deviceId":"CAB-001","userId":10001}'

# 模拟器会自动：开门 → 等3秒 → 关门 → 触发结算

# 查询会话（应 COMPLETED）
curl http://localhost:8080/api/v2/sessions/{sessionId}

# 查询订单
curl http://localhost:8080/api/v2/sessions/{sessionId}/order
```

## 种子数据

| 类型 | 值 |
|------|-----|
| 设备 | CAB-001 |
| 用户 | 10001（已实名，余额 100 元） |
| 商品 | SKU-DEMO-001（可乐 3.5 元） |

## 已实现

- [x] 用户校验（实名 + 余额 ≥ 5 元）
- [x] 设备占用检查（同设备不可重复开门）
- [x] 购物会话状态机
- [x] MQTT 开门指令 + 门状态回调
- [x] Vision mock 识别 + 扣款结算
- [x] 微信小程序骨架
- [x] 桌面设备模拟器

## 下一步（Phase 2）

- Android 真机 MQTT + 串口门锁
- 真实视觉识别模型
- 用户登录 / JWT 鉴权

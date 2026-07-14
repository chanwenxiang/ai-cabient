# Android 真机端

Kotlin 工程，替代 `device-simulator` 用于真实柜体部署。

## 架构

```
MQTT OPEN_DOOR
    → CabinetController
        → CameraX 录像（购物模式）
        → ILockDriver 开锁
        → 等待关门（DoorCloseWatcher 轮询）
        → 停录 → MinIO 上传 → MQTT DOOR CLOSED
        → 失败时 OfflineUploadQueue 断网续传
```

| 包 | 说明 |
|----|------|
| `config/EdgeRuntimeConfig` | BuildConfig + SharedPreferences 运行时配置 |
| `status/DeviceStatusHub` | MQTT/门状态/会话，供 MainActivity 展示 |
| `hal/ILockDriver` | 门锁抽象 |
| `hal/mock/MockLockDriver` | 无硬件模拟（默认），可手动/自动关门 |
| `hal/chzh/ChzhLockDriver` | 创智辉串口协议（参考旧 `ChzhDevice8`） |
| `hal/DoorCloseWatcher` | 轮询等待真实关门（替代固定 3 秒延时） |
| `mqtt/MqttDeviceClient` | MQTT 指令收发 + 心跳 |
| `video/SessionVideoRecorder` | 单摄/双摄 CameraX 录像 |
| `video/VideoClipJson` | MULTI 模式 clips JSON |
| `hal/serial/ChzhSerialPort` | 串口 19200（licheedev） |
| `upload/MinioUploader` | MinIO/S3 上传 |
| `upload/OfflineUploadQueue` | 断网续传 + trade attach video |
| `service/CabinetController` | 开门→购物→关门主流程 |

## 构建变体（Product Flavors）

| Flavor | 说明 |
|--------|------|
| **mock**（默认） | `USE_MOCK_DRIVER=true`，无串口/门锁，适合 Android 模拟器联调 |
| **device** | `USE_MOCK_DRIVER=false`，使用 `ChzhLockDriver` 串口 |

Android Studio 中选择 **mockDebug** 或 **deviceDebug** 后 Run。

## 硬件未定时的开发范围

柜体工控板、门锁、摄像头型号尚未确定时，**不必提前做 USB 外接双摄或全自动 OTA**。当前工程目标是用 **抽象层 + mock 模式** 跑通与云端的契约，硬件到位后只替换 HAL/摄像头绑定。

### 已具备（与具体硬件无关）

| 能力 | 说明 |
|------|------|
| MQTT 开门 / 心跳 / 关门事件 | 与 device-service、trade-service 契约一致 |
| 购物录像 + MinIO 上传 | CameraX；TOP/SIDE 仅为逻辑通道名 |
| MULTI 融合元数据 | `videoClipsJson` + `cameraFusionMode`，vision-service 可联调 |
| 断网续传 | `OfflineUploadQueue` |
| Mock 门锁 | 无硬件即可完成小程序 → 识别 → 扣款闭环 |
| 运行时配置 | Broker、设备 ID、双摄开关等，免重编译 |
| 状态页 | MQTT、门状态、会话、错误 |

### 暂缓（等硬件规格再定）

| 项 | 为何暂缓 | 硬件确定后怎么做 |
|----|----------|------------------|
| **USB 外接双摄** | UVC / MIPI / 厂商 SDK 差异大，现在写易作废 | 在 `SessionVideoRecorder` 内把 TOP/SIDE 绑到实际物理摄像头 |
| **OTA 全自动安装** | 涉及 ROM 权限、静默安装、重启策略 | 首批试点可 **adb / U 盘 / 人工点安装**；量产前再做「下载 APK → 系统安装界面」 |
| **串口门磁细节** | 创智辉协议字段待现场确认 | 扩展 `ChzhLockDriver.parseDoorFeedback()` 关键字 |

### OTA 现状与建议

- **已有**：启动时调用 `OtaChecker` → `GET /internal/v1/devices/{id}/ota/check`，日志输出是否有新版本。
- **开发阶段**：直接 Android Studio Run 或 `adb install` 即可，**无需**做自动下载安装。
- **试点阶段（可选）**：仅「发现新版本 → 下载到本地 → Toast 提示运维安装」。
- **量产阶段**：再评估强制升级、签名校验、回滚。

### 硬件规格书到位时需确认

1. **门锁**：串口路径（`/dev/ttyS2`?）、波特率、开锁/门磁报文格式  
2. **摄像头**：内置双摄 / USB UVC / 第三方 SDK；TOP、SIDE 各对应哪一路  
3. **网络**：工控机访问 EMQX、MinIO、trade 的内网 IP 或域名  
4. **部署**：是否 system 签名、是否 root、App 是否开机自启  

确认后优先改 **`ChzhLockDriver`** 与 **`SessionVideoRecorder`** 的实现，**不必改** MQTT 事件结构与上传 URI 格式。

## 配置

### 编译期（`app/build.gradle.kts`）

默认已指向 Android 模拟器访问本机：

```kotlin
buildConfigField("String", "MQTT_BROKER", "\"tcp://10.0.2.2:11883\"")
buildConfigField("String", "TRADE_SERVICE_URL", "\"http://10.0.2.2:8080\"")
buildConfigField("String", "MINIO_ENDPOINT", "\"http://10.0.2.2:9000\"")
```

真机部署改为电脑局域网 IP，例如 `192.168.1.100`。

### 运行时（App 界面）

MainActivity 可修改 **MQTT Broker** 并保存到 SharedPreferences，**无需重编译**（需重启 App 重连）。

## 本地联调（Android 模拟器）

1. 启动 Docker 基础设施 + trade/device 服务（见 `docs/LOCAL_SETUP.md`）
2. Android Studio 打开 `edge/android-app`，选择 **mockDebug**
3. 启动 Android 模拟器，Run 安装 App
4. **停止**桌面 `DeviceSimulator`（同一 `CAB-001` 不可同时在线）
5. App 界面应显示 **MQTT: 已连接**
6. 小程序消费者登录 → 开门购物
7. Mock 模式约 5 秒自动关门；也可点 **「模拟用户关门」** 立即关门

生产环境建议根据现场协议扩展 `ChzhLockDriver.parseDoorFeedback()` 中的门磁关键字。

### 双摄融合（MULTI）

App 内打开 **「双摄融合」** 开关（或 BuildConfig `MULTI_CAMERA_ENABLED=true`）：

- **TOP**：后置摄像头
- **SIDE**：前置摄像头（不可用时复制 TOP 文件）
- 关门 MQTT 事件携带 `videoClipsJson` + `cameraFusionMode=MULTI`
- 与 `device-simulator` 的 `AICABINET_SIM_MULTI_CAMERA=true` 及 vision-service 融合识别对齐

## 切换真机串口

1. 构建变体选 **deviceDebug**
2. 串口路径默认 `/dev/ttyS2`
3. 依赖 `com.licheedev:android-serialport`，波特率 **19200**
4. 开锁指令：`L1@200\r\n`
5. 串口回包含 `DOOR=C` / `DOOR=O` 时自动更新门状态
6. 需工控机串口权限（system app 或 root）

## 运营补货

`operatorMode=true` 的 OPEN_DOOR 指令：

- 不录像
- 开锁后等待关门（最长 10 分钟）
- 上报 `DOOR CLOSED`（**已修复**：此前补货模式不会上报关门）

## 与 device-simulator 的关系

| | device-simulator | android-app |
|--|----------------|-------------|
| 场景 | IDEA 桌面联调 | 工控机/模拟器真机部署 |
| 视频 | 环境变量指定测试图 | CameraX 录像 |
| 门锁 | 固定 sleep 3s | 等待关门 / Mock 自动关门 |

**同一 deviceId 只能有一个在线**（MQTT client + 心跳）。

## 构建

1. Android Studio 打开 `edge/android-app`
2. 修改 Broker / IP（或使用 App 内运行时配置）
3. Run 安装到柜体工控机或模拟器

## 版本

当前 `0.6.0`：双摄 MULTI 上传、串口 JNI（licheedev）、关门检测、补货流程、状态 UI。

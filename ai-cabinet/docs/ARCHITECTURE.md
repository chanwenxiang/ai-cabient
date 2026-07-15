# 设计文档索引

完整架构设计见对话记录；本文档为项目内快速参考。

## 与旧系统关系

```
demo/
├── easygo/ego-automat/     ← 旧系统，只读参考，不修改
├── ego-automat-android/    ← 旧设备端，只读参考
└── ai-cabinet/             ← 新系统（本项目）
```

## 核心改进

| 旧 (ego-automat) | 新 (ai-cabinet) |
|------------------|-----------------|
| 重力传感器识别 | AI 视觉识别 + 可选重力融合 |
| Socket.IO (M8) | MQTT 5.0 统一协议 |
| m8_door_current_status | shopping_session 状态机 |
| Spring Boot 1.2 / Java 8 | Spring Boot 3.2 / Java 17 |
| 29 模块单体 | 按域拆分微服务 |

## 服务边界

- **trade-service**：购物会话、订单、结算
- **device-service**：设备指令、MQTT、状态
- **vision-service**：AI 识别（Python）
- **edge/android-app**：设备端（待建）

## API 版本

- 旧：`/m8/v1/*` — 不迁移
- 新：`/api/v2/*` — 本项目管理

## 环境要求

- JDK 17+
- Docker（PostgreSQL、Redis、EMQX、MinIO）
- Python 3.10+（vision-service）

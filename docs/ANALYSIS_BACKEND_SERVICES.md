# 后端服务详细分析报告

## 一、服务架构概览

| 服务 | 端口 | 技术栈 | 主要职责 |
|------|------|--------|----------|
| trade-service | 8080 | Spring Boot 3 + Java 17 | 交易、订单、支付、用户 |
| device-service | 8081 | Spring Boot 3 + Java 17 | 设备管理、MQTT通信 |
| vision-service | 8082 | FastAPI + Python 3.10 | 视觉识别、SKU检测 |

---

## 二、trade-service (交易服务)

### 2.1 目录结构

`
services/trade-service/
├── src/main/java/com/aicabinet/trade/
│   ├── api/              # REST Controller (30+)
│   ├── service/          # 业务服务 (88个)
│   ├── domain/           # 领域模型
│   ├── mapper/           # MyBatis Mapper
│   ├── config/           # 配置类
│   ├── payment/          # 支付对接
│   ├── auth/             # 认证授权
│   ├── client/           # 外部客户端
│   ├── event/            # 事件处理
│   ├── filter/           # 过滤器
│   ├── messaging/        # 消息处理
│   ├── metrics/          # 监控指标
│   ├── reconciliation/   # 对账
│   ├── sms/              # 短信
│   ├── storage/          # 存储
│   ├── support/          # 支撑服务
│   └── util/             # 工具类
└── src/main/resources/
    ├── application.yml
    ├── application-dev.yml
    ├── application-prod.yml
    └── db/migration/     # Flyway迁移脚本
`

### 2.2 核心服务类清单

#### 订单与交易
| 服务 | 功能 | 状态 |
|------|------|------|
| OrderService | 订单管理 | ✅ 完成 |
| OrderPaymentService | 订单支付 | ✅ 完成 |
| SessionService | 购物会话 | ✅ 完成 |
| SettlementService | 结算服务 | ✅ 完成 |

#### 支付相关
| 服务 | 功能 | 状态 |
|------|------|------|
| PaymentService | 充值支付 | ✅ Mock完成 |
| WeChatPayClient | 微信支付SDK | ⚠️ 需配置 |
| AlipayPayClient | 支付宝SDK | ⚠️ 需配置 |
| PayScoreService | 微信支付分 | ⚠️ 需配置 |

#### 用户相关
| 服务 | 功能 | 状态 |
|------|------|------|
| AuthService | 认证服务 | ✅ 完成 |
| MemberService | 会员服务 | ✅ 完成 |
| AccountService | 账户服务 | ✅ 完成 |
| MerchantService | 商户服务 | ✅ 完成 |

#### 库存相关
| 服务 | 功能 | 状态 |
|------|------|------|
| InventoryService | 库存管理 | ✅ 完成 |
| InventoryLotService | 批次管理 | ✅ 完成 |
| ReplenishmentService | 补货服务 | ✅ 完成 |
| WarehouseService | 仓库服务 | ✅ 完成 |

#### 风控与争议
| 服务 | 功能 | 状态 |
|------|------|------|
| RiskControlService | 风控服务 | ✅ 完成 |
| DisputeService | 争议处理 | ✅ 完成 |
| DisputeSlaService | SLA管理 | ✅ 完成 |

#### 对账与结算
| 服务 | 功能 | 状态 |
|------|------|------|
| ReconciliationService | 对账服务 | ✅ Mock完成 |
| RevenueSplitService | 分账服务 | ✅ 完成 |
| FinanceReportService | 财务报表 | ✅ 完成 |

#### 技术保障
| 服务 | 功能 | 状态 |
|------|------|------|
| DistributedLockService | 分布式锁 | ✅ 完成 |
| IdempotencyService | 幂等性 | ✅ 完成 |
| DataConsistencyService | 数据一致性 | ✅ 完成 |
| CacheService | 缓存服务 | ✅ 完成 |

### 2.3 Mock配置清单

#### 应用配置
`yaml
# application.yml
aicabinet:
  security:
    mock-enabled: true  # P0: 上线必须改为false
  
  reconciliation:
    mock-enabled: true  # P0: 上线必须改为false
  
  wechat-pay:
    enabled: false      # P0: 上线必须改为true
  
  wechat-miniapp:
    enabled: false      # P0: 上线必须改为true
`

#### Mock相关代码位置
| 文件 | 行号 | 内容 | 状态 |
|------|------|------|------|
| DevMockPaymentController.java | 11-23 | Mock微信支付 | 生产禁用 |
| DevMockAlipayPaymentController.java | 11-23 | Mock支付宝 | 生产禁用 |
| PaymentService.java | 91-100 | Mock充值确认 | 生产禁用 |
| DemoDataBootstrap.java | 22 | 演示数据初始化 | 生产禁用 |

### 2.4 数据库迁移脚本

| 版本 | 文件 | 内容 |
|------|------|------|
| V1 | V1__init_schema.sql | 核心表结构 |
| V2 | V2__user_order_sku.sql | 用户/订单/SKU |
| V3 | V3__auth_payment.sql | 认证/支付 |
| V4 | V4__dispute_ops.sql | 争议/运营 |
| V5 | V5__dispute_vision.sql | 争议扩展/SKU视觉 |

---

## 三、device-service (设备服务)

### 3.1 目录结构

`
services/device-service/
├── src/main/java/com/aicabinet/device/
│   ├── api/        # REST Controller
│   ├── service/    # 业务服务
│   ├── mqtt/       # MQTT通信
│   ├── client/     # 外部客户端
│   ├── config/     # 配置类
│   ├── health/     # 健康检查
│   └── metrics/    # 监控指标
`

### 3.2 核心功能

| 功能 | 说明 | 状态 |
|------|------|------|
| 设备注册 | 柜机上线注册 | ✅ 完成 |
| 设备状态 | 在线/离线状态 | ✅ 完成 |
| 开门指令 | 发送开门指令 | ⚠️ 模拟器 |
| 关门事件 | 接收关门事件 | ⚠️ 模拟器 |
| 心跳处理 | 设备心跳上报 | ✅ 完成 |
| 门锁状态 | 门锁开关状态 | ⚠️ 待对接 |

### 3.3 MQTT主题设计

| 主题 | 方向 | 说明 |
|------|------|------|
| cabinet/{deviceId}/command | 下行 | 开门指令 |
| cabinet/{deviceId}/status | 上行 | 设备状态 |
| cabinet/{deviceId}/event | 上行 | 事件上报 |
| cabinet/{deviceId}/heartbeat | 上行 | 心跳 |

### 3.4 硬件协议对接需求

| 协议 | 功能 | 优先级 | 状态 |
|------|------|--------|------|
| 开门协议 | 发送开门指令 | P0 | 待对接 |
| 关门协议 | 接收关门事件 | P0 | 待对接 |
| 门锁协议 | 检测门锁状态 | P0 | 待对接 |
| 录制协议 | 控制摄像头录制 | P0 | 待对接 |
| 心跳协议 | 设备在线检测 | P1 | 已实现 |
| OTA协议 | 固件升级 | P2 | 待实现 |

---

## 四、vision-service (视觉识别服务)

### 4.1 目录结构

`
vision-service/
├── app/
│   ├── recognition/
│   │   ├── __init__.py
│   │   ├── detector.py     # YOLO检测器
│   │   ├── tracker.py      # 目标跟踪
│   │   └── classifier.py   # SKU分类
│   ├── main.py             # FastAPI入口
│   └── config.py           # 配置
├── requirements-base.txt   # 基础依赖
└── requirements-ml.txt     # ML依赖
`

### 4.2 核心功能

| 功能 | 说明 | 状态 |
|------|------|------|
| 视频解析 | 读取上传视频 | ✅ 完成 |
| 帧提取 | 提取关键帧 | ✅ 完成 |
| 商品检测 | YOLO目标检测 | ⚠️ 通用模型 |
| SKU分类 | 商品类别识别 | ⚠️ 待训练 |
| 数量统计 | 拿取/放回统计 | ✅ 完成 |
| 结果返回 | 返回识别结果 | ✅ 完成 |

### 4.3 识别流程

`
1. 接收视频URL
2. 下载视频文件
3. 提取关键帧
4. YOLO目标检测
5. SKU分类识别
6. 跟踪目标轨迹
7. 统计拿取/放回
8. 返回结算结果
`

### 4.4 模型训练需求

| 需求 | 说明 | 时间 |
|------|------|------|
| 数据采集 | 每SKU拍摄100+张图片 | 1-2周 |
| 数据标注 | 标注商品类别和边界 | 1周 |
| 模型训练 | 训练SKU专用YOLO模型 | 2-3天 |
| 模型验证 | 测试集验证准确率 | 2-3天 |
| 模型部署 | 部署到vision-service | 1天 |

---

## 五、Mock功能清理清单

### 5.1 后端Mock代码

| 模块 | 文件 | 内容 | 上线处理 |
|------|------|------|----------|
| trade-service | DevMockPaymentController.java | Mock微信支付 | @ConditionalOnProperty已控制 |
| trade-service | DevMockAlipayPaymentController.java | Mock支付宝 | @ConditionalOnProperty已控制 |
| trade-service | PaymentService.java:91-100 | Mock充值 | 需设置mock-enabled=false |
| trade-service | DemoDataBootstrap.java | 演示数据 | @ConditionalOnProperty已控制 |
| trade-service | AuthService.java | 验证码固定123456 | 需接入短信服务 |

### 5.2 前端Mock代码

| 端 | 文件 | 内容 | 上线处理 |
|------|------|------|----------|
| admin-vue | UserListView.vue | 测试余额 | 环境变量控制显示 |
| consumer-mp | mine.vue | Mock充值 | 环境变量控制显示 |
| consumer-mp | open-prep-drawer.vue | Mock充值 | 环境变量控制显示 |

---

## 六、生产环境配置

### 6.1 环境变量

`ash
# 数据库
SPRING_DATASOURCE_URL=jdbc:postgresql://prod-db:5432/aicabinet
SPRING_DATASOURCE_USERNAME=
SPRING_DATASOURCE_PASSWORD=

# Redis
REDIS_HOST=prod-redis
REDIS_PORT=6379
REDIS_PASSWORD=

# 关闭Mock
AICABINET_MOCK_ENABLED=false
RECON_MOCK_ENABLED=false

# 微信支付
WECHAT_APP_ID=
WECHAT_MCH_ID=
WECHAT_API_V3_KEY=
WECHAT_MCH_SERIAL=
WECHAT_PRIVATE_KEY=

# 支付宝
ALIPAY_APP_ID=
ALIPAY_PRIVATE_KEY=
ALIPAY_PUBLIC_KEY=

# JWT
JWT_SECRET=

# 内部API
INTERNAL_API_KEY=
VISION_API_KEY=
`

### 6.2 生产启动检查

`java
// ProductionStartupValidator.java
// 已实现以下检查：
// 1. prod profile禁止mock-enabled=true
// 2. 必须配置数据库
// 3. 必须配置Redis
// 4. 必须配置支付参数
`

---

## 七、安全配置清单

### 7.1 认证授权
- [x] JWT Token认证
- [x] RBAC权限控制
- [x] 接口鉴权
- [ ] 多因素认证（可选）

### 7.2 数据安全
- [x] 敏感数据加密存储
- [x] SQL注入防护
- [x] XSS防护
- [ ] 数据脱敏导出

### 7.3 接口安全
- [x] HTTPS强制
- [x] 请求签名验证
- [x] 幂等性控制
- [x] 请求限流

### 7.4 运行安全
- [x] 日志脱敏
- [x] 审计日志
- [ ] 异常告警
- [ ] 安全扫描

---

## 八、性能优化建议

### 8.1 数据库优化
- 读写分离
- 分库分表（订单表）
- 索引优化
- 连接池调优

### 8.2 缓存优化
- 热点数据缓存
- 分布式缓存
- 缓存预热
- 缓存穿透防护

### 8.3 服务优化
- 异步处理
- 批量操作
- 连接复用
- 资源池化

---

## 九、监控告警配置

### 9.1 基础监控
| 指标 | 阈值 | 告警级别 |
|------|------|----------|
| CPU使用率 | >80% | Warning |
| 内存使用率 | >85% | Warning |
| 磁盘使用率 | >90% | Critical |
| 服务健康状态 | Down | Critical |

### 9.2 业务监控
| 指标 | 阈值 | 告警级别 |
|------|------|----------|
| 开门成功率 | <99% | Warning |
| 识别超时率 | >5% | Warning |
| 支付失败率 | >1% | Critical |
| 争议处理延迟 | >24h | Warning |

### 9.3 告警通知
| 渠道 | 接收人 | 用途 |
|------|--------|------|
| 邮件 | 运维团队 | 系统告警 |
| 短信 | 运维负责人 | Critical告警 |
| 微信 | 开发团队 | 业务告警 |

---

## 十、上线前检查清单

### 10.1 服务检查
- [ ] trade-service健康检查通过
- [ ] device-service健康检查通过
- [ ] vision-service健康检查通过
- [ ] 数据库连接正常
- [ ] Redis连接正常
- [ ] MQTT连接正常
- [ ] MinIO连接正常

### 10.2 配置检查
- [ ] Mock功能全部关闭
- [ ] 支付配置正确
- [ ] 安全配置正确
- [ ] 日志配置正确
- [ ] 监控配置正确

### 10.3 安全检查
- [ ] 敏感信息已脱敏
- [ ] 密钥已更新
- [ ] 权限配置正确
- [ ] 防火墙规则正确

### 10.4 性能检查
- [ ] 压力测试通过
- [ ] 响应时间达标
- [ ] 资源使用正常
- [ ] 无内存泄漏

---

**文档状态**: 完成
**阻塞项**: 支付配置、硬件对接、SKU模型训练
**下一步**: 配置生产环境，对接支付，训练SKU模型

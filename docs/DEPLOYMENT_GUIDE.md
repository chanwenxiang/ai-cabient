# AI Cabinet 部署手册

## 📋 目录

1. [环境要求](#环境要求)
2. [快速部署](#快速部署)
3. [Docker部署](#docker部署)
4. [Kubernetes部署](#kubernetes部署)
5. [配置说明](#配置说明)
6. [常见问题](#常见问题)

---

## 环境要求

### 基础环境

| 组件 | 版本要求 | 说明 |
|------|---------|------|
| Java | 17+ | 运行环境 |
| Node.js | 24.18+（LTS） | 前端构建 / Maven frontend-maven-plugin |
| PostgreSQL | 14+ | 主数据库 |
| Redis | 7+ | 缓存/分布式锁 |
| EMQX | 5+ | MQTT消息服务 |
| MinIO | RELEASE.2024+ | 对象存储 |
| Kafka | 3+ | 消息队列 |

### 硬件要求

#### 开发环境
- CPU: 4核+
- 内存: 8GB+
- 磁盘: 50GB+

#### 生产环境
- CPU: 8核+
- 内存: 16GB+
- 磁盘: 200GB+ SSD

---

## 快速部署

### 1. 克隆项目

`ash
git clone https://github.com/your-org/ai-cabinet.git
cd ai-cabinet
`

### 2. 启动基础设施

`ash
# 使用Docker Compose启动
docker-compose up -d postgres redis emqx minio kafka
`

### 3. 初始化数据库

`ash
# 运行数据库迁移
./mvnw flyway:migrate -pl services/trade-service
`

### 4. 启动后端服务

`ash
# 构建项目
./mvnw clean package -DskipTests

# 启动服务
java -jar services/trade-service/target/trade-service.jar
`

### 5. 构建前端

`ash
# 消费者小程序
cd clients/consumer-mp
npm install
npm run build:mp-weixin

# 商户小程序
cd clients/merchant-mp
npm install
npm run build:mp-weixin

# 运营后台
cd clients/admin-vue
npm install
npm run build
`

---

## Docker部署

### 1. 构建镜像

`ash
# 构建Java服务镜像
docker build -f Dockerfile.java-service -t ai-cabinet/trade-service:latest .

# 构建前端镜像
docker build -f Dockerfile.frontend -t ai-cabinet/admin-ui:latest clients/admin-vue
`

### 2. Docker Compose配置

`yaml
version: '3.8'

services:
  postgres:
    image: postgres:14-alpine
    environment:
      POSTGRES_DB: ai_cabinet
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: postgres
    volumes:
      - postgres-data:/var/lib/postgresql/data
    ports:
      - "5432:5432"
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U postgres"]
      interval: 10s
      timeout: 5s
      retries: 5

  redis:
    image: redis:7-alpine
    command: redis-server --requirepass redis123
    ports:
      - "6379:6379"
    volumes:
      - redis-data:/data

  emqx:
    image: emqx/emqx:5
    ports:
      - "1883:1883"
      - "8083:8083"
      - "18083:18083"
    environment:
      EMQX_NAME: ai-cabinet
      EMQX_HOST: 0.0.0.0

  minio:
    image: minio/minio:latest
    command: server /data --console-address ":9001"
    environment:
      MINIO_ROOT_USER: admin
      MINIO_ROOT_PASSWORD: admin123
    ports:
      - "9000:9000"
      - "9001:9001"
    volumes:
      - minio-data:/data

  kafka:
    image: confluentinc/cp-kafka:latest
    depends_on:
      - zookeeper
    ports:
      - "9092:9092"
    environment:
      KAFKA_BROKER_ID: 1
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1

  zookeeper:
    image: confluentinc/cp-zookeeper:latest
    environment:
      ZOOKEEPER_CLIENT_PORT: 2181

  trade-service:
    image: ai-cabinet/trade-service:latest
    depends_on:
      postgres:
        condition: service_healthy
      redis:
        condition: service_started
    ports:
      - "8080:8080"
    environment:
      SPRING_PROFILES_ACTIVE: prod
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/ai_cabinet
      SPRING_DATASOURCE_USERNAME: postgres
      SPRING_DATASOURCE_PASSWORD: postgres
      SPRING_DATA_REDIS_HOST: redis
      SPRING_DATA_REDIS_PASSWORD: redis123
      EMQX_HOST: emqx
      MINIO_ENDPOINT: http://minio:9000

  device-service:
    image: ai-cabinet/device-service:latest
    depends_on:
      - trade-service
    ports:
      - "8081:8080"
    environment:
      SPRING_PROFILES_ACTIVE: prod

volumes:
  postgres-data:
  redis-data:
  minio-data:
`

### 3. 启动服务

`ash
# 启动所有服务
docker-compose up -d

# 查看日志
docker-compose logs -f trade-service

# 停止服务
docker-compose down
`

---

## Kubernetes部署

### 1. 创建命名空间

`yaml
apiVersion: v1
kind: Namespace
metadata:
  name: ai-cabinet
`

### 2. 配置ConfigMap

`yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: ai-cabinet-config
  namespace: ai-cabinet
data:
  SPRING_PROFILES_ACTIVE: "prod"
  SPRING_DATASOURCE_URL: "jdbc:postgresql://postgres:5432/ai_cabinet"
  SPRING_DATA_REDIS_HOST: "redis"
`

### 3. 配置Secret

`yaml
apiVersion: v1
kind: Secret
metadata:
  name: ai-cabinet-secret
  namespace: ai-cabinet
type: Opaque
stringData:
  SPRING_DATASOURCE_USERNAME: postgres
  SPRING_DATASOURCE_PASSWORD: postgres
  SPRING_DATA_REDIS_PASSWORD: redis123
`

### 4. 部署服务

`yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: trade-service
  namespace: ai-cabinet
spec:
  replicas: 3
  selector:
    matchLabels:
      app: trade-service
  template:
    metadata:
      labels:
        app: trade-service
    spec:
      containers:
      - name: trade-service
        image: ai-cabinet/trade-service:latest
        ports:
        - containerPort: 8080
        envFrom:
        - configMapRef:
            name: ai-cabinet-config
        - secretRef:
            name: ai-cabinet-secret
        resources:
          requests:
            cpu: 500m
            memory: 1Gi
          limits:
            cpu: 2000m
            memory: 2Gi
        livenessProbe:
          httpGet:
            path: /actuator/health
            port: 8080
          initialDelaySeconds: 60
          periodSeconds: 10
        readinessProbe:
          httpGet:
            path: /actuator/health/readiness
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 5
---
apiVersion: v1
kind: Service
metadata:
  name: trade-service
  namespace: ai-cabinet
spec:
  selector:
    app: trade-service
  ports:
  - port: 80
    targetPort: 8080
  type: ClusterIP
---
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: ai-cabinet-ingress
  namespace: ai-cabinet
spec:
  rules:
  - host: api.aicabinet.com
    http:
      paths:
      - path: /
        pathType: Prefix
        backend:
          service:
            name: trade-service
            port:
              number: 80
`

### 5. 部署命令

`ash
# 应用配置
kubectl apply -f k8s/namespace.yaml
kubectl apply -f k8s/configmap.yaml
kubectl apply -f k8s/secret.yaml

# 部署服务
kubectl apply -f k8s/deployment.yaml

# 查看状态
kubectl get pods -n ai-cabinet
kubectl logs -f deployment/trade-service -n ai-cabinet

# 扩容
kubectl scale deployment trade-service --replicas=5 -n ai-cabinet
`

---

## 配置说明

### 环境变量

| 变量名 | 说明 | 默认值 |
|-------|------|--------|
| SPRING_PROFILES_ACTIVE | 环境标识 | dev |
| SPRING_DATASOURCE_URL | 数据库地址 | jdbc:postgresql://localhost:5432/ai_cabinet |
| SPRING_DATASOURCE_USERNAME | 数据库用户名 | postgres |
| SPRING_DATASOURCE_PASSWORD | 数据库密码 | postgres |
| SPRING_DATA_REDIS_HOST | Redis地址 | localhost |
| SPRING_DATA_REDIS_PASSWORD | Redis密码 | - |
| EMQX_HOST | MQTT服务地址 | localhost |
| MINIO_ENDPOINT | 对象存储地址 | http://localhost:9000 |
| WECHAT_APPID | 微信小程序AppID | - |
| WECHAT_SECRET | 微信小程序Secret | - |

### application.yml配置

`yaml
spring:
  profiles:
    active: 
  
  datasource:
    url: 
    username: 
    password: 
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
  
  data:
    redis:
      host: 
      password: 
      port: 6379
  
  jpa:
    hibernate:
      ddl-auto: validate
    show-sql: false

server:
  port: 8080

management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
  metrics:
    export:
      prometheus:
        enabled: true
`

---

## 常见问题

### 1. 数据库连接失败

**问题**: 无法连接到PostgreSQL

**解决方案**:
`ash
# 检查PostgreSQL是否运行
docker ps | grep postgres

# 检查连接配置
psql -h localhost -U postgres -d ai_cabinet

# 查看日志
docker logs ai-cabinet-postgres
`

### 2. Redis连接超时

**问题**: Redis连接超时

**解决方案**:
`ash
# 检查Redis是否运行
docker ps | grep redis

# 测试连接
redis-cli -h localhost -p 6379 -a redis123 ping

# 检查密码配置
echo "requirepass redis123" >> redis.conf
`

### 3. EMQX无法连接

**问题**: 设备无法连接MQTT

**解决方案**:
`ash
# 检查EMQX状态
docker exec -it emqx emqx ctl status

# 查看EMQX日志
docker logs emqx

# 访问管理控制台
open http://localhost:18083
# 默认账号: admin / public
`

### 4. MinIO上传失败

**问题**: 文件上传失败

**解决方案**:
`ash
# 检查MinIO状态
curl http://localhost:9000/minio/health/live

# 创建bucket
mc alias set local http://localhost:9000 admin admin123
mc mb local/product-images
mc policy set public local/product-images
`

### 5. 服务启动慢

**问题**: 服务启动时间过长

**解决方案**:
`ash
# 检查数据库迁移状态
./mvnw flyway:info

# 跳过数据库迁移（已迁移）
java -jar app.jar --spring.flyway.enabled=false

# 减少日志输出
java -jar app.jar --logging.level.root=WARN
`

---

## 性能调优

### JVM参数

`ash
# 生产环境推荐
java -Xms2g -Xmx2g \
     -XX:+UseG1GC \
     -XX:MaxGCPauseMillis=200 \
     -XX:+HeapDumpOnOutOfMemoryError \
     -XX:HeapDumpPath=/var/log/heap_dump.hprof \
     -jar trade-service.jar
`

### 数据库优化

`sql
-- 创建索引
CREATE INDEX idx_orders_user_created ON orders(user_id, created_at DESC);
CREATE INDEX idx_devices_status ON devices(status);

-- 分析表
ANALYZE orders;
ANALYZE devices;

-- 查看慢查询
SELECT * FROM pg_stat_statements ORDER BY total_time DESC LIMIT 10;
`

### Redis优化

`ash
# 增加内存限制
maxmemory 2gb

# 使用LRU淘汰策略
maxmemory-policy allkeys-lru

# 持久化配置
save 900 1
save 300 10
appendonly yes
`

---

## 监控与日志

### Prometheus指标

访问: http://localhost:8080/actuator/prometheus

关键指标:
- http_server_requests_seconds - HTTP请求耗时
- jvm_memory_used_bytes - JVM内存使用
- hikaricp_connections_active - 数据库连接数

### 日志查看

`ash
# Docker日志
docker-compose logs -f trade-service

# Kubernetes日志
kubectl logs -f deployment/trade-service -n ai-cabinet

# 应用日志
tail -f /var/log/ai-cabinet/trade-service.log
`

---

**文档版本**: v1.0
**更新时间**: 2026-07-14

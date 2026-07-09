# 部署清单（Staging / Production）

与 [PRODUCTION.md](PRODUCTION.md) 互补：本文是可勾选的上线步骤。

---

## 1. 本地 / 演示（已完成能力）

| 步骤 | 命令 |
|------|------|
| 启动全栈 | `cd infra && docker compose -f docker-compose.yml -f docker-compose.apps.yml --profile apps up -d --build` |
| 完整 E2E | `.\scripts\verify-step1.ps1 -Build` |
| Phase A 效期/补货 | `.\scripts\e2e-inventory-phase-a.ps1` |
| Phase B 仓配 | `.\scripts\e2e-warehouse-phase-b.ps1` |

**演示数据（Flyway V25）：** 6 款 SKU（分类+占位图）、CAB-001 柜内库存、WH-DEMO-001 仓库批次。

**小程序：** 开发者工具勾选「不校验合法域名」以便加载 `placehold.co` 商品图；上线前改为 OSS/CDN 并配置 downloadFile 白名单。

---

## 2. Staging 预发

```powershell
copy infra\.env.staging.example infra\.env.staging
# 编辑 JWT / INTERNAL / POSTGRES 等密钥

.\scripts\deploy-staging.ps1
```

包含：
- `docker-compose.staging.yml`（`SPRING_PROFILES_ACTIVE=staging`，SMS mock，mock 支付关闭）
- `verify-step5.ps1 -CheckEnv` 校验
- 可选 E2E：inventory + warehouse

**Staging 与 dev 差异：**

| 项 | dev | staging |
|----|-----|---------|
| Mock 登录验证码 | 123456 | SMS mock 8099 |
| AICABINET_MOCK_ENABLED | true | false |
| Vision | mock | mock（可改 yolo） |

---

## 3. Production 生产

```powershell
copy infra\.env.production.example infra\.env.production
# 填写全部 WECHAT_* / SMS / OSS / 强密钥

.\scripts\deploy-production.ps1   # 仅校验，不自动部署
```

### 3.1 部署前必查

- [ ] `SPRING_PROFILES_ACTIVE=prod`
- [ ] `JWT_SECRET` / `INTERNAL_API_KEY` / `VISION_API_KEY` ≥ 32 字符，非默认值
- [ ] `SMS_WEBHOOK_URL` 可达
- [ ] 微信支付 API v3 证书与 notify URL HTTPS
- [ ] 小程序 `WECHAT_MINIAPP_ID` / `SECRET`
- [ ] PostgreSQL 备份；Flyway 迁移至 V26+
- [ ] MinIO/OSS 桶策略、CORS、视频生命周期
- [ ] EMQX TLS（设备 MQTT）
- [ ] Gateway HTTPS、CORS 仅运营域名

### 3.2 部署命令

```bash
cd infra
docker compose -f docker-compose.yml -f docker-compose.apps.yml \
  --env-file .env.production --profile apps up -d --build
```

### 3.3 部署后冒烟

1. `GET /actuator/health` → UP  
2. 运营后台登录 + 仪表盘  
3. 仓库页可见 WH-DEMO-001 库存  
4. 小程序：商品列表、开门购物 E2E（真 SMS/支付沙箱）  
5. Prometheus/Grafana（可选）

---

## 4. 数据库迁移版本

| 版本 | 说明 |
|------|------|
| V24 | Phase A 批次/效期/补货行 |
| V25 | 演示 SKU + 柜内库存 |
| V26 | Phase B 仓库 WMS + 出库绑定路线 |

---

## 5. 回滚

1. 停止 apps：`docker compose ... down`  
2. 恢复 PostgreSQL 快照（迁移不可逆时需还原库）  
3. 回退镜像 tag：`IMAGE_TAG=<previous> docker compose ... up -d`

---

## 6. 相关脚本

| 脚本 | 用途 |
|------|------|
| `deploy-staging.ps1` | 预发一键 compose + E2E |
| `deploy-production.ps1` | 生产 env 校验 + 人工清单 |
| `verify-full.ps1` | 本地 Maven + Docker 全量 |
| `init-staging-env.ps1` | 初始化 staging env 文件 |

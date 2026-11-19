# AI 开门柜正式上线准备清单

## P0：未完成不得真实收费上线

- 接入真实柜机开门、关门、门锁状态和心跳协议，完成断网与重复事件测试。
- 安装并启用真实视觉推理依赖，禁止 `MOCK_ENABLED=true` 进入生产。
- 使用真实柜内商品数据训练专用识别模型，不能直接使用通用 YOLO COCO 模型识别具体 SKU。
- 完成顶摄、侧摄、重力传感器和库存快照融合。
- 建立每个 SKU 的识别类别映射、最低置信度和低置信度人工审核规则。
- 完成至少 1,000 次真实拿取/放回组合测试，分别统计商品、数量和组合订单准确率。
- 配置真实微信/支付宝支付商户号、证书、回调域名和退款权限。
- 关闭所有 mock 支付、mock 短信、mock 识别和测试余额入口。
- 完成生产数据库、Redis、对象存储和消息系统的高可用、备份与恢复演练。（**2026-11-02 部分完成**：PG 做了 pg_dump→临时库恢复演练，179 张表与抽样行数一致、恢复 2s；已装 `scripts/backup-postgres.sh` 每日 02:30 cron：本机 7 天 + 流式上传 MinIO `db-backups` 保留 30 天；⚠️ MinIO 与 PG 仍同机，**真异地**需再把桶同步到外部 OSS（待凭据）；Redis/MinIO/EMQX 恢复演练已完成：Redis AOF 快照起临时实例复现探针键、MinIO 备份对象回读 sha256 一致、EMQX 重启后 2 个客户端自动重连；EMQX `--force-recreate` 已演练：认证数据在命名卷、重建后 2 个客户端自动重连；⚠️ bootstrap 对已存在用户跳过（改 CSV 密码不生效），轮换须更新 authn DB 或删卷）
- [ ] **真异地备份（⑱，2026-11-17）**：本机 MinIO 副本不是异地（单机故障同失）。生产须在 `infra/.env.production.example` 的 `BACKUP_REMOTE_CMD` 配好外部 OSS/第二台机器并**演练一次恢复**；未配之前不得把「备份与恢复」勾为完成。
- 所有密钥改用密钥管理服务或部署平台 Secret，不得保留仓库默认密钥（**2026-11-02 已加部署期校验**：`scripts/check-prod-env-secrets.sh` 在 `deploy-prod.sh` build 前运行，缺项/仍是 `aicabinet`·`devredis`·`minioadmin`·`dev-*`·`ci-*` 等开发默认值即中止，只输出键名）。
- 完成资金账本、订单、库存、退款和分账的日终对账。
- Testcontainers 集成测试必须在 CI 中全部执行，不能保留跳过状态。

## P1：灰度运营前完成

- 微信小程序完成主体认证、隐私协议、类目、域名白名单和审核发布。
- 配置正式 HTTPS API、对象存储回源域名和 CDN。
- 运营后台启用最小权限 RBAC，停用演示账号和默认密码。
- 商户账号按设备范围分权，禁止跨商户查看设备、消费者和资金数据。
- 增加异常中心默认“未解决”筛选和历史异常归档，避免已解决记录淹没待办。
- 建立消费者申诉 SLA、升级机制和人工值班表。
- 建立设备离线、长时间未关门、识别失败、余额不足、库存差异和资金差异告警。
- 所有退款、调账、免单和补扣必须二次确认并保留审计记录。
- 完成消费者、商户和运营后台商用 UI 设计与真机适配。
- 至少覆盖常见 iPhone、Android、微信版本和弱网环境。
- 完成用户协议、隐私政策、退款规则、客服入口和消费者账单说明。
- ✅ 微信小程序管理后台「客服」功能已开通（**2026-10-21 用户确认**）；仍须确认：①已绑定 ≥1 名客服人员（【功能】→【客服】→【客服管理】→ 添加客服微信号，对方微信确认）②已打开「在官方客服工作台收发消息」开关；③未因【开发】→【开发设置】→【消息推送】配置导致客服消息被劫持转发到回调（代码入口 `open-type="contact"` 已就绪，无需改码）。
- 复核生产 `system_config` 客服联系方式（2026-10-21 已处理）：`consumer.service_phone` / `merchant.service_phone` 已设为 `4000182992`（用户确认真实号）；`ops.support_email` 已置空 ⇒ C 端/商户端「复制邮箱」入口自动隐藏，不显示邮箱。

## P2：正式规模化运营前完成

- 建立模型版本管理、灰度发布、回滚和识别准确率监控。
- 建立设备固件 OTA 签名、分批升级和失败回滚。
- 建立库存批次、保质期、临期、损耗和盘点闭环。
- 建立补货路线、仓库出入库、在途库存和货道模板。
- 建立商户结算周期、发票、分账失败重试和财务报表。
- 接入日志集中检索、APM、Sentry/错误告警和核心业务指标看板。
- 制定容量目标并完成并发开门、订单结算、视频上传和识别任务压测。
- 制定 RTO/RPO，完成跨主机或跨可用区故障切换演练。
- 完成等保、漏洞扫描、渗透测试、数据脱敏和审计留存策略。

## 建议上线门槛

- 开门指令成功率不低于 99.9%。
- 关门事件完整率不低于 99.99%。
- 单商品识别准确率不低于 99%，多商品组合准确率达到业务可接受阈值。
- 错扣率必须低于明确运营红线，并保证可快速退款。
- 账本、订单和库存日终差异为 0，或全部进入可追踪异常。
- 关键服务连续灰度运行至少 14 天，无未闭环 P0 事故。

## 本地 / 预发：余额-only 无 mock 联调（无需微信商户密钥）

在尚未配置真实微信进件时，可用下列组合验证开门→识别→结算→争议闭环，避免「看起来像生产但仍在 mock」：

1. `AICABINET_MOCK_ENABLED=false`（关支付/短信 mock；短信需配置 `SMS_WEBHOOK_URL`，可用 `scripts/sms-webhook-mock.py`）
2. `CHECKOUT_BALANCE_ONLY=true`（结算仅走余额，不强制 live 微信预下单）
3. `RECON_MOCK_ENABLED=true`（无微信账单时保持对账 mock）
4. 消费者端使用 **生产构建**（`npm run build:mp-weixin`）验收：不应出现「验证码 123456 / 模拟充值」文案；开发构建（`DEV`）才显示联调入口
5. Vision：开发可用 mock；关闭 mock 前须确认端侧识别上报可用（见 `docs/VISION_QUECTEL_INTEGRATION.md`），勿再依赖已移除的云端 YOLO 权重

说明：正式 `prod` profile 仍要求微信 V3 / 小程序配置；本路径只用于预发验证业务闭环，不能替代真钱进件。

## 客服系统上线前置（2026-10-21 复核）

代码侧已就绪且闭环打通，无需改码，仅剩运营/配置动作：

- **微信客服后台开通**：`open-type="contact"` 入口已分布 help / order-detail / index / dispute 等页；**2026-10-21 用户确认已开通客服功能**，仍须确认：①绑定客服人员微信号；②打开「在官方客服工作台收发消息」开关。
- **配置生效链路已实证**（前后端 key 严格匹配，`SystemConfigService.java:597/666/598` → 前端 `help.vue:215/220`、`index.vue:1390`、`order-detail.vue:502`、`dispute/detail.vue:315`）：
  - `consumer.service_phone` / `merchant.service_phone` = `4000182992`（用户确认真实号，已 UPDATE 生产库；代码默认值同步改为 `4000182992`，见 `SystemConfigService.java:597`/`:666`）。
  - `ops.support_email` = 空（**2026-10-21 已置空 ⇒ 邮箱入口不显示**；代码默认值 `SystemConfigService.java:598` 同步改为空串，删 key 也不会兜底假邮箱）。
- 结构化「意见反馈」走自有 `feedback` 后端（`/api/v2/feedback` + admin `ops:feedback`），不受微信客服 48h/5 条限制，与 `open-type="contact"` 原生客服互补。
- 验证方式：开发者工具体验版进「我的 → 帮助与客服 → 在线客服」应拉起会话；用绑定客服微信号在「客服小助手」或 `mpkf.weixin.qq.com` 看是否收到测试消息。

## 柜门码扫码链路（2026-10-21 定版）

- 🔴 **根因实证**：生产 compose 链（4 文件链）此前从未映射 `QR_PUBLIC_HOST`（只在 full.yml 有，而生产不叠 full.yml）⇒ Spring `auto` 探测容器内网 IP `http://172.19.0.8`（Docker bridge）⇒ 柜门码 `http://172.19.0.8/o/{deviceId}` 外部手机不可达。
- **修复**：`docker-compose.production.yml` trade-service 补映射 `QR_PUBLIC_HOST`；`infra/.env.production` 显式设 `https://cabinet.easygovm.com`（与 `PUBLIC_BASE_URL` 同域，DNS 已解析到生产机，宿主 nginx 443 终结 TLS → gateway 容器 127.0.0.1:8180）。
- **微信后台「扫普通链接二维码打开小程序」规则填法**：协议 https；二维码规则 `https://cabinet.easygovm.com/o/`；前缀占用=不占用；小程序功能页面 `pages/index/index`（`parseLaunchOptions` 已支持从 `q` 参数解析 deviceId）；测试范围先「体验版」后「线上版本」。
- **校验文件**：⚠️ 微信要求放在**二维码规则前缀下**（`https://{域名}/o/{文件名}.txt`，实证仅根目录直出会「校验文件检查失败」）。nginx-full.conf 已加两处静态例外：根目录单段 `.txt` + `/o/` 下单段 `.txt`（正则优先于 `/o/` 的 proxy_pass）；文件置于 gateway 容器 `/usr/share/nginx/html/o/`（`docker cp`，一次性文件）。改动后必须 **force-recreate 或确认 inode 对齐后 reload**（单文件 bind mount 铁律）。
- 🔴 **2026-11-19 修正：`docker cp` 方案作废**——容器一重建文件即丢，校验恒 404（MA-362 实测 /o/*.txt 404）。改为**持久挂载**：`infra/gateway/mp-verify/` → 容器 `/usr/share/nginx/html/o`（只读），`docker-compose.full.yml` 与基础 `docker-compose.yml` 均已加该 volume。校验文件原样（不改名）放进该目录即可随仓库/重建一起存在；填法与自检见 `infra/gateway/mp-verify/README.md`。生产 nginx.conf 之前**缺** `/o/*.txt` 静态例外（只有 full 版有），本次一并补上。
- **校验文件只在「待校验」阶段出现**：规则保存后微信才给出「下载校验文件 / 校验」；一旦显示「已发布」，该入口消失，页面上**没有**任何下载链接（2026-11-19 用户截图实证）。⇒ 已发布的规则**不需要**日常维护该文件；只有下次重新校验（修改规则后保存、或被微信回查后取消发布）才需要，那时用上面的挂载目录。⚠️ 反过来，在「修改」页点保存会触发重新校验，校验不过可能退回未发布 —— 只是查看时**不要点保存**。
- **分工**：微信扫码 → 直接拉起小程序（走此规则）；支付宝扫同一码 → 仍走中间页 `/o/{deviceId}` UA 分流 → H5（H5 生产未部署，支付宝侧后置）。
- **巡检项（每次部署后 + 每周一条命令）**：`curl -s -w ' %{http_code} %{size_download}\n' https://cabinet.easygovm.com/o/aHNmrbOhxm.txt` 必须回 `200 32`（内容 `cf00a1a789ce06c096d42c89de66d10b`）；若回 `404 146`（nginx 页；应用自己的 404 页是 371 字节）⇒ 校验文件随容器重建丢了，重新部署 `infra/gateway/mp-verify/` 并 force-recreate gateway，否则微信定期回查会「取消发布」规则、扫码退回中间页。2026-11-19 实测生产即为 `404 146`（文件已丢，规则尚显示「已发布」）。
- ⚠️ **连带发现并修复（2026-10-21）**：生产 gateway 旧容器绑定 `127.0.0.1:8180` 的配置只存在于历史容器、从未入库——一 recreate 即爆「`address already in use 0.0.0.0:80`」（宿主 nginx 占 80，且 compose ports 是追加合并、prod-server 层加 8180 挡不住 base 的硬编码 80）。修复 = base `docker-compose.yml` 端口变量化 `${GATEWAY_PUBLISH:-80:80}` + `.env.production` 设 `GATEWAY_PUBLISH=127.0.0.1:8180:80`。另印证铁律：单文件 bind mount（nginx-full.conf）在 tar 解包换 inode 后**必须 force-recreate 容器**，`nginx -s reload` 读到的仍是旧 inode 内容。


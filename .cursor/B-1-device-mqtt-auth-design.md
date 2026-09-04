# 三端之外 · 柜机鉴权与密钥加固备忘（B-1 / B-3 及关联）

> 状态：**仅设计，不全量实现**（与 JWT B-11 备忘同级）  
> 更新日期：2026-09-04  
> 来源：`ai-cabinet三端之外代码审查报告.md`  
> 触发落地：真实柜机上线 / 接真实支付与公网 broker 前专项开干

---

## 1. 现状摘要

| 项 | 现状 |
|----|------|
| B-1 MQTT 设备鉴权 | device-service 用服务账号连 broker；事件信任 topic/payload `deviceId`；android-app 当前无用户名密码/TLS |
| B-2 门事件 | **本轮已部分加固**：失败释放幂等键 + `notifyDoorEvent` 3 次退避重试；稳定键优先 `eventSeq` |
| B-3 APK 密钥 | `INTERNAL_API_KEY` 仍可能打进 APK；cleartext 联调默认存在 |
| B-20 SCAN | 仍每 5s SCAN Redis（量产前可改 ZSET），本轮未改 |

---

## 2. B-1 目标（头号阻塞）

1. **一机一密**：每台柜机独立 MQTT username/password 或 mTLS 客户端证书。  
2. **EMQX ACL**：仅允许 `cabinet/{ownDeviceId}/#` 发布/订阅。  
3. **TLS**：生产 `ssl://`；`ProductionStartupValidator` 已有 MQTT TLS 相关守卫，保持强制。  
4. **服务端**：`MqttEventListener` 校验 topic deviceId 与 body 一致（**本轮已加 mismatch 拒绝**）；后续可校验连接客户端 ID ↔ deviceId。  
5. **android-app**：`MqttConnectOptions` 读 flavor/配置注入凭据；device flavor 强制 TLS。

---

## 3. B-3 目标

1. 内部 API Key **不**进 APK 明文：设备注册后下发短期凭证，或仅走 MQTT 不持有全局 `INTERNAL_API_KEY`。  
2. 预签名上传继续由 trade 签发（已具备）。  
3. 生产 `usesCleartextTraffic=false`；仅 debug/mock flavor 允许 cleartext。  
4. 敏感配置进 Android Keystore / 构建时 secret，不进 git。

---

## 4. 非目标

- 修补 mock recognizer / DeepSeek / device-simulator（A 类上线删除）  
- 客户端「加密存储」冒充设备凭据安全  

---

## 5. 运维立即项（I-1）

本机 `infra/.env` **已被 gitignore、未进 git 历史**，但仍可能含真实 PAT/云厂商 key。请在本机：

1. 吊销并轮换：GitHub Runner/PAT、DeepSeek、支付宝沙箱、Sonar、高德等曾出现在 `.env` 的密钥。  
2. 确认未把 `.env` 拷贝到群聊/网盘。  
3. 无需 `filter-repo`（历史未提交该文件）；若其它仓库/fork 曾提交过密钥，另案处理。

---

## 6. 决策

| 日期 | 决策 |
|------|------|
| 2026-09-04 | B-1/B-3 停在本备忘；B-2/B-4 等确定性项本轮代码修复 |

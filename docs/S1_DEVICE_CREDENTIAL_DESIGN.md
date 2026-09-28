# 设备侧凭据重构设计（S1 · 接真硬件前置）

> 依据：`docs/THREE_END_FULL_REVIEW_2026-09-27.md` §10.3 S1。状态：**已实施（2026-09-28，除 APK 现场装机页）**——实施摘要与本文件 SOP 见文末「§7 实施记录」。
> 触发条件：接真实硬件前必须完成；纯 dev/模拟器阶段不阻塞。

## 1. 现状与风险

- 全部柜机共享 MQTT 账号 `aicabinet-device` 一个口令；APK `buildConfigField` 内嵌 dev 默认口令（生产现场经 SharedPreferences 覆盖为「生产强口令」——仍是全 fleet 共享）。
- ACL：`{allow, {user,"aicabinet-device"}, all, ["cabinet/${clientid}/#"]}`，clientId 由客户端自选 ⇒ **提取任意一台柜机口令后，以 `clientId=受害者deviceId` 接入即可收发受害者全部主题**（截获开门指令、伪造 door/识别事件干扰结算）。ACL 文件内「拿到共享密码也伪造不了别的柜机」的注释不成立。
- dev（无真机）暂不可利用；接真硬件后接近 P0。

## 2. 目标

1. 每设备独立凭据（username = deviceId，逐设备 secret）。
2. 单台泄露可独立吊销/轮换，不影响 fleet。
3. APK 不内嵌任何生产口令；现场装机注入，云端可重置。
4. 生产凭据签发可审计：`.env.production`/设备清单 → 生成物 gitignore。

## 3. 方案（四部分）

### A. 凭据模型（推荐：扩展 EMQX 内置库 bootstrap，零新增运行时依赖）
- `scripts/gen-emqx-auth-bootstrap.ps1` 从「设备清单」（.env.production 的 DEVICE_IDS 或 DB 导出）生成 **N 台 N 行** 的 `auth-bootstrap.production.csv`（deviceId, PBKDF2 口令哈希, salt）。
- 明文 secret 仅在生成时输出一次（控制台/受控通道），不落 git。
- 备选（后续演进）：EMQX HTTP authenticator 查 trade `/internal/v1/devices/mqtt-auth`，支持运行时吊销——首期不必。

### B. ACL 绑定 username==clientId
- 现规则 `cabinet/${clientid}/#` 保留；**新增身份绑定约束**：认证按 username=deviceId 查内置库（每设备独立口令）后，clientid ≠ username 的连接一律拒绝（ACL 规则改为按 `${username}` 展开或加前置规则 `{allow,{user,"${username}"}...}`；**需验证** EMQX 5 file authorizer 是否支持 `clientid == username` 相等比较——若不支持，改用 per-device 规则行生成，或以内置库授权替代文件授权）。
- 效果：伪造他柜需同时持有他柜 secret。

### C. APK
1. 移除 `build.gradle.kts` 中 `MQTT_USERNAME/MQTT_PASSWORD` 生产默认值（置空，仅保留 dev/simulator 用 dev 值由 debug 分支注入）。
2. 首启「装机配置」：输入/扫码 deviceId + 一次性 secret（内容=deviceId:secret 或 provisioning token），Keystore 加密落盘；**加密失败即拒绝联网**（对齐既有 P2，不降级明文）。
3. 设置页新增「重置连接凭据」入口（配合云端重置）。

### D. 云端/运营
- 运营后台设备详情新增「MQTT 凭据」操作：签发（生成一次性 secret 并展示一次）/吊销/轮换；写审计日志。
- 生产 compose 保留 `aicabinet-backend` 账号不动；共享 `aicabinet-device` 在迁移完成后吊销。
- dev/simulator 保留共享 dev 账号，ACL 限定 `cabinet/DEV-*/` 且仅 dev profile 生成。

## 4. 迁移与验收步骤

1. 生成器批量发卡（1 设备 1 行）+ ACL 绑定约束 → 全栈 UAT：模拟器用新凭据上线。
2. 伪装测试（**必须做**）：拿 A 柜 secret 以 B 柜 clientId 连接 → EMQX 必须拒绝；用 A 柜凭据发布 `cabinet/B/#` → 必须被 deny。
3. APK 配置页 + 现场注入 SOP 文档。
4. 吊销共享 `aicabinet-device`，跑一个批次观察。
5. 全部通过后，本文件标记「已实施」，并回填 ACL/生成器/APK 的实际 diff 链接。

## 5. 工作量预估

| 部分 | 预估 |
|---|---|
| 生成器 + ACL + 运营签发 API | 1 天 |
| APK 装机配置页 + Keystore 强制 | 1–2 天（含真机联调） |
| 伪装 UAT + SOP 文档 | 半天 |

## 6. 待验证项（实施前确认）

- [ ] EMQX 5 file authorizer 能否表达 `clientid == username`（不能则改内置库授权或 per-device 规则）。
- [ ] EMQX 内置库 bootstrap 的 PBKDF2 参数与生成工具对齐（`password_hash_format` 配置）。
- [ ] 现场弱网下装机配置页的重试/断点体验。

---

## 7. 实施记录（2026-09-28）

| 项 | 状态 | 落点 |
|---|---|---|
| A 凭据模型 | ✅ | `V290__device_mqtt_credential.sql` + `DeviceMqttCredentialService`（issue/revoke/status；明文 secret 仅签发响应返回一次，表内明文仅用于 bootstrap 导出，敏感级同 CSV） |
| B ACL 绑定 | ✅ | `aicabinet-acl.conf` 新增 `{allow, all, all, ["cabinet/${username}/#"]}`——username=deviceId 命名空间。⚠ 实测 EMQX 5.8.6 file authorizer 的 who 位不接受 `{all}` 元组（invalid_client_match_condition），须写裸 `all` |
| C 签发 API | ✅ | `POST/DELETE/GET /api/v2/ops/admin/devices/{deviceId}/mqtt-credential`（ops:device:edit / ops:device:list；审计 `DEVICE_MQTT_CREDENTIAL_ISSUE/REVOKE`） |
| 生成器 | ✅ | `-IncludeDevicesFromDb`：docker exec psql 读 trade 库 ACTIVE 行（明文不出库容器）逐设备一行；共享账号默认**不进 bootstrap**（`-KeepSharedDevice` 仅回退） |
| 伪装 UAT | ✅ | §6 |
| ACL 生效方式 | ✅ 实测 | dev 侧改 ACL 文件后必须 `--force-recreate emqx`（文件挂载不热加载；EMQX 5.8.6 对 `{all}` who 位会 schema 校验失败拒启，改裸 `all` 通过） |
| APK 装机页 | ⏳ | 需真机联调；现有 SharedPreferences 注入机制兼容（username=deviceId + 一次性 secret），生产 bootstrap 不含共享账号即可生效 |

### 伪装 UAT 实测（dev 栈，mosquitto 客户端，QoS1 + 订阅端双验证）

| 用例 | 结果 |
|---|---|
| 设备凭据（user=898548016998）发本柜 `cabinet/898548016998/door/state` QoS1 | ✅ 订阅端收到 `POSITIVE-SELF` |
| 同凭据发他柜 `cabinet/166813762350/door/state` | ✅ 拒发+断连（`Error: The connection was lost`；EMQX 日志 `cannot_publish_to_topic_due_to_not_authorized`） |
| 同凭据发 `cabinet/backend/x` | ✅ 同上拒发断连 |
| 共享模拟器账号（dev 仅存）收发 | ✅ 不破坏既有 dev 流程 |

### 运维 SOP（签发 → 生效）

1. 运营台/`POST /api/v2/ops/admin/devices/{deviceId}/mqtt-credential` 签发 → **响应里的 secret 只出现一次**，随派工单交现场。
2. `pwsh scripts/gen-emqx-auth-bootstrap.ps1 -EnvFile .env.production -IncludeDevicesFromDb` 重新生成 bootstrap CSV（含该设备行）。
3. 重建 emqx 容器（bootstrap 仅启动导入）。
4. 现场装机：设备注入 username=deviceId + secret（SharedPreferences）。
吊销：`DELETE …/mqtt-credential?reason=…` → 重跑步骤 2–3。

### 遗留/后续

- EMQX 内库 REST 同步（运行时签发免重建 emqx）——硬化路径，非必需。
- APK「装机配置页」UI——需真机。
- 生产部署时 `.env.production` 增加 `MQTT_DEVICE_PASSWORD` 的历史行可移除（共享账号已不进生产 bootstrap）。

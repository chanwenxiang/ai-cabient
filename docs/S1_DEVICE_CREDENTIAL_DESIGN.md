# 设备侧凭据重构设计（S1 · 接真硬件前置）

> 依据：`docs/THREE_END_FULL_REVIEW_2026-09-27.md` §10.3 S1。状态：**设计稿，未实施**。
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

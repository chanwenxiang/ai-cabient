# 待办交接 — 代码审查跟进

> 更新时间：2026-09-04  
> 来源：管理后台 + 小程序 + **三端之外**审查  
> 分支：`dev`

---

## 管理后台 / 小程序（已合入）

| 范围 | 状态 |
|------|------|
| 管理后台 A/B/F/N 核心 | ✅ |
| 小程序 P0–P2 + 高价值 P3 | ✅ |
| B-11 JWT access/refresh | 📝 `.cursor/B-11-auth-refresh-design.md` |

---

## 三端之外（本轮）

| 编号 | 状态 | 说明 |
|------|------|------|
| **B-2** | ✅ 部分 | 失败释放幂等键 + notifyDoorEvent 3 次退避；优先 eventSeq |
| **B-4** | ✅ | `$share/` 剥离 + topic/body deviceId 一致性 |
| **B-5** | ✅（核实已修） | InternalApiAuthInterceptor 判空 |
| **B-7 / B-8** | ✅ | `/health` 收敛；详情需 Key；上传限流；生产关 docs |
| **B-9** | ✅ | backup 脚本禁止硬编码弱口令 |
| **B-11（infra）** | ✅ 部分 | production.yml 关 EMQX 匿名 + MinIO/CIDR/识别后端强校验 |
| **B-12 / B-13** | ✅ | getToken 一次；ops:admin 不跨 merchant 域 |
| **B-21 / B-23 / B-24** | ✅ | Map 防 NPE；扫码 autoOpen 默认 false；yuanToCents 上界 |
| **B-1 / B-3** | 📝 | `.cursor/B-1-device-mqtt-auth-design.md`（一机一密 + APK 密钥） |
| **I-1** | ⏳ 运维 | `.env` 已 gitignore 未进历史；**请本机轮换** PAT/DeepSeek/支付宝/Sonar/高德 |
| A 类 mock/模拟器 | ⏳ 上线清单 | 不修补，上线替换/删除 |
| B-6 代码强校验 / B-20 / B-22 / B-25/28 | ⏳ | production 已要求 CIDR；XXL/Grafana 弱口令、SCAN、设备存在性待后续 |

---

## 仍开放（低优先）

- B-1/B-3 实现、B-11 JWT 实现  
- P3 余量 / N-13/N-15  
- A 类上线切换清单（见审查报告 §4）

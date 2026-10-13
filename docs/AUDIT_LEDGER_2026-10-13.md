# 上线前全量审计 · 问题总台账（2026-10-13）

> **唯一事实源**。基线 commit `9911a2ee`（dev 最新；工作分支 `worktree-audit-pre-launch`）。
> 纪律：不采信自报绿——每条"已修"必须 file:line + 实跑证据；"判非缺陷"同样必须给证据。
> 等级：P0=当前即资损/安全事件 ｜ P1=接真前必修或现存实质功能缺陷 ｜ P2=质量 ｜ P3=债务。
> 处置：待修 / 修复中 / 已回归 / 判非缺陷 / 挂起(阻塞原因) / 待拍板。

## 环境基线（审计时点）

- 服务器 `http://120.25.246.179`（HTTP 过渡期，`AUTH_COOKIE_SECURE=false` 阶段模式）
- `WECHAT_PAY_ENABLED=false`、`CHECKOUT_BALANCE_ONLY=true`（余额-only）
- 无正式小程序号；识别=将邑端侧（模拟器测，真硬件未接）
- 生产库账号仅剩 user_id=0（系统）+ 100000012（超管）；演示 SKU/商户种子部分已手工清理
- 微信开发者工具自动化：**已探明可用**（`D:\devTools\微信web开发者工具\wechatidecli.cmd`，islogin=true，IDE HTTP 服务 127.0.0.1:22277；旧 cli.bat reg.exe 问题绕开）

## 台账

| ID | 轨道 | 等级 | 域/文件 | 发现 | 证据 | 与存量关系 | 处置 | 修复提交 | 回归证据 |
|----|------|------|---------|------|------|-----------|------|----------|----------|
| AU-001 | A | 待定 | FIX_QUEUE 批次1 | P0-4/P0-1/P0-6 已修复验通过 | OrderPaymentService.java:374-404、MinioVideoService.java:296-307、check-compose-ports.mjs | 复验存量 | 已回归(复扫) | — | 子代理复扫中，余项见 TRACK_A 报告 |
| AU-002 | A/D | P1候选 | trade 退款链 | G1 退款提交补偿 job 仍缺（Java 侧 refundRetry/RefundSubmitJob 零命中）；余额-only 档下影响待 E 级评估 | grep 零命中（worktree 9911a2ee） | GAP_SCAN G1 确认 | 待子代理定案后登记 | — | — |
| AU-003 | E | P1候选 | 商户→将邑回调 | V16 §5.4.2–5.4.8 回调（sendOrderAmt 等）未实现，是否上线阻塞待 §5.4 原文定案 | grep sendOrderAmt 零命中；jiangyi-gateway api/ 无对应发送端 | 新增 | Track E 审计中 | — | — |
| AU-004 | F | P1 | Flyway 版本迁移 | V2/V11/V25/V54/V56/V62 内嵌演示种子：任何全新生产库重建即再长出演示数据（生产已手工清理但未治本） | .workbuddy/memory/2026-10-13.md「商品管理/选品诊断演示数据清零」段 | 新增(10-13 部署实证) | 待修(方案：守卫迁移 prod 删 demo 或种子外提) | — | — |
| AU-005 | B/C/F | — | 接口契约/三端实测 | Track B-1/B-2/B-3 子代理审计进行中 | — | — | 审计中 | — | — |

## 待用户拍板清单（执行中滚动）

| ID | 事项 | 背景 |
|----|------|------|
| DC-1 | 生产/staging 共用将邑租户 426 凭据与 OSS 桶 | 测试数据污染真租户风险（Track E-3 出细节后定） |
| DC-2 | 生产环境是否允许开门类实测 | 建议开门类只在本地/staging |
| DC-3 | 低置信识别 DISPUTED 先不扣款 vs 旧系统 NOT_FALL 自动退款 | 业务口径拍板（Track D-3） |
| DC-4 | getArtificialCheckByIdentifier 恒 false（只传异常视频）产品口径确认 | Track E-1 |

## 轨道状态

- Track A（存量复扫）：子代理 ×2 运行中 → `docs/AUDIT_TRACK_A_*.md`
- Track B（接口契约）：B-1/B-2/B-3 运行中；B-4~B-9 待第 2 波 → `docs/AUDIT_TRACK_B_*.md`
- Track C（三端实测）：基础设施已探明；C-admin/C-mp 待第 2 波 → `docs/AUDIT_TRACK_C_*.md`
- Track D（easygo diff）：待第 2 波 → `docs/AUDIT_TRACK_D_*.md`
- Track E（将邑专项）：子代理运行中 → `docs/AUDIT_TRACK_E_*.md`
- Track F（补充项）：待第 2 波 → `docs/AUDIT_TRACK_F_*.md`

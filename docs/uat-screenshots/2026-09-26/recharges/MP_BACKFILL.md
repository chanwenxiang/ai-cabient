# 充值管理 · MP 回补 · 2026-09-27

> 权威：mp-weixin（DevTools + automator :9420）· 禁止 H5 冒充  
> Admin：`RECHARGES_FULL_BROWSER_UAT.md` · Backlog P0#1  
> 账号：消费者 `13800138000` → userId **10001**

## 结论

**DONE · PASS**（只读；未点微信/确认充值）

| 面 | 值 |
|----|-----|
| Admin `balanceCents` | **19300** → ¥193.00 |
| MP 我的 / 余额明细 / 充值页 | 均显示 **¥193.00** |
| Admin 充值单（userId=10001 PAID） | `1789658820257378119` · ¥20.00 |
| MP 充值记录 | 同单号 · **已支付** · ¥20.00 |

## 步骤

1. `build:mp-weixin:dev` → `dist/dev/mp-weixin`（urlCheck=false）
2. `cli close` 商户 → `cli open` 消费者 → `cli auto --auto-port 9420`
3. 注入 `consumer_token`；截图 mine / balance / recharge（**未硬充**）

## 证据

| 文件 | 内容 |
|------|------|
| `mp-c-p0-01-mine.png` | 可用余额 ¥193.00 |
| `mp-c-p0-02-balance.png` | 余额明细 + 流水 |
| `mp-c-p0-03-recharge.png` | 当前余额 ¥193.00 · 记录已支付 |
| `mp-c-p0-recharges-probe.json` | automator 路径记录 |

## 口径

- wxml/data 在 release minify 下键名混淆，**以截图金额为准**。  
- 测前须确认 DevTools 打开的是**消费者**工程（商户登录页「补货与运营」≠ 本项）。
|

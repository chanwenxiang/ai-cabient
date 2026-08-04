# AI开门柜 · 商户小程序

`@aicabinet/merchant-mp` — 独立微信小程序，面向商户日常运营。

## 页面

| 路径 | 说明 |
|------|------|
| `pages/login/login` | 商户登录 |
| `pages/home/home` | 概览 · KPI + 营收趋势 |
| `pages/devices/devices` | 柜机列表 |
| `pages/device-detail/device-detail` | 柜机详情 / 货道 |
| `pages/pricing/pricing` | 点位定价 |
| `pages/alerts/alerts` | 待办 / 告警 |
| `pages/mine/mine` | 我的 |

TabBar：概览 / 柜机 / 待办 / 我的

## 开发

```bash
cd clients/merchant-mp
npm install
npm run dev:mp-weixin    # 开发
npm run build:mp-weixin  # 构建
```

用微信开发者工具打开 `dist/dev/mp-weixin`（开发）或 `dist/build/mp-weixin`（生产）。

## 依赖

- `@aicabinet/shared-types` — DTO 类型
- `@aicabinet/shared-uni` — 共享格式化工具

## 配置

- `src/manifest.json` → `mp-weixin.appid` 填入微信小程序 AppID
- 演示账号：13800138001 / 123456

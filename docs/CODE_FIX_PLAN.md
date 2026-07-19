# 需要修复的代码bug清单（测试阶段保留Mock）

## P1 - 必须修复
1. 开门API无超时 - index.vue startShoppingFlow函数
2. 扫码结果无校验 - index.vue onScan函数
3. Token刷新竞态 - consumer-api.ts refreshTokenSilently

## P2 - 建议修复
1. 支付待处理订单累积 - echarge.ts
2. 争议无图片上传 - esult.vue

## 保留测试功能
- Mock充值按钮保留
- 测试余额文案改为\"余额\"

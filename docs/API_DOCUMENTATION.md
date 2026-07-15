# AI Cabinet API 文档生成指南

## 访问地址

### 开发环境
- Swagger UI: http://localhost:8080/swagger-ui.html
- OpenAPI JSON: http://localhost:8080/v3/api-docs
- OpenAPI YAML: http://localhost:8080/v3/api-docs.yaml

## API 分组

### 核心业务接口
1. **认证** - 用户登录、注册、Token刷新
2. **订单** - 订单查询、详情、售后
3. **支付** - 充值、预支付、回调
4. **设备** - 状态查询、商品列表、故障上报

### 商业模式接口
5. **分账** - 收入查询、结算记录
6. **会员** - 等级查询、积分明细
7. **营销** - 活动列表、参与活动
8. **加盟商** - 加盟商管理
9. **线长** - 线长管理

### 运营管理接口
10. **商户管理** - 商户入驻、设备管理
11. **运营后台** - 数据统计、商品管理

## 认证方式

### Bearer Token (JWT)
`http
Authorization: Bearer <your-jwt-token>
`

### 微信小程序
`http
X-WeChat-OpenId: <openid>
`

## 错误码说明

| 错误码 | 说明 |
|-------|------|
| 200 | 成功 |
| 400 | 参数错误 |
| 401 | 未认证 |
| 403 | 无权限 |
| 404 | 资源不存在 |
| 500 | 服务器内部错误 |
| 10001 | 设备离线 |
| 10002 | 商品售罄 |
| 20001 | 余额不足 |
| 20002 | 支付失败 |
| 30001 | 订单不存在 |
| 30002 | 订单已取消 |

## 示例请求

### 1. 获取订单列表
`ash
curl -X GET "http://localhost:8080/api/v2/orders?page=0&size=20" \
  -H "Authorization: Bearer <token>"
`

### 2. 创建充值预支付
`ash
curl -X POST "http://localhost:8080/api/v2/payment/recharge/prepay" \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "channel": "WECHAT_JSAPI",
    "amountCents": 10000,
    "idempotencyKey": "unique-key-123"
  }'
`

### 3. 查询设备状态
`ash
curl -X GET "http://localhost:8080/api/v2/devices/{deviceId}/status"
`

### 4. 参与营销活动
`ash
curl -X POST "http://localhost:8080/api/v2/marketing/campaigns/{campaignId}/join" \
  -H "Authorization: Bearer <token>"
`

## 数据格式

### 时间格式
- 所有时间字段使用 ISO 8601 格式
- 示例: 2026-07-14T20:30:00+08:00

### 货币格式
- 所有金额以分为单位
- 示例: 100 = 1元

### 分页格式
`json
{
  "items": [],
  "total": 100,
  "page": 0,
  "size": 20,
  "hasMore": true
}
`

## 导出文档

### 导出为 JSON
`ash
curl http://localhost:8080/v3/api-docs > api-docs.json
`

### 导出为 YAML
`ash
curl http://localhost:8080/v3/api-docs.yaml > api-docs.yaml
`

### 生成静态文档
使用 Swagger Codegen 或 Redoc 生成静态HTML文档:
`ash
# 使用 Redoc
npx @redocly/cli build-docs api-docs.yaml --output api-docs.html
`

## 测试建议

1. 使用 Swagger UI 进行接口测试
2. Postman 导入 OpenAPI JSON 进行批量测试
3. 编写自动化测试脚本验证接口正确性

---
**生成时间**: 2026-07-14
**版本**: 1.0.0

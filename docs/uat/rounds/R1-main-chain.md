# R1 主链冒烟 — 轮报告（2026-11-07，uat 库，三端真实操作）

> 执行方式：consumer=微信开发者工具模拟器（computer-use 真实点击）+ 用户扫码协作；admin=浏览器/API 真实操作链；设备侧=MQTT 模拟柜容器 + internal 事件。
> 锚：`d009ff5c`（uat 库 aicabinet_uat，Flyway V1→V447 全链 443 迁移）。

## 结论：主链 PASS（资金/库存/券/分账全对账平），过程发现 1 条 P2 产品缺陷（MA-163）+ 3 条环境待办

## 主链用例（真实 UI 操作，全部通过）

| 步骤 | 操作人 | 结果 |
|------|--------|------|
| 扫码开门（柜码 PNG → 模拟扫码） | 用户扫 + computer-use 点 | ✓ 会话 SHOPPING（MQTT 模拟柜 ACK） |
| 实名 | 用户 UI 提交 + API | ✓（mock 不置 verified=V322 保护，按生产前提 SQL 置位） |
| 充值 ¥200 | API mock 通道 | ✓ 余额 20000 |
| 选商品 ×2（原味薯片 70g ¥6.50） | computer-use 点步进器 | ✓ 购物车 ¥13.00 |
| 关门结算 | computer-use 点按钮 | ✓ 正确转 DISPUTED（无识别结果+重力证据=需人工审核，资金保护正确） |
| 运营裁决 CONFIRM 薯片×2 | admin API | ✓ 首次结算 PAID |

## 数据断言（全对平）

| 断言 | 期望 | 实际 | 判定 |
|------|------|------|------|
| 恒等式 gross−member−activity−coupon=total | 1300−26−0−200=1074 | 1074 | ✓ |
| 折后式 Σ行−coupon=total | 1274−200=1074 | 1074 | ✓ |
| 会员 2%×1300 | 26 | 26 | ✓ |
| 券核销 | USED | USED（+另一张 UNUSED 未被误用） | ✓ |
| 用户余额 | 20000−1074=18926 | 18926 | ✓ |
| 商户钱包 | 50000+1074=51074 | 51074 | ✓ |
| 分账 | LEDGER_ONLY, 0+1074 | LEDGER_ONLY, 0/1074 | ✓ |
| 开门柜库存直扣（无批次/无 movement，符合铁律 78） | 2−2=0 | 0 | ✓ |
| admin 端同读 | PAID/1074/1300/26/200 | 一致 | ✓ |

## 过程发现

### 缺陷
- **MA-163（P2）Hikari 只读连接污染**：间歇性全写失败（25006），两轮窗口、两次重启止血；生产同代码有同型风险。修法方向已记（connection-init-sql 兜底+复现用例）。

### 环境待办（非产品缺陷）
1. **将邑 WS 模拟器无 WS 保活 ping** → 网关 idle timeout 1001 踢线（模拟器基建补 ping；将邑真链已验证到「token/激活/WS 连接」三步）。
2. **模拟柜 DEVICE_ID 写死启动参数** → 需 override 切柜（已落 `infra/docker-compose.sim-eg.yml`）。
3. **真实微信登录已生效**（wx-login 走真实 code2Session，mock 分支不再触发）——API 造数需用户真实登录态，经 automator `callWxMethod('getStorageSync','consumer_token')` 取得。

### UI 实测反馈（已修，d009ff5c）
- 购物卡活动价行挤压换行错乱 → gap 收窄+wrap+划线价 nowrap。
- 步进器「−」白底细边在照片亮区看不清 → 浅灰底/深边/深字。

### 顺带核验
- 活动展示位链路：素材上传→尺寸校验（512×512 拒，750×300 过）→BANNER 位→launch 409→上线 ✓。
- 柜码生成（qr-link/qr.png）✓； qr host 仍指旧 IP `.200`（生产前改公网域名，备忘）。
- 误启动迁移预置活动「新客开门礼」→ 已 stop 恢复基线。

## 轮前后对账

| 面 | 轮首 | 轮末 | 差额解释 |
|----|------|------|----------|
| 用户余额 | 0→充值 20000 | 18926 | −1074=订单实付 ✓ |
| 商户钱包 | 50000（demo 种） | 51074 | +1074=LEDGER_ONLY 入账 ✓ |
| SNACK 库存 | 2（demo 种） | 0 | −2=裁决 CONFIRM 扣减 ✓ |
| 券 | 0 | 1 UNUSED + 1 USED | +1 发放；USED=本单核销 ✓ |

## 覆盖表触达（table-coverage.md 更新）

本轮触达 28 表（cabinet_order/line、user_info/user_account、coupon_definition/user_coupon、
promotion_activity/placement、media_asset、shopping_session、dispute_ticket、order_revenue_split、
wallet_account/ledger、payment_operation、device_info/sku_inventory、jiangyi_device/class_mapping、
jiangyi_model_deployment、merchant、admin_audit_log、user_consent_log、announcement、inventory_movement(读)、
device_command 相关、file_attachment、ops_exception、flyway_schema_history）。

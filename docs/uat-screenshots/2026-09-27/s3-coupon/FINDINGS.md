# S3-B1 · 发券 → 下单抵扣（2026-09-27）

## 动作

1. Admin `POST /coupons/definitions`：`AMOUNT_OFF` ¥1 / 门槛 ¥1 · defId=2
2. `POST /coupons/issue` → user 10001 · couponId=4 · UNUSED
3. 二次 `e2e-shopping` BALANCE（关卡住的 sim `/close` 后）

## 结果

| 项 | 值 |
|----|-----|
| orderId | `1790493168114576060218` |
| original | ¥3.50 |
| couponDiscount | **¥1.00** |
| paid | **¥2.50** BALANCE |
| coupon | 4 → **USED** |

Admin 优惠券页：定义「S3…」满减券 1/100 已发；订单页 **¥2.50 原 ¥3.50 / −¥1.00**。

截图：`s3-b1-coupon-def.png` · `s3-b1-order-discount.png`

## 备注

- 首次重购失败因模拟器门停在 OPEN（等 `/close`）；关闸后 PASS。
- `e2e-shopping` 会把余额重置为 20000（覆盖 S3 充值余额）。

---

## S3-B2 会员 / 积分（抽样）

| 步骤 | 结果 |
|------|------|
| `GET /member/points` | available **35** · level **普通会员** · EARN 流水可见 |
| Admin `/admin/member-levels` | 普通/银卡/金卡/白金 **启用**（只读抽样） |
| Admin `/admin/points-redeem` | 续测软写项 **#4** `S3-soft-redeem` · 9999 分 · **停用**（须 `couponDefId`；见 `s3-mp/FINDINGS`） |

截图：`s3b2-member-levels.png` · `s3b2-points-redeem.png`

## S3-B3 消息 / 公告

| 步骤 | 结果 |
|------|------|
| Admin `/admin/announcements` | **暂无公告** · 共 0（Wipe 后未重建） |
| 「发布公告」→ **取消** | 弹窗关闭；列表仍 0（软写） |
| `GET /api/v2/announcements` · `/merchant/announcements` | 空列表 PASS |
| Consumer/Merchant 公告页 UI | **SKIP**（H5 未挂） |

截图：`s3b3-announcements.png` · `s3b3-announce-create-cancel.png`

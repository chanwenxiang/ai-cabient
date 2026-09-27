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

-- 活动与优惠券定义关联，供消费者「一键领券」解析
UPDATE coupon_definition d
SET activity_id = p.activity_id
FROM promotion_activity p
WHERE d.coupon_name = '满减券 ¥5'
  AND p.activity_name = '夏日冰饮满减周'
  AND (d.activity_id IS NULL OR d.activity_id <> p.activity_id);

UPDATE coupon_definition d
SET activity_id = p.activity_id
FROM promotion_activity p
WHERE d.coupon_name = '新人立减 ¥2'
  AND p.activity_name = '新客开门礼'
  AND (d.activity_id IS NULL OR d.activity_id <> p.activity_id);

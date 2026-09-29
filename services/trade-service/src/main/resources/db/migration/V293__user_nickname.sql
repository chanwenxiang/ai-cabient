-- S1 接真：微信登录用户身份可辨识
-- MIGRATION_KIND: schema
-- 1) user_info 补 nickname（微信昵称；wx.login 不回传昵称，由用户在「我的」页自助填写，
--    默认沿用 name——openid 用户建号时为「微信用户」）
-- 2) openid 用户的 phone_number 是 openid 前缀占位（如 wx10004），account DTO 需把
--    真实身份（昵称/掩码手机号/微信标志）下发，消费者端「我的」页才能显示登录身份。

ALTER TABLE user_info ADD COLUMN IF NOT EXISTS nickname character varying(64);
COMMENT ON COLUMN user_info.nickname IS '微信昵称（用户自助填写；openid 用户的可辨识身份）';

-- 本地分账联调：为默认商户补齐微信分账接收方（Mock / 真机均可）
UPDATE merchant
SET wechat_receiver_id = '1900000109',
    updated_at = NOW()
WHERE merchant_id = 'MCH-DEFAULT'
  AND (wechat_receiver_id IS NULL OR wechat_receiver_id = '');

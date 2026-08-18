-- 通知标题去掉「#单号」：编号已在关联单号展示，标题只保留文案
UPDATE notification_template
SET title_template = '新补货任务',
    updated_at = NOW()
WHERE template_code = 'replenishment_assigned'
  AND title_template LIKE '%#{%';

UPDATE notification_log
SET title = regexp_replace(title, ' #\S+$', '')
WHERE title LIKE '% #%';

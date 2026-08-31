-- 审批站内信文案：与「待审批」任务区块区分，统一为「审批提醒」
UPDATE notification_template
SET title_template = '审批提醒：{title}'
WHERE template_code = 'ops_approval_pending'
  AND title_template = '待审批：{title}';

UPDATE notification_log
SET title = '审批提醒：' || substr(title, char_length('待审批：') + 1)
WHERE audience = 'OPS'
  AND title LIKE '待审批：%';

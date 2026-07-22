-- 识别争议审单子类：低置信 / 未映射 / 空识别 等
ALTER TABLE dispute_ticket
    ADD COLUMN IF NOT EXISTS review_code VARCHAR(32),
    ADD COLUMN IF NOT EXISTS detected_classes TEXT;

CREATE INDEX IF NOT EXISTS idx_dispute_review_code
    ON dispute_ticket (category, review_code, status);

-- 历史工单按原因文案回填（便于运营子 Tab 立刻可用）
UPDATE dispute_ticket
SET review_code = 'EMPTY'
WHERE category = 'RECOGNITION'
  AND review_code IS NULL
  AND reason LIKE '%未识别%';

UPDATE dispute_ticket
SET review_code = 'LOW_CONF'
WHERE category = 'RECOGNITION'
  AND review_code IS NULL
  AND (reason LIKE '%置信%' OR reason LIKE '%阈值%');

UPDATE dispute_ticket
SET review_code = 'WHITELIST'
WHERE category = 'RECOGNITION'
  AND review_code IS NULL
  AND (reason LIKE '%白名单%' OR reason LIKE '%未登记%' OR reason LIKE '%未授权%' OR reason LIKE '%未开通%');

UPDATE dispute_ticket
SET review_code = 'NEED_REVIEW'
WHERE category = 'RECOGNITION'
  AND review_code IS NULL;

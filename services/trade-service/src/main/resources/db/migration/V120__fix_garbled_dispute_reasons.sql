-- 争议原因乱码 soft-heal：按 review_code 回填标准中文说明（UTF-8）。
-- 仅更新纯问号 / 明显损坏的 reason，不覆盖人工填写的正常文案。

UPDATE dispute_ticket
SET reason = CASE UPPER(COALESCE(review_code, ''))
    WHEN 'GRAVITY_FILL' THEN '视觉为空，仅有重力信号（非生产识别精度），需人工审核'
    WHEN 'GRAVITY_MISMATCH' THEN '视觉与重力数量不一致，需人工审核'
    WHEN 'MOCK' THEN '模拟/兜底识别结果，非生产精度，需人工审核'
    WHEN 'FALLBACK' THEN '模拟/兜底识别结果，非生产精度，需人工审核'
    WHEN 'LOW_CONFIDENCE' THEN '识别置信度不足，需人工审核'
    WHEN 'EMPTY' THEN '未识别到商品，需人工审核'
    WHEN 'TIMEOUT' THEN '识别超时，已转人工审核，本次暂未扣款'
    ELSE '识别结果需人工审核'
END
WHERE reason ~ '^\?+$'
   OR reason LIKE '%???%';

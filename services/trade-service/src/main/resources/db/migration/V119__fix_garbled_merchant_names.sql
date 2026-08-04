-- Fix garbled demo merchant name (literal '????' / 0x3f bytes from bad seed).
UPDATE merchant
SET merchant_name = '华东演示商户',
    remark = COALESCE(NULLIF(TRIM(remark), ''), '区域演示商户'),
    updated_at = NOW()
WHERE merchant_id = 'MCH-EAST'
  AND (
    merchant_name = '????'
    OR merchant_name ~ '^\?+$'
    OR merchant_name !~ '[^?[:space:]]'
  );

-- Soft-heal any other merchant rows that are only question marks (encoding corruption).
UPDATE merchant
SET merchant_name = '演示商户-' || merchant_id,
    updated_at = NOW()
WHERE merchant_name ~ '^\?+$'
  AND merchant_id <> 'MCH-EAST';

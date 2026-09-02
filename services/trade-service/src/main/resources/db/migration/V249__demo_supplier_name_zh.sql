-- IMP-006：演示供应商名称中文化
UPDATE supplier
SET supplier_name = '演示饮品供应商'
WHERE supplier_id = 'SUP-DEMO-001'
  AND supplier_name = 'Demo Beverage Supplier';

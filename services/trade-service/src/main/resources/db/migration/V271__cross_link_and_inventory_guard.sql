-- CROSS_LINK 字典 + 钱货关联 CONSTRAINT TRIGGER（新单硬拦；旧单按创建时间豁免）

INSERT INTO sys_dict_data (dict_type, dict_value, dict_label, sort_order, status, remark)
SELECT 'consistency_check_type', 'CROSS_LINK', U&'\5173\8054\6295\5f71', 210, 'ACTIVE', 'cross-link'
WHERE EXISTS (SELECT 1 FROM sys_dict_type WHERE dict_type = 'consistency_check_type')
  AND NOT EXISTS (
    SELECT 1 FROM sys_dict_data d
    WHERE d.dict_type = 'consistency_check_type' AND d.dict_value = 'CROSS_LINK'
  );

-- 已付有货必须 inventory_deducted；若柜机启用批次账本则须有 SALE+ORDER 流水。
-- DEFERRABLE：同事务内先改状态再写流水或反过来均可，提交时校验。
-- 豁免：created_at < 2026-09-10（历史脏数据由 CROSS_LINK 巡检暴露，不阻塞迁移）。

CREATE OR REPLACE FUNCTION assert_cabinet_order_inventory_link()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    has_lines boolean;
    has_lots boolean;
    has_sale boolean;
    cutoff timestamptz := TIMESTAMPTZ '2026-09-10 00:00:00+08';
BEGIN
    IF NEW.created_at IS NOT NULL AND NEW.created_at < cutoff THEN
        RETURN NEW;
    END IF;
    IF NEW.status IS NULL OR UPPER(NEW.status) NOT IN ('PAID', 'PARTIAL_REFUNDED') THEN
        RETURN NEW;
    END IF;

    SELECT EXISTS (
        SELECT 1 FROM cabinet_order_line ol
        WHERE ol.order_id = NEW.order_id AND ol.quantity > 0
    ) INTO has_lines;
    IF NOT has_lines THEN
        RETURN NEW;
    END IF;

    IF COALESCE(NEW.inventory_deducted, FALSE) = FALSE THEN
        RAISE EXCEPTION 'cabinet_order % PAID with lines requires inventory_deducted=true', NEW.order_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    SELECT EXISTS (
        SELECT 1 FROM device_sku_lot l WHERE l.device_id = NEW.device_id
    ) INTO has_lots;
    IF has_lots THEN
        SELECT EXISTS (
            SELECT 1 FROM inventory_movement m
            WHERE m.movement_type = 'SALE'
              AND m.ref_type = 'ORDER'
              AND m.ref_id = NEW.order_id
        ) INTO has_sale;
        IF NOT has_sale THEN
            RAISE EXCEPTION 'cabinet_order % PAID with lot ledger requires SALE inventory_movement', NEW.order_id
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_cabinet_order_inventory_link ON cabinet_order;
CREATE CONSTRAINT TRIGGER trg_cabinet_order_inventory_link
    AFTER INSERT OR UPDATE OF status, inventory_deducted, device_id
    ON cabinet_order
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION assert_cabinet_order_inventory_link();

COMMENT ON FUNCTION assert_cabinet_order_inventory_link() IS
    '钱货关联：新单已付有货须扣库标记；有批次账本时须有 SALE 流水（旧单按 created_at 豁免）';

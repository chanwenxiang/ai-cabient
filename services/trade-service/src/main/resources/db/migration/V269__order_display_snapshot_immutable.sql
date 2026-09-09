-- 订单展示快照写一次即固化：禁止改已写入的 device_name / merchant_name / merchant_id

CREATE OR REPLACE FUNCTION prevent_cabinet_order_display_snapshot_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.device_name IS NOT NULL AND btrim(OLD.device_name) <> ''
       AND NEW.device_name IS DISTINCT FROM OLD.device_name THEN
        RAISE EXCEPTION 'cabinet_order.device_name is immutable after first write'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF OLD.merchant_name IS NOT NULL AND btrim(OLD.merchant_name) <> ''
       AND NEW.merchant_name IS DISTINCT FROM OLD.merchant_name THEN
        RAISE EXCEPTION 'cabinet_order.merchant_name is immutable after first write'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF OLD.merchant_id IS NOT NULL AND btrim(OLD.merchant_id) <> ''
       AND NEW.merchant_id IS DISTINCT FROM OLD.merchant_id THEN
        RAISE EXCEPTION 'cabinet_order.merchant_id is immutable after first write'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_cabinet_order_display_snapshot_immutable ON cabinet_order;
CREATE TRIGGER trg_cabinet_order_display_snapshot_immutable
    BEFORE UPDATE OF device_name, merchant_name, merchant_id ON cabinet_order
    FOR EACH ROW
    EXECUTE FUNCTION prevent_cabinet_order_display_snapshot_mutation();

COMMENT ON FUNCTION prevent_cabinet_order_display_snapshot_mutation() IS
    '订单结算写入的展示快照不可变，防止随柜机/商户改名漂移';

-- B-06 / IMP-015/016 UAT: pending purchase order at finance node.
-- Super admin (100000001) sees 审批节点 + 待他人处理, no 通过/驳回 buttons.
-- Idempotent: keyed by ref_no PO-UAT-B06.

DO $$
DECLARE
    po_id BIGINT;
    inst_id BIGINT;
BEGIN
    SELECT purchase_order_id INTO po_id
    FROM purchase_order
    WHERE ref_no = 'PO-UAT-B06'
    LIMIT 1;

    IF po_id IS NULL THEN
        INSERT INTO purchase_order (supplier_id, warehouse_id, status, ref_no, operator_id, notes)
        VALUES (
            'SUP-DEMO-001',
            'WH-DEMO-001',
            'PENDING_APPROVAL',
            'PO-UAT-B06',
            100000001,
            'UAT B-06 pending finance approval demo'
        )
        RETURNING purchase_order_id INTO po_id;

        INSERT INTO purchase_order_line (
            purchase_order_id, sku_id, batch_no, expiry_date,
            ordered_qty, received_qty, unit_cost_cents, quality_status
        )
        VALUES (
            po_id,
            'SKU-DEMO-001',
            'BATCH-UAT-B06',
            CURRENT_DATE + 180,
            24,
            0,
            190,
            'PENDING'
        );
    ELSE
        UPDATE purchase_order
        SET status = 'PENDING_APPROVAL',
            notes = 'UAT B-06 pending finance approval demo'
        WHERE purchase_order_id = po_id
          AND status <> 'PENDING_APPROVAL';
    END IF;

    SELECT instance_id INTO inst_id
    FROM approval_instance
    WHERE biz_type = 'PURCHASE_ORDER'
      AND biz_id = po_id::text
    LIMIT 1;

    IF inst_id IS NULL THEN
        INSERT INTO approval_instance (
            def_id, biz_type, biz_id, title, status,
            submitter_id, current_node_seq, remark
        )
        VALUES (
            2,
            'PURCHASE_ORDER',
            po_id::text,
            '采购单 PO-UAT-B06',
            'PENDING',
            100000001,
            2,
            'UAT seed for B-06'
        )
        RETURNING instance_id INTO inst_id;
    ELSE
        UPDATE approval_instance
        SET status = 'PENDING',
            current_node_seq = 2,
            finished_at = NULL,
            title = '采购单 PO-UAT-B06',
            remark = 'UAT seed for B-06'
        WHERE instance_id = inst_id;
    END IF;

    UPDATE approval_task
    SET status = 'SKIPPED',
        acted_at = COALESCE(acted_at, NOW())
    WHERE instance_id = inst_id
      AND node_seq <> 2
      AND status = 'PENDING';

    INSERT INTO approval_task (
        instance_id, node_seq, node_name, assignee_user_id, status, acted_at
    )
    SELECT inst_id, 1, '采购申请', 100000001, 'APPROVED', NOW()
    WHERE NOT EXISTS (
        SELECT 1 FROM approval_task
        WHERE instance_id = inst_id AND node_seq = 1
    );

    UPDATE approval_task
    SET status = 'APPROVED',
        acted_at = COALESCE(acted_at, NOW())
    WHERE instance_id = inst_id
      AND node_seq = 1;

    INSERT INTO approval_task (
        instance_id, node_seq, node_name, assignee_user_id, status
    )
    SELECT inst_id, 2, '财务审批', 100000007, 'PENDING'
    WHERE NOT EXISTS (
        SELECT 1 FROM approval_task
        WHERE instance_id = inst_id
          AND node_seq = 2
          AND assignee_user_id = 100000007
          AND status = 'PENDING'
    );

    UPDATE approval_task
    SET status = 'PENDING',
        acted_at = NULL
    WHERE instance_id = inst_id
      AND node_seq = 2
      AND assignee_user_id = 100000007;
END $$;

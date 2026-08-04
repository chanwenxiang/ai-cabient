-- Remove MIN-UAT browser test replenishment routes (legacy garbled names like MIN-UAT ????).
DELETE FROM warehouse_in_transit wit
USING warehouse_outbound wo, replenishment_route rr
WHERE wit.outbound_id = wo.outbound_id
  AND wo.route_id = rr.route_id
  AND rr.route_name LIKE 'MIN-UAT %';

DELETE FROM warehouse_outbound_line wol
USING warehouse_outbound wo, replenishment_route rr
WHERE wol.outbound_id = wo.outbound_id
  AND wo.route_id = rr.route_id
  AND rr.route_name LIKE 'MIN-UAT %';

DELETE FROM replenishment_task_line rtl
USING replenishment_task rt, replenishment_route rr
WHERE rtl.task_id = rt.task_id
  AND rt.route_id = rr.route_id
  AND rr.route_name LIKE 'MIN-UAT %';

DELETE FROM replenishment_task rt
USING replenishment_route rr
WHERE rt.route_id = rr.route_id
  AND rr.route_name LIKE 'MIN-UAT %';

DELETE FROM warehouse_outbound wo
USING replenishment_route rr
WHERE wo.route_id = rr.route_id
  AND rr.route_name LIKE 'MIN-UAT %';

DELETE FROM replenishment_route
WHERE route_name LIKE 'MIN-UAT %';

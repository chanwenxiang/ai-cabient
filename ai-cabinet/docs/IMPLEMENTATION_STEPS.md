# AI Cabinet Implementation Steps

## Current Step

**Step 1: full business loop implementation.**

Goal: make the complete AI cabinet business chain callable from procurement inbound to warehouse, replenishment, cabinet inventory, consumer shopping, AI recognition, mock settlement, reconciliation, and commercial ledger outputs.

## Step 1 Acceptance Entry

Use the operations API below in local/mock mode:

```http
POST /api/v2/ops/admin/commercial-flow/run
Authorization: Bearer <ops-token>
Content-Type: application/json

{
  "deviceId": "CAB-001",
  "skuId": "SKU-DEMO-001",
  "inboundQty": 24,
  "consumerUserId": 10001,
  "channel": "MOCK"
}
```

The response returns every flow node:

- `DEMO_CONTEXT`: demo catalog, device, warehouse, inventory and user are ready.
- `PURCHASE_INBOUND`: procurement stock enters the warehouse.
- `REPLENISHMENT_ROUTE`: replenishment route and task are created.
- `WAREHOUSE_OUTBOUND`: outbound order is created from replenishment demand.
- `WAREHOUSE_SHIP`: stock is shipped and recorded as in-transit.
- `CABINET_REPLENISHED`: replenishment task updates cabinet inventory and batches.
- `SHOPPING_SESSION`: a local dev shopping session is created without real hardware.
- `AI_SETTLEMENT`: mock recognition creates a paid cabinet order and deducts inventory.
- `RECONCILIATION`: daily reconciliation runs for the selected channel.
- `BUSINESS_CLOSED_LOOP`: full chain has reached the commercial ledger stage.

## Step 1 Business APIs Added

The first production-oriented source ledger is now explicit instead of relying on anonymous test inventory.

- `GET /api/v2/ops/admin/suppliers`: list suppliers.
- `PUT /api/v2/ops/admin/suppliers/{supplierId}`: create or update an active supplier.
- `GET /api/v2/ops/admin/purchase-orders`: list purchase orders with lines.
- `POST /api/v2/ops/admin/purchase-orders`: create a purchase order for a warehouse and SKU batch.
- `POST /api/v2/ops/admin/purchase-orders/{purchaseOrderId}/receive`: receive ordered stock into warehouse inventory.
- `GET /api/v2/ops/admin/warehouse/movements`: inspect the latest warehouse stock movements.

The procurement receive path writes all of these records:

- `purchase_order` and `purchase_order_line`.
- `warehouse_inbound` with `purchase_order_id`.
- `warehouse_inbound_line` with batch, expiry and unit cost.
- `warehouse_inventory` with FEFO-ready batch balance.
- `warehouse_movement` for auditable stock deltas.

## Step 2

Optimize each node in the same order:

1. SKU and procurement quality rules.
   - Purchase lines now persist `quality_status`, `quality_note` and `rejected_qty`.
   - Receiving rejects expired stock, date ranges where production is after expiry, short shelf-life stock, and zero-cost lines.
   - Partial receiving is tracked as `PARTIAL_RECEIVED`; repeated receive calls for the same accepted quantity are idempotent.
2. Warehouse FEFO, batch and expiry handling.
   - Outbound allocation uses FEFO and excludes quantities already allocated to `DRAFT` or `PICKED` outbound orders.
   - Expired warehouse lots are skipped during allocation.
3. Outbound, in-transit and replenishment handover.
   - Outbound orders and lines now track `handover_status`.
   - Shipping requires a picked outbound order, moves lines to `IN_TRANSIT`, and replenishment completion marks device lines as `RECEIVED`.
4. Device opening, ACK, offline event replay and video upload.
   - Consumer and replenishment door validation now blocks devices that are not `ONLINE`.
   - Device-service already records published commands, ACK success/failure/timeout, and unknown ACKs.
   - Edge MQTT offline replay is backed by the persistent `OutboundMqttQueue`.
5. AI recognition confidence, dispute and manual review.
   - Existing confidence gate routes low-confidence, empty, or review-required recognition to dispute unless staging gravity fallback can settle.
   - Dispute tickets preserve suggested recognition items and manual resolution items.
6. Payment idempotency, refund and reconciliation.
   - Cabinet order charge, dispute adjustment charge, and refunds now write `payment_operation` rows with idempotency keys.
   - Refund gateway request numbers are deterministic per refund idempotency key.
7. Merchant ledger, profit sharing and settlement reporting.
   - Revenue split rows now include `settlement_batch_no`, `settle_after`, and `settled_at` fields for merchant ledger aging and reporting.

## Step 3

Turn Step 1 into repeatable tests:

- API integration tests for inbound, outbound, replenishment, shopping and settlement.
- E2E script for `commercial-flow/run`.
- Failure-path tests for low stock, video missing, low confidence, payment failure and duplicate MQTT events.

## Step 4

Deployment readiness:

- Production profiles and startup validators.
- Docker image build and migration check.
- MQTT TLS, object storage, SMS, payment gateway and monitoring.
- Backup/restore and rollback procedures.

## Step 5

Go-live plan:

- 1 real cabinet hardware integration.
- 3-5 cabinet pilot.
- Up to 20 cabinet operational trial.
- Scale only after online rate, recognition rate, dispute rate and reconciliation are stable.

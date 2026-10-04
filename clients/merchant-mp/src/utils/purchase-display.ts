export function satellitePurchaseStatusLabel(status: string): string {
  const key = String(status || '')
    .trim()
    .toUpperCase();
  if (key === 'PENDING_APPROVAL') return '待审核';
  if (key === 'CREATED') return '待收货';
  if (key === 'PARTIAL_RECEIVED') return '部分收货';
  if (key === 'RECEIVED') return '已收货';
  if (key === 'REJECTED') return '已驳回';
  if (key === 'CANCELLED') return '已取消';
  return '处理中';
}

export function canSubmitSatellitePurchase(input: {
  warehouseId: string;
  supplierId: string;
  lines: { selected: boolean; qty: number }[];
}): boolean {
  if (!input.warehouseId || !input.supplierId) return false;
  return input.lines.some((line) => line.selected && line.qty > 0);
}

export function canReceiveSatellitePurchase(status: string): boolean {
  const key = String(status || '')
    .trim()
    .toUpperCase();
  return key === 'CREATED' || key === 'PARTIAL_RECEIVED';
}

export function defaultSatelliteReceiveBatch(now = new Date()): string {
  const y = now.getFullYear();
  const m = String(now.getMonth() + 1).padStart(2, '0');
  const d = String(now.getDate()).padStart(2, '0');
  return `${y}${m}${d}`;
}

export function defaultSatelliteReceiveExpiry(now = new Date()): string {
  const dt = new Date(now.getTime());
  dt.setDate(dt.getDate() + 90);
  const y = dt.getFullYear();
  const m = String(dt.getMonth() + 1).padStart(2, '0');
  const d = String(dt.getDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}

export function canConfirmSatelliteReceive(
  lines: { batchNo?: string; expiryDate?: string; receivedQty?: number }[]
): boolean {
  return lines.some(
    (line) =>
      String(line.batchNo || '').trim() &&
      String(line.expiryDate || '').trim() &&
      Number(line.receivedQty) > 0
  );
}

import { describe, expect, it } from 'vitest';
import {
  canConfirmSatelliteReceive,
  canReceiveSatellitePurchase,
  canSubmitSatellitePurchase,
  defaultSatelliteReceiveBatch,
  defaultSatelliteReceiveExpiry,
  satellitePurchaseStatusLabel
} from './purchase-display';

describe('purchase-display', () => {
  it('maps purchase status to Chinese without hash prefix', () => {
    expect(satellitePurchaseStatusLabel('PENDING_APPROVAL')).toBe('待审核');
    expect(satellitePurchaseStatusLabel('CREATED')).toBe('待收货');
  });

  it('submit requires warehouse, supplier and selected qty', () => {
    expect(
      canSubmitSatellitePurchase({
        warehouseId: 'WH-1',
        supplierId: 'SUP-1',
        lines: [{ selected: true, qty: 2 }]
      })
    ).toBe(true);
    expect(
      canSubmitSatellitePurchase({
        warehouseId: '',
        supplierId: 'SUP-1',
        lines: [{ selected: true, qty: 2 }]
      })
    ).toBe(false);
    expect(
      canSubmitSatellitePurchase({
        warehouseId: 'WH-1',
        supplierId: 'SUP-1',
        lines: [{ selected: true, qty: 0 }]
      })
    ).toBe(false);
  });

  it('only pending receive statuses can confirm inbound', () => {
    expect(canReceiveSatellitePurchase('CREATED')).toBe(true);
    expect(canReceiveSatellitePurchase('PENDING_APPROVAL')).toBe(false);
  });

  it('receive confirm needs batch, expiry and qty', () => {
    expect(
      canConfirmSatelliteReceive([
        { batchNo: '20261005', expiryDate: '2026-12-31', receivedQty: 2 }
      ])
    ).toBe(true);
    expect(
      canConfirmSatelliteReceive([{ batchNo: '', expiryDate: '2026-12-31', receivedQty: 2 }])
    ).toBe(false);
  });

  it('receive confirm gates every line (audit P2-19) and rejects empty rows', () => {
    // 提交发全量行：单行批次被清空时整单必须拦下（原 some() 会放行）
    expect(
      canConfirmSatelliteReceive([
        { batchNo: 'B1', expiryDate: '2026-12-31', receivedQty: 2 },
        { batchNo: '', expiryDate: '2026-12-31', receivedQty: 2 }
      ])
    ).toBe(false);
    // 空行数组保持不可提交（every 对空数组为 true，需显式守卫）
    expect(canConfirmSatelliteReceive([])).toBe(false);
  });

  it('defaults batch to yyyymmdd and expiry about 90 days out', () => {
    const now = new Date('2026-10-05T00:00:00');
    expect(defaultSatelliteReceiveBatch(now)).toBe('20261005');
    expect(defaultSatelliteReceiveExpiry(now)).toBe('2027-01-03');
  });
});

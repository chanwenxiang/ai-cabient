import { describe, expect, it } from 'vitest';
import { resolveMerchantIdForDevice } from './device-settings';

describe('device-settings · M8', () => {
  it('优先柜机列表 merchantId', () => {
    expect(
      resolveMerchantIdForDevice({
        deviceId: 'D1',
        devices: [{ deviceId: 'D1', merchantId: 'M9' }],
        me: { merchants: [{ merchantId: 'M1' }] } as never
      })
    ).toBe('M9');
  });

  it('列表未命中回落 me 首商户', () => {
    expect(
      resolveMerchantIdForDevice({
        deviceId: 'D1',
        devices: [],
        me: { merchants: [{ merchantId: 'M1' }] } as never
      })
    ).toBe('M1');
  });

  it('皆无则空串', () => {
    expect(resolveMerchantIdForDevice({ deviceId: 'D1', devices: [], me: null })).toBe('');
  });
});

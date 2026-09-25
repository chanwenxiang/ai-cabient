/**
 * 柜机详情：从柜机列表/me 解析商户 ID（settings DTO 无 merchantId；M8）。
 */
import type { MerchantDeviceInfo, MerchantMe } from '@aicabinet/shared-types';

export function resolveMerchantIdForDevice(input: {
  deviceId: string;
  devices: MerchantDeviceInfo[];
  me: MerchantMe | null;
}): string {
  const fromList = input.devices.find((d) => d.deviceId === input.deviceId)?.merchantId;
  if (fromList) return String(fromList);
  return String(input.me?.merchants?.[0]?.merchantId || '');
}

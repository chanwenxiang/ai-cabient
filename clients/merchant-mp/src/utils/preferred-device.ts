/** Persist preferred cabinet filter for merchant day-ops pages (client-side lite scope). */
const KEY = 'merchant_preferred_device_id';

export function getPreferredDeviceId(): string {
  return String(uni.getStorageSync(KEY) || '').trim();
}

export function setPreferredDeviceId(deviceId?: string | null) {
  const id = String(deviceId || '').trim();
  if (!id) {
    uni.removeStorageSync(KEY);
    return;
  }
  uni.setStorageSync(KEY, id);
}

export function clearPreferredDeviceId() {
  uni.removeStorageSync(KEY);
}

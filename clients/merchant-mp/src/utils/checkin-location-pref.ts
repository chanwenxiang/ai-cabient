/** 本机是否跳过补货签到 GPS（竞品常见：室内/H5/权限受限时记住选择） */
const SKIP_CHECKIN_LOCATION_KEY = 'merchant_skip_checkin_location';

export function getSkipCheckInLocation(): boolean {
  try {
    return uni.getStorageSync(SKIP_CHECKIN_LOCATION_KEY) === '1';
  } catch {
    return false;
  }
}

export function setSkipCheckInLocation(skip: boolean): void {
  try {
    uni.setStorageSync(SKIP_CHECKIN_LOCATION_KEY, skip ? '1' : '0');
  } catch {
    // storage unavailable
  }
}

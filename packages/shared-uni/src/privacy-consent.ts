/** H5 首屏隐私同意：两端共用存储键与判定（运行时探测，不依赖条件编译）。 */

export const PRIVACY_CONSENT_KEY = 'aicabinet_privacy_consent_v1';

export function isH5PrivacyRuntime(): boolean {
  return (
    typeof window !== 'undefined' &&
    typeof navigator !== 'undefined' &&
    !/miniProgram|miniprogram/i.test(navigator.userAgent)
  );
}

export function hasPrivacyConsent(): boolean {
  try {
    return uni.getStorageSync(PRIVACY_CONSENT_KEY) === '1';
  } catch {
    return false;
  }
}

export function acceptPrivacyConsent(): void {
  uni.setStorageSync(PRIVACY_CONSENT_KEY, '1');
}

/** H5 且未同意时需要弹出首屏隐私窗。 */
export function shouldShowPrivacyConsent(): boolean {
  return isH5PrivacyRuntime() && !hasPrivacyConsent();
}

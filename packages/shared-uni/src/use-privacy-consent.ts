import { ref, onMounted } from 'vue';
import {
  isWeixinMpRuntime,
  shouldShowMpPrivacyConsent,
  shouldShowPrivacyConsent
} from '@aicabinet/shared-uni/privacy-consent';

/** 页面级隐私首屏：挂到首页/登录等入口即可（H5 与微信小程序共用）。 */
export function usePrivacyConsentModal() {
  const showPrivacy = ref(false);

  function refreshPrivacyGate() {
    if (isWeixinMpRuntime()) {
      // mp：官方同步状态异步查询——该用户尚未同意当前版《用户隐私保护指引》时弹首屏窗；
      // 查询失败 / 低版本基础库 → 不弹（调用隐私接口时由微信官方默认弹窗兜底）。
      // 注意 mp 端同意后不重载：request 层拦截仅 H5（request.ts:232 isH5PrivacyRuntime 分支）。
      void shouldShowMpPrivacyConsent().then((need) => {
        showPrivacy.value = need;
      });
      return;
    }
    showPrivacy.value = shouldShowPrivacyConsent();
  }

  onMounted(refreshPrivacyGate);

  function onPrivacyAccepted() {
    showPrivacy.value = false;
    // 同意前所有请求被 request 层拦下（合规收口，仅 H5），同意后整页重载让入口页的数据拉取自然发生。
    if (typeof window !== 'undefined') {
      window.location.reload();
    }
  }

  function onPrivacyDeclined() {
    showPrivacy.value = false;
  }

  return {
    showPrivacy,
    refreshPrivacyGate,
    onPrivacyAccepted,
    onPrivacyDeclined
  };
}

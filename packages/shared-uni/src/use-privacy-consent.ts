import { ref, onMounted } from 'vue';
import { shouldShowPrivacyConsent } from '@aicabinet/shared-uni/privacy-consent';

/** 页面级 H5 隐私首屏：挂到首页/登录等入口即可。 */
export function usePrivacyConsentModal() {
  const showPrivacy = ref(false);

  function refreshPrivacyGate() {
    showPrivacy.value = shouldShowPrivacyConsent();
  }

  onMounted(refreshPrivacyGate);

  function onPrivacyAccepted() {
    showPrivacy.value = false;
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

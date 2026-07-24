import { setDictOverrides, clearDictOverrides, buildOverridesFromRuntime } from '@aicabinet/shared-dict';
import { api, isLoggedIn } from '@/api/client';

/** 登录后拉取运营字典覆盖；失败时保留编译期默认文案。 */
export async function loadRuntimeDict() {
  if (!isLoggedIn()) {
    clearDictOverrides();
    return;
  }
  try {
    const data = await api.request('/api/v2/dicts/runtime', 'GET');
    setDictOverrides(buildOverridesFromRuntime(data as Parameters<typeof buildOverridesFromRuntime>[0]));
  } catch {
    // 失败时保留编译期 DICT 默认文案
  }
}

export function resetRuntimeDict() {
  clearDictOverrides();
}

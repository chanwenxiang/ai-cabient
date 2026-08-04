import { setDictOverrides, clearDictOverrides, buildOverridesFromRuntime } from '@aicabinet/shared-dict';
import { api, isLoggedIn } from '@/api/client';

/** 登录后拉取运营字典覆盖；失败时不标记 loaded，继续用编译期 DICT / 上次成功缓存。 */
export async function loadRuntimeDict() {
  if (!isLoggedIn()) {
    clearDictOverrides();
    return;
  }
  try {
    const data = await api.request('/api/v2/dicts/runtime', 'GET');
    setDictOverrides(buildOverridesFromRuntime(data as Parameters<typeof buildOverridesFromRuntime>[0]), {
      loaded: true
    });
  } catch {
    // 失败：不 clear、不设 loaded，系统枚举与未加载的可配字典仍走 DICT 兜底
  }
}

export function resetRuntimeDict() {
  clearDictOverrides();
}

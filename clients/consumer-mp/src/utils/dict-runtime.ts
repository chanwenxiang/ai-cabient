import { setDictOverrides, clearDictOverrides, buildOverridesFromRuntime } from '@aicabinet/shared-dict';
import { getConsumerToken, request } from '@/utils/consumer-api';

/** 登录后拉取运营字典覆盖；失败时不标记 loaded，继续用编译期 DICT / 上次成功缓存。 */
export async function loadRuntimeDict() {
  if (!getConsumerToken()) {
    clearDictOverrides();
    return;
  }
  try {
    const data = await request('/api/v2/dicts/runtime', 'GET');
    setDictOverrides(buildOverridesFromRuntime(data as Parameters<typeof buildOverridesFromRuntime>[0]), {
      loaded: true
    });
  } catch {
    // 失败：不 clear、不设 loaded
  }
}

export function resetRuntimeDict() {
  clearDictOverrides();
}

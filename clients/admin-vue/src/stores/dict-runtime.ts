import { ref } from 'vue';
import {
  setDictOverrides,
  clearDictOverrides,
  buildOverridesFromRuntime
} from '@aicabinet/shared-dict-core';
import { api, isLoggedIn } from '@/api/client';

/**
 * 每次 runtime 拉取/清空后递增，供下拉 computed / 响应式 dictOptions 依赖。
 * shared-dict 内部状态非 Vue 响应式，禁用字典项后必须靠此触发 UI 刷新。
 */
export const dictRuntimeEpoch = ref(0);

function bumpDictRuntime() {
  dictRuntimeEpoch.value += 1;
}

/** 登录后拉取运营字典覆盖；失败时不标记 loaded，继续用编译期 DICT / 上次成功缓存。 */
export async function loadRuntimeDict() {
  if (!isLoggedIn()) {
    clearDictOverrides();
    bumpDictRuntime();
    return;
  }
  try {
    const data = await api.request('/api/v2/dicts/runtime', 'GET');
    setDictOverrides(buildOverridesFromRuntime(data as Parameters<typeof buildOverridesFromRuntime>[0]), {
      loaded: true
    });
    bumpDictRuntime();
  } catch {
    // 失败：不 clear、不设 loaded，系统枚举与未加载的可配字典仍走 DICT 兜底
  }
}

export function resetRuntimeDict() {
  clearDictOverrides();
  bumpDictRuntime();
}

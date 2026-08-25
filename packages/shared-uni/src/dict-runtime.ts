import {
  clearDictOverrides,
  setDictOverrides,
  buildOverridesFromRuntime
} from '@aicabinet/shared-dict';

export interface RuntimeDictLoader {
  getToken(): string;
  fetchRuntime(): Promise<unknown>;
}

/** 登录后拉取运营字典覆盖；失败时不标记 loaded，继续用编译期 DICT / 上次成功缓存。 */
export async function loadRuntimeDict(loader: RuntimeDictLoader) {
  if (!loader.getToken()) {
    clearDictOverrides();
    return;
  }
  try {
    const data = await loader.fetchRuntime();
    setDictOverrides(
      buildOverridesFromRuntime(data as Parameters<typeof buildOverridesFromRuntime>[0]),
      {
        loaded: true
      }
    );
  } catch {
    // 失败：不 clear、不设 loaded
  }
}

export function resetRuntimeDict() {
  clearDictOverrides();
}

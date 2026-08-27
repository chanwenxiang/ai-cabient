import { computed, type ComputedRef } from 'vue';
import { dictOptions } from '@aicabinet/shared-dict-core';
import { consumeDictRuntimeEpoch } from '@/stores/dict-runtime';

/**
 * 响应式字典下拉：依赖 dictRuntimeEpoch，禁用项在 loadRuntimeDict 后立即从选项中消失。
 * 勿在 setup 顶层写 `const x = dictOptions(...)`（只会快照一次）。
 */
export function useDictOptions(type: string): ComputedRef<{ value: string; label: string }[]> {
  return computed(() => {
    consumeDictRuntimeEpoch();
    return dictOptions(type);
  });
}

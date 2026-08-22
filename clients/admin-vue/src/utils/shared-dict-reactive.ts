/**
 * 运营后台用的响应式字典出口。
 * 模板里直接调用 dictOptions/dictLabel 时会读取 dictRuntimeEpoch，
 * 字典启用/停用并 loadRuntimeDict 后，keep-alive 页面也会立刻重算下拉。
 */
import { EMPTY_TEXT } from '@/utils/display';
import { dictRuntimeEpoch } from '@/stores/dict-runtime';
import {
  dictLabel as coreDictLabel,
  dictOptions as coreDictOptions,
  displayLabel as coreDisplayLabel
} from '@aicabinet/shared-dict-core';

export * from '@aicabinet/shared-dict-core';

export function dictOptions(type: string) {
  void dictRuntimeEpoch.value;
  return coreDictOptions(type);
}

export function dictLabel(type: string, code: string | null | undefined) {
  void dictRuntimeEpoch.value;
  return coreDictLabel(type, code);
}

export function displayLabel(type: string, code: string | null | undefined, empty = EMPTY_TEXT) {
  void dictRuntimeEpoch.value;
  return coreDisplayLabel(type, code, empty);
}

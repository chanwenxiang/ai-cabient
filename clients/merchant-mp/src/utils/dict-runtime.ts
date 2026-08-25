import {
  loadRuntimeDict as sharedLoadRuntimeDict,
  resetRuntimeDict as sharedResetRuntimeDict
} from '@aicabinet/shared-uni/dict-runtime';
import { getToken, request } from '@/utils/merchant-api';

export function loadRuntimeDict() {
  return sharedLoadRuntimeDict({
    getToken,
    fetchRuntime: () => request('/api/v2/dicts/runtime', 'GET')
  });
}

export const resetRuntimeDict = sharedResetRuntimeDict;

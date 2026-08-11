import {
  loadRuntimeDict as sharedLoadRuntimeDict,
  resetRuntimeDict as sharedResetRuntimeDict
} from '@aicabinet/shared-uni/dict-runtime';
import { getConsumerToken, request } from '@/utils/consumer-api';

export function loadRuntimeDict() {
  return sharedLoadRuntimeDict({
    getToken: getConsumerToken,
    fetchRuntime: () => request('/api/v2/dicts/runtime', 'GET')
  });
}

export const resetRuntimeDict = sharedResetRuntimeDict;

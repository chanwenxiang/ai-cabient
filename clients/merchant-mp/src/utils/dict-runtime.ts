import { loadRuntimeDict as sharedLoadRuntimeDict } from '@aicabinet/shared-uni/dict-runtime';
import { getToken, request } from '@/utils/merchant-api';

export function loadRuntimeDict() {
  return sharedLoadRuntimeDict({
    getToken,
    fetchRuntime: () => request('/api/v2/dicts/runtime', 'GET')
  });
}

export { resetRuntimeDict } from '@aicabinet/shared-uni/dict-runtime';

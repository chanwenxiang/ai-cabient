import { loadRuntimeDict as sharedLoadRuntimeDict } from '@aicabinet/shared-uni/dict-runtime';
import { getToken, request } from '@/utils/merchant-api';
import { MerchantEndpoints } from '@/api/endpoints';

export function loadRuntimeDict() {
  return sharedLoadRuntimeDict({
    getToken,
    // M9/M3 复审：此前为裸路径字面量，收口到 MerchantEndpoints
    fetchRuntime: () => request(MerchantEndpoints.dictsRuntime, 'GET')
  });
}

export { resetRuntimeDict } from '@aicabinet/shared-uni/dict-runtime';

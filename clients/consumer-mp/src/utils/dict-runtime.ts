import { loadRuntimeDict as sharedLoadRuntimeDict } from '@aicabinet/shared-uni/dict-runtime';
import { ConsumerEndpoints } from '@/api/endpoints';
import { getConsumerToken, request } from '@/utils/consumer-api';

export function loadRuntimeDict() {
  return sharedLoadRuntimeDict({
    getToken: getConsumerToken,
    fetchRuntime: () => request(ConsumerEndpoints.dictsRuntime, 'GET')
  });
}

export { resetRuntimeDict } from '@aicabinet/shared-uni/dict-runtime';

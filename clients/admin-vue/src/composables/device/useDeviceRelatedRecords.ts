import { ref } from 'vue';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import { softFallback } from '@/utils/soft-fallback';
import type { OrderReadModel, PageResult, SessionDto } from '@aicabinet/shared-types';

export type UseDeviceRelatedRecordsDeps = {
  deviceId: string;
};

/**
 * 设备详情「关联单据」：最近会话/订单列表与 total（供重生编号门闩）。
 * 从 DeviceDetailView 抽出（debt-tracker D22）。
 */
export function useDeviceRelatedRecords(deps: UseDeviceRelatedRecordsDeps) {
  const sessions = ref<SessionDto[]>([]);
  const orders = ref<OrderReadModel[]>([]);
  const sessionTotal = ref(0);
  const orderTotal = ref(0);
  const relatedHydrated = ref(false);

  async function loadRelated() {
    try {
      const [sess, ord] = await Promise.all([
        softFallback(
          api.request<PageResult<SessionDto>>(
            AdminEndpoints.sessionsList(
              `page=0&size=8&deviceId=${encodeURIComponent(deps.deviceId)}`
            ),
            'GET'
          ),
          { items: [] as SessionDto[], total: 0, page: 0, size: 8 },
          '关联会话'
        ),
        softFallback(
          api.request<PageResult<OrderReadModel>>(
            AdminEndpoints.ordersList(
              `page=0&size=8&deviceId=${encodeURIComponent(deps.deviceId)}`
            ),
            'GET'
          ),
          { items: [] as OrderReadModel[], total: 0, page: 0, size: 8 },
          '关联订单'
        )
      ]);
      sessions.value = sess.items || [];
      orders.value = ord.items || [];
      sessionTotal.value = 'total' in sess ? (sess.total ?? 0) : sessions.value.length;
      orderTotal.value = 'total' in ord ? (ord.total ?? 0) : orders.value.length;
    } finally {
      relatedHydrated.value = true;
    }
  }

  function markRelatedHydrated() {
    relatedHydrated.value = true;
  }

  return {
    sessions,
    orders,
    sessionTotal,
    orderTotal,
    relatedHydrated,
    loadRelated,
    markRelatedHydrated
  };
}

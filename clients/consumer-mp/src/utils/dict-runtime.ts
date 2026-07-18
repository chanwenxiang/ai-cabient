import { setDictOverrides, clearDictOverrides } from '@aicabinet/shared-dict';
import { getConsumerToken, request } from '@/utils/consumer-api';

interface RuntimePayload {
  itemsByType?: Record<string, { dictValue: string; dictLabel: string; status?: string }[]>;
}

export async function loadRuntimeDict() {
  if (!getConsumerToken()) {
    clearDictOverrides();
    return;
  }
  try {
    const data = await request<RuntimePayload>('/api/v2/dicts/runtime', 'GET');
    const map: Record<string, Record<string, string>> = {};
    for (const [type, rows] of Object.entries(data?.itemsByType || {})) {
      map[type] = {};
      for (const row of rows || []) {
        if (row.status && row.status !== 'ACTIVE') continue;
        map[type][row.dictValue] = row.dictLabel;
      }
    }
    setDictOverrides(map);
  } catch {
    // keep compile-time DICT defaults
  }
}

export function resetRuntimeDict() {
  clearDictOverrides();
}

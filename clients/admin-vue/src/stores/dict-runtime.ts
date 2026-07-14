import { setDictOverrides, clearDictOverrides } from '@aicabinet/shared-dict';
import { api, isLoggedIn } from '@/api/client';

interface RuntimePayload {
  itemsByType?: Record<string, { dictValue: string; dictLabel: string; status?: string }[]>;
}

export async function loadRuntimeDict() {
  if (!isLoggedIn()) {
    clearDictOverrides();
    return;
  }
  try {
    const data = await api.request<RuntimePayload>('/api/v2/ops/admin/dicts/runtime', 'GET');
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

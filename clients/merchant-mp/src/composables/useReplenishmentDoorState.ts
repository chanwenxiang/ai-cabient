import { ref } from 'vue';
import { merchantApi } from '@/utils/merchant-api';

function doorCacheKey(taskId: number) {
  return `replenish_door_${taskId}`;
}

/** 本地开门缓存结构校验（M-24） */
export function parseDoorCache(raw: unknown): { sessionId: string } | null {
  let cached: unknown = raw;
  if (typeof raw === 'string') {
    try {
      cached = JSON.parse(raw);
    } catch {
      return null;
    }
  }
  if (!cached || typeof cached !== 'object') return null;
  const sessionId = String((cached as { sessionId?: unknown }).sessionId ?? '').trim();
  if (!sessionId) return null;
  return { sessionId };
}

/** 补货开门本地缓存 + 服务端会话同步 */
export function useReplenishmentDoorState() {
  const doorOpened = ref(false);
  const openSessionId = ref('');

  function clearDoorState(taskId?: number) {
    doorOpened.value = false;
    openSessionId.value = '';
    if (taskId == null) return;
    try {
      uni.removeStorageSync(doorCacheKey(taskId));
    } catch {
      /* ignore */
    }
  }

  function restoreDoorState(taskId: number) {
    try {
      const raw = uni.getStorageSync(doorCacheKey(taskId));
      if (!raw) {
        clearDoorState();
        return;
      }
      const cached = parseDoorCache(raw);
      if (!cached) {
        clearDoorState(taskId);
        return;
      }
      doorOpened.value = true;
      openSessionId.value = cached.sessionId;
    } catch {
      clearDoorState();
    }
  }

  /** 以服务端补货会话覆盖本地开门缓存（M-12） */
  async function syncDoorStateFromServer(taskId: number) {
    try {
      const info = await merchantApi.replenishmentDoorSession(taskId);
      if (info?.doorOpened && info.sessionId) {
        doorOpened.value = true;
        openSessionId.value = String(info.sessionId);
        persistDoorState(taskId, String(info.sessionId));
        return;
      }
      clearDoorState(taskId);
    } catch {
      // 网络失败时保留本地乐观状态，完成任务仍由服务端门禁兜底
    }
  }

  function persistDoorState(taskId: number, sessionId: string) {
    uni.setStorageSync(doorCacheKey(taskId), { sessionId, at: Date.now() });
  }

  return {
    doorOpened,
    openSessionId,
    restoreDoorState,
    syncDoorStateFromServer,
    persistDoorState,
    clearDoorState
  };
}

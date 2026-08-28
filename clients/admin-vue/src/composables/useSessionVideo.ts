import { ElMessage } from 'element-plus';
import { authFetch } from '@/api/client';

export type SessionVideoLoadResult = {
  url: string;
  revoke: () => void;
};

async function fetchSessionVideoBlob(sessionId?: string | null): Promise<SessionVideoLoadResult> {
  const id = String(sessionId || '').trim();
  if (!id) {
    throw new Error('无关联会话，无法播放录像');
  }
  const res = await authFetch(
    `${globalThis.location.origin}/api/v2/ops/admin/sessions/${encodeURIComponent(id)}/video`
  );
  if (!res.ok) {
    if (res.status === 404) throw new Error('录像尚未上传或不存在');
    if (res.status === 403) throw new Error('无录像查看权限');
    throw new Error(`播放失败（HTTP ${res.status}）`);
  }
  const blob = await res.blob();
  const url = URL.createObjectURL(blob);
  return {
    url,
    revoke: () => URL.revokeObjectURL(url)
  };
}

/** Stream session video via authenticated ops API. */
export function useSessionVideo() {
  /** Open video in a new tab (list row / overflow actions). */
  async function playSessionVideo(sessionId?: string | null) {
    try {
      const { url, revoke } = await fetchSessionVideoBlob(sessionId);
      globalThis.open(url, '_blank');
      globalThis.setTimeout(revoke, 60_000);
    } catch (e) {
      ElMessage.error(e instanceof Error ? e.message : '播放失败');
    }
  }

  return { playSessionVideo, fetchSessionVideoBlob };
}

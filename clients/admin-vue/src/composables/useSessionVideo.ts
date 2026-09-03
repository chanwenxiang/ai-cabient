import { ElMessage } from 'element-plus';
import { authFetch } from '@/api/client';

export type SessionVideoLoadResult = {
  url: string;
  revoke: () => void;
};

const VIDEO_BLOB_TTL_MS = 10 * 60 * 1000;

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
  const blobRaw = await res.blob();
  // Gateway/代理偶发把 Content-Type 变成 octet-stream，Chrome 无法解码
  const blob =
    blobRaw.type && blobRaw.type.startsWith('video/')
      ? blobRaw
      : new Blob([await blobRaw.arrayBuffer()], { type: 'video/mp4' });
  const url = URL.createObjectURL(blob);
  return {
    url,
    revoke: () => URL.revokeObjectURL(url)
  };
}

/** 带鉴权拉取会话录像并播放 */
export function useSessionVideo() {
  /**
   * 新标签页播放：先同步 open 空白页（避免 await 后被浏览器拦截弹窗），
   * blob URL 在标签关闭时回收，兜底 10 分钟 TTL（不再用固定 60s 断链）。
   */
  async function playSessionVideo(sessionId?: string | null) {
    const win = globalThis.open('about:blank', '_blank');
    try {
      const { url, revoke } = await fetchSessionVideoBlob(sessionId);
      if (!win || win.closed) {
        revoke();
        ElMessage.warning('浏览器拦截了新窗口，请允许本站弹窗后重试');
        return;
      }
      win.location.href = url;
      const safeRevoke = () => {
        try {
          revoke();
        } catch {
          /* ignore */
        }
      };
      win.addEventListener('beforeunload', safeRevoke);
      globalThis.setTimeout(safeRevoke, VIDEO_BLOB_TTL_MS);
    } catch (e) {
      try {
        win?.close();
      } catch {
        /* ignore */
      }
      ElMessage.error(e instanceof Error ? e.message : '播放失败');
    }
  }

  return { playSessionVideo, fetchSessionVideoBlob };
}

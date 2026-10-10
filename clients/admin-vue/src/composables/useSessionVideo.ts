import { ElMessage } from 'element-plus';
import { authFetch } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';

export type SessionVideoLoadResult = {
  url: string;
  revoke: () => void;
};

/**
 * 旧边缘录像（shopping_session.video_uri → /ops/sessions/{id}/video）不可用时的回退取址。
 *
 * <p>将邑柜机（现网全部柜机）从不写 shopping_session.video_uri（CB-030），所以对将邑单
 * blob 拉流必然失败；调用方用本回调提供「将邑柜机台账」的可播地址兜底（CB-029）。</p>
 */
export type SessionVideoFallback = () => Promise<string | null> | string | null;

async function resolveFallback(fallback?: SessionVideoFallback): Promise<string | null> {
  if (!fallback) return null;
  try {
    const url = await fallback();
    const trimmed = (url || '').trim();
    return trimmed || null;
  } catch {
    return null;
  }
}

const VIDEO_BLOB_TTL_MS = 10 * 60 * 1000;

async function fetchSessionVideoBlob(sessionId?: string | null): Promise<SessionVideoLoadResult> {
  const id = String(sessionId || '').trim();
  if (!id) {
    throw new Error('无关联会话，无法播放录像');
  }
  const res = await authFetch(`${globalThis.location.origin}${AdminEndpoints.sessionVideo(id)}`);
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
   *
   * @param fallback 旧边缘录像不可用时提供可播地址（如将邑柜机台账，见 {@link SessionVideoFallback}）
   */
  async function playSessionVideo(sessionId?: string | null, fallback?: SessionVideoFallback) {
    // 此处必须保留 opener 句柄：后续 win.location.href 指向 blob URL 并挂 beforeunload 回收，
    // noopener 会让 open 返回 null 导致播放失效。目标是同源 blob，不存在 reverse tabnabbing 风险。
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
      // 旧边缘 blob 拉流失败（对象不存在 / 无权限 / HTTP 错）。将邑柜机不写
      // shopping_session.video_uri（CB-030）⇒ 对将邑单此处必失败，改回退将邑台账地址。
      const fallbackUrl = await resolveFallback(fallback);
      if (fallbackUrl) {
        const target = win && !win.closed ? win : globalThis.open('about:blank', '_blank');
        if (target) target.location.href = fallbackUrl;
        return;
      }
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

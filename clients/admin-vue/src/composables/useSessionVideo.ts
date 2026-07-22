import { ElMessage } from 'element-plus';

/** Stream session video via authenticated ops API and open in a new tab. */
export function useSessionVideo() {
  async function playSessionVideo(sessionId?: string | null) {
    const id = String(sessionId || '').trim();
    if (!id) {
      ElMessage.warning('无关联会话，无法播放录像');
      return;
    }
    const token = localStorage.getItem('admin_token');
    try {
      const res = await fetch(
        `${window.location.origin}/api/v2/ops/admin/sessions/${encodeURIComponent(id)}/video`,
        { headers: token ? { Authorization: `Bearer ${token}` } : {} }
      );
      if (!res.ok) {
        if (res.status === 404) throw new Error('录像尚未上传或不存在');
        if (res.status === 403) throw new Error('无录像查看权限');
        throw new Error(`播放失败（HTTP ${res.status}）`);
      }
      const blob = await res.blob();
      const url = URL.createObjectURL(blob);
      window.open(url, '_blank');
      window.setTimeout(() => URL.revokeObjectURL(url), 60_000);
    } catch (e) {
      ElMessage.error(e instanceof Error ? e.message : '播放失败');
    }
  }

  return { playSessionVideo };
}

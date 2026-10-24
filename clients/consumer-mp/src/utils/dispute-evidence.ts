import { safeChooseImageWithReason } from '@aicabinet/shared-uni/safe-uni-call';
import { BoundedFifoCache } from '@/utils/bounded-fifo-cache';
import { consumerApi, getConsumerToken } from '@/utils/consumer-api';
import { showError } from '@/utils/notify';
import { API_BASE_URL } from '@/config/api';
import type { FileAttachmentDto } from '@aicabinet/shared-types';
import { assertLocalImageSize } from '@aicabinet/shared-uni/upload-limits';

export type LocalEvidence = {
  localPath: string;
  fileId?: number;
  url?: string;
  uploading?: boolean;
};

/**
 * 选择并上传申诉附图。
 * @param onChange 每次列表变化时回调（须立刻写回页面 ref），否则上传中态不可见、无法拦提交。
 *
 * F4 入口门闩（2026-10-24 缺陷类门禁）：选图弹窗打开期间连点入口会二次进入，
 * 与 addEvidence/chooseEvidence 同口径加进程内单航班闸。
 */
let pickingEvidence = false;
export async function pickAndUploadEvidence(
  current: LocalEvidence[],
  maxCount = 5,
  onChange?: (items: LocalEvidence[]) => void
): Promise<LocalEvidence[]> {
  if (pickingEvidence) return current;
  pickingEvidence = true;
  try {
    const remain = maxCount - current.length;
    if (remain <= 0) {
      showError(`最多 ${maxCount} 张`);
      return current;
    }
    // C9：H5 无选图实现/用户取消 → 空数组走「未添加」分支；
    // 用户拒绝相册授权 → 明确提示，不再静默无反馈（2026-10-23 第五轮审计）
    const { paths, denied } = await safeChooseImageWithReason(remain);
    if (denied) {
      showError('需要相册权限：请在微信「设置-隐私-照片」中允许后重试');
      return current;
    }
    if (!paths.length) return current;
    const next = [...current];
    const notify = () => onChange?.([...next]);
    for (const path of paths) {
      try {
        await assertLocalImageSize(path);
      } catch (e) {
        showError(e instanceof Error ? e.message : '图片过大');
        continue;
      }
      const placeholder: LocalEvidence = { localPath: path, uploading: true };
      next.push(placeholder);
      notify();
      try {
        const uploaded: FileAttachmentDto = await consumerApi.uploadDisputeEvidence(path);
        placeholder.fileId = uploaded.fileId;
        placeholder.url = absoluteEvidenceUrl(uploaded.url);
        placeholder.uploading = false;
        notify();
      } catch (e) {
        placeholder.uploading = false;
        const idx = next.lastIndexOf(placeholder);
        if (idx >= 0) next.splice(idx, 1);
        notify();
        showError(e instanceof Error ? e.message : '图片上传失败');
      }
    }
    return next;
  } finally {
    pickingEvidence = false;
  }
}

export function evidenceFileIds(items: LocalEvidence[]): number[] {
  return items.map((i) => i.fileId).filter((id): id is number => typeof id === 'number' && id > 0);
}

export function absoluteEvidenceUrl(url?: string): string {
  if (!url) return '';
  if (url.startsWith('http://') || url.startsWith('https://')) return url;
  const base = API_BASE_URL.replace(/\/$/, '');
  return url.startsWith('/') ? base + url : `${base}/${url}`;
}

/** H5 image 标签无法带 Authorization，用本地预览路径；已上传则仍用 localPath */
export function previewEvidenceSrc(item: LocalEvidence): string {
  return item.localPath || absoluteEvidenceUrl(item.url);
}

/**
 * 本地预览路径缓存（url → tempFilePath）。
 * 2026-10-23 审计残留修复：原先只进不出，长时间使用会无限增长；现 FIFO 上限 50 条，
 * 淘汰后再次展示时重新下载即可（uni.downloadFile 本就有系统级临时文件管理）。
 * 淘汰逻辑抽在 BoundedFifoCache（可直测），此处只是接线。
 */
const evidenceLocalCache = new BoundedFifoCache(50);

/**
 * 带 Authorization 下载证据图到本地临时路径，避免把 token 拼进 image URL。
 * C-7：下载失败 resolve('') 由展示层回退占位——不再回退 query access_token
 * （曾为兼容旧版 AuthInterceptor，现该路径已不存在；拼 token 进 URL 会落日志）。
 */
export function fetchEvidenceLocalPath(url?: string): Promise<string> {
  const abs = absoluteEvidenceUrl(url);
  if (!abs) return Promise.resolve('');
  const cached = evidenceLocalCache.get(abs);
  if (cached) return Promise.resolve(cached);
  const token = getConsumerToken();
  return new Promise((resolve) => {
    uni.downloadFile({
      url: abs,
      header: token
        ? { Authorization: 'Bearer ' + token, 'X-Requested-With': 'XMLHttpRequest' }
        : { 'X-Requested-With': 'XMLHttpRequest' },
      // #ifdef H5
      withCredentials: true,
      // #endif
      success: (res) => {
        if (res.statusCode >= 200 && res.statusCode < 300 && res.tempFilePath) {
          evidenceLocalCache.set(abs, res.tempFilePath);
          resolve(res.tempFilePath);
          return;
        }
        // C-7：失败不再把 token 拼进 URL（防泄露）；交由上层显示占位/重试
        resolve('');
      },
      fail: () => resolve('')
    });
  });
}

export function removeEvidenceAt(items: LocalEvidence[], index: number): LocalEvidence[] {
  return items.filter((_, i) => i !== index);
}

import { consumerApi, getConsumerToken } from '@/utils/consumer-api';
import { API_BASE_URL } from '@/config/api';
import type { FileAttachmentDto } from '@aicabinet/shared-types';

export type LocalEvidence = {
  localPath: string;
  fileId?: number;
  url?: string;
  uploading?: boolean;
};

/** 选择并上传申诉附图，返回已成功的 fileId 列表 */
export async function pickAndUploadEvidence(
  current: LocalEvidence[],
  maxCount = 5
): Promise<LocalEvidence[]> {
  const remain = maxCount - current.length;
  if (remain <= 0) {
    uni.showToast({ title: `最多 ${maxCount} 张`, icon: 'none' });
    return current;
  }
  const paths = await new Promise<string[]>((resolve) => {
    uni.chooseImage({
      count: remain,
      sizeType: ['compressed'],
      sourceType: ['album', 'camera'],
      success: (res) => {
        // @dcloudio/types 中 tempFilePaths 声明为 string | string[]，统一归一化为数组
        const raw = res.tempFilePaths || [];
        resolve(Array.isArray(raw) ? raw : [raw]);
      },
      fail: () => resolve([])
    });
  });
  if (!paths.length) return current;
  const next = [...current];
  for (const path of paths) {
    const placeholder: LocalEvidence = { localPath: path, uploading: true };
    next.push(placeholder);
    try {
      const uploaded: FileAttachmentDto = await consumerApi.uploadDisputeEvidence(path);
      placeholder.fileId = uploaded.fileId;
      placeholder.url = absoluteEvidenceUrl(uploaded.url);
      placeholder.uploading = false;
    } catch (e) {
      placeholder.uploading = false;
      next.pop();
      uni.showToast({
        title: e instanceof Error ? e.message : '图片上传失败',
        icon: 'none'
      });
    }
  }
  return next;
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

const evidenceLocalCache = new Map<string, string>();

/**
 * 带 Authorization 下载证据图到本地临时路径，避免把 token 拼进 image URL。
 * 下载失败时才回退 query access_token（兼容后端 AuthInterceptor）。
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
      header: token ? { Authorization: 'Bearer ' + token } : {},
      success: (res) => {
        if (res.statusCode >= 200 && res.statusCode < 300 && res.tempFilePath) {
          evidenceLocalCache.set(abs, res.tempFilePath);
          resolve(res.tempFilePath);
          return;
        }
        resolve(token ? withAccessToken(abs, token) : abs);
      },
      fail: () => resolve(token ? withAccessToken(abs, token) : abs)
    });
  });
}

function withAccessToken(url: string, token: string) {
  return `${url}${url.includes('?') ? '&' : '?'}access_token=${encodeURIComponent(token)}`;
}

export function removeEvidenceAt(items: LocalEvidence[], index: number): LocalEvidence[] {
  return items.filter((_, i) => i !== index);
}

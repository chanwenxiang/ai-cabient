/**
 * 管理后台上传前端校验（与 FileAttachmentService / MediaAssetService 白名单对齐）。
 * 后端仍为最终门禁；此处减少无意义请求与差体验。
 */

export const IMAGE_MAX_BYTES = 5 * 1024 * 1024;
export const AD_ASSET_MAX_BYTES = 50 * 1024 * 1024;

const IMAGE_MIME = new Set(['image/jpeg', 'image/jpg', 'image/png', 'image/webp', 'image/gif']);
const IMAGE_EXT = new Set(['.jpg', '.jpeg', '.png', '.webp', '.gif']);
const VIDEO_MIME = new Set(['video/mp4', 'video/webm']);
const VIDEO_EXT = new Set(['.mp4', '.webm']);

export type UploadValidation = { ok: true } | { ok: false; message: string };

function fileExt(name: string): string {
  const lower = (name || '').toLowerCase();
  const i = lower.lastIndexOf('.');
  return i >= 0 ? lower.slice(i) : '';
}

function mbLabel(maxBytes: number): string {
  return `${Math.round(maxBytes / (1024 * 1024))}MB`;
}

/** 运营图片：SKU/头像/品牌/争议建议帧等，≤5MB，JPG/PNG/WEBP/GIF。 */
export function validateImageFile(
  file: File,
  maxBytes: number = IMAGE_MAX_BYTES
): UploadValidation {
  if (!file || file.size <= 0) return { ok: false, message: '文件为空' };
  if (file.size > maxBytes) {
    return { ok: false, message: `单张图片不能超过 ${mbLabel(maxBytes)}` };
  }
  const mime = (file.type || '').toLowerCase().trim();
  const ext = fileExt(file.name);
  if (mime && !IMAGE_MIME.has(mime)) {
    return { ok: false, message: '仅支持 JPG / PNG / WEBP / GIF' };
  }
  if (!mime && ext && !IMAGE_EXT.has(ext)) {
    return { ok: false, message: '仅支持 JPG / PNG / WEBP / GIF' };
  }
  if (!mime && !ext) {
    return { ok: false, message: '无法识别图片类型' };
  }
  return { ok: true };
}

/** 广告素材：图片或视频按 assetType 校验，≤50MB。 */
export function validateAdAssetFile(
  file: File,
  assetType: 'IMAGE' | 'VIDEO',
  maxBytes: number = AD_ASSET_MAX_BYTES
): UploadValidation {
  if (!file || file.size <= 0) return { ok: false, message: '文件为空' };
  if (file.size > maxBytes) {
    return { ok: false, message: `文件不能超过 ${mbLabel(maxBytes)}` };
  }
  const mime = (file.type || '').toLowerCase().trim();
  const ext = fileExt(file.name);
  if (assetType === 'IMAGE') {
    if ((mime && !IMAGE_MIME.has(mime)) || (!mime && ext && !IMAGE_EXT.has(ext))) {
      return { ok: false, message: '图片素材仅支持 JPG / PNG / WEBP / GIF' };
    }
    return { ok: true };
  }
  if ((mime && !VIDEO_MIME.has(mime)) || (!mime && ext && !VIDEO_EXT.has(ext))) {
    return { ok: false, message: '视频素材仅支持 MP4 / WEBM' };
  }
  return { ok: true };
}

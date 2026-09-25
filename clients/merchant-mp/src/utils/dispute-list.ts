/**
 * 争议列表可播放地址（M7）。
 * 后端故意对不存在对象返回空 preview；禁止回退 minio://（浏览器无法播）。
 */
const PLAYABLE_MEDIA_RE = /^(https?:|blob:|data:|file:)/i;

export function playablePlaybackUrl(videoPreviewUrl?: string | null): string {
  const url = String(videoPreviewUrl || '').trim();
  return PLAYABLE_MEDIA_RE.test(url) ? url : '';
}

/** 争议列表首屏/翻页大小；禁止 ≥100（对齐 orders=50、splits≤20 lessons）。 */
export const DISPUTES_PAGE_SIZE = 50;

/** 深链按 sessionId 扫描时最多翻页数（防一次拉全量）。 */
export const DISPUTES_FOCUS_SCAN_MAX_PAGES = 5;

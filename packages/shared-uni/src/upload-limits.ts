/** 小程序本地图上传大小门禁（与后端 FileAttachmentService 5MB 对齐）。 */

export const IMAGE_MAX_BYTES = 5 * 1024 * 1024;

export function getLocalFileSize(filePath: string): Promise<number | null> {
  return new Promise((resolve) => {
    if (typeof uni === 'undefined' || typeof uni.getFileInfo !== 'function') {
      resolve(null);
      return;
    }
    uni.getFileInfo({
      filePath,
      success: (res) => resolve(typeof res.size === 'number' ? res.size : null),
      fail: () => resolve(null)
    });
  });
}

/** 超过上限抛错；无法取 size 时放行交由服务端校验。 */
export async function assertLocalImageSize(
  filePath: string,
  maxBytes: number = IMAGE_MAX_BYTES
): Promise<void> {
  const size = await getLocalFileSize(filePath);
  if (size != null && size > maxBytes) {
    throw new Error(`单张图片不能超过 ${Math.round(maxBytes / (1024 * 1024))}MB`);
  }
}

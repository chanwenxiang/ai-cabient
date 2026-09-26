import { describe, expect, it } from 'vitest';
import { buildBearerDownloadHeader, classifyDownloadResult } from './consumer-download';

describe('consumer-download · C12c', () => {
  it('拼装 Bearer 下载头', () => {
    expect(buildBearerDownloadHeader()).toEqual({ 'X-Requested-With': 'XMLHttpRequest' });
    expect(buildBearerDownloadHeader('tok')).toEqual({
      'X-Requested-With': 'XMLHttpRequest',
      Authorization: 'Bearer tok'
    });
  });

  it('分类下载结果', () => {
    expect(classifyDownloadResult(200, true).ok).toBe(true);
    expect(classifyDownloadResult(401, false).unauthorized).toBe(true);
    expect(classifyDownloadResult(500, false).message).toContain('500');
  });
});

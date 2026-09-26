import { describe, expect, it, vi } from 'vitest';
import {
  evidenceLocalFallback,
  mapReplenishmentEvidenceItems,
  mergeEvidenceCountMap
} from './replenishment-evidence';

describe('replenishment-evidence · M4b', () => {
  it('无 fileId / 下载失败回退 url', () => {
    expect(evidenceLocalFallback({ url: 'https://a' })).toEqual({
      localPath: 'https://a',
      fileId: undefined
    });
    expect(evidenceLocalFallback({ fileId: 1, url: 'https://b' }, 'tmp://x')).toEqual({
      localPath: 'tmp://x',
      fileId: 1
    });
  });

  it('map：有 fileId 走 download；失败回退', async () => {
    const download = vi.fn(async (id: number) => {
      if (id === 2) throw new Error('fail');
      return `local-${id}`;
    });
    const mapped = await mapReplenishmentEvidenceItems(
      [{ fileId: 1, url: 'https://a' }, { fileId: 2, url: 'https://b' }, { url: 'https://c' }],
      download
    );
    expect(mapped).toEqual([
      { localPath: 'local-1', fileId: 1 },
      { localPath: 'https://b', fileId: 2 },
      { localPath: 'https://c', fileId: undefined }
    ]);
    expect(download).toHaveBeenCalledTimes(2);
  });

  it('合并证据张数 map', () => {
    expect(mergeEvidenceCountMap({ 1: 2 }, 9, 3)).toEqual({ 1: 2, 9: 3 });
  });
});

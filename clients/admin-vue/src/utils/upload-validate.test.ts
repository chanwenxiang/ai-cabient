import { describe, expect, it } from 'vitest';
import {
  AD_ASSET_MAX_BYTES,
  IMAGE_MAX_BYTES,
  validateAdAssetFile,
  validateImageFile
} from '@/utils/upload-validate';

function fakeFile(name: string, type: string, size: number): File {
  const blob = new Blob([new Uint8Array(Math.min(size, 16))], { type });
  const file = new File([blob], name, { type });
  Object.defineProperty(file, 'size', { value: size });
  return file;
}

describe('validateImageFile', () => {
  it('accepts jpeg under limit', () => {
    expect(validateImageFile(fakeFile('a.jpg', 'image/jpeg', 1024))).toEqual({ ok: true });
  });

  it('rejects oversized', () => {
    const r = validateImageFile(fakeFile('a.png', 'image/png', IMAGE_MAX_BYTES + 1));
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.message).toContain('5MB');
  });

  it('rejects non-image mime', () => {
    const r = validateImageFile(fakeFile('x.html', 'text/html', 100));
    expect(r.ok).toBe(false);
  });
});

describe('validateAdAssetFile', () => {
  it('accepts mp4 video', () => {
    expect(validateAdAssetFile(fakeFile('a.mp4', 'video/mp4', 1024), 'VIDEO')).toEqual({
      ok: true
    });
  });

  it('rejects image when assetType is VIDEO', () => {
    const r = validateAdAssetFile(fakeFile('a.jpg', 'image/jpeg', 1024), 'VIDEO');
    expect(r.ok).toBe(false);
  });

  it('rejects over 50MB', () => {
    const r = validateAdAssetFile(
      fakeFile('a.mp4', 'video/mp4', AD_ASSET_MAX_BYTES + 1),
      'VIDEO'
    );
    expect(r.ok).toBe(false);
  });
});

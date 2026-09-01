import { describe, expect, it } from 'vitest';
import { normalizeListPage } from '@/utils/normalize-list-page';
import { safeRedirectPath } from '@/utils/safe-redirect';

describe('normalizeListPage', () => {
  it('accepts bare arrays', () => {
    expect(normalizeListPage([{ id: 1 }])).toEqual({ items: [{ id: 1 }], total: 1 });
  });

  it('reads PageResult items/total', () => {
    expect(normalizeListPage({ items: ['a', 'b'], total: 10 })).toEqual({
      items: ['a', 'b'],
      total: 10
    });
  });

  it('falls back total to items.length', () => {
    expect(normalizeListPage({ items: [1, 2, 3] })).toEqual({ items: [1, 2, 3], total: 3 });
  });

  it('handles null/undefined', () => {
    expect(normalizeListPage(null)).toEqual({ items: [], total: 0 });
    expect(normalizeListPage(undefined)).toEqual({ items: [], total: 0 });
  });
});

describe('safeRedirectPath', () => {
  it('keeps same-origin paths', () => {
    expect(safeRedirectPath('/disputes')).toBe('/disputes');
  });

  it('rewrites legacy hash deep links', () => {
    expect(safeRedirectPath('/dashboard#/merchants')).toBe('/merchants');
  });

  it('rejects external and protocol-relative URLs', () => {
    expect(safeRedirectPath('https://evil.example/x')).toBe('/dashboard');
    expect(safeRedirectPath('//evil.example')).toBe('/dashboard');
    expect(safeRedirectPath(null)).toBe('/dashboard');
  });
});

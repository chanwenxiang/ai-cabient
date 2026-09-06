import { describe, expect, it } from 'vitest';
import {
  ADMIN_HASH_BOOTSTRAP,
  extractRouteFromIndexHtmlHash
} from '@/utils/admin-hash-history';

describe('extractRouteFromIndexHtmlHash', () => {
  it('extracts path from hash deep link', () => {
    expect(extractRouteFromIndexHtmlHash('#/warehouse')).toBe('/warehouse');
    expect(extractRouteFromIndexHtmlHash('#/merchants?x=1')).toBe('/merchants?x=1');
  });

  it('rejects non-hash routes', () => {
    expect(extractRouteFromIndexHtmlHash('')).toBeNull();
    expect(extractRouteFromIndexHtmlHash('#warehouse')).toBeNull();
  });
});

describe('ADMIN_HASH_BOOTSTRAP', () => {
  it('rewrites bare index.html without requiring hash', () => {
    expect(ADMIN_HASH_BOOTSTRAP).toContain("h.indexOf('#/')===0");
    expect(ADMIN_HASH_BOOTSTRAP).toContain("location.replace(b+(r?r.replace");
  });
});

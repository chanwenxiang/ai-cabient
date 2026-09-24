import { describe, expect, it } from 'vitest';
import {
  ADMIN_CATALOG_PAGE_SIZE,
  ADMIN_OPTIONS_PAGE_SIZE,
  adminCatalogQuery,
  adminOptionsQuery
} from './admin-catalog-query';

describe('admin-catalog-query', () => {
  it('目录默认 page=0&size=500', () => {
    expect(adminCatalogQuery()).toBe(`page=0&size=${ADMIN_CATALOG_PAGE_SIZE}`);
    expect(ADMIN_CATALOG_PAGE_SIZE).toBe(500);
  });

  it('选项默认 page=0&size=200', () => {
    expect(adminOptionsQuery()).toBe(`page=0&size=${ADMIN_OPTIONS_PAGE_SIZE}`);
    expect(ADMIN_OPTIONS_PAGE_SIZE).toBe(200);
  });

  it('附加过滤且跳过空值', () => {
    expect(adminCatalogQuery({ status: 'ACTIVE', q: '' })).toBe(
      'page=0&size=500&status=ACTIVE'
    );
    expect(adminOptionsQuery({ returnableOnly: true })).toBe(
      'page=0&size=200&returnableOnly=true'
    );
  });
});

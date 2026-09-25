import { describe, expect, it } from 'vitest';
import {
  exceptionPageCount,
  mergeExceptionRowsById,
  OPEN_EXCEPTIONS_DEFAULT_MAX_PAGES,
  OPEN_EXCEPTIONS_HOME_MAX_PAGES
} from './exception-pages';

describe('exception-pages · M4', () => {
  it('页数预算：ceil(total/size) 封顶 maxPages', () => {
    expect(exceptionPageCount({ total: 250, pageSize: 100, maxPages: 3 })).toBe(3);
    expect(exceptionPageCount({ total: 50, pageSize: 100, maxPages: 3 })).toBe(1);
    expect(exceptionPageCount({ total: 250, pageSize: 100, maxPages: 1 })).toBe(1);
    expect(exceptionPageCount({ total: 0, pageSize: 100, maxPages: 3 })).toBe(1);
  });

  it('首页 maxPages=1；待办默认 3', () => {
    expect(OPEN_EXCEPTIONS_HOME_MAX_PAGES).toBe(1);
    expect(OPEN_EXCEPTIONS_DEFAULT_MAX_PAGES).toBe(3);
  });

  it('按 exceptionId 去重合并', () => {
    const a = [{ exceptionId: 'E1' }, { exceptionId: 'E2' }];
    const b = [{ exceptionId: 'E2' }, { exceptionId: 'E3' }];
    expect(
      mergeExceptionRowsById(a, b)
        .map((r) => r.exceptionId)
        .sort()
    ).toEqual(['E1', 'E2', 'E3']);
  });
});

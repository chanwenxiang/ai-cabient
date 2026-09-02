import { describe, expect, it } from 'vitest';
import { comparePrimaryKey, sortByPrimaryKey } from '@/utils/sort-by-pk';
import { csvFileName, escapeCsvCell, parseCsv, csvRowsToObjects, toCsv } from '@/utils/csv';
import { numOrZero, rateText, textOrNone, yuanText } from '@/utils/display';
import { buildPermTree, flattenForParentSelect, permTypeLabel } from '@/utils/rbac-tree';

describe('sortByPrimaryKey', () => {
  it('sorts snowflake digit ids numerically', () => {
    const rows = [{ id: '100' }, { id: '20' }, { id: '3' }];
    expect(sortByPrimaryKey(rows, 'id').map((r) => r.id)).toEqual(['3', '20', '100']);
  });

  it('supports desc and nulls last on asc', () => {
    const rows = [{ id: '2' }, { id: null }, { id: '1' }];
    expect(sortByPrimaryKey(rows, 'id', 'asc').map((r) => r.id)).toEqual(['1', '2', null]);
    expect(sortByPrimaryKey(rows, 'id', 'desc').map((r) => r.id)).toEqual([null, '2', '1']);
  });

  it('comparePrimaryKey treats equal bigints as 0', () => {
    expect(comparePrimaryKey('9007199254740993', '9007199254740993')).toBe(0);
    expect(comparePrimaryKey('9007199254740993', '9007199254740994')).toBeLessThan(0);
  });
});

describe('csv helpers', () => {
  it('escapes quotes and commas', () => {
    expect(escapeCsvCell('a,b')).toBe('"a,b"');
    expect(escapeCsvCell('say "hi"')).toBe('"say ""hi"""');
  });

  it('builds BOM csv and parses round-trip', () => {
    const csv = toCsv(
      ['name', 'qty'],
      [
        ['可乐', 2],
        ['薯片,原味', 1]
      ]
    );
    expect(csv.startsWith('\uFEFF')).toBe(true);
    const rows = parseCsv(csv);
    expect(rows[0]).toEqual(['name', 'qty']);
    expect(csvRowsToObjects(rows)).toEqual([
      { name: '可乐', qty: '2' },
      { name: '薯片,原味', qty: '1' }
    ]);
  });

  it('csvFileName includes prefix and stamp', () => {
    const d = new Date(2026, 8, 1, 14, 30, 5);
    expect(csvFileName('orders', d)).toBe('orders_20260901_143005.csv');
  });
});

describe('display helpers', () => {
  it('textOrNone and numOrZero', () => {
    expect(textOrNone('')).toBe('暂无');
    expect(textOrNone('  ok  ')).toBe('ok');
    expect(numOrZero('12')).toBe(12);
    expect(numOrZero('x')).toBe(0);
  });

  it('rateText and yuanText', () => {
    expect(rateText(0.856)).toBe('85.6%');
    expect(rateText(null)).toBe('未统计');
    expect(yuanText(350)).toBe('¥3.50');
    expect(yuanText('')).toBe('未统计');
  });
});

describe('rbac-tree', () => {
  it('builds sorted tree with labels', () => {
    const tree = buildPermTree([
      {
        permissionId: 2,
        parentId: 1,
        permCode: 'c',
        permName: '菜单',
        permType: 'C',
        sortOrder: 2
      },
      {
        permissionId: 1,
        parentId: 0,
        permCode: 'm',
        permName: '目录',
        permType: 'M',
        sortOrder: 1
      },
      { permissionId: 3, parentId: 1, permCode: 'f', permName: '按钮', permType: 'F', sortOrder: 1 }
    ]);
    expect(tree).toHaveLength(1);
    expect(tree[0].label).toBe('[目录] 目录');
    expect(tree[0].children?.map((c) => c.permissionId)).toEqual([3, 2]);
  });

  it('flattenForParentSelect excludes self subtree', () => {
    const tree = buildPermTree([
      { permissionId: 1, parentId: 0, permCode: 'm', permName: '根', permType: 'M' },
      { permissionId: 2, parentId: 1, permCode: 'c', permName: '子', permType: 'C' }
    ]);
    expect(flattenForParentSelect(tree, 1).map((n) => n.permissionId)).toEqual([]);
    expect(flattenForParentSelect(tree, 2).map((n) => n.permissionId)).toEqual([1]);
    expect(permTypeLabel('F')).toBe('按钮');
  });
});

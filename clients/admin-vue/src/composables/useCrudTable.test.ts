import { describe, expect, it, vi, beforeEach } from 'vitest';

vi.mock('element-plus', () => ({
  ElMessage: { error: vi.fn(), success: vi.fn(), warning: vi.fn() }
}));

import { ElMessage } from 'element-plus';
import { useCrudTable, type CrudPageParams } from './useCrudTable';

type Row = { id: number; name: string };

function makeRows(ids: number[]): Row[] {
  return ids.map((id) => ({ id, name: `row-${id}` }));
}

describe('useCrudTable', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('加载一页：fetchPage 收到 0 起页码，items/total 落位，hydrated 置位', async () => {
    const fetchPage = vi.fn().mockResolvedValue({ items: makeRows([2, 1]), total: 42 });
    const crud = useCrudTable<Row>({ rowKey: (r) => r.id, fetchPage, autoLoad: false });

    await crud.load();

    expect(fetchPage).toHaveBeenCalledWith({
      page: 0,
      size: 20,
      sortProp: undefined,
      sortDir: 'asc'
    });
    expect(crud.items).toHaveLength(2);
    expect(crud.total).toBe(42);
    expect(crud.hydrated).toBe(true);
    expect(crud.loading).toBe(false);
  });

  it('search() 重置回第一页（wire 页码为 0）', async () => {
    const fetchPage = vi.fn().mockResolvedValue([]);
    const crud = useCrudTable<Row>({ rowKey: (r) => r.id, fetchPage, autoLoad: false });

    crud.page = 3;
    await crud.search();

    const params = fetchPage.mock.calls[fetchPage.mock.calls.length - 1]![0] as CrudPageParams;
    expect(crud.page).toBe(1);
    expect(params.page).toBe(0);
  });

  it('local 排序：displayItems 按主键升/降序重排，翻页数据不变', async () => {
    const fetchPage = vi.fn().mockResolvedValue(makeRows([3, 1, 2]));
    const crud = useCrudTable<Row>({
      rowKey: (r) => r.id,
      fetchPage,
      sort: { prop: 'id', mode: 'local' },
      autoLoad: false
    });

    await crud.load();
    expect(crud.displayItems.map((r) => r.id)).toEqual([1, 2, 3]);

    crud.toggleSortDir();
    expect(crud.sortDir).toBe('desc');
    expect(crud.displayItems.map((r) => r.id)).toEqual([3, 2, 1]);
    expect(fetchPage).toHaveBeenCalledTimes(1); // local 模式不重查
  });

  it('sort.pinned：置顶行稳定排在最前，升降序只影响其余行相对顺序', async () => {
    type StuckRow = Row & { stuck?: boolean };
    const rows: StuckRow[] = [
      { id: 3, name: 'r3' },
      { id: 1, name: 'r1', stuck: true },
      { id: 5, name: 'r5', stuck: true },
      { id: 2, name: 'r2' }
    ];
    const fetchPage = vi.fn().mockResolvedValue(rows);
    const crud = useCrudTable<StuckRow>({
      rowKey: (r) => r.id,
      fetchPage,
      sort: { prop: 'id', mode: 'local', pinned: (r) => Boolean(r.stuck) },
      autoLoad: false
    });

    await crud.load();
    // 升序：置顶组内升序 [1,5]，其余组升序 [2,3]
    expect(crud.displayItems.map((r) => r.id)).toEqual([1, 5, 2, 3]);

    crud.toggleSortDir();
    // 降序：置顶组内降序 [5,1]，其余组降序 [3,2]
    expect(crud.displayItems.map((r) => r.id)).toEqual([5, 1, 3, 2]);
  });

  it('sort.pinned：全部命中（或全部未命中）时不改变排序结果', async () => {
    const fetchPage = vi.fn().mockResolvedValue(makeRows([3, 1, 2]));
    const crud = useCrudTable<Row>({
      rowKey: (r) => r.id,
      fetchPage,
      sort: { prop: 'id', mode: 'local', pinned: () => true },
      autoLoad: false
    });

    await crud.load();
    expect(crud.displayItems.map((r) => r.id)).toEqual([1, 2, 3]);
  });

  it('server 排序：翻转方向后带 sortDir 重查', async () => {
    const fetchPage = vi.fn().mockResolvedValue({ items: makeRows([1]), total: 1 });
    const crud = useCrudTable<Row>({
      rowKey: (r) => r.id,
      fetchPage,
      sort: { prop: 'id', mode: 'server' },
      autoLoad: false
    });

    await crud.load();
    crud.toggleSortDir();

    expect(fetchPage).toHaveBeenCalledTimes(2);
    const params = fetchPage.mock.calls[fetchPage.mock.calls.length - 1]![0] as CrudPageParams;
    expect(params.sortDir).toBe('desc');
    expect(params.sortProp).toBe('id');
  });

  it('多选：onSelectionChange 记录 rowKey，pickSelected 选中优先，clearSelection 清空', async () => {
    const rows = makeRows([1, 2, 3]);
    const fetchPage = vi.fn().mockResolvedValue(rows);
    const crud = useCrudTable<Row>({ rowKey: (r) => r.id, fetchPage, autoLoad: false });

    await crud.load();
    crud.onSelectionChange([rows[0], rows[2]]);

    expect(crud.selectedKeys).toEqual([1, 3]);
    expect(crud.hasSelection).toBe(true);
    expect(crud.exportButtonLabel).toBe('导出选中 (2)');
    expect(crud.pickSelected(rows).map((r) => r.id)).toEqual([1, 3]);

    crud.clearSelection();
    expect(crud.selectedKeys).toEqual([]);
    expect(crud.pickSelected(rows)).toHaveLength(3);
  });

  it('加载失败：提示 errorMessage，不置 hydrated 之外的状态残留', async () => {
    const fetchPage = vi.fn().mockRejectedValue(new Error('网络炸了'));
    const crud = useCrudTable<Row>({
      rowKey: (r) => r.id,
      fetchPage,
      autoLoad: false,
      errorMessage: '加载失败'
    });

    await crud.load();

    expect(ElMessage.error).toHaveBeenCalledWith('网络炸了');
    expect(crud.loading).toBe(false);
    expect(crud.hydrated).toBe(true);
  });

  it('onSizeChange：钳制 size 并重置页码', async () => {
    const fetchPage = vi.fn().mockResolvedValue([]);
    const crud = useCrudTable<Row>({ rowKey: (r) => r.id, fetchPage, autoLoad: false });

    crud.size = 999 as unknown as 20;
    crud.page = 5;
    await crud.onSizeChange();

    const params = fetchPage.mock.calls[fetchPage.mock.calls.length - 1]![0] as CrudPageParams;
    expect(crud.page).toBe(1);
    expect(params.size).toBe(50); // ADMIN_LIST_MAX_PAGE_SIZE
  });
});

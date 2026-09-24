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

  // ---- 自动刷新（CrudTable 的 auto-refresh）走的静默通道 ----
  // 自动刷新是**后台行为**：不能闪 loading、不能清掉运营正在勾的行、失败不能弹提示，
  // 否则「每 30 秒把正在审单的人打断一次」，开关本身就是个坑。

  it('silentRefresh：不置 loading、不清选择、保留当前页', async () => {
    const rows = makeRows([1, 2]);
    const fetchPage = vi.fn().mockResolvedValue(rows);
    const crud = useCrudTable<Row>({ rowKey: (r) => r.id, fetchPage, autoLoad: false });

    await crud.load();
    crud.onSelectionChange([rows[0]]);
    crud.page = 3;
    expect(crud.selectedKeys).toEqual([1]);

    await crud.silentRefresh();

    expect(crud.loading).toBe(false); // 全程没有 loading 态（不闪遮罩）
    expect(crud.selectedKeys).toEqual([1]); // 勾选保留
    expect(crud.page).toBe(3); // 当前页保留
    const params = fetchPage.mock.calls[fetchPage.mock.calls.length - 1]![0] as CrudPageParams;
    expect(params.page).toBe(2); // wire 页码 = 前端页 - 1
  });

  it('silentRefresh 失败：不弹提示、不残留 loading', async () => {
    const fetchPage = vi.fn().mockRejectedValue(new Error('后台超时'));
    const crud = useCrudTable<Row>({ rowKey: (r) => r.id, fetchPage, autoLoad: false });

    await crud.silentRefresh();

    expect(ElMessage.error).not.toHaveBeenCalled();
    expect(crud.loading).toBe(false);
  });

  it('refresh（手动按钮）：仍会闪 loading 并清选择 —— 与静默通道刻意区分', async () => {
    const rows = makeRows([1, 2]);
    const fetchPage = vi.fn().mockResolvedValue(rows);
    const crud = useCrudTable<Row>({ rowKey: (r) => r.id, fetchPage, autoLoad: false });

    await crud.load();
    crud.onSelectionChange([rows[0]]);
    await crud.refresh();

    expect(crud.selectedKeys).toEqual([]);
  });

  // ---- 勾选复原快照 ----
  // ⚠️ 本层测不出真实勾选是否被冲掉：`<el-table>` 在 `:data` 换新数组实例时会**自己**
  //    store.clearSelection()（与我们的 selectedKeys 无关），这一层根本看不到。
  //    所以这里只固化「快照协议」；端到端「tick 后勾选还在」由
  //    `.tmp/mp-auto/probe-admin-auto-refresh.mjs` 在真实浏览器里判（B7/C2）。
  it('silentRefresh：留下勾选快照，供组件层复原（一次性消费）', async () => {
    const rows = makeRows([1, 2]);
    const fetchPage = vi.fn().mockResolvedValue(rows);
    const crud = useCrudTable<Row>({ rowKey: (r) => r.id, fetchPage, autoLoad: false });

    await crud.load();
    crud.onSelectionChange([rows[0], rows[1]]);
    await crud.silentRefresh();

    expect(crud.takeSelectionRestore()).toEqual([1, 2]);
    expect(crud.takeSelectionRestore()).toBeNull(); // 消费一次即失效，避免污染下一次数据变更
  });

  it('普通 load：不留快照（翻页/筛选仍按既有行为清空勾选）', async () => {
    const rows = makeRows([1, 2]);
    const fetchPage = vi.fn().mockResolvedValue(rows);
    const crud = useCrudTable<Row>({ rowKey: (r) => r.id, fetchPage, autoLoad: false });

    await crud.load();
    crud.onSelectionChange([rows[0]]);
    await crud.load();

    expect(crud.takeSelectionRestore()).toBeNull();
  });

  it('静默刷新后紧跟一次普通 load：陈旧快照被丢弃（不到下一次翻页才被消费）', async () => {
    const rows = makeRows([1, 2]);
    const fetchPage = vi.fn().mockResolvedValue(rows);
    const crud = useCrudTable<Row>({ rowKey: (r) => r.id, fetchPage, autoLoad: false });

    await crud.load();
    crud.onSelectionChange([rows[0]]);
    await crud.silentRefresh(); // 留下 [1] 快照（模拟组件层还没消费：如请求后被竞态提前 return）
    await crud.load(); // 非静默路径应顺手丢弃

    expect(crud.takeSelectionRestore()).toBeNull();
  });
});

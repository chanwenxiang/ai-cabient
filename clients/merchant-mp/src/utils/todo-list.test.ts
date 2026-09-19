import { beforeEach, describe, expect, it, vi } from 'vitest';
import { mergeTodoItems } from './todo-list';
import type { TodoSourceAction, TodoSourceException, TodoSourceExpiry } from './todo-list';

/**
 * 只替换掉会牵出 uni 网络栈的两个展示函数，合并/去重逻辑全部走真实实现；
 * 用 mock 顺带断言「传进去的是哪个字段」（防参数传错）。
 */
const alertTypeLabelMock = vi.hoisted(() =>
  vi.fn((type: string) => `L:${String(type || '').toUpperCase()}`)
);
const merchantAlertTitleMock = vi.hoisted(() =>
  vi.fn((_type: string, title: string) => `T:${String(title || '')}`)
);

vi.mock('@/utils/merchant-api', () => ({
  alertTypeLabel: alertTypeLabelMock,
  merchantAlertTitle: merchantAlertTitleMock
}));

function exception(over: Partial<TodoSourceException> = {}): TodoSourceException {
  return {
    exceptionId: 'EX-1',
    exceptionType: 'DEVICE_FAULT',
    title: '柜机故障',
    detail: '门锁异常',
    deviceId: 'D1',
    ...over
  };
}

function action(over: Partial<TodoSourceAction> = {}): TodoSourceAction {
  return { type: 'DEVICE_FAULT', title: '工单标题', deviceId: 'D1', ...over };
}

function expiry(over: Partial<TodoSourceExpiry> = {}): TodoSourceExpiry {
  return {
    deviceId: 'D1',
    skuId: 'SKU-1',
    batchNo: 'B1',
    quantity: 2,
    reason: '临期',
    status: 'OPEN',
    ...over
  };
}

beforeEach(() => {
  alertTypeLabelMock.mockClear();
  merchantAlertTitleMock.mockClear();
});

describe('mergeTodoItems · 基础形态', () => {
  it('三源皆空返回空数组', () => {
    expect(mergeTodoItems({})).toEqual([]);
    expect(mergeTodoItems({ exceptions: [], actionItems: [], expiryRows: [] })).toEqual([]);
  });

  it('异常项按 exceptionType / title / detail / deviceId 映射，并把类型原样交给标签函数', () => {
    const items = mergeTodoItems({ exceptions: [exception()] });
    expect(items).toHaveLength(1);
    expect(items[0].type).toBe('DEVICE_FAULT');
    expect(items[0].typeLabel).toBe('L:DEVICE_FAULT');
    expect(items[0].title).toBe('T:柜机故障');
    expect(items[0].deviceId).toBe('D1');
    expect(items[0].exceptionId).toBe('EX-1');
    expect(alertTypeLabelMock).toHaveBeenCalledWith('DEVICE_FAULT');
    expect(merchantAlertTitleMock).toHaveBeenCalledWith('DEVICE_FAULT', '柜机故障');
  });

  it('异常类型缺失时用空串查标签，而不是把 title 当类型', () => {
    mergeTodoItems({ exceptions: [exception({ exceptionType: undefined })] });
    expect(alertTypeLabelMock).toHaveBeenCalledWith('');
  });

  it('工单项带上 ticketId / dueAt / severity', () => {
    const items = mergeTodoItems({
      actionItems: [
        action({ type: 'SALES_LOCKED', ticketId: 'TK-1', dueAt: '2026-09-20', severity: 'HIGH' })
      ]
    });
    expect(items).toHaveLength(1);
    expect(items[0].ticketId).toBe('TK-1');
    expect(items[0].dueAt).toBe('2026-09-20');
    expect(items[0].severity).toBe('HIGH');
  });
});

describe('mergeTodoItems · 临期行与 workbench EXPIRY 汇总的互斥', () => {
  it('没有结构化临期行时，保留 workbench 的 EXPIRY 汇总', () => {
    const items = mergeTodoItems({ actionItems: [action({ type: 'EXPIRY', title: '临期汇总' })] });
    expect(items).toHaveLength(1);
    expect(items[0].type).toBe('EXPIRY');
  });

  it('有结构化临期行时，丢弃 workbench 的 EXPIRY 汇总（避免同一批货重复出现两遍）', () => {
    const items = mergeTodoItems({
      actionItems: [action({ type: 'EXPIRY', title: '临期汇总' })],
      expiryRows: [expiry()]
    });
    expect(items).toHaveLength(1);
    expect(items[0].typeLabel).toBe('L:EXPIRY');
    expect(items[0].title).toContain('SKU-1');
    expect(items[0].title).not.toContain('汇总');
  });

  it('结构化行按 OPEN 过滤；全被过滤时 EXPIRY 汇总重新出现（两处判据必须联动）', () => {
    const items = mergeTodoItems({
      actionItems: [action({ type: 'EXPIRY', title: '临期汇总' })],
      expiryRows: [expiry({ status: 'DONE' })]
    });
    expect(items).toHaveLength(1);
    // 留下的必须是 workbench 那条（标题走 merchantAlertTitle），而不是结构化模板
    expect(items[0].title).toBe('T:临期汇总');
  });

  it('临期行标题带 SKU 与数量，明细按 柜机 · 批次 · 原因 拼接（缺项自动省略）', () => {
    const items = mergeTodoItems({
      expiryRows: [expiry({ skuId: 'SKU-9', quantity: 3, reason: '', batchNo: 'B7' })]
    });
    expect(items[0].title).toContain('SKU-9');
    expect(items[0].title).toContain('3');
    expect(items[0].detail).toBe('D1 · B7');
  });
});

describe('mergeTodoItems · 同源屏蔽', () => {
  it('异常已覆盖「类型|柜机」时，屏蔽同组合的工单', () => {
    const items = mergeTodoItems({
      exceptions: [exception({ exceptionType: 'DEVICE_FAULT', deviceId: 'D1' })],
      actionItems: [action({ type: 'DEVICE_FAULT', deviceId: 'D1', title: '重复工单' })]
    });
    expect(items).toHaveLength(1);
    expect(items[0].exceptionId).toBe('EX-1');
  });

  it('只屏蔽同组合：其它柜机、其它类型的工单必须保留', () => {
    const items = mergeTodoItems({
      exceptions: [exception({ exceptionType: 'DEVICE_FAULT', deviceId: 'D1' })],
      actionItems: [
        action({ type: 'DEVICE_FAULT', deviceId: 'D2', title: '别的柜机' }),
        action({ type: 'SALES_LOCKED', deviceId: 'D1', title: '销售锁定' })
      ]
    });
    expect(items).toHaveLength(3);
    expect(items.map((i) => i.title)).toContain('T:别的柜机');
    expect(items.map((i) => i.title)).toContain('T:销售锁定');
  });

  it('柜机号大小写/空白差异视为同一台，屏蔽仍然生效', () => {
    const items = mergeTodoItems({
      exceptions: [exception({ exceptionType: 'DEVICE_FAULT', deviceId: ' d1 ' })],
      actionItems: [action({ type: 'DEVICE_FAULT', deviceId: 'D1', title: '重复工单' })]
    });
    expect(items).toHaveLength(1);
    expect(items[0].exceptionId).toBe('EX-1');
  });

  it('故障/离线/锁定设备上的 DEVICE_OFFLINE 工单被屏蔽（故障优先于离线重复提示）', () => {
    const items = mergeTodoItems({
      exceptions: [exception({ exceptionType: 'DEVICE_FAULT', deviceId: 'D1' })],
      actionItems: [action({ type: 'DEVICE_OFFLINE', deviceId: 'D1', title: '离线提示' })]
    });
    expect(items).toHaveLength(1);
    expect(items[0].type).toBe('DEVICE_FAULT');
  });

  it('设备没有问题异常时，DEVICE_OFFLINE 工单正常保留', () => {
    const items = mergeTodoItems({
      actionItems: [action({ type: 'DEVICE_OFFLINE', deviceId: 'D1', title: '离线提示' })]
    });
    expect(items).toHaveLength(1);
    expect(items[0].type).toBe('DEVICE_OFFLINE');
  });
});

describe('mergeTodoItems · 去重', () => {
  it('同类型+同柜机+同 exceptionId 的重复异常只留一条', () => {
    const items = mergeTodoItems({
      exceptions: [exception(), exception({ title: '另一条标题但同 ID' })]
    });
    expect(items).toHaveLength(1);
  });

  it('同类型+同柜机+同 ticketId 的重复工单只留一条', () => {
    const items = mergeTodoItems({
      actionItems: [action({ ticketId: 'TK-1' }), action({ ticketId: 'TK-1', title: '副本' })]
    });
    expect(items).toHaveLength(1);
  });

  it('既无 ticketId 也无 exceptionId 时按标题区分，标题不同则都保留', () => {
    const items = mergeTodoItems({
      actionItems: [action({ title: '甲' }), action({ title: '乙' })]
    });
    expect(items).toHaveLength(2);
  });
});

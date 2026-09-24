import { formatExceptionDetail } from '@aicabinet/shared-dict';
import { alertTypeLabel, merchantAlertTitle } from '@/utils/merchant-api';
import type { OpenApiOpsExceptionDto, OpenApiPullOffTaskDto } from '@aicabinet/shared-types';

export type TodoSourceException = Pick<
  OpenApiOpsExceptionDto,
  'exceptionId' | 'exceptionType' | 'title' | 'detail' | 'deviceId'
>;

export type TodoSourceAction = {
  type: string;
  title: string;
  detail?: string;
  deviceId?: string;
  ticketId?: string;
  dueAt?: string;
  severity?: string;
};

export type TodoSourceExpiry = Pick<
  OpenApiPullOffTaskDto,
  'deviceId' | 'skuId' | 'batchNo' | 'quantity' | 'reason' | 'status'
>;

export type TodoListItem = {
  type: string;
  typeLabel: string;
  title: string;
  detail: string;
  deviceId?: string;
  ticketId?: string;
  exceptionId?: string;
  dueAt?: string;
  severity?: string;
};

function typeKey(type?: string) {
  return String(type || '').toUpperCase();
}

function deviceKey(deviceId?: string) {
  return String(deviceId || '')
    .trim()
    .toUpperCase();
}

/** 与待办页同一套合并/去重，供角标与列表共用 */
export function mergeTodoItems(input: {
  exceptions?: TodoSourceException[];
  actionItems?: TodoSourceAction[];
  expiryRows?: TodoSourceExpiry[];
}): TodoListItem[] {
  const exceptionItems = (input.exceptions || []).map((a) => ({
    type: a.exceptionType || '',
    typeLabel: alertTypeLabel(a.exceptionType || ''),
    title: merchantAlertTitle(a.exceptionType || '', a.title || ''),
    detail: formatExceptionDetail(a.detail || ''),
    deviceId: a.deviceId,
    exceptionId: a.exceptionId
  }));
  const workbenchItems = (input.actionItems || []).map((a) => ({
    type: a.type,
    typeLabel: alertTypeLabel(a.type),
    title: merchantAlertTitle(a.type, a.title),
    detail: merchantAlertTitle(a.type, a.detail || ''),
    deviceId: a.deviceId,
    ticketId: a.ticketId,
    dueAt: a.dueAt,
    severity: a.severity
  }));
  const expiryItems = (input.expiryRows || [])
    .filter((e) => String(e.status || 'OPEN').toUpperCase() === 'OPEN')
    .map((e) => ({
      type: 'EXPIRY',
      typeLabel: alertTypeLabel('EXPIRY'),
      title: `${e.skuId || '商品'} · 临期/过期 ${e.quantity || 0} 件`,
      detail: [e.deviceId, e.batchNo, e.reason].filter(Boolean).join(' · '),
      deviceId: e.deviceId
    }));

  // 有结构化临期行时，丢掉 workbench 的汇总 EXPIRY（避免重复）
  const hasExpiryApi = expiryItems.length > 0;
  // 仅按「类型+柜机」屏蔽同源 workbench，避免一条异常吞掉其它柜同类型待办
  const exceptionTypeDevice = new Set(
    exceptionItems.map((a) => `${typeKey(a.type)}|${deviceKey(a.deviceId)}`)
  );
  const faultOrOfflineDevices = new Set(
    exceptionItems
      .filter((a) => ['DEVICE_FAULT', 'DEVICE_OFFLINE', 'SALES_LOCKED'].includes(typeKey(a.type)))
      .map((a) => deviceKey(a.deviceId))
      .filter(Boolean)
  );

  const workbenchFiltered = workbenchItems.filter((a) => {
    const t = typeKey(a.type);
    const d = deviceKey(a.deviceId);
    if (hasExpiryApi && t === 'EXPIRY') return false;
    if (exceptionTypeDevice.has(`${t}|${d}`)) return false;
    if (t === 'DEVICE_OFFLINE' && d && faultOrOfflineDevices.has(d)) return false;
    return true;
  });

  // 显式标注为 TodoListItem，避免三源对象在 filter 回调里联合类型缺字段（TS2339）
  const merged: TodoListItem[] = [...exceptionItems, ...workbenchFiltered, ...expiryItems];
  const seen = new Set<string>();
  return merged.filter((a) => {
    const key = `${typeKey(a.type)}|${deviceKey(a.deviceId)}|${a.ticketId || a.exceptionId || a.title}`;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

export type TodoSummaryItem = {
  type: string;
  typeLabel: string;
  title: string;
  detail: string;
  deviceId?: string;
  /** 该类型下待办条数（聚合前） */
  count: number;
  /** 涉及的柜机台数 */
  deviceCount: number;
};

/** 「设备不可用」类优先暴露：这类事项直接损失营收，比积压工单更该被先看到 */
const TODO_TYPE_RANK: Record<string, number> = {
  DEVICE_FAULT: 0,
  DEVICE_OFFLINE: 0,
  SALES_LOCKED: 0
};

function typeRank(type?: string) {
  return TODO_TYPE_RANK[typeKey(type)] ?? 1;
}

/**
 * 首页「优先待办」摘要：按事项**类型**聚合。
 *
 * 动机（实测缺陷）：`workbench` 曾一次返回 9 张同柜机的 `DISPUTE` 待审核单，
 * 直接 `slice(0, 3)` 会让首屏三行**文字完全相同**，而「柜机离线」「库存偏低」
 * 被挤到看不见 —— 看起来像「后端重复数据」，其实是**列表没聚合**。
 *
 * 契约：
 * - `count === 1` 时**原样透传** detail/deviceId（单条场景行为零变化）
 * - 多条的 detail 只报数量，不再泄漏某一条的明细（避免误导）
 * - 排序：设备不可用类优先，其后按条数降序
 */
export function summarizeTodoItems(items: TodoListItem[], limit = 3): TodoSummaryItem[] {
  const groups = new Map<string, TodoSummaryItem & { devices: Set<string> }>();
  for (const it of items || []) {
    const k = typeKey(it.type);
    let g = groups.get(k);
    if (!g) {
      g = {
        type: it.type,
        typeLabel: it.typeLabel,
        title: it.title,
        detail: it.detail,
        deviceId: it.deviceId,
        count: 0,
        deviceCount: 0,
        devices: new Set<string>()
      };
      groups.set(k, g);
    }
    g.count += 1;
    const d = deviceKey(it.deviceId);
    if (d) g.devices.add(d);
  }

  const list = [...groups.values()].map((g) => {
    const deviceCount = g.devices.size;
    let { detail, deviceId } = g;
    if (g.count > 1) {
      // 多台柜机时 deviceId 行会误导（只指其中一台），改为在 detail 里报台数
      if (deviceCount > 1) {
        detail = `共 ${g.count} 件 · ${deviceCount} 台柜机`;
        deviceId = undefined;
      } else {
        detail = `共 ${g.count} 件待处理`;
      }
    }
    return {
      type: g.type,
      typeLabel: g.typeLabel,
      title: g.title,
      detail,
      deviceId,
      count: g.count,
      deviceCount
    } as TodoSummaryItem;
  });

  list.sort((a, b) => {
    const r = typeRank(a.type) - typeRank(b.type);
    if (r !== 0) return r;
    if (b.count !== a.count) return b.count - a.count;
    return a.title.localeCompare(b.title);
  });

  return list.slice(0, Math.max(0, limit));
}

import { formatExceptionDetail } from '@aicabinet/shared-dict';
import { alertTypeLabel, merchantAlertTitle } from '@/utils/merchant-api';

export type TodoSourceException = {
  exceptionId: string;
  exceptionType: string;
  title: string;
  detail?: string;
  deviceId?: string;
};

export type TodoSourceAction = {
  type: string;
  title: string;
  detail?: string;
  deviceId?: string;
  ticketId?: string;
  dueAt?: string;
  severity?: string;
};

export type TodoSourceExpiry = {
  deviceId?: string;
  skuId?: string;
  batchNo?: string;
  quantity?: number;
  reason?: string;
  status?: string;
};

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
    type: a.exceptionType,
    typeLabel: alertTypeLabel(a.exceptionType),
    title: merchantAlertTitle(a.exceptionType, a.title),
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
      .filter((a) => ['DEVICE_FAULT', 'DEVICE_OFFLINE'].includes(typeKey(a.type)))
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

import { dictLabel } from '@aicabinet/shared-dict';

type DateInput = string | number | Date | null | undefined;

function parseDate(value: DateInput): Date | null {
  if (value == null || value === '') return null;
  const date = value instanceof Date ? value : new Date(value);
  return Number.isNaN(date.getTime()) ? null : date;
}

function dateParts(date: Date, options: Intl.DateTimeFormatOptions) {
  return new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false, ...options }).formatToParts(date);
}

function part(parts: Intl.DateTimeFormatPart[], type: Intl.DateTimeFormatPartTypes) {
  return parts.find((item) => item.type === type)?.value ?? '';
}

/** 完整时间：YYYY-MM-DD HH:mm:ss（东八区） */
export function formatDateTime(value?: DateInput, fallback = '-'): string {
  const date = parseDate(value ?? null);
  if (!date) return value != null && value !== '' ? String(value) : fallback;
  const parts = dateParts(date, {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  });
  return `${part(parts, 'year')}-${part(parts, 'month')}-${part(parts, 'day')} ${part(parts, 'hour')}:${part(parts, 'minute')}:${part(parts, 'second')}`;
}

/** 列表常用：YYYY-MM-DD HH:mm（东八区） */
export function formatDateTimeMinute(value?: DateInput, fallback = '-'): string {
  const date = parseDate(value ?? null);
  if (!date) return value != null && value !== '' ? String(value) : fallback;
  const parts = dateParts(date, {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });
  return `${part(parts, 'year')}-${part(parts, 'month')}-${part(parts, 'day')} ${part(parts, 'hour')}:${part(parts, 'minute')}`;
}

/** 紧凑时间：MM/DD HH:mm（东八区），适合移动端列表 */
export function formatDateTimeShort(value?: DateInput, fallback = '-'): string {
  const date = parseDate(value ?? null);
  if (!date) return value != null && value !== '' ? String(value) : fallback;
  const parts = dateParts(date, { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });
  return `${part(parts, 'month')}/${part(parts, 'day')} ${part(parts, 'hour')}:${part(parts, 'minute')}`;
}

export function fmtMoney(cents?: number) {
  if (cents == null) return '-';
  return '¥' + (cents / 100).toFixed(2);
}

export function formatError(err: unknown): string {
  if (!err) return '未知错误';
  if (typeof err === 'string') return err;
  const e = err as { status?: number; message?: string; errMsg?: string };
  if (e.status === 429) return '开门过于频繁，请稍后再试';
  if (e.message) {
    const msg = String(e.message);
    if (/too many door open/i.test(msg)) return '开门过于频繁，请稍后再试';
    if (/余额|balance/i.test(msg)) return '余额不足，请先充值';
    if (/device not found/i.test(msg)) return '设备不存在，请检查设备编号';
    if (/[\u4e00-\u9fff]/.test(msg)) return msg;
    return '操作失败，请稍后重试';
  }
  if (e.errMsg) return String(e.errMsg);
  return '请求失败';
}

export function orderStatusLabel(status?: string) {
  return dictLabel('order_status', status);
}

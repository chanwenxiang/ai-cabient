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

export type OpenErrorKind =
  | 'balance'
  | 'device_not_found'
  | 'device_paused'
  | 'device_busy'
  | 'rate_limit'
  | 'other';

function errorText(err: unknown): string {
  if (!err) return '';
  if (typeof err === 'string') return err;
  const e = err as { message?: string; errMsg?: string };
  return String(e.message || e.errMsg || '');
}

export function classifyOpenError(err: unknown): OpenErrorKind {
  const e = err as { status?: number; message?: string; errMsg?: string } | null;
  const msg = errorText(err);
  if (e?.status === 429 || /too many door open|过于频繁/i.test(msg)) return 'rate_limit';
  if (e?.status === 404 || /设备不存在|柜机不存在|device not found|编号无效|检查设备编号|检查柜机编号/i.test(msg)) {
    return 'device_not_found';
  }
  if (/余额不足|请先充值|insufficient balance|BALANCE/i.test(msg)) return 'balance';
  if (/暂停营业|已锁机|sales.?lock|LOCKED/i.test(msg)) return 'device_paused';
  if (/补货|使用中|占用|SESSION|REPLENISHMENT|正在购物/i.test(msg)) return 'device_busy';
  return 'other';
}

export function formatError(err: unknown): string {
  if (!err) return '未知错误';
  const kind = classifyOpenError(err);
  if (kind === 'rate_limit') return '开门过于频繁，请稍后再试';
  if (kind === 'balance') return '余额不足，请先充值后再开门（建议 ≥ ¥5，或开通免密支付）';
  if (kind === 'device_not_found') return '柜机不存在或编号有误，请重新扫描柜门二维码';
  if (kind === 'device_paused') return '柜机已暂停营业，请稍后再试或换一台';
  if (kind === 'device_busy') return '柜机正在使用或补货中，请稍后再试';
  if (typeof err === 'string') return err;
  const e = err as { message?: string; errMsg?: string };
  if (e.message) {
    const msg = String(e.message);
    if (/[\u4e00-\u9fff]/.test(msg)) return msg;
    return '操作失败，请稍后重试';
  }
  if (e.errMsg) return String(e.errMsg);
  return '请求失败';
}

export function orderStatusLabel(status?: string) {
  return dictLabel('order_status', status);
}

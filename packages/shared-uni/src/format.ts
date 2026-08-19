import { displayLabel } from '@aicabinet/shared-dict';

type DateInput = string | number | Date | null | undefined;

/** 小程序/H5 空值文案（对齐运营后台「无 / 暂无」语义，避免裸 `-`） */
export const EMPTY = {
  none: '无',
  text: '暂无',
  device: '无柜机',
  session: '无会话',
  order: '无单号',
  money: '暂无',
  date: '暂无',
  status: '未知状态',
  channel: '未知渠道',
  batch: '无批次',
  expiry: '未填',
  reason: '暂无说明'
} as const;

export type EmptyKind = keyof typeof EMPTY;

/** 空值展示：null / 空白 → 语义文案；有值则原样字符串化 */
export function emptyDisplay(
  value: string | number | null | undefined,
  kind: EmptyKind = 'text'
): string {
  if (value == null) return EMPTY[kind];
  const s = String(value).trim();
  return s ? s : EMPTY[kind];
}

function parseDate(value: DateInput): Date | null {
  if (value == null || value === '') return null;
  const date = value instanceof Date ? value : new Date(value);
  return Number.isNaN(date.getTime()) ? null : date;
}

function pad2(n: number): string {
  return n < 10 ? '0' + n : String(n);
}

/**
 * 东八区墙钟。微信小程序（尤其真机/低版本基础库）没有 Intl，不能用 DateTimeFormat。
 * 中国无夏令时，UTC+8 固定。
 */
function shanghaiClock(date: Date) {
  const sh = new Date(date.getTime() + 8 * 60 * 60 * 1000);
  return {
    year: String(sh.getUTCFullYear()),
    month: pad2(sh.getUTCMonth() + 1),
    day: pad2(sh.getUTCDate()),
    hour: pad2(sh.getUTCHours()),
    minute: pad2(sh.getUTCMinutes()),
    second: pad2(sh.getUTCSeconds())
  };
}

/** 完整时间：YYYY-MM-DD HH:mm:ss（东八区） */
export function formatDateTime(value?: DateInput, fallback: string = EMPTY.none): string {
  const date = parseDate(value ?? null);
  if (!date) return value != null && value !== '' ? String(value) : fallback;
  const p = shanghaiClock(date);
  return `${p.year}-${p.month}-${p.day} ${p.hour}:${p.minute}:${p.second}`;
}

/** 列表常用：YYYY-MM-DD HH:mm（东八区） */
export function formatDateTimeMinute(value?: DateInput, fallback: string = EMPTY.date): string {
  const date = parseDate(value ?? null);
  if (!date) return value != null && value !== '' ? String(value) : fallback;
  const p = shanghaiClock(date);
  return `${p.year}-${p.month}-${p.day} ${p.hour}:${p.minute}`;
}

/** 紧凑时间：MM/DD HH:mm（东八区），适合移动端列表 */
export function formatDateTimeShort(value?: DateInput, fallback: string = EMPTY.date): string {
  const date = parseDate(value ?? null);
  if (!date) return value != null && value !== '' ? String(value) : fallback;
  const p = shanghaiClock(date);
  return `${p.month}/${p.day} ${p.hour}:${p.minute}`;
}

/** 东八区当天 00:00 的时间戳，用于「今天」筛选 */
export function startOfTodayShanghaiMs(now: Date = new Date()): number {
  const p = shanghaiClock(now);
  return new Date(`${p.year}-${p.month}-${p.day}T00:00:00+08:00`).getTime();
}

export function fmtMoney(cents?: number | null, empty: string = EMPTY.money) {
  if (cents == null) return empty;
  return '¥' + (cents / 100).toFixed(2);
}

export type OpenErrorKind =
  'balance' | 'device_not_found' | 'device_paused' | 'device_busy' | 'rate_limit' | 'other';

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
  if (
    e?.status === 404 ||
    /设备不存在|柜机不存在|device not found|编号无效|检查设备编号|检查柜机编号/i.test(msg)
  ) {
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
  if (kind === 'balance')
    return '可用余额不足，请先充值后再开门（默认预授权约 ¥20，或开通免密支付）';
  if (kind === 'device_not_found') return '柜机不存在或编号有误，请重新扫描柜门二维码';
  if (kind === 'device_paused') return '柜机已暂停营业，请稍后再试或换一台';
  if (kind === 'device_busy') return '柜机正在使用或补货中，请稍后再试';
  if (typeof err === 'string') return localizeApiMessage(err);
  const e = err as { message?: string; errMsg?: string };
  if (e.message) return localizeApiMessage(String(e.message));
  if (e.errMsg) return localizeApiMessage(String(e.errMsg));
  return '请求失败';
}

/**
 * 将后端 / SDK 英文或技术错误文案转为用户可读中文。
 * 已含中文的业务提示原样返回。
 */
export function localizeApiMessage(
  message?: string | null,
  fallback = '操作失败，请稍后重试'
): string {
  const msg = String(message || '').trim();
  if (!msg) return fallback;
  if (/[\u4e00-\u9fff]/.test(msg)) return msg;

  if (/requestPayment:fail\s*cancel|cancel.*payment|user cancel/i.test(msg)) {
    return '已取消支付';
  }
  if (/request:fail|timeout|network|ERR_NETWORK|Failed to fetch|ECONN/i.test(msg)) {
    return '网络异常，请稍后重试';
  }
  if (/unauthorized|token.*(expired|invalid)|login.*(required|expired)/i.test(msg)) {
    return '登录已失效，请重新登录';
  }
  if (/forbidden|permission|access denied/i.test(msg)) {
    return '权限不足';
  }
  if (/not found|404/i.test(msg)) {
    return '未找到相关数据';
  }
  if (/conflict|already exists|duplicate/i.test(msg)) {
    return '操作冲突，请刷新后重试';
  }
  if (/too many|rate limit|429/i.test(msg)) {
    return '操作过于频繁，请稍后再试';
  }
  if (/internal|server error|500/i.test(msg)) {
    return '服务暂时不可用，请稍后重试';
  }
  // 纯英文技术码（如 SESSION_STATE_INVALID）不直接展示
  if (/^[A-Z][A-Z0-9_ .\-:()]+$/.test(msg)) {
    return fallback;
  }
  return fallback;
}

/** 将争议 reason / 内部英文话术转为可读中文（商户/消费者列表共用） */
export function localizeDisputeReason(reason?: string | null): string {
  const r = (reason || '').trim();
  if (!r) return '';
  if (/recognition needs manual review/i.test(r) || /no charge yet/i.test(r)) {
    return '商品识别结果需要人工确认，本次暂未扣款。审核完成后会生成账单。';
  }
  if (/confidence/i.test(r) && /threshold|below|manual/i.test(r)) {
    return '部分商品识别置信度不足，需人工确认后再扣款。';
  }
  if (/timeout|识别超时|STIMEOUT/i.test(r)) {
    return '识别超时，本次暂未扣款，工作人员正在核对账单。';
  }
  if (
    /非生产|重力信号|仅有重力|重力回填|模拟\/兜底|模拟识别|gravity-fill|gravity-mismatch|mock-v/i.test(
      r
    )
  ) {
    return '商品识别结果需要人工确认，本次暂未扣款。审核完成后会生成账单。';
  }
  const letters = (r.match(/[A-Za-z]/g) || []).length;
  const cjk = (r.match(/[\u4e00-\u9fff]/g) || []).length;
  if (letters >= 8 && cjk === 0) {
    return '商品识别结果需要人工确认，本次暂未扣款。审核完成后会生成账单。';
  }
  return r;
}

export function orderStatusLabel(status?: string) {
  return displayLabel('order_status', status, '未知状态');
}

/**
 * 订单号 / 会话号 / 充值单 / 支付流水 / 异常单 / 分账单等「字母+十六进制」业务编号 → 纯数字展示。
 * 不转换柜机编号、SKU、配置键等业务编码。导航/接口仍用原始 id。
 */
export function displayBizNo(id?: string | number | null, empty: string = EMPTY.order): string {
  if (id == null) return empty;
  const raw = String(id).trim();
  if (!raw) return empty;
  if (/^\d+$/.test(raw)) return raw;
  // 柜机 / SKU / 仓库等可读编码原样展示
  if (/^(CAB|SKU|ORG|WH|BIN|LOT|ROUTE)[-_]/i.test(raw)) return raw;
  if (/^[A-Z]{2,}[-_][A-Z]*\d/i.test(raw) && /[G-Z]/i.test(raw)) return raw;

  const body = raw
    .replace(/^MOCK-[A-Z]+-/i, '')
    .replace(/^(PSC|ALI-AG|PREVIEW)-?/i, '')
    .replace(/^(BL|MW|LW|RF|ADJ|EX|ADM)-?/i, '')
    .replace(/^[A-Z]+-?/i, '')
    .replace(/-/g, '');

  const hex = body.replace(/[^0-9A-Fa-f]/g, '');
  if (hex.length >= 8 && /^[0-9A-Fa-f]+$/i.test(hex)) {
    const slice = hex.length > 15 ? hex.slice(-15) : hex;
    try {
      const digits = BigInt(`0x${slice}`).toString();
      if (digits) return digits;
    } catch {
      /* fallthrough */
    }
  }

  const digitsOnly = raw.replace(/\D/g, '');
  return digitsOnly.length >= 4 ? digitsOnly : raw;
}

/** 列表短号：纯数字，超长取末尾 */
export function shortBizNo(
  id?: string | number | null,
  maxLen = 14,
  empty: string = EMPTY.order
): string {
  const full = displayBizNo(id, empty);
  if (full === empty) return full;
  return full.length <= maxLen ? full : full.slice(-maxLen);
}

/** 把正文里嵌套的字母+十六进制业务号替换为纯数字（消息模板等） */
export function rewriteBizNosInText(text?: string | null): string {
  if (text == null || text === '') return '';
  return String(text).replace(
    /\b(?:MOCK-[A-Z]+-)?(?:BL|MW|LW|RF|ADJ|EX|ADM)-?[0-9A-Fa-f]{8,}\b|\b[OSDR][0-9A-Fa-f]{10,}\b/g,
    (m) => displayBizNo(m, m)
  );
}

/**
 * 通知标题去掉末尾「 #单号 / #{var}」——编号改在「关联单号」行展示。
 * 例：「新补货任务 #16」→「新补货任务」
 */
export function sanitizeNotifyTitle(text?: string | null): string {
  if (text == null || text === '') return '';
  return String(text)
    .replace(/\s*#\{\w+\}\s*$/u, '')
    .replace(/\s*#[\w.-]+\s*$/u, '')
    .trim();
}

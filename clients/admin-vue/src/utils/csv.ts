/** CSV helpers for admin list import/export. */

export function fileStamp(d = new Date()): string {
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}_${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}`;
}

export function csvFileName(prefix: string, d = new Date()): string {
  return `${prefix}_${fileStamp(d)}.csv`;
}

/** Safe display string for CSV / UI — never rely on Object.toString(). */
function unknownToDisplayString(value: unknown): string {
  if (value == null) return '';
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean' || typeof value === 'bigint') {
    return String(value);
  }
  if (typeof value === 'symbol') return value.description ?? '';
  if (typeof value === 'object') {
    try {
      return JSON.stringify(value);
    } catch {
      return '';
    }
  }
  return '';
}

export function escapeCsvCell(value: unknown): string {
  const s = unknownToDisplayString(value);
  if (/[",\n\r]/.test(s)) return `"${s.replaceAll('"', '""')}"`;
  return s;
}

export function toCsv(headers: string[], rows: Array<Array<unknown>>): string {
  const lines = [headers.map(escapeCsvCell).join(',')];
  for (const row of rows) {
    lines.push(row.map(escapeCsvCell).join(','));
  }
  return `\uFEFF${lines.join('\n')}`;
}

export function downloadCsv(filename: string, headers: string[], rows: Array<Array<unknown>>) {
  const blob = new Blob([toCsv(headers, rows)], { type: 'text/csv;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  a.click();
  URL.revokeObjectURL(url);
}

type CsvParseState = {
  rows: string[][];
  row: string[];
  cell: string;
  inQuotes: boolean;
};

function handleQuotedChar(state: CsvParseState, raw: string, i: number): number {
  const ch = raw[i];
  if (ch === '"') {
    if (raw[i + 1] === '"') {
      state.cell += '"';
      return i + 1;
    }
    state.inQuotes = false;
    return i;
  }
  state.cell += ch;
  return i;
}

function flushRow(state: CsvParseState) {
  state.row.push(state.cell);
  state.cell = '';
  if (state.row.some((c) => c.trim() !== '')) {
    state.rows.push(state.row);
  }
  state.row = [];
}

function handleUnquotedChar(state: CsvParseState, ch: string) {
  if (ch === '"') {
    state.inQuotes = true;
    return;
  }
  if (ch === ',') {
    state.row.push(state.cell);
    state.cell = '';
    return;
  }
  if (ch === '\n') {
    flushRow(state);
    return;
  }
  state.cell += ch;
}

/** Minimal CSV parser: supports quoted fields and commas. */
export function parseCsv(text: string): string[][] {
  const raw = text
    .replace(/^\uFEFF/, '')
    .replaceAll('\r\n', '\n')
    .replaceAll('\r', '\n');
  const state: CsvParseState = { rows: [], row: [], cell: '', inQuotes: false };
  for (let i = 0; i < raw.length; i++) {
    const ch = raw[i];
    if (state.inQuotes) {
      i = handleQuotedChar(state, raw, i);
      continue;
    }
    handleUnquotedChar(state, ch);
  }
  state.row.push(state.cell);
  if (state.row.some((c) => c.trim() !== '')) state.rows.push(state.row);
  return state.rows;
}

export function csvRowsToObjects(rows: string[][]): Record<string, string>[] {
  if (rows.length < 2) return [];
  const headers = rows[0].map((h) => h.trim());
  return rows.slice(1).map((cols) => {
    const obj: Record<string, string> = {};
    headers.forEach((h, i) => {
      obj[h] = (cols[i] ?? '').trim();
    });
    return obj;
  });
}

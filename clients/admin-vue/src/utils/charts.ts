/** Lightweight SVG chart helpers for analytics dashboard (no chart lib). */

export type ChartKind = 'line' | 'area' | 'bar';

function niceStep(n: number): number {
  if (n <= 1) return 1;
  if (n <= 2) return 2;
  if (n <= 5) return 5;
  return 10;
}

export function niceMax(val: number): number {
  if (val <= 0) return 1;
  const exp = Math.pow(10, Math.floor(Math.log10(val)));
  const n = val / exp;
  const step = niceStep(n);
  return step * exp;
}

export function formatYuan(cents: number): string {
  return '¥' + (cents / 100).toFixed(2);
}

export function formatPct(rate: number): string {
  return (Number(rate) * 100).toFixed(1) + '%';
}

export function shortDate(iso: string): string {
  if (!iso) return '';
  const parts = iso.split('-');
  return parts.length >= 3 ? `${parts[1]}-${parts[2]}` : iso;
}

export interface LineSeries {
  name: string;
  values: number[];
  color: string;
}

interface PlotBox {
  W: number;
  H: number;
  padL: number;
  padR: number;
  padT: number;
  padB: number;
  plotW: number;
  plotH: number;
  maxY: number;
  labels: string[];
}

function plotBox(labels: string[], maxRaw: number, height: number, padL = 52): PlotBox {
  const W = 640;
  const H = height;
  const padR = 16;
  const padT = 18;
  const padB = 36;
  return {
    W,
    H,
    padL,
    padR,
    padT,
    padB,
    plotW: W - padL - padR,
    plotH: H - padT - padB,
    maxY: niceMax(maxRaw),
    labels
  };
}

function xAt(box: PlotBox, i: number): number {
  const n = box.labels.length;
  return box.padL + (n <= 1 ? box.plotW / 2 : (i / (n - 1)) * box.plotW);
}

function yAt(box: PlotBox, v: number): number {
  return box.padT + box.plotH - (box.maxY > 0 ? (v / box.maxY) * box.plotH : 0);
}

function gridSvg(box: PlotBox, formatY: (v: number) => string): string {
  return [0, 0.25, 0.5, 0.75, 1]
    .map((t) => {
      const y = box.padT + box.plotH * (1 - t);
      return `<line x1="${box.padL}" y1="${y}" x2="${box.W - box.padR}" y2="${y}" stroke="var(--layout-border)" stroke-opacity="0.7" stroke-dasharray="3 4"/>
      <text x="${box.padL - 8}" y="${y + 4}" text-anchor="end" fill="var(--layout-muted)" font-size="11" font-family="inherit">${escapeXml(formatY(box.maxY * t))}</text>`;
    })
    .join('');
}

function xLabelsSvg(box: PlotBox): string {
  const step = box.labels.length > 16 ? Math.ceil(box.labels.length / 8) : 1;
  return box.labels
    .map((lb, i) =>
      i % step === 0 || i === box.labels.length - 1
        ? `<text x="${xAt(box, i)}" y="${box.H - 8}" text-anchor="middle" fill="var(--layout-muted)" font-size="11" font-family="inherit">${escapeXml(lb)}</text>`
        : ''
    )
    .join('');
}

function seriesMax(series: LineSeries[]): number {
  let maxY = 0;
  series.forEach((s) =>
    s.values.forEach((v) => {
      if (v > maxY) maxY = v;
    })
  );
  return maxY;
}

function hexToRgb(hex: string): { r: number; g: number; b: number } | null {
  const m = /^#([0-9a-f]{6})$/i.exec(hex.trim());
  if (!m) return null;
  const n = Number.parseInt(m[1], 16);
  return { r: (n >> 16) & 255, g: (n >> 8) & 255, b: n & 255 };
}

function rgba(color: string, a: number): string {
  const safe = safeCssColor(color);
  const rgb = hexToRgb(safe);
  if (!rgb) return safe;
  return `rgba(${rgb.r},${rgb.g},${rgb.b},${a})`;
}

/** Catmull-Rom 平滑曲线：折线升级为连续贝塞尔，视觉上更柔和。 */
function smoothPath(box: PlotBox, values: number[]): string {
  const pts = values.map((v, i) => ({ x: xAt(box, i), y: yAt(box, v) }));
  if (!pts.length) return '';
  if (pts.length === 1) return `M${pts[0].x.toFixed(2)},${pts[0].y.toFixed(2)}`;
  let d = `M${pts[0].x.toFixed(2)},${pts[0].y.toFixed(2)}`;
  for (let i = 0; i < pts.length - 1; i++) {
    const p0 = pts[i - 1] || pts[i];
    const p1 = pts[i];
    const p2 = pts[i + 1];
    const p3 = pts[i + 2] || p2;
    const c1x = p1.x + (p2.x - p0.x) / 6;
    const c1y = p1.y + (p2.y - p0.y) / 6;
    const c2x = p2.x - (p3.x - p1.x) / 6;
    const c2y = p2.y - (p3.y - p1.y) / 6;
    d += ` C${c1x.toFixed(2)},${c1y.toFixed(2)} ${c2x.toFixed(2)},${c2y.toFixed(2)} ${p2.x.toFixed(2)},${p2.y.toFixed(2)}`;
  }
  return d;
}

function pathLine(box: PlotBox, values: number[]): string {
  return smoothPath(box, values);
}

function areaPath(box: PlotBox, values: number[]): string {
  if (!values.length) return '';
  const top = pathLine(box, values);
  const lastX = xAt(box, values.length - 1);
  const firstX = xAt(box, 0);
  const baseY = box.padT + box.plotH;
  return `${top} L${lastX.toFixed(2)},${baseY.toFixed(2)} L${firstX.toFixed(2)},${baseY.toFixed(2)} Z`;
}

function tipAttr(lines: string[]): string {
  return escapeXml(lines.filter(Boolean).join('\n'));
}

/** Column hit zones + crosshair for line/area (easier hover than tiny dots). */
function lineHitZones(box: PlotBox, series: LineSeries[], formatY: (v: number) => string): string {
  const n = box.labels.length;
  if (!n) return '';
  const half = n <= 1 ? box.plotW / 2 : Math.max(10, box.plotW / (n - 1) / 2);

  return box.labels
    .map((lb, i) => {
      const cx = xAt(box, i);
      const x = Math.max(box.padL, cx - half);
      const w = Math.min(box.W - box.padR, cx + half) - x;
      const rows = series.map((s) => `${s.name}|${formatY(s.values[i] ?? 0)}|${s.color}`);
      const tip = tipAttr([lb, ...rows]);
      return `<g class="chart-col" data-tip="${tip}" data-i="${i}" data-x="${cx.toFixed(1)}">
        <rect class="chart-col-hit" x="${x.toFixed(1)}" y="${box.padT}" width="${Math.max(w, 8).toFixed(1)}" height="${box.plotH}" fill="transparent"/>
        <line class="chart-crosshair" x1="${cx.toFixed(1)}" y1="${box.padT}" x2="${cx.toFixed(1)}" y2="${box.padT + box.plotH}" stroke="var(--layout-muted)" stroke-opacity="0" stroke-dasharray="3 3"/>
      </g>`;
    })
    .join('');
}

/** Unified cartesian chart: line | area | bar (single or multi-series). */
export function buildSeriesChart(opts: {
  labels: string[];
  series: LineSeries[];
  kind?: ChartKind;
  height?: number;
  formatY?: (v: number) => string;
}): string {
  const { labels, kind = 'line', height = 240, formatY = (v) => String(Math.round(v)) } = opts;
  const series = opts.series.map((s) => ({ ...s, color: safeCssColor(s.color) }));
  if (!labels.length || !series.length) return '';

  if (kind === 'bar') {
    return buildGroupedBarChart({ labels, series, height, formatY });
  }

  const box = plotBox(labels, seriesMax(series), height);
  const defs = series
    .map((s, idx) => {
      const id = `cg${idx}`;
      return `<linearGradient id="${id}" x1="0" y1="0" x2="0" y2="1">
        <stop offset="0%" stop-color="${s.color}" stop-opacity="0.35"/>
        <stop offset="100%" stop-color="${s.color}" stop-opacity="0.02"/>
      </linearGradient>`;
    })
    .join('');

  const bodies = series
    .map((s, idx) => {
      const line = pathLine(box, s.values);
      const area =
        kind === 'area'
          ? `<path d="${areaPath(box, s.values)}" fill="url(#cg${idx})" stroke="none" pointer-events="none"/>`
          : '';
      const glow =
        kind === 'line'
          ? `<path d="${line}" fill="none" stroke="${rgba(s.color, 0.25)}" stroke-width="6" stroke-linecap="round" stroke-linejoin="round" pointer-events="none"/>`
          : '';
      const stroke = `<path d="${line}" fill="none" stroke="${s.color}" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round" pointer-events="none"/>`;
      const dots = s.values
        .map((v, i) => {
          const cx = xAt(box, i);
          const cy = yAt(box, v);
          return `<g class="chart-pt" data-i="${i}" data-series="${idx}" pointer-events="none">
            <circle class="chart-pt-halo" cx="${cx}" cy="${cy}" r="5.5" fill="${rgba(s.color, 0.18)}" stroke="none"/>
            <circle class="chart-pt-dot" cx="${cx}" cy="${cy}" r="3.2" fill="${s.color}" stroke="var(--layout-card-bg, #0f172a)" stroke-width="1.5"/>
          </g>`;
        })
        .join('');
      return `${area}${glow}${stroke}${dots}`;
    })
    .join('');

  const hits = lineHitZones(box, series, formatY);

  return `<svg viewBox="0 0 ${box.W} ${box.H}" preserveAspectRatio="xMidYMid meet" role="img" class="chart-svg chart-interactive">
    <defs>${defs}</defs>
    ${gridSvg(box, formatY)}${bodies}${hits}${xLabelsSvg(box)}
  </svg>`;
}

export function buildLineChart(opts: {
  labels: string[];
  series: LineSeries[];
  height?: number;
  formatY?: (v: number) => string;
}): string {
  return buildSeriesChart({ ...opts, kind: 'line' });
}

export function buildAreaChart(opts: {
  labels: string[];
  series: LineSeries[];
  height?: number;
  formatY?: (v: number) => string;
}): string {
  return buildSeriesChart({ ...opts, kind: 'area' });
}

export function buildGroupedBarChart(opts: {
  labels: string[];
  series: LineSeries[];
  height?: number;
  formatY?: (v: number) => string;
}): string {
  const { labels, height = 240, formatY = (v) => String(Math.round(v)) } = opts;
  const series = opts.series.map((s) => ({ ...s, color: safeCssColor(s.color) }));
  if (!labels.length || !series.length) return '';
  const box = plotBox(labels, seriesMax(series), height, 52);
  const groupW = box.plotW / labels.length;
  const n = series.length;
  const barGap = 2;
  const barW = Math.min(28, Math.max(6, (groupW * 0.62 - barGap * (n - 1)) / n));
  const groupInner = n * barW + (n - 1) * barGap;

  const bars = labels
    .map((lb, i) =>
      series
        .map((s, si) => {
          const v = s.values[i] ?? 0;
          const h = box.maxY > 0 ? (v / box.maxY) * box.plotH : 0;
          const gx = box.padL + i * groupW + (groupW - groupInner) / 2;
          const x = gx + si * (barW + barGap);
          const y = box.padT + box.plotH - h;
          const gradId = `bg${si}`;
          const tip = tipAttr([lb, `${s.name}|${formatY(v)}|${s.color}`]);
          const barH = Math.max(h, v > 0 ? 2 : 0);
          return `<rect class="chart-bar" data-tip="${tip}" x="${x}" y="${y}" width="${barW}" height="${barH}" rx="3.5" fill="url(#${gradId})"/>`;
        })
        .join('')
    )
    .join('');

  const defs = series
    .map(
      (s, si) => `<linearGradient id="bg${si}" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0%" stop-color="${s.color}" stop-opacity="1"/>
      <stop offset="100%" stop-color="${s.color}" stop-opacity="0.55"/>
    </linearGradient>`
    )
    .join('');

  return `<svg viewBox="0 0 ${box.W} ${box.H}" preserveAspectRatio="xMidYMid meet" role="img" class="chart-svg chart-interactive">
    <defs>${defs}</defs>
    ${gridSvg(box, formatY)}${bars}${xLabelsSvg(box)}
  </svg>`;
}

export function buildBarChart(opts: {
  labels: string[];
  values: number[];
  height?: number;
  color?: string;
  formatY?: (v: number) => string;
}): string {
  const { labels, values, height = 240, color = '#3b82f6', formatY = (v) => String(v) } = opts;
  return buildGroupedBarChart({
    labels,
    series: [{ name: '值', values, color }],
    height,
    formatY
  });
}

export function buildDonutChart(opts: {
  parts: { label: string; value: number; color: string }[];
  size?: number;
  /** 中心合计展示；默认原始数值 */
  formatCenter?: (total: number) => string;
  /** 悬停 tip 中数值列；默认原值 */
  formatValue?: (value: number) => string;
  /** tip 数值列标题，默认「数值」 */
  valueLabel?: string;
}): string {
  const { parts: rawParts, size = 200, formatCenter, formatValue, valueLabel = '数值' } = opts;
  const parts = rawParts.map((p) => ({ ...p, color: safeCssColor(p.color) }));
  const total = parts.reduce((s, p) => s + Math.max(p.value, 0), 0);
  if (total <= 0) return '';
  const cx = size / 2;
  const cy = size / 2;
  const r = size * 0.34;
  const stroke = size * 0.17;
  let angle = -Math.PI / 2;
  const centerText = formatCenter ? formatCenter(total) : String(total);
  const arcs = parts
    .map((p) => {
      const sweep = (Math.max(p.value, 0) / total) * Math.PI * 2;
      if (sweep <= 0) return '';
      const x1 = cx + r * Math.cos(angle);
      const y1 = cy + r * Math.sin(angle);
      angle += sweep;
      const x2 = cx + r * Math.cos(angle);
      const y2 = cy + r * Math.sin(angle);
      const large = sweep > Math.PI ? 1 : 0;
      const pct = ((Math.max(p.value, 0) / total) * 100).toFixed(1);
      const raw = Math.max(p.value, 0);
      const shown = formatValue ? formatValue(raw) : String(raw);
      const tip = tipAttr([
        p.label,
        `${valueLabel}|${shown}|${p.color}`,
        `占比|${pct}%|${p.color}`
      ]);
      return `<path class="chart-arc" data-tip="${tip}" d="M ${x1} ${y1} A ${r} ${r} 0 ${large} 1 ${x2} ${y2}" fill="none" stroke="${p.color}" stroke-width="${stroke}" stroke-linecap="butt"/>`;
    })
    .join('');
  return `<svg viewBox="0 0 ${size} ${size}" role="img" class="chart-svg chart-interactive">
    <circle cx="${cx}" cy="${cy}" r="${r}" fill="none" stroke="var(--layout-border)" stroke-width="${stroke}" opacity="0.35" pointer-events="none"/>
    ${arcs}
    <text x="${cx}" y="${cy - 2}" text-anchor="middle" fill="var(--layout-text)" font-size="22" font-weight="700" font-family="inherit" pointer-events="none">${escapeXml(centerText)}</text>
    <text x="${cx}" y="${cy + 18}" text-anchor="middle" fill="var(--layout-muted)" font-size="11" font-family="inherit" pointer-events="none">合计</text>
  </svg>`;
}

function escapeXml(v: string | number): string {
  return String(v ?? '')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;');
}

/**
 * ChartBox v-html 前的兜底清洗：图表 SVG 应由本模块生成，仍剥离常见 XSS 载体。
 * 非 `<svg` 开头的内容直接丢弃。
 */
export function sanitizeChartSvg(raw: string): string {
  const s = String(raw || '').trim();
  if (!s.startsWith('<svg')) return '';
  return (
    s
      .replaceAll(/<script\b[\s\S]*?<\/script>/gi, '')
      .replaceAll(/<\/?(?:foreignObject|iframe|object|embed|link|meta|base)\b[^>]*>/gi, '')
      .replaceAll(/\son[a-z]+\s*=\s*("[^"]*"|'[^']*'|[^\s>]+)/gi, '')
      // 仅保留页内锚点 href；外链 / javascript / data URI 一并去掉
      .replaceAll(/\s(?:href|xlink:href)\s*=\s*("(?!#)[^"]*"|'(?!#)[^']*')/gi, '')
      .replaceAll(/javascript:/gi, '')
  );
}

/** 仅允许 CSS 颜色字面量进入 SVG 属性，防止色值里夹带引号/表达式。 */
export function safeCssColor(color: string, fallback = '#64748b'): string {
  const c = String(color || '').trim();
  if (c.length > 64) return fallback;
  if (/^#([0-9a-f]{3}|[0-9a-f]{6}|[0-9a-f]{8})$/i.test(c)) return c;
  // 固定上限的通道数，避免 [\d.\s%,.]+ 类开放量词触发 ReDoS
  // 分段匹配，避免单一长正则回溯（Sonar typescript:S5852 ReDoS）
  if (/^rgba?\(/i.test(c) && c.endsWith(')')) {
    const inner = c.slice(c.indexOf('(') + 1, c.lastIndexOf(')')).trim();
    const parts = inner.split(',').map((p) => p.trim());
    if (
      (parts.length === 3 || parts.length === 4) &&
      parts.slice(0, 3).every((p) => /^\d{1,3}$/.test(p)) &&
      (parts.length === 3 || /^(?:0|1|0?\.\d{1,4})$/.test(parts[3]))
    ) {
      return c;
    }
  }
  if (/^hsla?\(/i.test(c) && c.endsWith(')')) {
    const inner = c.slice(c.indexOf('(') + 1, c.lastIndexOf(')')).trim();
    const parts = inner.split(',').map((p) => p.trim());
    if (
      (parts.length === 3 || parts.length === 4) &&
      /^\d{1,3}(?:\.\d{1,4})?$/.test(parts[0]) &&
      parts.slice(1, 3).every((p) => /^\d{1,3}%$/.test(p)) &&
      (parts.length === 3 || /^(?:0|1|0?\.\d{1,4})$/.test(parts[3]))
    ) {
      return c;
    }
  }
  if (/^var\(--[a-z0-9_-]{1,40}\)$/i.test(c)) return c;
  const varWithFallback = /^var\((--[a-z0-9_-]{1,40})\s*,\s*([^)]{1,40})\)$/i.exec(c);
  if (varWithFallback) return c;
  return fallback;
}

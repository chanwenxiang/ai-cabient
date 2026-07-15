/** 数据概览 — 纯 SVG 图表（无第三方依赖） */

function escSvgText(v) {
  return String(v ?? '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/"/g, '&quot;');
}

function niceMax(val) {
  if (val <= 0) return 1;
  const exp = Math.pow(10, Math.floor(Math.log10(val)));
  const n = val / exp;
  const step = n <= 1 ? 1 : n <= 2 ? 2 : n <= 5 ? 5 : 10;
  return step * exp;
}

function formatYuan(cents) {
  return '¥' + (cents / 100).toFixed(cents >= 10000 ? 0 : 2);
}

function formatPct(rate) {
  return (Number(rate) * 100).toFixed(1) + '%';
}

function deltaBadge(current, previous, opts = {}) {
  if (previous == null || current == null) return '';
  const diff = previous === 0 ? (current > 0 ? 100 : 0) : ((current - previous) / Math.abs(previous)) * 100;
  const up = diff >= 0;
  const invert = opts.invert;
  const good = invert ? !up : up;
  const cls = Math.abs(diff) < 0.05 ? 'flat' : good ? 'up' : 'down';
  const sign = diff >= 0 ? '+' : '';
  const label = opts.suffix === '%' ? `${sign}${diff.toFixed(1)}%` : `${sign}${diff.toFixed(1)}%`;
  return `<span class="delta-badge ${cls}" title="较前一周期">${label}</span>`;
}

/**
 * 折线图
 * series: [{ name, values, color }]
 */
function renderLineChart({ labels, series, height = 220, formatY = (v) => String(v) }) {
  if (!labels?.length || !series?.length) {
    return '<p class="meta chart-empty">暂无趋势数据</p>';
  }
  const W = 640;
  const H = height;
  const padL = 52;
  const padR = 16;
  const padT = 20;
  const padB = 36;
  const plotW = W - padL - padR;
  const plotH = H - padT - padB;

  let maxY = 0;
  series.forEach((s) => {
    (s.values || []).forEach((v) => { if (v > maxY) maxY = v; });
  });
  maxY = niceMax(maxY);

  const xAt = (i) => padL + (labels.length <= 1 ? plotW / 2 : (i / (labels.length - 1)) * plotW);
  const yAt = (v) => padT + plotH - (maxY > 0 ? (v / maxY) * plotH : 0);

  const gridLines = [0, 0.25, 0.5, 0.75, 1].map((t) => {
    const y = padT + plotH * (1 - t);
    const val = maxY * t;
    return `<line x1="${padL}" y1="${y}" x2="${W - padR}" y2="${y}" class="chart-grid"/>
      <text x="${padL - 8}" y="${y + 4}" class="chart-axis-y" text-anchor="end">${escSvgText(formatY(val))}</text>`;
  }).join('');

  const xLabels = labels.map((lb, i) =>
    `<text x="${xAt(i)}" y="${H - 8}" class="chart-axis-x" text-anchor="middle">${escSvgText(lb)}</text>`
  ).join('');

  const paths = series.map((s) => {
    const pts = (s.values || []).map((v, i) => `${xAt(i)},${yAt(v)}`).join(' ');
    const dots = (s.values || []).map((v, i) =>
      `<circle cx="${xAt(i)}" cy="${yAt(v)}" r="4" class="chart-dot" fill="${s.color || 'var(--chart-1)'}">
        <title>${escSvgText(s.name)} ${escSvgText(labels[i])}: ${escSvgText(formatY(v))}</title>
      </circle>`
    ).join('');
    return `<polyline points="${pts}" fill="none" stroke="${s.color || 'var(--chart-1)'}" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"/>
      ${dots}`;
  }).join('');

  const legend = series.map((s) =>
    `<span class="chart-legend-item"><i style="background:${s.color || 'var(--chart-1)'}"></i>${escSvgText(s.name)}</span>`
  ).join('');

  return `<div class="svg-chart-wrap">
    <svg class="svg-chart" viewBox="0 0 ${W} ${H}" preserveAspectRatio="xMidYMid meet" role="img" aria-label="折线图">
      ${gridLines}
      ${paths}
      ${xLabels}
    </svg>
    <div class="chart-legend">${legend}</div>
  </div>`;
}

function renderBarChart({ labels, values, height = 200, formatY = (v) => String(v), color = 'var(--chart-1)' }) {
  if (!labels?.length) return '<p class="meta chart-empty">暂无数据</p>';
  const W = 640;
  const H = height;
  const padL = 48;
  const padR = 12;
  const padT = 16;
  const padB = 32;
  const plotW = W - padL - padR;
  const plotH = H - padT - padB;
  const maxY = niceMax(Math.max(...values, 0));
  const barW = Math.min(48, plotW / labels.length * 0.55);
  const gap = plotW / labels.length;

  const bars = values.map((v, i) => {
    const h = maxY > 0 ? (v / maxY) * plotH : 0;
    const x = padL + i * gap + (gap - barW) / 2;
    const y = padT + plotH - h;
    return `<rect x="${x}" y="${y}" width="${barW}" height="${Math.max(h, 2)}" rx="4" fill="${color}" opacity="0.9">
      <title>${escSvgText(labels[i])}: ${escSvgText(formatY(v))}</title>
    </rect>`;
  }).join('');

  const xLabels = labels.map((lb, i) => {
    const x = padL + i * gap + gap / 2;
    return `<text x="${x}" y="${H - 8}" class="chart-axis-x" text-anchor="middle">${escSvgText(lb)}</text>`;
  }).join('');

  const grid = [0, 0.5, 1].map((t) => {
    const y = padT + plotH * (1 - t);
    return `<line x1="${padL}" y1="${y}" x2="${W - padR}" y2="${y}" class="chart-grid"/>
      <text x="${padL - 6}" y="${y + 4}" class="chart-axis-y" text-anchor="end">${escSvgText(formatY(maxY * t))}</text>`;
  }).join('');

  return `<div class="svg-chart-wrap">
    <svg class="svg-chart" viewBox="0 0 ${W} ${H}" preserveAspectRatio="xMidYMid meet" role="img" aria-label="柱状图">
      ${grid}${bars}${xLabels}
    </svg>
  </div>`;
}

function renderDonutChart({ segments, size = 160 }) {
  const total = segments.reduce((s, x) => s + x.value, 0) || 1;
  const cx = size / 2;
  const cy = size / 2;
  const r = size * 0.38;
  const ir = r * 0.58;
  let angle = -Math.PI / 2;
  const arcs = segments.map((seg) => {
    const sweep = (seg.value / total) * Math.PI * 2;
    const x1 = cx + r * Math.cos(angle);
    const y1 = cy + r * Math.sin(angle);
    angle += sweep;
    const x2 = cx + r * Math.cos(angle);
    const y2 = cy + r * Math.sin(angle);
    const ix1 = cx + ir * Math.cos(angle - sweep);
    const iy1 = cy + ir * Math.sin(angle - sweep);
    const ix2 = cx + ir * Math.cos(angle);
    const iy2 = cy + ir * Math.sin(angle);
    const large = sweep > Math.PI ? 1 : 0;
    const d = `M ${x1} ${y1} A ${r} ${r} 0 ${large} 1 ${x2} ${y2} L ${ix2} ${iy2} A ${ir} ${ir} 0 ${large} 0 ${ix1} ${iy1} Z`;
    return `<path d="${d}" fill="${seg.color}"><title>${escSvgText(seg.label)}: ${seg.value}</title></path>`;
  }).join('');

  const legend = segments.map((seg) =>
    `<span class="chart-legend-item"><i style="background:${seg.color}"></i>${escSvgText(seg.label)} ${Math.round(seg.value / total * 100)}%</span>`
  ).join('');

  return `<div class="donut-chart-wrap">
    <svg width="${size}" height="${size}" viewBox="0 0 ${size} ${size}" role="img" aria-label="环形图">${arcs}</svg>
    <div class="chart-legend donut-legend">${legend}</div>
  </div>`;
}

function renderHorizontalBars(items) {
  if (!items?.length) return '';
  const max = Math.max(...items.map((i) => i.value), 1);
  return `<div class="h-bar-list">${items.map((item) => {
    const pct = Math.round((item.value / max) * 100);
    return `<div class="h-bar-row">
      <span class="h-bar-label">${escSvgText(item.label)}</span>
      <div class="h-bar-track"><div class="h-bar-fill" style="width:${pct}%;background:${item.color || 'var(--chart-1)'}"></div></div>
      <span class="h-bar-val">${escSvgText(item.display ?? item.value)}</span>
    </div>`;
  }).join('')}</div>`;
}

/** 组装数据概览分析区块 HTML */
function renderDashboardAnalytics(stats, trend, opsTrend) {
  const days = trend?.last7Days || [];
  const opsDays = opsTrend?.last7Days || [];
  const labels = days.map((d) => d.date.slice(5));
  const revenues = days.map((d) => d.revenueCents);
  const orders = days.map((d) => d.orderCount);

  const opsByDate = Object.fromEntries(opsDays.map((d) => [d.date, d]));
  const recRates = days.map((d) => {
    const o = opsByDate[d.date];
    return o ? Math.round((o.recognitionRate || 0) * 1000) / 10 : 0;
  });
  const disputeRates = days.map((d) => {
    const o = opsByDate[d.date];
    return o ? Math.round((o.disputeRate || 0) * 1000) / 10 : 0;
  });
  const sessionCounts = days.map((d) => {
    const o = opsByDate[d.date];
    return o ? (o.completedSessions || 0) + (o.disputedSessions || 0) : 0;
  });

  const totalRevenue = revenues.reduce((a, b) => a + b, 0);
  const totalOrders = orders.reduce((a, b) => a + b, 0);
  const avgRec = recRates.length ? recRates.reduce((a, b) => a + b, 0) / recRates.length : 0;

  const lastRev = revenues[revenues.length - 1];
  const prevRev = revenues[revenues.length - 2];
  const lastOrd = orders[orders.length - 1];
  const prevOrd = orders[orders.length - 2];

  const online = stats.deviceOnline || 0;
  const offline = Math.max(0, (stats.deviceTotal || 0) - online);

  return `
    <div class="analytics-section">
      <div class="analytics-head">
        <h3 class="section-title">数据分析</h3>
        <span class="meta">近 7 日趋势 · 点击指标卡片可跳转详情</span>
      </div>
      <div class="analytics-kpi">
        <div class="kpi-card">
          <div class="kpi-label">7日总营收</div>
          <div class="kpi-value">${formatYuan(totalRevenue)}</div>
          ${deltaBadge(lastRev, prevRev)}
        </div>
        <div class="kpi-card">
          <div class="kpi-label">7日订单量</div>
          <div class="kpi-value">${totalOrders}</div>
          ${deltaBadge(lastOrd, prevOrd)}
        </div>
        <div class="kpi-card">
          <div class="kpi-label">平均识别率</div>
          <div class="kpi-value ok">${avgRec.toFixed(1)}%</div>
        </div>
        <div class="kpi-card">
          <div class="kpi-label">设备在线率</div>
          <div class="kpi-value">${stats.deviceTotal ? Math.round(online / stats.deviceTotal * 100) : 0}%</div>
        </div>
      </div>
      <div class="analytics-grid">
        <div class="card chart-card">
          <div class="chart-card-head">
            <h4>营收趋势</h4>
            ${deltaBadge(lastRev, prevRev)}
          </div>
          ${renderLineChart({
            labels,
            series: [{ name: '营收', values: revenues, color: 'var(--chart-1)' }],
            formatY: (v) => formatYuan(v)
          })}
        </div>
        <div class="card chart-card">
          <div class="chart-card-head">
            <h4>订单量趋势</h4>
            ${deltaBadge(lastOrd, prevOrd)}
          </div>
          ${renderLineChart({
            labels,
            series: [{ name: '订单', values: orders, color: 'var(--chart-2)' }],
            formatY: (v) => String(Math.round(v))
          })}
        </div>
        <div class="card chart-card">
          <div class="chart-card-head">
            <h4>识别质量</h4>
            <span class="meta">识别率 vs 争议率</span>
          </div>
          ${renderLineChart({
            labels,
            series: [
              { name: '识别率', values: recRates, color: 'var(--chart-3)' },
              { name: '争议率', values: disputeRates, color: 'var(--chart-4)' }
            ],
            formatY: (v) => v + '%'
          })}
        </div>
        <div class="card chart-card">
          <div class="chart-card-head">
            <h4>关门会话量</h4>
          </div>
          ${renderBarChart({
            labels,
            values: sessionCounts,
            color: 'var(--chart-2)',
            formatY: (v) => String(Math.round(v))
          })}
        </div>
        <div class="card chart-card chart-card-sm">
          <div class="chart-card-head"><h4>设备状态</h4></div>
          ${renderDonutChart({
            segments: [
              { label: '在线', value: online, color: 'var(--chart-3)' },
              { label: '离线', value: offline || (online ? 0 : 1), color: 'var(--chart-muted)' }
            ]
          })}
        </div>
        <div class="card chart-card chart-card-sm">
          <div class="chart-card-head"><h4>运营健康度</h4></div>
          ${renderHorizontalBars([
            { label: '24h 开门成功率', value: stats.doorSuccessRate24h || 0, display: formatPct(stats.doorSuccessRate24h), color: 'var(--chart-3)' },
            { label: '24h 自动识别率', value: stats.recognitionAutoRate24h || 0, display: formatPct(stats.recognitionAutoRate24h), color: 'var(--chart-1)' },
            { label: '24h 争议率', value: stats.disputeRate24h || 0, display: formatPct(stats.disputeRate24h), color: 'var(--chart-4)' }
          ])}
        </div>
      </div>
    </div>`;
}

export {
  renderDashboardAnalytics,
  renderLineChart,
  renderBarChart,
  renderDonutChart,
  formatYuan
};

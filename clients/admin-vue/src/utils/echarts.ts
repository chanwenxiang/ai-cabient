/**
 * ECharts **按需注册**（与 merchant-mp 的 echarts-setup 同一策略，进程内执行一次）。
 * 只注册本项目用到的图表与组件；新增图表类型时在 `echarts.use([...])` 里按需追加，
 * 禁止 `import * as echarts from 'echarts'` 全量引入（全量 dist 约 1MB）。
 */
import { BarChart, LineChart, PieChart } from 'echarts/charts';
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components';
import * as echarts from 'echarts/core';
import { CanvasRenderer } from 'echarts/renderers';
import type { BarSeriesOption, LineSeriesOption, PieSeriesOption } from 'echarts/charts';
import type {
  GridComponentOption,
  LegendComponentOption,
  TooltipComponentOption
} from 'echarts/components';
import type { ComposeOption } from 'echarts/core';

echarts.use([
  LineChart,
  BarChart,
  PieChart,
  GridComponent,
  LegendComponent,
  TooltipComponent,
  CanvasRenderer
]);

export { echarts };

export type EChartsOption = ComposeOption<
  | BarSeriesOption
  | LineSeriesOption
  | PieSeriesOption
  | GridComponentOption
  | LegendComponentOption
  | TooltipComponentOption
>;

/** 运营后台图表用的浅色系配色（与旧 SVG 图表保持一致的系列色）。 */
export const CHART_FONT_FAMILY = "'Segoe UI', 'Microsoft YaHei', 'PingFang SC', sans-serif";

interface ChartPalette {
  axisLabel: string;
  splitLine: string;
  tooltipBg: string;
  tooltipText: string;
  centerText: string;
  centerMuted: string;
}

const LIGHT_PALETTE: ChartPalette = {
  axisLabel: '#64748b',
  splitLine: 'rgba(100, 116, 139, 0.22)',
  tooltipBg: 'rgba(15, 23, 42, 0.92)',
  tooltipText: '#e2e8f0',
  centerText: '#334155',
  centerMuted: '#64748b'
};

const DARK_PALETTE: ChartPalette = {
  axisLabel: '#8fa8c7',
  splitLine: 'rgba(148, 197, 255, 0.14)',
  tooltipBg: 'rgba(8, 20, 38, 0.88)',
  tooltipText: '#d7e7ff',
  centerText: '#d7e7ff',
  centerMuted: '#8fa8c7'
};

export interface EChartSeries {
  name: string;
  values: number[];
  color: string;
  /** 单系列覆盖整体 kind（用于柱+线组合图） */
  kind?: ChartKind;
}

export type ChartKind = 'line' | 'area' | 'bar';

/**
 * 折线 / 面积 / 柱状通用 option（替代旧 utils/charts.ts 的 SVG 拼串）。
 * labels 为 x 轴类目；series 与旧 LineSeries 形状一致，便于视图层平移。
 * dark: 大屏暗色轴/悬浮配色；horizontal: 横向条形（类目在 y 轴，竞品榜单式）。
 */
export function seriesOption(opts: {
  labels: string[];
  series: EChartSeries[];
  kind?: ChartKind;
  formatY?: (v: number) => string;
  formatValue?: (v: number) => string;
  dark?: boolean;
  horizontal?: boolean;
}): EChartsOption {
  const { labels, kind = 'line', formatY = (v) => String(Math.round(v)), dark = false } = opts;
  const fmtValue = opts.formatValue ?? formatY;
  const palette = dark ? DARK_PALETTE : LIGHT_PALETTE;
  const valueAxis = {
    type: 'value' as const,
    axisLabel: {
      color: palette.axisLabel,
      fontSize: 11,
      fontFamily: CHART_FONT_FAMILY,
      formatter: (v: number) => fmtY(v)
    },
    axisLine: { show: false },
    axisTick: { show: false },
    splitLine: { lineStyle: { color: palette.splitLine, type: 'dashed' as const } }
  };
  const categoryAxis = {
    type: 'category' as const,
    data: labels,
    boundaryGap: kind === 'bar',
    axisLabel: { color: palette.axisLabel, fontSize: 11, fontFamily: CHART_FONT_FAMILY },
    axisLine: { show: kind !== 'bar', lineStyle: { color: palette.splitLine } },
    axisTick: { show: false }
  };
  return {
    grid: { left: 8, right: 20, top: 24, bottom: 4, containLabel: true },
    tooltip: {
      trigger: 'axis',
      backgroundColor: palette.tooltipBg,
      borderWidth: 0,
      textStyle: { color: palette.tooltipText, fontSize: 12, fontFamily: CHART_FONT_FAMILY },
      axisPointer:
        kind === 'bar'
          ? {
              type: 'shadow',
              shadowStyle: {
                color: dark ? 'rgba(56, 189, 248, 0.08)' : 'rgba(100, 116, 139, 0.08)'
              }
            }
          : { type: 'line' },
      valueFormatter: (v) => fmtValue(Number(v))
    },
    xAxis: opts.horizontal ? valueAxis : categoryAxis,
    yAxis: opts.horizontal
      ? { ...categoryAxis, boundaryGap: true, axisLine: { show: false } }
      : valueAxis,
    series: opts.series.map((s) => {
      const k = s.kind ?? kind;
      return k === 'bar'
        ? {
            type: 'bar' as const,
            name: s.name,
            data: s.values,
            itemStyle: {
              color: barGradient(s.color),
              borderRadius: opts.horizontal ? [0, 8, 8, 0] : [6, 6, 0, 0]
            },
            barMaxWidth: opts.horizontal ? 14 : 28
          }
        : {
            type: 'line' as const,
            name: s.name,
            data: s.values,
            smooth: true,
            symbol: 'circle',
            symbolSize: 6,
            itemStyle: { color: s.color },
            lineStyle: { width: 2.5, color: s.color },
            areaStyle:
              k === 'area'
                ? {
                    color: {
                      type: 'linear',
                      x: 0,
                      y: 0,
                      x2: 0,
                      y2: 1,
                      colorStops: [
                        { offset: 0, color: withAlpha(s.color, 0.35) },
                        { offset: 1, color: withAlpha(s.color, 0.02) }
                      ]
                    }
                  }
                : undefined
          };
    })
  };
}

/** 柱条竖向渐变：顶部实色 → 底部半透明（竞品大屏通用做法）。 */
function barGradient(color: string) {
  return {
    type: 'linear' as const,
    x: 0,
    y: 0,
    x2: 0,
    y2: 1,
    colorStops: [
      { offset: 0, color: color },
      { offset: 1, color: withAlpha(color, 0.25) }
    ]
  };
}

/** 数值缩写：1234 → 1.2k，用于 y 轴刻度。 */
function fmtY(v: number): string {
  const abs = Math.abs(v);
  if (abs >= 1_000_000) return `${(v / 1_000_000).toFixed(1)}M`;
  if (abs >= 1000) return `${(v / 1000).toFixed(1)}k`;
  return `${v}`;
}

function withAlpha(hex: string, alpha: number): string {
  const m = /^#([0-9a-f]{6})$/i.exec(hex.trim());
  if (!m) return hex;
  const n = Number.parseInt(m[1], 16);
  return `rgba(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255}, ${alpha})`;
}

export interface DonutPart {
  label: string;
  value: number;
  color: string;
}

/** 大屏环形图默认色板（HTML 图例与图表共用，保证两侧颜色一致）。 */
export const DONUT_PALETTE = ['#2dd4bf', '#38bdf8', '#a78bfa', '#fbbf24', '#f472b6', '#4ade80'];

export function donutColor(index: number): string {
  return DONUT_PALETTE[index % DONUT_PALETTE.length];
}

/** 环形图 option（替代旧 buildDonutChart），中心为合计文本。dark: 大屏暗色文字。 */
export function donutOption(opts: {
  parts: DonutPart[];
  /** 中心合计文本；不传则显示原始合计 */
  formatCenter?: (total: number) => string;
  /** 悬浮数值列格式；默认原值 */
  formatValue?: (value: number) => string;
  /** 悬浮数值列标题 */
  valueLabel?: string;
  /** 图内半径布局（百分比），默认给中心文本留空间 */
  radius?: [string, string];
  dark?: boolean;
}): EChartsOption {
  const {
    parts,
    formatCenter = (t) => String(t),
    formatValue = (v) => String(v),
    valueLabel = '数值',
    radius = ['52%', '76%'],
    dark = false
  } = opts;
  const palette = dark ? DARK_PALETTE : LIGHT_PALETTE;
  const total = parts.reduce((s, p) => s + Math.max(p.value, 0), 0);
  return {
    tooltip: {
      trigger: 'item',
      backgroundColor: palette.tooltipBg,
      borderWidth: 0,
      textStyle: { color: palette.tooltipText, fontSize: 12, fontFamily: CHART_FONT_FAMILY },
      formatter: (p) => {
        const q = (Array.isArray(p) ? p[0] : p) as {
          name?: string;
          value?: unknown;
          percent?: number;
        };
        return `${q.name ?? ''}<br/>${valueLabel}：<b>${formatValue(Number(q.value ?? 0))}</b>（${q.percent ?? 0}%）`;
      }
    },
    title: {
      text: formatCenter(total),
      subtext: '合计',
      left: 'center',
      top: '38%',
      itemGap: 4,
      textStyle: {
        fontSize: 20,
        fontWeight: 700,
        color: palette.centerText,
        fontFamily: CHART_FONT_FAMILY
      },
      subtextStyle: {
        fontSize: 11,
        color: palette.centerMuted,
        fontFamily: CHART_FONT_FAMILY
      }
    },
    series: [
      {
        type: 'pie',
        radius,
        center: ['50%', '50%'],
        avoidLabelOverlap: true,
        itemStyle: { borderColor: dark ? '#07111f' : '#fff', borderWidth: 2 },
        label: { show: false },
        emphasis: {
          scaleSize: 6,
          itemStyle: {
            shadowBlur: 16,
            shadowColor: 'rgba(45, 212, 191, 0.4)'
          }
        },
        data: parts.map((p) => ({ name: p.label, value: p.value, itemStyle: { color: p.color } }))
      }
    ]
  };
}

/** 运营大屏暗色主题：注册一次，EChart 组件通过 theme="bigscreen-dark" 引用。 */
export const BIGSCREEN_THEME = 'bigscreen-dark';

let bigScreenThemeRegistered = false;

export function registerBigScreenTheme(): void {
  if (bigScreenThemeRegistered) return;
  bigScreenThemeRegistered = true;
  echarts.registerTheme(BIGSCREEN_THEME, {
    textStyle: { fontFamily: CHART_FONT_FAMILY },
    color: ['#2dd4bf', '#38bdf8', '#a78bfa', '#fbbf24', '#f472b6', '#4ade80'],
    backgroundColor: 'transparent',
    axisPointer: {
      lineStyle: { color: 'rgba(148, 197, 255, 0.4)' }
    }
  });
}

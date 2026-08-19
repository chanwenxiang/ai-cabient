<template>
  <div ref="hostRef" class="chart-host" @pointermove="onMove" @pointerleave="hide">
    <div class="chart-box" :class="{ donut: donut }" v-html="safeSvg" />
    <Teleport to="body">
      <div
        v-if="tip.show"
        class="chart-float-tip"
        :style="{ left: tip.x + 'px', top: tip.y + 'px' }"
        role="tooltip"
      >
        <div v-if="tip.title" class="tip-title">{{ tip.title }}</div>
        <div v-for="(row, i) in tip.rows" :key="i" class="tip-row">
          <i v-if="row.color" :style="{ background: row.color }" />
          <span class="tip-name">{{ row.name }}</span>
          <strong class="tip-val">{{ row.value }}</strong>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue';
import { sanitizeChartSvg } from '@/utils/charts';

const props = defineProps<{
  svg: string;
  donut?: boolean;
}>();

const safeSvg = computed(() => sanitizeChartSvg(props.svg));

const hostRef = ref<HTMLElement | null>(null);
const tip = reactive({
  show: false,
  x: 0,
  y: 0,
  title: '',
  rows: [] as { name: string; value: string; color?: string }[]
});

let activeEl: Element | null = null;

function clearActive() {
  if (activeEl) {
    activeEl.classList.remove('is-active');
    // highlight matching points by index
    const host = hostRef.value;
    if (host) {
      host.querySelectorAll('.chart-pt.is-lit').forEach((n) => n.classList.remove('is-lit'));
    }
    activeEl = null;
  }
}

function parseTip(raw: string) {
  const lines = raw.split('\n').filter(Boolean);
  const title = lines[0] || '';
  const rows = lines.slice(1).map((line) => {
    const [name, value, color] = line.split('|');
    return { name: name || '', value: value || '', color: color || undefined };
  });
  return { title, rows };
}

function hide() {
  tip.show = false;
  clearActive();
}

function onMove(e: PointerEvent) {
  const target = e.target as Element | null;
  if (!target || !hostRef.value) {
    hide();
    return;
  }
  const hit = target.closest('[data-tip]') as HTMLElement | null;
  if (!hit || !hostRef.value.contains(hit)) {
    hide();
    return;
  }

  if (activeEl !== hit) {
    clearActive();
    activeEl = hit;
    hit.classList.add('is-active');
    // column hover lights all series points at same x index
    if (hit.classList.contains('chart-col')) {
      const i = hit.getAttribute('data-i');
      hostRef.value.querySelectorAll(`.chart-pt[data-i="${i}"]`).forEach((pt) => {
        pt.classList.add('is-lit');
      });
    }
  }

  const parsed = parseTip(hit.getAttribute('data-tip') || '');
  tip.title = parsed.title;
  tip.rows = parsed.rows;
  tip.show = true;

  const pad = 14;
  const tw = 200;
  const th = 24 + tip.rows.length * 22;
  let x = e.clientX + pad;
  let y = e.clientY + pad;
  if (x + tw > window.innerWidth - 8) x = e.clientX - tw - pad;
  if (y + th > window.innerHeight - 8) y = e.clientY - th - pad;
  tip.x = Math.max(8, x);
  tip.y = Math.max(8, y);
}

watch(
  () => hostRef.value,
  () => hide()
);

onBeforeUnmount(hide);
</script>

<style scoped>
.chart-host {
  position: relative;
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
}
.chart-box {
  width: 100%;
  max-width: 100%;
  padding: 0;
}
.chart-box:not(.donut) {
  aspect-ratio: 640 / 260;
  max-height: 268px;
  min-height: 200px;
}
.chart-box.donut {
  width: 168px;
  height: 168px;
  flex-shrink: 0;
}
.chart-box :deep(svg) {
  width: 100%;
  height: 100%;
  display: block;
}

/* Column / crosshair */
.chart-box :deep(.chart-col-hit) {
  cursor: crosshair;
}
.chart-box :deep(.chart-col.is-active .chart-crosshair) {
  stroke-opacity: 0.55;
}
.chart-box :deep(.chart-col.is-active .chart-col-hit) {
  fill: color-mix(in srgb, var(--app-primary, #2dd4bf) 8%, transparent);
}

/* Line points lit on column hover */
.chart-box :deep(.chart-pt .chart-pt-dot) {
  transition:
    r 0.12s ease,
    stroke-width 0.12s ease;
}
.chart-box :deep(.chart-pt.is-lit .chart-pt-halo) {
  r: 9;
  fill-opacity: 0.45;
}
.chart-box :deep(.chart-pt.is-lit .chart-pt-dot) {
  r: 4.8;
  stroke-width: 2;
  filter: drop-shadow(0 0 4px rgba(45, 212, 191, 0.45));
}

/* Bars */
.chart-box :deep(.chart-bar) {
  cursor: pointer;
  transition:
    opacity 0.12s ease,
    filter 0.12s ease;
}
.chart-box :deep(.chart-bar:hover),
.chart-box :deep(.chart-bar.is-active) {
  opacity: 1;
  filter: brightness(1.18) drop-shadow(0 2px 6px rgba(0, 0, 0, 0.28));
}
.chart-box :deep(.chart-interactive:hover .chart-bar:not(:hover):not(.is-active)) {
  opacity: 0.45;
}

/* Donut arcs */
.chart-box :deep(.chart-arc) {
  cursor: pointer;
  transition:
    stroke-width 0.15s ease,
    opacity 0.15s ease,
    filter 0.15s ease;
}
.chart-box :deep(.chart-arc:hover),
.chart-box :deep(.chart-arc.is-active) {
  stroke-width: 38;
  filter: brightness(1.12) drop-shadow(0 0 8px rgba(45, 212, 191, 0.35));
}
.chart-box :deep(.chart-interactive:hover .chart-arc:not(:hover):not(.is-active)) {
  opacity: 0.4;
}
</style>

<style>
/* Teleported tooltip — unscoped */
.chart-float-tip {
  position: fixed;
  z-index: 4000;
  min-width: 132px;
  max-width: 240px;
  padding: 10px 12px;
  border-radius: 10px;
  pointer-events: none;
  color: var(--layout-text, #e2e8f0);
  background: color-mix(in srgb, var(--layout-card-bg, #0f172a) 92%, #000);
  border: 1px solid var(--layout-border, #334155);
  box-shadow: 0 12px 28px rgba(0, 0, 0, 0.35);
  backdrop-filter: blur(8px);
  font-size: 12px;
  line-height: 1.4;
}
.chart-float-tip .tip-title {
  margin-bottom: 6px;
  font-weight: 600;
  color: var(--layout-muted, #94a3b8);
  font-size: 11px;
  letter-spacing: 0.02em;
}
.chart-float-tip .tip-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 4px;
}
.chart-float-tip .tip-row i {
  width: 8px;
  height: 8px;
  border-radius: 999px;
  flex-shrink: 0;
}
.chart-float-tip .tip-name {
  flex: 1;
  color: var(--layout-muted, #94a3b8);
}
.chart-float-tip .tip-val {
  font-variant-numeric: tabular-nums;
  font-weight: 600;
}
</style>

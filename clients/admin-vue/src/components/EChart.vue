<template>
  <div class="echart-host" :style="hostStyle">
    <div v-if="loading" class="echart-state echart-skeleton" aria-live="polite">
      <div class="skel-bar" />
      <div class="skel-bar short" />
      <div class="skel-bar mid" />
      <output class="echart-state-text">图表{{ UI_COPY.loading }}</output>
    </div>
    <div v-else-if="error" class="echart-state echart-error" role="alert">
      <span class="echart-state-text">{{ error }}</span>
      <el-button type="primary" link @click="emit('retry')">重试</el-button>
    </div>
    <div v-else-if="!option" class="echart-state echart-empty">
      <span class="echart-state-text">{{ emptyText || '暂无数据' }}</span>
    </div>
    <div v-show="!loading && !!option" ref="hostRef" class="echart-canvas" />
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { UI_COPY } from '@aicabinet/shared-uni/ui-copy';
import { echarts, type EChartsOption } from '@/utils/echarts';

const props = withDefaults(
  defineProps<{
    /** 图表配置；null 视为暂无数据 */
    option: EChartsOption | null;
    /** 加载中骨架 */
    loading?: boolean;
    /** 失败文案；有值时展示重试 */
    error?: string;
    emptyText?: string;
    /** echarts 注册主题名（如 bigscreen-dark） */
    theme?: string;
    /** 画布高度 px；不传时占满父容器 */
    height?: number;
    /** 画布宽度 px；不传时占满父容器（环形图在 auto 网格列里需要显式宽） */
    width?: number;
  }>(),
  {
    loading: false,
    error: '',
    emptyText: '',
    theme: undefined,
    height: undefined,
    width: undefined
  }
);

const emit = defineEmits<{ retry: [] }>();

const hostStyle = computed(() => ({
  ...(props.height != null ? { height: `${props.height}px` } : {}),
  ...(props.width != null ? { width: `${props.width}px` } : {})
}));

const hostRef = ref<HTMLDivElement | null>(null);
let chart: echarts.ECharts | null = null;
let resizeObserver: ResizeObserver | null = null;

function ensureChart() {
  if (chart || !hostRef.value) return;
  chart = echarts.init(hostRef.value, props.theme);
  resizeObserver = new ResizeObserver(() => chart?.resize());
  resizeObserver.observe(hostRef.value);
}

function render(option: EChartsOption | null) {
  if (!chart || !option) return;
  chart.setOption(option, { notMerge: true });
}

onMounted(() => {
  ensureChart();
  render(props.option);
});

watch(
  () => props.option,
  (opt) => {
    ensureChart();
    render(opt);
  }
);

onBeforeUnmount(() => {
  resizeObserver?.disconnect();
  resizeObserver = null;
  chart?.dispose();
  chart = null;
});
</script>

<style scoped>
.echart-host {
  width: 100%;
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.echart-canvas {
  flex: 1;
  min-height: 0;
  width: 100%;
}
.echart-state {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  min-height: 120px;
}
.echart-state-text {
  color: var(--layout-muted);
  font-size: var(--admin-font-size-table);
}
.echart-error {
  color: var(--el-color-danger, #ef4444);
}
.skel-bar {
  width: 70%;
  height: 12px;
  border-radius: 6px;
  background: linear-gradient(
    90deg,
    var(--layout-border) 25%,
    color-mix(in srgb, var(--layout-border) 55%, var(--layout-card)) 50%,
    var(--layout-border) 75%
  );
  background-size: 200% 100%;
  animation: echart-skel 1.4s ease infinite;
}
.skel-bar.short {
  width: 40%;
}
.skel-bar.mid {
  width: 55%;
}
@keyframes echart-skel {
  from {
    background-position: 200% 0;
  }
  to {
    background-position: -200% 0;
  }
}
</style>

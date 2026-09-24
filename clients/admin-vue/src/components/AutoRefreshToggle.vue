<template>
  <div class="auto-refresh" :title="hint">
    <el-switch
      :model-value="enabled"
      size="small"
      aria-label="自动刷新"
      @update:model-value="onSwitch"
    />
    <span class="auto-refresh__label">自动刷新</span>
    <template v-if="enabled">
      <span class="auto-refresh__count" :class="{ 'is-busy': refreshing }">{{ countdown }}s</span>
      <el-select
        v-model="intervalSec"
        size="small"
        class="auto-refresh__interval"
        aria-label="自动刷新间隔"
      >
        <el-option v-for="s in intervals" :key="s" :label="`${s} 秒`" :value="s" />
      </el-select>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { useAutoRefresh } from '@/composables/useAutoRefresh';

/**
 * 列表页工具条上的「自动刷新」开关 —— 默认关闭，按传入的 storageKey 记住选择。
 *
 * `refresh` 应传**静默刷新**（不闪 loading、不清选择）：自动刷新是后台行为，
 * 每次到点都把运营的勾选/滚动冲掉等于不能同时用。
 */
const props = defineProps<{
  storageKey: string;
  refresh: () => unknown | Promise<unknown>;
}>();

const { enabled, intervalSec, intervals, countdown, refreshing, setEnabled, refreshNow } =
  useAutoRefresh({ storageKey: props.storageKey, refresh: () => props.refresh() });

const hint = computed(() =>
  enabled.value
    ? `每 ${intervalSec.value} 秒静默拉取一次；标签页切到后台自动暂停，点击倒计时可立即刷新`
    : '开启后按设定间隔静默拉取最新数据（不打断当前勾选与分页）'
);

function onSwitch(next: string | number | boolean) {
  const on = Boolean(next);
  setEnabled(on);
  // 刚打开就先拉一把，省得运营盯着旧数据等一个间隔
  if (on) refreshNow();
}

defineExpose({ refreshNow });
</script>

<style scoped>
.auto-refresh {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.auto-refresh__label {
  font-size: 13px;
  color: var(--el-text-color-regular);
  white-space: nowrap;
}
.auto-refresh__count {
  min-width: 34px;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  text-align: right;
  color: var(--el-text-color-secondary);
}
.auto-refresh__count.is-busy {
  opacity: 0.45;
}
.auto-refresh__interval {
  width: 88px;
}
</style>

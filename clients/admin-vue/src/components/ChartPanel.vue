<template>
  <el-card shadow="never" class="chart-panel" :class="{ 'is-fill': fill }">
    <template v-if="title || $slots.actions" #header>
      <div class="chart-panel-head">
        <div class="chart-panel-title">
          <strong v-if="title">{{ title }}</strong>
          <span v-if="hint" class="chart-panel-hint">{{ hint }}</span>
        </div>
        <div v-if="$slots.actions" class="chart-panel-actions">
          <slot name="actions" />
        </div>
      </div>
    </template>
    <div class="chart-panel-body" :class="{ 'is-donut': donut, 'is-compact': compact }">
      <slot />
    </div>
    <div v-if="$slots.footer" class="chart-panel-foot">
      <slot name="footer" />
    </div>
  </el-card>
</template>

<script setup lang="ts">
defineProps<{
  title?: string;
  hint?: string;
  /** 在侧栏堆叠时占满剩余高度 */
  fill?: boolean;
  /** 环形图专用布局 */
  donut?: boolean;
  /** 描述列表等紧凑内容 */
  compact?: boolean;
}>();
</script>

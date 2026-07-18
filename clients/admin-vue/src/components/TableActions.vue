<template>
  <div class="table-actions" :class="{ 'is-label': showLabel }" @click.stop>
    <template v-for="act in primary" :key="act.key">
      <el-tooltip :content="act.label" placement="top" :show-after="120" :hide-after="0" :disabled="showLabel">
        <span class="action-icon-wrap" :class="{ 'is-disabled': act.disabled }">
          <button
            type="button"
            class="action-icon-btn"
            :class="[act.type ? `is-${act.type}` : '', { 'is-with-label': showLabel }]"
            :disabled="act.disabled"
            :aria-label="act.label"
            @click="emit('action', act.key)"
          >
            <el-icon><component :is="act.icon" /></el-icon>
            <span v-if="showLabel" class="action-label">{{ act.label }}</span>
          </button>
        </span>
      </el-tooltip>
    </template>

    <el-dropdown v-if="more.length" trigger="click" @command="(k: string) => emit('action', k)">
      <span class="action-icon-wrap" title="更多操作">
        <button
          type="button"
          class="action-icon-btn"
          :class="{ 'is-with-label': showLabel }"
          aria-label="更多操作"
        >
          <el-icon><MoreFilled /></el-icon>
          <span v-if="showLabel" class="action-label">更多</span>
        </button>
      </span>
      <template #dropdown>
        <el-dropdown-menu>
          <el-dropdown-item
            v-for="act in more"
            :key="act.key"
            :command="act.key"
            :divided="act.divided"
            :disabled="act.disabled"
          >
            <el-icon v-if="act.icon" class="menu-ico"><component :is="act.icon" /></el-icon>
            {{ act.label }}
          </el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>
  </div>
</template>

<script setup lang="ts">
import { computed, type Component } from 'vue';
import { MoreFilled } from '@element-plus/icons-vue';
import { useSettingsStore } from '@/stores/settings';

export interface TableAction {
  key: string;
  label: string;
  icon: Component;
  type?: 'primary' | 'success' | 'warning' | 'danger' | 'info';
  disabled?: boolean;
  divided?: boolean;
  /** 放入「更多」下拉；默认主按钮最多 maxPrimary 个 */
  overflow?: boolean;
}

const props = withDefaults(
  defineProps<{
    actions: TableAction[];
    maxPrimary?: number;
  }>(),
  { maxPrimary: 3 }
);

const emit = defineEmits<{ action: [key: string] }>();
const settings = useSettingsStore();

const showLabel = computed(() => settings.tableActionMode === 'label');
const effectiveMax = computed(() =>
  showLabel.value ? Math.min(props.maxPrimary, 2) : props.maxPrimary
);

const visible = computed(() => props.actions.filter((a) => a && a.key));

const primary = computed(() => {
  const main = visible.value.filter((a) => !a.overflow);
  // 全部被标为 overflow 时，仍展示前 N 个为主按钮，避免只剩「更多」
  const pool = main.length ? main : visible.value;
  return pool.slice(0, effectiveMax.value);
});

const more = computed(() => {
  const keys = new Set(primary.value.map((a) => a.key));
  return visible.value.filter((a) => !keys.has(a.key));
});
</script>

<style scoped>
.table-actions {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  flex-wrap: nowrap;
}
.table-actions.is-label {
  gap: 6px;
}
.action-icon-wrap {
  display: inline-flex;
  line-height: 1;
}
.action-icon-wrap.is-disabled {
  cursor: not-allowed;
}
.action-icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  margin: 0;
  padding: 0;
  border: 1px solid var(--layout-border, #334155);
  border-radius: 8px;
  /* 实心底：避免 fixed 操作列上半透明按钮透视出时间文字 */
  background: var(--layout-card, #1a2332);
  color: var(--layout-text, #e2e8f0);
  cursor: pointer;
  transition: color 0.15s ease, background 0.15s ease, border-color 0.15s ease, transform 0.15s ease,
    box-shadow 0.15s ease;
}
.action-icon-btn.is-with-label {
  width: auto;
  min-width: 32px;
  padding: 0 8px;
  gap: 4px;
}
.action-label {
  font-size: 12px;
  line-height: 1;
  white-space: nowrap;
}
.action-icon-wrap:hover .action-icon-btn:not(:disabled),
.action-icon-btn:hover:not(:disabled),
.action-icon-btn:focus-visible:not(:disabled) {
  color: var(--app-primary, #0f766e);
  background: color-mix(in srgb, var(--app-primary, #0f766e) 28%, var(--layout-card, #1a2332));
  border-color: color-mix(in srgb, var(--app-primary, #0f766e) 55%, var(--layout-border, #334155));
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--app-primary, #0f766e) 22%, transparent);
  transform: translateY(-1px);
  outline: none;
}
.action-icon-wrap:hover .action-icon-btn.is-primary:not(:disabled),
.action-icon-btn.is-primary:hover:not(:disabled),
.action-icon-btn.is-primary:focus-visible:not(:disabled) {
  color: #5eead4;
  background: color-mix(in srgb, #14b8a6 32%, var(--layout-card, #1a2332));
  border-color: color-mix(in srgb, #14b8a6 55%, var(--layout-border, #334155));
  box-shadow: 0 0 0 2px color-mix(in srgb, #14b8a6 20%, transparent);
}
.action-icon-wrap:hover .action-icon-btn.is-success:not(:disabled),
.action-icon-btn.is-success:hover:not(:disabled),
.action-icon-btn.is-success:focus-visible:not(:disabled) {
  color: #34d399;
  background: color-mix(in srgb, #34d399 28%, var(--layout-card, #1a2332));
  border-color: color-mix(in srgb, #34d399 50%, var(--layout-border, #334155));
  box-shadow: 0 0 0 2px color-mix(in srgb, #34d399 18%, transparent);
}
.action-icon-wrap:hover .action-icon-btn.is-warning:not(:disabled),
.action-icon-btn.is-warning:hover:not(:disabled),
.action-icon-btn.is-warning:focus-visible:not(:disabled) {
  color: #fbbf24;
  background: color-mix(in srgb, #fbbf24 28%, var(--layout-card, #1a2332));
  border-color: color-mix(in srgb, #fbbf24 50%, var(--layout-border, #334155));
  box-shadow: 0 0 0 2px color-mix(in srgb, #fbbf24 18%, transparent);
}
.action-icon-wrap:hover .action-icon-btn.is-danger:not(:disabled),
.action-icon-btn.is-danger:hover:not(:disabled),
.action-icon-btn.is-danger:focus-visible:not(:disabled) {
  color: #f87171;
  background: color-mix(in srgb, #f87171 28%, var(--layout-card, #1a2332));
  border-color: color-mix(in srgb, #f87171 50%, var(--layout-border, #334155));
  box-shadow: 0 0 0 2px color-mix(in srgb, #f87171 18%, transparent);
}
.action-icon-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
  transform: none;
}
.action-icon-btn .el-icon {
  font-size: 16px;
}
.menu-ico {
  margin-right: 6px;
  vertical-align: middle;
}
</style>

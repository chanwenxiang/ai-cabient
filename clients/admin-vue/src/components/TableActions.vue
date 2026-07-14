<template>
  <div class="table-actions" @click.stop>
    <template v-for="act in primary" :key="act.key">
      <el-tooltip :content="act.label" placement="top" :show-after="120" :hide-after="0">
        <span class="action-icon-wrap" :class="{ 'is-disabled': act.disabled }">
          <button
            type="button"
            class="action-icon-btn"
            :class="act.type ? `is-${act.type}` : ''"
            :disabled="act.disabled"
            :aria-label="act.label"
            @click="emit('action', act.key)"
          >
            <el-icon><component :is="act.icon" /></el-icon>
          </button>
        </span>
      </el-tooltip>
    </template>

    <el-dropdown v-if="more.length" trigger="click" @command="(k: string) => emit('action', k)">
      <span class="action-icon-wrap" title="更多操作">
        <button type="button" class="action-icon-btn" aria-label="更多操作">
          <el-icon><MoreFilled /></el-icon>
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

const visible = computed(() => props.actions.filter((a) => a && a.key));

const primary = computed(() => {
  const main = visible.value.filter((a) => !a.overflow);
  return main.slice(0, props.maxPrimary);
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
  border: 1px solid color-mix(in srgb, var(--layout-border, #334155) 80%, transparent);
  border-radius: 8px;
  background: color-mix(in srgb, var(--layout-hover, #1e293b) 55%, transparent);
  color: var(--layout-text, #e2e8f0);
  cursor: pointer;
  transition: color 0.15s ease, background 0.15s ease, border-color 0.15s ease, transform 0.15s ease,
    box-shadow 0.15s ease;
}
.action-icon-wrap:hover .action-icon-btn:not(:disabled),
.action-icon-btn:hover:not(:disabled),
.action-icon-btn:focus-visible:not(:disabled) {
  color: var(--app-primary, #3b82f6);
  background: color-mix(in srgb, var(--app-primary, #3b82f6) 22%, transparent);
  border-color: color-mix(in srgb, var(--app-primary, #3b82f6) 55%, transparent);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--app-primary, #3b82f6) 18%, transparent);
  transform: translateY(-1px);
  outline: none;
}
.action-icon-wrap:hover .action-icon-btn.is-success:not(:disabled),
.action-icon-btn.is-success:hover:not(:disabled),
.action-icon-btn.is-success:focus-visible:not(:disabled) {
  color: #34d399;
  background: rgba(52, 211, 153, 0.18);
  border-color: rgba(52, 211, 153, 0.45);
  box-shadow: 0 0 0 2px rgba(52, 211, 153, 0.16);
}
.action-icon-wrap:hover .action-icon-btn.is-warning:not(:disabled),
.action-icon-btn.is-warning:hover:not(:disabled),
.action-icon-btn.is-warning:focus-visible:not(:disabled) {
  color: #fbbf24;
  background: rgba(251, 191, 36, 0.18);
  border-color: rgba(251, 191, 36, 0.45);
  box-shadow: 0 0 0 2px rgba(251, 191, 36, 0.16);
}
.action-icon-wrap:hover .action-icon-btn.is-danger:not(:disabled),
.action-icon-btn.is-danger:hover:not(:disabled),
.action-icon-btn.is-danger:focus-visible:not(:disabled) {
  color: #f87171;
  background: rgba(248, 113, 113, 0.18);
  border-color: rgba(248, 113, 113, 0.45);
  box-shadow: 0 0 0 2px rgba(248, 113, 113, 0.16);
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

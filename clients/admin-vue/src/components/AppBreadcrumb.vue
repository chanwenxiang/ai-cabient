<template>
  <div ref="hostRef" class="app-breadcrumb-host">
    <el-breadcrumb v-if="!compact" separator="/" class="app-breadcrumb">
      <el-breadcrumb-item :to="{ path: '/dashboard' }">首页</el-breadcrumb-item>
      <el-breadcrumb-item v-if="group">{{ group }}</el-breadcrumb-item>
      <el-breadcrumb-item v-if="parentTitle" :to="parentPath">{{ parentTitle }}</el-breadcrumb-item>
      <el-breadcrumb-item>{{ currentTitle }}</el-breadcrumb-item>
    </el-breadcrumb>
    <span v-else class="app-breadcrumb-compact" :title="fullTrail">{{ currentTitle }}</span>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue';
import { useRoute } from 'vue-router';
import { findNavByPath } from '@/config/menu';

const route = useRoute();
const hostRef = ref<HTMLElement | null>(null);
const compact = ref(false);

const nav = computed(() => {
  const hit = findNavByPath(route.path);
  if (hit) return hit as { group?: string; title?: string; parentTitle?: string; parentPath?: string };
  return {
    group: (route.meta.group as string) || '',
    title: (route.meta.title as string) || route.path,
    parentTitle: route.meta.parentTitle as string | undefined,
    parentPath: route.meta.parentPath as string | undefined
  };
});

const group = computed(() => nav.value.group);
const currentTitle = computed(() => nav.value.title || (route.meta.title as string));
const parentTitle = computed(() => nav.value.parentTitle);
const parentPath = computed(() => nav.value.parentPath);

const fullTrail = computed(() => {
  const parts = ['首页'];
  if (group.value) parts.push(group.value);
  if (parentTitle.value) parts.push(parentTitle.value);
  parts.push(currentTitle.value);
  return parts.join(' / ');
});

let observer: ResizeObserver | null = null;

function updateCompact() {
  const el = hostRef.value;
  if (!el) return;
  compact.value = el.clientWidth < 300;
}

onMounted(() => {
  updateCompact();
  if (typeof ResizeObserver !== 'undefined') {
    observer = new ResizeObserver(updateCompact);
    if (hostRef.value) observer.observe(hostRef.value);
  } else {
    window.addEventListener('resize', updateCompact, { passive: true });
  }
});

onUnmounted(() => {
  observer?.disconnect();
  window.removeEventListener('resize', updateCompact);
});
</script>

<style scoped>
.app-breadcrumb-host {
  min-width: 0;
  flex: 1;
  overflow: hidden;
}

.app-breadcrumb {
  margin: 0;
  font-size: 13px;
  line-height: 1.2;
  width: 100%;
  min-width: 0;
  overflow: hidden;
}

.app-breadcrumb :deep(.el-breadcrumb) {
  display: flex;
  flex-wrap: nowrap;
  align-items: center;
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
}

.app-breadcrumb :deep(.el-breadcrumb__item) {
  display: inline-flex;
  align-items: center;
  flex: 0 0 auto;
  float: none;
  max-width: 42%;
}

.app-breadcrumb :deep(.el-breadcrumb__item:last-child) {
  flex: 1 1 auto;
  min-width: 0;
  max-width: none;
}

.app-breadcrumb :deep(.el-breadcrumb__inner),
.app-breadcrumb :deep(.el-breadcrumb__inner a) {
  display: block;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 100%;
}

.app-breadcrumb :deep(.el-breadcrumb__separator) {
  flex: 0 0 auto;
}

.app-breadcrumb-compact {
  display: block;
  font-size: 13px;
  font-weight: 600;
  line-height: 1.2;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  color: var(--layout-text);
}
</style>

<template>
  <el-breadcrumb separator="/" class="app-breadcrumb">
    <el-breadcrumb-item :to="{ path: '/dashboard' }">首页</el-breadcrumb-item>
    <el-breadcrumb-item v-if="group">{{ group }}</el-breadcrumb-item>
    <el-breadcrumb-item v-if="parentTitle" :to="parentPath">{{ parentTitle }}</el-breadcrumb-item>
    <el-breadcrumb-item>{{ currentTitle }}</el-breadcrumb-item>
  </el-breadcrumb>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import { findNavByPath } from '@/config/menu';

const route = useRoute();

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
</script>

<style scoped>
.app-breadcrumb {
  margin-bottom: 4px;
  font-size: 13px;
}
</style>

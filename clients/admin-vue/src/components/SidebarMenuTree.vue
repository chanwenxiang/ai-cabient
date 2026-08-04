<template>
  <template v-for="node in nodes" :key="node.key">
    <el-sub-menu v-if="node.children?.length" :index="node.key">
      <template #title>
        <el-icon><component :is="node.icon" /></el-icon>
        <span>{{ node.label }}</span>
      </template>
      <SidebarMenuTree :nodes="node.children" />
    </el-sub-menu>
    <el-menu-item
      v-else-if="node.path"
      :index="node.path"
      :title="node.label"
      :aria-label="node.label"
    >
      <el-icon><component :is="node.icon" /></el-icon>
      <template #title>{{ node.label }}</template>
    </el-menu-item>
  </template>
</template>

<script setup lang="ts">
import type { SidebarNode } from '@/config/sidebar';

defineOptions({ name: 'SidebarMenuTree' });

defineProps<{
  nodes: SidebarNode[];
}>();
</script>

<script setup lang="ts">
// 「仓储」顶级模块子页：复用 WarehouseView，仅渲染本页的 tab 子集（见 only 注释）
import WarehouseView from './WarehouseView.vue';
</script>

<template>
  <WarehouseView :only="['stocktakes', 'inventory', 'movements']" />
</template>

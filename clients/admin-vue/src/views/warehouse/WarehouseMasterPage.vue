<script setup lang="ts">
// 「仓储」顶级模块子页：复用 WarehouseView，仅渲染本页的 tab 子集（见 only 注释）
import WarehouseView from './WarehouseView.vue';
</script>

<template>
  <WarehouseView
    :only="['warehouses', 'bins']"
    hint="仓库主数据与货位：先订清有哪些仓、每个仓的存放位置怎么编"
  />
</template>

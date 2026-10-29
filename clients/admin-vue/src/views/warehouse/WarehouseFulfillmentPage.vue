<script setup lang="ts">
// 「仓储」顶级模块子页：复用 WarehouseView，仅渲染本页的 tab 子集（见 only 注释）
import WarehouseView from './WarehouseView.vue';
</script>

<template>
  <WarehouseView
    :only="['transfers', 'outbounds', 'transit']"
    hint="柜机侧履约：建出库单 → 备货 → 补货员领货 → 柜机确认上柜（自提自送，无运输段）"
  />
</template>

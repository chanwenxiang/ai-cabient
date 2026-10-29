<script setup lang="ts">
// 「仓储」顶级模块子页：复用 WarehouseView，仅渲染本页的 tab 子集（见 only 注释）
import WarehouseView from './WarehouseView.vue';
</script>

<template>
  <WarehouseView
    :only="['suppliers', 'purchase', 'suggestions', 'returns', 'payables']"
    hint="采购链路：供应商档案 → 下单 → 收货入库 → 建议 / 退货 → 应付账款"
  />
</template>

<template>
  <el-config-provider :locale="zhCn">
    <router-view />
  </el-config-provider>
</template>

<script setup lang="ts">
import { onMounted } from 'vue';
import zhCn from 'element-plus/es/locale/lang/zh-cn';
import { useBrandStore } from '@/stores/brand';

onMounted(() => {
  void useBrandStore().load();
  // A-P2-003：RBAC restore 只走 router.beforeEach（!rbacHydrated 时），避免与 App 双拉 /me/*
});
</script>

<template>
  <el-config-provider :locale="zhCn">
    <router-view />
  </el-config-provider>
</template>

<script setup lang="ts">
import { onMounted } from 'vue';
import zhCn from 'element-plus/es/locale/lang/zh-cn';
import { useAuthStore } from '@/stores/auth';
import { useBrandStore } from '@/stores/brand';

onMounted(() => {
  void useBrandStore().load();
  useAuthStore().restore();
});
</script>

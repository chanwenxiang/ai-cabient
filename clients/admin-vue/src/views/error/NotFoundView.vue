<template>
  <el-card class="page-card error-page" shadow="never">
    <el-result icon="warning" title="页面不存在" :sub-title="subTitle">
      <template #extra>
        <el-button type="primary" @click="goHome">返回工作台</el-button>
        <el-button @click="goBack">返回上一页</el-button>
      </template>
    </el-result>
  </el-card>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import { useErrorPageActions } from '@/composables/useErrorPageActions';

const route = useRoute();
const { goHome, goBack } = useErrorPageActions();

const subTitle = computed(() => {
  const path = route.path || '/';
  return `未找到「${path}」。可能是地址写错，或该菜单已下线。`;
});
</script>

<style scoped>
.error-page {
  min-height: 420px;
  display: flex;
  align-items: center;
  justify-content: center;
}
</style>

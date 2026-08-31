<template>
  <el-card class="page-card error-page" shadow="never">
    <el-result icon="error" title="无权访问" :sub-title="subTitle">
      <template #extra>
        <el-button type="primary" @click="goHome">返回工作台</el-button>
        <el-button @click="goBack">返回上一页</el-button>
        <el-button plain @click="goProfile">个人中心</el-button>
      </template>
    </el-result>
  </el-card>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useErrorPageActions } from '@/composables/useErrorPageActions';

const route = useRoute();
const router = useRouter();
const { goHome, goBack } = useErrorPageActions();

const fromPath = computed(() => String(route.query.from || '').trim());
const pageTitle = computed(() => String(route.query.title || '').trim());

const subTitle = computed(() => {
  const label = pageTitle.value || fromPath.value || '该页面';
  return `当前账号没有「${label}」的访问权限。如需开通，请联系管理员配置角色权限。`;
});

function goProfile() {
  router.push('/profile');
}
</script>

<style scoped>
.error-page {
  min-height: 420px;
  display: flex;
  align-items: center;
  justify-content: center;
}
</style>

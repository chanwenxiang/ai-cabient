<template>
  <template v-if="!failed">
    <slot />
  </template>
  <div v-else class="page-error-boundary">
    <el-result icon="error" title="本页出现异常" sub-title="页面渲染出错，其余功能不受影响。">
      <template #extra>
        <el-button type="primary" @click="recover">重载本页</el-button>
      </template>
    </el-result>
  </div>
</template>

<script setup lang="ts">
/**
 * 页面级错误边界（2026-10-14 审计补齐）：此前全仓无 errorHandler/onErrorCaptured，
 * 任一页面渲染抛错会冒泡到根 → 整站白屏。本组件包住 keep-alive 页面：
 * - onErrorCaptured 返回 false 阻断向上传播，只把当前页替换为兜底 UI；
 * - 路由切换自动复位（keep-alive 的 key 含路由，切页即重建子树）。
 * 全局兜底另见 main.ts 的 app.config.errorHandler（拦截边界之外的错误）。
 */
import { onErrorCaptured, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { adminDevWarn } from '@/utils/admin-dev-log';

const failed = ref(false);
const route = useRoute();

onErrorCaptured((err) => {
  failed.value = true;
  adminDevWarn('[admin] 页面渲染异常，已用兜底 UI 替代：', err);
  return false;
});

watch(
  () => route.fullPath,
  () => {
    failed.value = false;
  }
);

function recover() {
  failed.value = false;
}
</script>

<style scoped>
.page-error-boundary {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 320px;
}
</style>

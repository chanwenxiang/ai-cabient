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
import { ElMessage } from 'element-plus';
import { adminDevWarn } from '@/utils/admin-dev-log';
import { errorMessage, isUserDismiss } from '@/utils/error-message';

const failed = ref(false);
const route = useRoute();

/**
 * 只有「setup/渲染期」抛错才说明子树已不可用（白屏），整页替换兜底 UI 才是正确处置。
 * 事件处理（@click / emit 监听）、watcher、生命周期钩子的异常此前被同一钩子吞掉并整页替换 ——
 * 一次接口 403 或确认框点「取消」就会让运营丢掉正在填的表单/筛选/标签页（2026-11-10 R2-A1）。
 * Vue 3 的 info 形如 "native event handler" / "component event handler" / "render function" /
 * "setup function" / "lifecycle hook" / "watcher callback"。
 */
const FATAL_INFO_RE = /^(setup function|render|render function|component render function)$/;

onErrorCaptured((err, _instance, info) => {
  if (FATAL_INFO_RE.test(info)) {
    failed.value = true;
    adminDevWarn('[admin] 页面渲染异常，已用兜底 UI 替代：', err);
    return false;
  }
  adminDevWarn(`[admin] 页面交互异常（${info}），已提示、未替换页面：`, err);
  // 确认框点「取消」以 'cancel' reject，属用户主动行为，不提示
  if (!isUserDismiss(err)) ElMessage.error(errorMessage(err, '操作失败，请重试'));
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

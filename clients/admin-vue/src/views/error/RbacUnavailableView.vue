<template>
  <el-card class="page-card error-page" shadow="never">
    <el-result icon="warning" title="权限加载失败" :sub-title="subTitle">
      <template #extra>
        <el-button type="primary" :loading="retrying" @click="retry">重新加载</el-button>
        <el-button :disabled="retrying" @click="relogin">重新登录</el-button>
      </template>
    </el-result>
  </el-card>
</template>

<script setup lang="ts">
/**
 * R19-23（2026-11-12）：会话有效但 RBAC 拉取失败时的专用落地页。
 *
 * <p><b>为什么不能复用「无权访问」页</b>：路由守卫里 `await auth.restore()` 的返回值此前被忽略，
 * 而 restore 在 429 / 5xx / 网络抖动时**刻意不 logout**（R15-K1/K2：瞬态失败不能广播登出）。
 * 于是 `rbacHydrated` 保持 false ⇒ `hasPerm()` 全 false ⇒ 守卫把用户送到「无权访问」。
 * 用户看到的是「我权限没了」，实际是网关/后端抖了一下——排障方向完全错，且页面没有任何重试入口，
 * 只能手动刷浏览器。
 *
 * <p>本页只做两件事：说明「不是权限问题」，以及给一个可重复点击的重试（重试失败仍停在本页，
 * 不会在「守卫→失败→守卫」之间打转）。
 */
import { computed, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '@/stores/auth';

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();
const retrying = ref(false);

const pageTitle = computed(() => String(route.query.title || '').trim());
const fromPath = computed(() => String(route.query.from || '').trim());

const subTitle = computed(() => {
  const label = pageTitle.value || fromPath.value || '当前页面';
  return `没能加载到「${label}」的权限数据（网络或服务暂时不可用），这不是权限不足。`
    + '请稍后重试；多次失败请联系管理员检查网关与 RBAC 服务。';
});

async function retry() {
  retrying.value = true;
  try {
    const ok = await auth.restore();
    if (ok) {
      await router.replace(fromPath.value || '/dashboard');
    }
  } finally {
    retrying.value = false;
  }
}

async function relogin() {
  await auth.logout();
  await router.replace('/login');
}
</script>

<style scoped>
/* 铺满高度与居中见 main.css `.layout-main-scroll > .page-card.error-page` */
.error-page :deep(.el-result) {
  width: 100%;
  max-width: 560px;
}
</style>

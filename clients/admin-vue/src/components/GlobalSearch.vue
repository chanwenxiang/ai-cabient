<template>
  <div class="global-search">
    <el-input
      v-model="keyword"
      aria-label="全局搜索"
      placeholder="搜索页面名称或关键词 (Ctrl+K)…"
      :prefix-icon="Search"
      clearable
      class="search-input"
      @click="openPalette"
      @input="openPalette"
      @keydown.enter="pickFirst"
    />
    <el-dialog
      v-model="open"
      title="全局搜索"
      width="520px"
      append-to-body
      destroy-on-close
      @opened="focusInput"
      @closed="onClosed"
    >
      <el-input
        ref="inputRef"
        v-model="keyword"
        aria-label="搜索页面"
        placeholder="输入页面名称、分组或关键词…"
        :prefix-icon="Search"
        clearable
        @keydown.enter="pickFirst"
      />
      <div class="result-list" role="listbox" aria-label="搜索结果">
        <button
          v-for="item in results"
          :key="item.path"
          type="button"
          class="result-item"
          role="option"
          @click="go(item.path)"
        >
          <span class="result-title">{{ item.title }}</span>
          <span class="result-meta">{{ item.group }}</span>
        </button>
        <el-empty v-if="keyword && !results.length" description="无匹配页面" :image-size="64" />
        <div v-if="!keyword" class="hint">可搜索：设备、订单、争议、对账、个人中心等</div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { Search } from '@element-plus/icons-vue';
import type { ElInput } from 'element-plus';
import { searchNavItems } from '@/config/menu';
import { useAuthStore } from '@/stores/auth';

const router = useRouter();
const auth = useAuthStore();
const keyword = ref('');
const open = ref(false);
const inputRef = ref<InstanceType<typeof ElInput>>();
/** 关闭后短时忽略再次打开，避免焦点回落到顶栏输入框又弹起对话框 */
let suppressOpenUntil = 0;

const results = computed(() =>
  searchNavItems(keyword.value, (item) => auth.canAccessNav(item)).slice(0, 12)
);

function openPalette() {
  if (Date.now() < suppressOpenUntil) return;
  open.value = true;
}

function go(path: string) {
  suppressOpenUntil = Date.now() + 250;
  open.value = false;
  keyword.value = '';
  nextTick(() => {
    (document.activeElement as HTMLElement | null)?.blur?.();
  });
  router.push(path);
}

function pickFirst() {
  if (results.value[0]) go(results.value[0].path);
}

function focusInput() {
  inputRef.value?.focus();
}

function onClosed() {
  suppressOpenUntil = Date.now() + 250;
  keyword.value = '';
  nextTick(() => {
    (document.activeElement as HTMLElement | null)?.blur?.();
  });
}

function onKeydown(e: KeyboardEvent) {
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
    e.preventDefault();
    openPalette();
  }
}

onMounted(() => window.addEventListener('keydown', onKeydown));
onUnmounted(() => window.removeEventListener('keydown', onKeydown));
</script>

<style scoped>
.global-search {
  flex: 0 1 auto;
  min-width: 0;
  max-width: 220px;
}
.search-input {
  width: clamp(108px, 16vw, 220px);
  max-width: 100%;
}
.result-list {
  margin-top: 12px;
  max-height: 360px;
  overflow-y: auto;
  overscroll-behavior: contain;
}
.result-item {
  display: block;
  width: 100%;
  text-align: left;
  font: inherit;
  color: inherit;
  background: transparent;
  padding: 10px 12px;
  border-radius: 8px;
  cursor: pointer;
  border: 1px solid var(--layout-border);
  margin-bottom: 8px;
}
.result-item:hover { background: var(--layout-hover); }
.result-item:focus-visible {
  outline: none;
  box-shadow: 0 0 0 2px var(--el-color-primary);
}
.result-title { display: block; font-weight: 600; }
.result-meta { font-size: 12px; color: var(--layout-muted); }
.hint { color: var(--layout-muted); font-size: 13px; padding: 8px 0; }
@media (max-width: 900px) {
  .global-search {
    max-width: 40px;
  }
  .search-input {
    width: 40px;
  }
  .search-input :deep(.el-input__inner) {
    padding-left: 0;
    padding-right: 0;
    opacity: 0;
    width: 0;
  }
  .search-input :deep(.el-input__wrapper) {
    padding: 0 8px;
    justify-content: center;
  }
  .search-input :deep(.el-input__suffix) {
    display: none;
  }
}
</style>

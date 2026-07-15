<template>
  <div class="global-search">
    <el-input
      v-model="keyword"
      placeholder="全局搜索页面 (Ctrl+K)"
      :prefix-icon="Search"
      clearable
      class="search-input"
      @focus="open = true"
      @input="open = true"
      @keydown.enter="pickFirst"
    />
    <el-dialog v-model="open" title="全局搜索" width="520px" append-to-body @opened="focusInput">
      <el-input
        ref="inputRef"
        v-model="keyword"
        placeholder="输入页面名称、分组或关键词…"
        :prefix-icon="Search"
        clearable
        @keydown.enter="pickFirst"
      />
      <div class="result-list">
        <div
          v-for="item in results"
          :key="item.path"
          class="result-item"
          @click="go(item.path)"
        >
          <span class="result-title">{{ item.title }}</span>
          <span class="result-meta">{{ item.group }} · {{ item.path }}</span>
        </div>
        <el-empty v-if="keyword && !results.length" description="无匹配页面" :image-size="64" />
        <div v-if="!keyword" class="hint">可搜索：设备、订单、争议、对账、个人中心等</div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { Search } from '@element-plus/icons-vue';
import type { ElInput } from 'element-plus';
import { searchNavItems } from '@/config/menu';

const router = useRouter();
const keyword = ref('');
const open = ref(false);
const inputRef = ref<InstanceType<typeof ElInput>>();

const results = computed(() => searchNavItems(keyword.value).slice(0, 12));

function go(path: string) {
  open.value = false;
  keyword.value = '';
  router.push(path);
}

function pickFirst() {
  if (results.value[0]) go(results.value[0].path);
}

function focusInput() {
  inputRef.value?.focus();
}

function onKeydown(e: KeyboardEvent) {
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
    e.preventDefault();
    open.value = true;
  }
}

onMounted(() => window.addEventListener('keydown', onKeydown));
onUnmounted(() => window.removeEventListener('keydown', onKeydown));
</script>

<style scoped>
.search-input { width: 220px; }
.result-list { margin-top: 12px; max-height: 360px; overflow-y: auto; }
.result-item {
  padding: 10px 12px;
  border-radius: 8px;
  cursor: pointer;
  border: 1px solid var(--layout-border);
  margin-bottom: 8px;
}
.result-item:hover { background: var(--layout-hover); }
.result-title { display: block; font-weight: 600; }
.result-meta { font-size: 12px; color: var(--layout-muted); }
.hint { color: var(--layout-muted); font-size: 13px; padding: 8px 0; }
</style>

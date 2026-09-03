<template>
  <div class="global-search" title="全局搜索（Ctrl+K）">
    <el-input
      :model-value="''"
      readonly
      aria-label="全局搜索"
      placeholder="搜索页面名称或关键词…"
      title="全局搜索（Ctrl+K）"
      :prefix-icon="Search"
      class="search-input search-trigger"
      @click="openPalette"
      @keydown.enter.prevent="openPalette"
    />
    <el-dialog
      v-model="open"
      title="全局搜索"
      width="520px"
      append-to-body
      destroy-on-close
      :close-on-click-modal="true"
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
        @keydown.esc.prevent="closePalette"
      />
      <div class="result-list" aria-label="搜索结果">
        <div v-if="results.length" class="result-section">页面</div>
        <button
          v-for="item in results"
          :key="item.path"
          type="button"
          class="result-item"
          @click="go(item.path)"
        >
          <span class="result-title">{{ item.title }}</span>
          <span class="result-meta">{{ item.group }}</span>
        </button>
        <div v-if="recordResults.length" class="result-section">记录</div>
        <button
          v-for="hit in recordResults"
          :key="hit.type + hit.title"
          type="button"
          class="result-item"
          @click="goRecord(hit)"
        >
          <span class="result-title">{{ hit.title }}</span>
          <span class="result-meta">{{ hit.meta }}</span>
        </button>
        <el-empty
          v-if="keyword && !results.length && !recordResults.length"
          description="无匹配结果"
          :image-size="64"
        />
        <div v-if="!keyword" class="hint">可搜索：设备、订单、争议、对账、个人中心等</div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { Search } from '@element-plus/icons-vue';
import type { ElInput } from 'element-plus';
import { displayLabel } from '@aicabinet/shared-dict';
import { searchNavItems } from '@/config/menu';
import { useAuthStore } from '@/stores/auth';
import { api } from '@/api/client';

const router = useRouter();
const auth = useAuthStore();
const keyword = ref('');
const open = ref(false);
const inputRef = ref<InstanceType<typeof ElInput>>();
interface RecordHit {
  type: string;
  title: string;
  meta: string;
  path: string;
  query?: Record<string, string>;
}
const recordResults = ref<RecordHit[]>([]);
let searchTimer: ReturnType<typeof setTimeout> | null = null;
let searchSeq = 0;
/** 关闭后短时忽略再次打开，避免焦点回落到顶栏输入框又弹起对话框 */
let suppressOpenUntil = 0;

const results = computed(() =>
  searchNavItems(keyword.value, (item) => auth.canAccessNav(item)).slice(0, 12)
);

function openPalette() {
  if (Date.now() < suppressOpenUntil) return;
  open.value = true;
}

function closePalette() {
  suppressOpenUntil = Date.now() + 250;
  open.value = false;
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

function goRecord(hit: RecordHit) {
  suppressOpenUntil = Date.now() + 250;
  open.value = false;
  keyword.value = '';
  nextTick(() => {
    (document.activeElement as HTMLElement | null)?.blur?.();
  });
  if (hit.query) router.push({ path: hit.path, query: hit.query });
  else router.push(hit.path);
}

async function searchRecords(q: string) {
  const seq = ++searchSeq;
  const hits: RecordHit[] = [];
  const SEARCH_TIMEOUT_MS = 8_000;
  const take = (perm: string, url: string, pick: (items: any[]) => RecordHit[]) => {
    if (!auth.hasPerm(perm)) return Promise.resolve();
    const req = api
      .request<any>(url, 'GET')
      .then((data) => {
        if (seq !== searchSeq) return;
        const items = Array.isArray(data) ? data : data?.items || [];
        hits.push(...pick(items));
      })
      .catch(() => {});
    // 单路超时不拖垮整次搜索；慢接口被丢弃
    return Promise.race([
      req,
      new Promise<void>((resolve) => {
        setTimeout(resolve, SEARCH_TIMEOUT_MS);
      })
    ]);
  };
  await Promise.all([
    take(
      'ops:device:list',
      `/api/v2/ops/admin/devices?page=0&size=5&q=${encodeURIComponent(q)}`,
      (items) =>
        items.map((d: any) => ({
          type: 'device',
          title: d.deviceName || d.deviceId,
          meta: `设备 ${d.deviceId} · ${displayLabel('online_status', String(d.onlineStatus || ''), '未知')}`,
          path: `/devices/${encodeURIComponent(d.deviceId)}`
        }))
    ),
    take(
      'ops:order:list',
      `/api/v2/ops/admin/orders?page=0&size=5&orderId=${encodeURIComponent(q)}`,
      (items) =>
        items.map((o: any) => ({
          type: 'order',
          title: String(o.orderId || ''),
          meta: [
            '订单',
            displayLabel('pay_channel', String(o.payChannel || o.channel || ''), ''),
            displayLabel('order_status', String(o.status || ''), '')
          ]
            .filter(Boolean)
            .join(' · '),
          path: '/orders',
          query: { orderId: String(o.orderId) }
        }))
    ),
    take(
      'ops:session:list',
      `/api/v2/ops/admin/sessions?page=0&size=5&q=${encodeURIComponent(q)}`,
      (items) =>
        items.map((s: any) => ({
          type: 'session',
          title: String(s.sessionId || ''),
          meta: ['会话', s.deviceId || '', displayLabel('session_state', String(s.state || ''), '')]
            .filter(Boolean)
            .join(' · '),
          path: '/sessions',
          query: { sessionId: String(s.sessionId) }
        }))
    ),
    take(
      'ops:user:list',
      `/api/v2/ops/admin/users?page=0&size=5&phone=${encodeURIComponent(q)}`,
      (items) =>
        items.map((u: any) => ({
          type: 'user',
          title: String(u.phoneNumber || u.userId || ''),
          meta: `用户 ${u.userId || ''}`,
          path: '/users',
          query: { keyword: String(u.phoneNumber || u.userId || '') }
        }))
    ),
    take(
      'ops:merchant:list',
      `/api/v2/ops/admin/merchants?page=0&size=5&q=${encodeURIComponent(q)}`,
      (items) =>
        items.slice(0, 5).map((m: any) => ({
          type: 'merchant',
          title: m.merchantName || String(m.merchantId || ''),
          meta: `商户 ${m.merchantId || ''}`,
          path: '/merchants',
          query: { keyword: String(m.merchantId || '') }
        }))
    )
  ]);
  if (seq !== searchSeq) return;
  recordResults.value = hits.slice(0, 12);
}

watch(keyword, (k) => {
  if (searchTimer) clearTimeout(searchTimer);
  const q = (k || '').trim();
  if (!q) {
    searchSeq += 1;
    recordResults.value = [];
    return;
  }
  searchTimer = setTimeout(() => void searchRecords(q), 250);
});

function pickFirst() {
  if (results.value[0]) go(results.value[0].path);
  else if (recordResults.value[0]) goRecord(recordResults.value[0]);
}

function focusInput() {
  inputRef.value?.focus();
}

function onClosed() {
  suppressOpenUntil = Date.now() + 250;
  keyword.value = '';
  recordResults.value = [];
  nextTick(() => {
    (document.activeElement as HTMLElement | null)?.blur?.();
  });
}

function onKeydown(e: KeyboardEvent) {
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
    e.preventDefault();
    openPalette();
    return;
  }
  if (e.key === 'Escape' && open.value) {
    e.preventDefault();
    closePalette();
  }
}

onMounted(() => globalThis.addEventListener('keydown', onKeydown));
onUnmounted(() => {
  globalThis.removeEventListener('keydown', onKeydown);
  if (searchTimer) clearTimeout(searchTimer);
});
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
.search-trigger :deep(.el-input__wrapper) {
  cursor: pointer;
}
.search-trigger :deep(.el-input__inner) {
  cursor: pointer;
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
.result-item:hover {
  background: var(--layout-hover);
}
.result-item:focus-visible {
  outline: none;
  box-shadow: 0 0 0 2px var(--el-color-primary);
}
.result-title {
  display: block;
  font-weight: 600;
}
.result-meta {
  font-size: 12px;
  color: var(--layout-muted);
}
.result-section {
  font-size: 12px;
  color: var(--layout-muted);
  margin: 4px 2px 6px;
  font-weight: 600;
}
.hint {
  color: var(--layout-muted);
  font-size: 13px;
  padding: 8px 0;
}
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

<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">通知公告</span>
            <span class="hint">面向商户 / 消费者的运营公告；支持发布与归档</span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button @click="onExport">{{ exportButtonLabel }}</el-button>
          <el-button @click="onDownloadTemplate(['示例公告', '公告正文', '全部', '普通', '已发布', ''])">导入模板</el-button>
          <el-button :loading="importing" @click="triggerImport">导入</el-button>
          <input ref="importInput" type="file" accept=".csv,text/csv" class="hidden-input" @change="onImportFile" />
          <el-button type="primary" @click="showCreate = true">发布公告</el-button>
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </template>

    <el-form inline class="filter-bar filter-bar--compact" @submit.prevent="search">
      <el-form-item label="关键词">
        <el-input
          v-model="keyword"
          clearable
          placeholder="标题"
          style="width: 180px"
          @keyup.enter="search"
          @clear="search"
        />
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="statusFilter" clearable placeholder="全部" style="width: 120px" @change="search">
          <el-option label="草稿" value="DRAFT" />
          <el-option label="已发布" value="PUBLISHED" />
          <el-option label="存档" value="ARCHIVED" />
        </el-select>
      </el-form-item>
      <el-form-item label="优先级">
        <el-select v-model="priorityFilter" clearable placeholder="全部" style="width: 120px" @change="search">
          <el-option label="普通" value="NORMAL" />
          <el-option label="高" value="HIGH" />
          <el-option label="紧急" value="URGENT" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </el-form-item>
    </el-form>

    <div v-if="error" class="error-state">
      <el-alert :title="error" type="error" show-icon />
      <el-button size="small" @click="load">重试</el-button>
    </div>

    <div class="table-scroll">
      <div class="table-scroll-inner" style="min-width: 900px">
        <el-table
          v-loading="loading"
          :data="paged"
          border
          stripe
          class="report-table"
          row-key="announceId"
          @selection-change="onSelectionChange"
        >
          <template #empty><el-empty description="暂无公告" /></template>
          <el-table-column type="selection" width="48" align="center" />
          <el-table-column label="公告" min-width="220" class-name="col-text">
            <template #default="{ row }">
              <div class="name-cell">
                <strong>{{ row.title }}</strong>
                <small v-if="row.announceId">ID {{ row.announceId }}</small>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="优先级" width="88" align="center">
            <template #default="{ row }">
              <el-tag :type="priorityType(row.priority) || 'info'" size="small">
                {{ priorityMap[row.priority] || row.priority }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="目标" width="100" align="center">
            <template #default="{ row }">{{ scopeMap[row.targetScope] || row.targetScope }}</template>
          </el-table-column>
          <el-table-column label="状态" width="88" align="center">
            <template #default="{ row }">
              <el-tag :type="statusType(row.status)" size="small">
                {{ statusMap[row.status] || row.status }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="发布时间" width="168" class-name="col-text">
            <template #default="{ row }">
              <span class="cell-datetime">{{ formatTime(row.publishAt) || '-' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="120" class-name="col-action" align="center" fixed="right">
            <template #default="{ row }">
              <TableActions :actions="rowActions(row)" :max-primary="2" @action="(k) => onRowAction(k, row)" />
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <div class="page-pager">
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="size"
        :total="filtered.length"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        background
      />
    </div>

    <el-dialog v-model="showCreate" title="发布公告" width="600px" destroy-on-close>
      <el-form :model="form" label-width="80px">
        <el-form-item label="标题" required>
          <el-input v-model="form.title" maxlength="100" show-word-limit />
        </el-form-item>
        <el-form-item label="内容" required>
          <el-input v-model="form.content" type="textarea" :rows="6" maxlength="2000" show-word-limit />
        </el-form-item>
        <el-form-item label="目标">
          <el-select v-model="form.targetScope">
            <el-option label="全部用户" value="ALL" />
            <el-option label="商户" value="MERCHANT" />
            <el-option label="消费者" value="CONSUMER" />
          </el-select>
        </el-form-item>
        <el-form-item label="优先级">
          <el-select v-model="form.priority">
            <el-option label="普通" value="NORMAL" />
            <el-option label="高" value="HIGH" />
            <el-option label="紧急" value="URGENT" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showCreate = false">取消</el-button>
        <el-button type="primary" :loading="publishing" @click="onPublishSubmit">发布</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="previewVisible" title="公告详情" width="600px" destroy-on-close>
      <el-descriptions v-if="previewRow" :column="2" border>
        <el-descriptions-item label="标题" :span="2">{{ previewRow.title }}</el-descriptions-item>
        <el-descriptions-item label="优先级">{{ priorityMap[previewRow.priority] || previewRow.priority }}</el-descriptions-item>
        <el-descriptions-item label="目标">{{ scopeMap[previewRow.targetScope] || previewRow.targetScope }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ statusMap[previewRow.status] || previewRow.status }}</el-descriptions-item>
        <el-descriptions-item label="发布时间">{{ formatTime(previewRow.publishAt) || '—' }}</el-descriptions-item>
        <el-descriptions-item label="内容" :span="2">
          <div class="announcement-content">{{ previewRow.content || '暂无内容' }}</div>
        </el-descriptions-item>
      </el-descriptions>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onActivated, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { FolderOpened, Promotion, Refresh, View } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { get, post } from '@/api/client';
import TableActions, { type TableAction } from '@/components/TableActions.vue';
import { useListCsv } from '@/composables/useListCsv';
import { useTableSelection } from '@/composables/useTableSelection';

const route = useRoute();
const router = useRouter();
const loading = ref(false);
const publishing = ref(false);
const error = ref('');
const list = ref<any[]>([]);
const page = ref(1);
const size = ref(20);
const keyword = ref('');
const statusFilter = ref('');
const priorityFilter = ref('');
const showCreate = ref(false);
const previewVisible = ref(false);
const previewRow = ref<any>(null);
const form = ref<any>({ title: '', content: '', targetScope: 'ALL', priority: 'NORMAL' });

const filtered = computed(() => {
  const q = keyword.value.trim().toLowerCase();
  return list.value.filter((row) => {
    if (statusFilter.value && row.status !== statusFilter.value) return false;
    if (priorityFilter.value && row.priority !== priorityFilter.value) return false;
    if (q && !String(row.title || '').toLowerCase().includes(q)) return false;
    return true;
  });
});

const paged = computed(() => {
  const start = (page.value - 1) * size.value;
  return filtered.value.slice(start, start + size.value);
});

watch([keyword, statusFilter, priorityFilter], () => {
  page.value = 1;
});

const { onSelectionChange, pickSelected, exportButtonLabel, clearSelection } = useTableSelection<any>(
  (r) => r.announceId ?? `${r.title}-${r.publishAt}`
);

const scopeMap: Record<string, string> = { ALL: '全部', MERCHANT: '商户', CONSUMER: '消费者' };
const statusMap: Record<string, string> = { DRAFT: '草稿', PUBLISHED: '已发布', ARCHIVED: '存档' };
const priorityMap: Record<string, string> = { LOW: '低', NORMAL: '普通', HIGH: '高', URGENT: '紧急' };
const scopeCodeByLabel: Record<string, string> = {
  全部: 'ALL',
  商户: 'MERCHANT',
  消费者: 'CONSUMER',
  ALL: 'ALL',
  MERCHANT: 'MERCHANT',
  CONSUMER: 'CONSUMER'
};
const priorityCodeByLabel: Record<string, string> = {
  低: 'LOW',
  普通: 'NORMAL',
  高: 'HIGH',
  紧急: 'URGENT',
  LOW: 'LOW',
  NORMAL: 'NORMAL',
  HIGH: 'HIGH',
  URGENT: 'URGENT'
};

const CSV_HEADERS = ['标题', '内容', '目标', '优先级', '状态', '发布时间'];

const { importing, importInput, onExport, onDownloadTemplate, triggerImport, onImportFile } = useListCsv({
  filePrefix: '公告',
  headers: CSV_HEADERS,
  toRows: () =>
    pickSelected(list.value).map((row) => [
      row.title,
      row.content || '',
      scopeMap[row.targetScope] || row.targetScope,
      priorityMap[row.priority] || row.priority,
      statusMap[row.status] || row.status,
      formatTime(row.publishAt)
    ]),
  onImportRows: async (rows) => {
    let ok = 0;
    for (const row of rows) {
      const title = row['标题'] || row.title;
      if (!title?.trim()) continue;
      await post('/api/v2/ops/announcements', {
        title: title.trim(),
        content: row['内容'] || row.content || '',
        targetScope: scopeCodeByLabel[row['目标'] || row.targetScope] || 'ALL',
        priority: priorityCodeByLabel[row['优先级'] || row.priority] || 'NORMAL',
        publishAt: new Date().toISOString()
      });
      ok++;
    }
    await load();
    return ok;
  }
});

function syncRouteQuery() {
  const query: Record<string, string> = {};
  if (keyword.value.trim()) query.keyword = keyword.value.trim();
  if (statusFilter.value) query.status = statusFilter.value;
  if (priorityFilter.value) query.priority = priorityFilter.value;
  router.replace({ query });
}

function applyRouteQuery() {
  let changed = false;
  if (typeof route.query.keyword === 'string' && route.query.keyword !== keyword.value) {
    keyword.value = route.query.keyword;
    changed = true;
  }
  if (typeof route.query.status === 'string' && route.query.status !== statusFilter.value) {
    statusFilter.value = route.query.status;
    changed = true;
  }
  if (typeof route.query.priority === 'string' && route.query.priority !== priorityFilter.value) {
    priorityFilter.value = route.query.priority;
    changed = true;
  }
  return changed;
}

function search() {
  page.value = 1;
  syncRouteQuery();
}

function reset() {
  keyword.value = '';
  statusFilter.value = '';
  priorityFilter.value = '';
  page.value = 1;
  syncRouteQuery();
}

async function load() {
  loading.value = true;
  error.value = '';
  try {
    const res = await get('/api/v2/ops/announcements');
    list.value = res.data ?? [];
    clearSelection();
  } catch (e: any) {
    error.value = e?.message || '加载失败';
    ElMessage.error('加载失败');
  } finally {
    loading.value = false;
  }
}

function priorityType(p: string) {
  const m: Record<string, string> = { LOW: 'info', NORMAL: 'primary', HIGH: 'warning', URGENT: 'danger' };
  return m[p] || 'info';
}

function statusType(s: string) {
  const m: Record<string, string> = { DRAFT: 'info', PUBLISHED: 'success', ARCHIVED: 'warning' };
  return m[s] || 'info';
}

function formatTime(t: string) {
  if (!t) return '';
  return t.substring(0, 16).replace('T', ' ');
}

function rowActions(row: any): TableAction[] {
  const actions: TableAction[] = [{ key: 'preview', label: '查看', icon: View, type: 'primary' }];
  if (row.status === 'DRAFT') {
    actions.push({ key: 'publish', label: '发布', icon: Promotion, type: 'success' });
  }
  if (row.status === 'PUBLISHED') {
    actions.push({ key: 'archive', label: '归档', icon: FolderOpened, type: 'warning' });
  }
  return actions;
}

function onRowAction(key: string, row: any) {
  if (key === 'preview') onPreview(row);
  else if (key === 'publish') onPublish(row);
  else if (key === 'archive') onArchive(row);
}

async function onPublishSubmit() {
  if (!form.value.title.trim() || !form.value.content.trim()) {
    ElMessage.warning('请填写公告标题和内容');
    return;
  }
  publishing.value = true;
  try {
    const res = await post('/api/v2/ops/announcements', {
      ...form.value,
      title: form.value.title.trim(),
      content: form.value.content.trim()
    });
    const id = res?.data?.announceId;
    if (id) {
      await post(`/api/v2/ops/announcements/${id}/publish`);
    }
    ElMessage.success('发布成功');
    showCreate.value = false;
    form.value = { title: '', content: '', targetScope: 'ALL', priority: 'NORMAL' };
    await load();
  } catch (e: any) {
    ElMessage.error(e?.message || '发布失败');
  } finally {
    publishing.value = false;
  }
}

function onPreview(row: any) {
  previewRow.value = row;
  previewVisible.value = true;
}

async function onPublish(row: any) {
  try {
    await post(`/api/v2/ops/announcements/${row.announceId}/publish`);
    ElMessage.success('已发布');
    load();
  } catch (e: any) {
    ElMessage.error(e?.message || '发布失败');
  }
}

async function onArchive(row: any) {
  try {
    await ElMessageBox.confirm(`确认归档公告「${row.title}」？`, '归档公告');
    await post(`/api/v2/ops/announcements/${row.announceId}/archive`);
    ElMessage.success('已归档');
    await load();
  } catch (e: any) {
    if (e === 'cancel' || e === 'close') return;
    ElMessage.error(e?.message || '归档失败');
  }
}

onMounted(() => {
  applyRouteQuery();
  load();
});
onActivated(() => {
  applyRouteQuery();
});
</script>

<style scoped>
.page-card-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  flex-wrap: wrap;
}
.page-card-head__meta { min-width: 0; }
.page-card-head__title { display: flex; flex-direction: column; gap: 4px; }
.title { font-size: 15px; font-weight: 600; }
.hint { color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.4; }
.page-card-head__actions { display: flex; gap: 8px; flex-wrap: wrap; }
.name-cell { display: grid; gap: 2px; line-height: 1.35; }
.name-cell strong { font-weight: 650; }
.name-cell small {
  color: var(--el-text-color-secondary);
  font-size: 11px;
  font-family: var(--app-font-mono);
}
.error-state { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; }
.hidden-input { display: none; }
.announcement-content { white-space: pre-wrap; word-break: break-word; line-height: 1.7; }
</style>

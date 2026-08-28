<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">通知公告</span>
            <span class="hint">面向商户 / 消费者的运营公告；支持编辑、发布与归档</span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button v-hasPermi="['ops:announcement:export']" @click="onExport">{{
            exportButtonLabel
          }}</el-button>
          <el-button
            v-hasPermi="['ops:announcement:import']"
            @click="onDownloadTemplate(['示例公告', '公告正文', '全部', '普通', '已发布', ''])"
            >导入模板</el-button
          >
          <el-button
            v-hasPermi="['ops:announcement:import']"
            :loading="importing"
            @click="triggerImport"
            >导入</el-button
          >
          <input
            ref="importInput"
            type="file"
            accept=".csv,text/csv"
            class="hidden-input"
            @change="onImportFile"
          />
          <el-button v-hasPermi="['ops:announcement:create']" type="primary" @click="openCreate"
            >发布公告</el-button
          >
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
        <el-select
          v-model="statusFilter"
          clearable
          placeholder="全部"
          style="width: 120px"
          @change="search"
        >
          <el-option
            v-for="item in dictOptions('announcement_status')"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="优先级">
        <el-select
          v-model="priorityFilter"
          clearable
          placeholder="全部"
          style="width: 120px"
          @change="search"
        >
          <el-option
            v-for="item in dictOptions('dispute_priority')"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
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
      <div class="table-scroll-inner">
        <el-table
          v-loading="loading"
          :data="paged"
          border
          stripe
          class="report-table"
          row-key="announceId"
          :default-sort="idDefaultSort"
          @sort-change="onIdSortChange"
          @selection-change="onSelectionChange"
          empty-text=" "
        >
          <template #empty>
            <el-empty v-if="listHydrated && !loading" description="暂无公告" />
          </template>
          <el-table-column type="selection" width="48" align="center" />
          <el-table-column
            prop="announceId"
            label="公告编号"
            width="100"
            align="center"
            class-name="col-text"
            sortable="custom"
          >
            <template #default="{ row }">
              <span class="cell-id">{{ row.announceId ?? '无' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="公告" min-width="200" align="center" class-name="col-text">
            <template #default="{ row }">{{ row.title || '无' }}</template>
          </el-table-column>
          <el-table-column label="优先级" width="88" align="center">
            <template #default="{ row }">
              <el-tag :type="priorityType(row.priority) || 'info'" size="small">
                {{ priorityMap[row.priority] || '普通' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="目标" width="100" align="center">
            <template #default="{ row }">{{
              displayLabel('announcement_audience', row.targetScope)
            }}</template>
          </el-table-column>
          <el-table-column label="状态" width="88" align="center">
            <template #default="{ row }">
              <el-tag :type="statusType(row.status)" size="small">
                {{ displayLabel('announcement_status', row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="发布时间" width="168" align="center" class-name="col-text">
            <template #default="{ row }">
              <span class="cell-datetime">{{ formatTime(row.publishAt) || '无' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="160" class-name="col-action" align="center" fixed="right">
            <template #default="{ row }">
              <TableActions
                :actions="rowActions(row)"
                :max-primary="2"
                @action="(k) => onRowAction(k, row)"
              />
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <PagePager
      :hydrated="listHydrated"
      v-model:current-page="page"
      v-model:page-size="size"
      :total="total"
      :page-sizes="[10, 20, 50, 100]"
      layout="total, sizes, prev, pager, next"
      background
      @current-change="load"
      @size-change="onSizeChange"
    />

    <el-dialog
      v-model="showForm"
      :title="editingId ? '编辑公告' : '发布公告'"
      width="480px"
      destroy-on-close
    >
      <el-form :model="form" label-width="80px">
        <el-form-item label="标题" required>
          <el-input v-model="form.title" maxlength="100" show-word-limit />
        </el-form-item>
        <el-form-item label="内容" required>
          <el-input
            v-model="form.content"
            type="textarea"
            :rows="6"
            maxlength="2000"
            show-word-limit
          />
        </el-form-item>
        <el-form-item label="目标">
          <el-select v-model="form.targetScope">
            <el-option
              v-for="item in dictOptions('announcement_audience')"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="优先级">
          <el-select v-model="form.priority">
            <el-option
              v-for="item in dictOptions('dispute_priority')"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showForm = false">取消</el-button>
        <el-button
          v-if="editingId"
          v-hasPermi="['ops:announcement:edit']"
          type="primary"
          :loading="saving"
          @click="onSaveSubmit"
          >保存</el-button
        >
        <el-button
          v-else
          v-hasPermi="['ops:announcement:publish']"
          type="primary"
          :loading="saving"
          @click="onPublishSubmit"
          >发布</el-button
        >
      </template>
    </el-dialog>

    <el-dialog v-model="previewVisible" title="公告详情" width="480px" destroy-on-close>
      <el-descriptions v-if="previewRow" :column="2" border>
        <el-descriptions-item label="标题" :span="2">{{ previewRow.title }}</el-descriptions-item>
        <el-descriptions-item label="优先级">{{
          priorityMap[previewRow.priority] || '普通'
        }}</el-descriptions-item>
        <el-descriptions-item label="目标">{{
          displayLabel('announcement_audience', previewRow.targetScope)
        }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{
          displayLabel('announcement_status', previewRow.status)
        }}</el-descriptions-item>
        <el-descriptions-item label="发布时间">{{
          formatTime(previewRow.publishAt) || '无'
        }}</el-descriptions-item>
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
import { EditPen, FolderOpened, Promotion, Refresh, View } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { dictOptions, displayLabel } from '@aicabinet/shared-dict';
import { get, post, put } from '@/api/client';
import TableActions, { type TableAction } from '@/components/TableActions.vue';
import PagePager from '@/components/PagePager.vue';
import { useListCsv } from '@/composables/useListCsv';
import { useTableSelection } from '@/composables/useTableSelection';
import { useAuthStore } from '@/stores/auth';
import { useIdColumnSort } from '@/composables/useIdColumnSort';

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();
const { idDefaultSort, onIdSortChange, sortById } = useIdColumnSort('announceId');
const loading = ref(false);
const listHydrated = ref(false);
const saving = ref(false);
const error = ref('');
const list = ref<any[]>([]);
const total = ref(0);
const page = ref(1);
const size = ref(20);
const keyword = ref('');
const statusFilter = ref('');
const priorityFilter = ref('');
const showForm = ref(false);
const editingId = ref<number | null>(null);
const previewVisible = ref(false);
const previewRow = ref<any>(null);
const form = ref<any>({ title: '', content: '', targetScope: 'ALL', priority: 'NORMAL' });

function emptyForm() {
  return { title: '', content: '', targetScope: 'ALL', priority: 'NORMAL' };
}

const filtered = computed(() => sortById(list.value));

const paged = computed(() => list.value);

const { onSelectionChange, pickSelected, exportButtonLabel, clearSelection } =
  useTableSelection<any>((r) => r.announceId ?? `${r.title}-${r.publishAt}`);

const priorityMap: Record<string, string> = Object.fromEntries(
  dictOptions('dispute_priority').map((o) => [o.value, o.label])
);
const scopeCodeByLabel: Record<string, string> = Object.fromEntries(
  dictOptions('announcement_audience').flatMap(
    (o) =>
      [
        [o.label, o.value],
        [o.value, o.value],
        ...(o.value === 'ALL' ? ([['全部', 'ALL']] as [string, string][]) : [])
      ] as [string, string][]
  )
);
const priorityCodeByLabel: Record<string, string> = Object.fromEntries(
  dictOptions('dispute_priority').flatMap(
    (o) =>
      [
        [o.label, o.value],
        [o.value, o.value]
      ] as [string, string][]
  )
);

const CSV_HEADERS = ['标题', '内容', '目标', '优先级', '状态', '发布时间'];

const { importing, importInput, onExport, onDownloadTemplate, triggerImport, onImportFile } =
  useListCsv({
    filePrefix: '公告',
    headers: CSV_HEADERS,
    toRows: () =>
      pickSelected(filtered.value).map((row) => [
        row.title,
        row.content || '',
        displayLabel('announcement_audience', row.targetScope),
        priorityMap[row.priority] || '普通',
        displayLabel('announcement_status', row.status),
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
  const qKeyword = typeof route.query.keyword === 'string' ? route.query.keyword : '';
  if (qKeyword !== keyword.value) {
    keyword.value = qKeyword;
    changed = true;
  }
  const qStatus = typeof route.query.status === 'string' ? route.query.status : '';
  if (qStatus !== statusFilter.value) {
    statusFilter.value = qStatus;
    changed = true;
  }
  const qPriority = typeof route.query.priority === 'string' ? route.query.priority : '';
  if (qPriority !== priorityFilter.value) {
    priorityFilter.value = qPriority;
    changed = true;
  }
  return changed;
}

function search() {
  page.value = 1;
  syncRouteQuery();
  void load();
}

function onSizeChange() {
  page.value = 1;
  void load();
}

function reset() {
  keyword.value = '';
  statusFilter.value = '';
  priorityFilter.value = '';
  page.value = 1;
  syncRouteQuery();
  void load();
}

async function load() {
  loading.value = true;
  error.value = '';
  try {
    const q = new URLSearchParams({
      page: String(page.value - 1),
      size: String(size.value)
    });
    if (keyword.value.trim()) q.set('q', keyword.value.trim());
    if (statusFilter.value) q.set('status', statusFilter.value);
    if (priorityFilter.value) q.set('priority', priorityFilter.value);
    const res = await get(`/api/v2/ops/announcements?${q}`);
    list.value = (res.data?.items as any[]) ?? [];
    total.value = Number(res.data?.total ?? 0);
    clearSelection();
  } catch (e: any) {
    error.value = e?.message || '加载失败';
    ElMessage.error('加载失败');
  } finally {
    listHydrated.value = true;
    loading.value = false;
  }
}

function priorityType(p: string) {
  const m: Record<string, string> = {
    LOW: 'info',
    NORMAL: 'primary',
    HIGH: 'warning',
    URGENT: 'danger'
  };
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
  if (row.status !== 'ARCHIVED' && auth.hasPerm('ops:announcement:edit')) {
    actions.push({ key: 'edit', label: '编辑', icon: EditPen, type: 'primary' });
  }
  if (row.status === 'DRAFT' && auth.hasPerm('ops:announcement:publish')) {
    actions.push({ key: 'publish', label: '发布', icon: Promotion, type: 'success' });
  }
  if (row.status === 'PUBLISHED' && auth.hasPerm('ops:announcement:edit')) {
    actions.push({ key: 'archive', label: '归档', icon: FolderOpened, type: 'warning' });
  }
  return actions;
}

function onRowAction(key: string, row: any) {
  if (key === 'preview') onPreview(row);
  else if (key === 'edit') openEdit(row);
  else if (key === 'publish') onPublish(row);
  else if (key === 'archive') onArchive(row);
}

function openCreate() {
  editingId.value = null;
  form.value = emptyForm();
  showForm.value = true;
}

function openEdit(row: any) {
  editingId.value = row.announceId;
  form.value = {
    title: row.title || '',
    content: row.content || '',
    targetScope: row.targetScope || 'ALL',
    priority: row.priority || 'NORMAL'
  };
  showForm.value = true;
}

function formBody() {
  return {
    title: form.value.title.trim(),
    content: form.value.content.trim(),
    targetScope: form.value.targetScope || 'ALL',
    priority: form.value.priority || 'NORMAL'
  };
}

async function onSaveSubmit() {
  if (!form.value.title.trim() || !form.value.content.trim()) {
    ElMessage.warning('请填写公告标题和内容');
    return;
  }
  if (!editingId.value) return;
  saving.value = true;
  try {
    await put(`/api/v2/ops/announcements/${editingId.value}`, formBody());
    ElMessage.success('已保存');
    showForm.value = false;
    editingId.value = null;
    form.value = emptyForm();
    await load();
  } catch (e: any) {
    ElMessage.error(e?.message || '保存失败');
  } finally {
    saving.value = false;
  }
}

async function onPublishSubmit() {
  if (!form.value.title.trim() || !form.value.content.trim()) {
    ElMessage.warning('请填写公告标题和内容');
    return;
  }
  saving.value = true;
  try {
    const res = await post('/api/v2/ops/announcements', formBody());
    const id = res?.data?.announceId;
    if (id) {
      await post(`/api/v2/ops/announcements/${id}/publish`);
    }
    ElMessage.success('发布成功');
    showForm.value = false;
    editingId.value = null;
    form.value = emptyForm();
    await load();
  } catch (e: any) {
    ElMessage.error(e?.message || '发布失败');
  } finally {
    saving.value = false;
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

async function reloadFromRouteQuery() {
  if (!applyRouteQuery()) return;
  page.value = 1;
}

watch(
  () => [route.query.keyword, route.query.status, route.query.priority] as const,
  () => {
    void reloadFromRouteQuery();
  }
);

onActivated(() => {
  void reloadFromRouteQuery();
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
.page-card-head__meta {
  min-width: 0;
}
.page-card-head__title {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.title {
  font-size: 15px;
  font-weight: 600;
}
.hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.4;
}
.page-card-head__actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.error-state {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}
.hidden-input {
  display: none;
}
.announcement-content {
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.7;
}
</style>

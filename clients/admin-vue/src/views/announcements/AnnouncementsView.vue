<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">通知公告</span>
            <span class="hint">仅 CMS 上架：发布后客户端打开公告列表可见，不主动推送</span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button v-hasPermi="['ops:announcement:create']" type="primary" @click="openCreate"
            >发布公告</el-button
          >
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

    <div class="table-scroll">
      <div class="table-scroll-inner">
        <CrudTable
          :table="crud"
          row-key="announceId"
          selectable
          :actions="rowActions"
          :action-width="160"
          actions-testid="announcement"
          empty-text="暂无公告"
          sort-field-label="公告编号"
          :csv="csvOptions"
          @action="onRowAction"
        >
          <el-table-column prop="announceId" label="公告编号" width="100" class-name="col-text">
            <template #default="{ row }">
              <span class="cell-id">{{ row.announceId ?? '无' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="公告" min-width="200" class-name="col-text">
            <template #default="{ row }">{{ row.title || '无' }}</template>
          </el-table-column>
          <el-table-column
            label="优先级"
            width="88"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <el-tag :type="priorityType(row.priority) || 'info'" size="small">
                {{ priorityMap[row.priority] || '普通' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column
            label="目标"
            width="100"
            class-name="col-text"
            label-class-name="col-text"
          >
            <template #default="{ row }">{{
              displayLabel('announcement_audience', row.targetScope)
            }}</template>
          </el-table-column>
          <el-table-column
            label="状态"
            width="88"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <el-tag :type="statusType(row.status)" size="small">
                {{ displayLabel('announcement_status', row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column
            align="center"
            label="发布时间"
            width="168"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <span class="cell-datetime">{{ formatTime(row.publishAt) || '无' }}</span>
            </template>
          </el-table-column>
        </CrudTable>
      </div>
    </div>

    <el-dialog v-model="showForm" :title="editingId ? '编辑公告' : '发布公告'" destroy-on-close>
      <el-form :model="form" label-width="auto">
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

    <el-dialog v-model="previewVisible" title="公告详情" destroy-on-close>
      <el-descriptions v-if="previewRow" :column="2" border>
        <el-descriptions-item label="标题" :span="2">{{ previewRow.title }}</el-descriptions-item>
        <el-descriptions-item label="优先级">{{
          priorityMap[previewRow.priority || ''] || '普通'
        }}</el-descriptions-item>
        <el-descriptions-item label="目标">{{
          displayLabel('announcement_audience', previewRow.targetScope)
        }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{
          displayLabel('announcement_status', previewRow.status)
        }}</el-descriptions-item>
        <el-descriptions-item label="发布时间">{{
          formatTime(previewRow.publishAt || '') || '无'
        }}</el-descriptions-item>
        <el-descriptions-item label="内容" :span="2">
          <div class="announcement-content">{{ previewRow.content || '暂无内容' }}</div>
        </el-descriptions-item>
      </el-descriptions>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { onActivated, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { EditPen, FolderOpened, Promotion, View } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { dictOptions, displayLabel } from '@aicabinet/shared-dict';
import type { OpenApiAnnouncement, OpenApiPageResultAnnouncement } from '@aicabinet/shared-types';
import { get, post, put } from '@/api/client';
import CrudTable, { type CrudCsvOptions, type CrudRowAction } from '@/components/CrudTable.vue';
import { useCrudTable } from '@/composables/useCrudTable';
import { errorMessage } from '@/utils/error-message';

type AnnouncementForm = {
  title: string;
  content: string;
  targetScope: string;
  priority: string;
};

const route = useRoute();
const router = useRouter();
const saving = ref(false);
const keyword = ref('');
const statusFilter = ref('');
const priorityFilter = ref('');
const showForm = ref(false);
const editingId = ref<number | null>(null);
const previewVisible = ref(false);
const previewRow = ref<OpenApiAnnouncement | null>(null);
const form = ref<AnnouncementForm>({
  title: '',
  content: '',
  targetScope: 'ALL',
  priority: 'NORMAL'
});

function emptyForm(): AnnouncementForm {
  return { title: '', content: '', targetScope: 'ALL', priority: 'NORMAL' };
}

// 列表状态机统一交给 CrudTable：分页 / 排序 / 多选 / 竞态 / 空态 全部内建
const crud = useCrudTable<OpenApiAnnouncement>({
  rowKey: (r) => r.announceId ?? `${r.title}-${r.publishAt}`,
  // 首查前需先应用路由查询参数（applyRouteQuery），故关闭 autoLoad 由 onMounted 显式首查
  autoLoad: false,
  fetchPage: async (params) => {
    const q = new URLSearchParams({
      page: String(params.page), // 0 起（useCrudTable 已换算）
      size: String(params.size)
    });
    if (keyword.value.trim()) q.set('q', keyword.value.trim());
    if (statusFilter.value) q.set('status', statusFilter.value);
    if (priorityFilter.value) q.set('priority', priorityFilter.value);
    const res = await get<OpenApiPageResultAnnouncement>(`/api/v2/ops/announcements?${q}`);
    return res.data;
  },
  // 公告编号本地排序（替代原 useIdColumnSort 表头排序，改由壳内「按公告编号 升/降序」切换）
  sort: { prop: 'announceId', mode: 'local' }
});

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

// 导出 / 下载模板 / 导入统一并入 CrudTable 工具条（选中优先导出、文件命名由组件内置）
const csvOptions: CrudCsvOptions = {
  filePrefix: '公告',
  exportPerm: 'ops:announcement:export',
  importPerm: 'ops:announcement:import',
  headers: ['标题', '内容', '目标', '优先级', '状态', '发布时间'],
  templateSample: [
    '示例公告',
    '公告正文',
    '全部',
    '普通',
    displayLabel('announcement_status', 'PUBLISHED'),
    ''
  ],
  toRows: (rows) =>
    rows.map((row) => [
      row.title,
      row.content || '',
      displayLabel('announcement_audience', row.targetScope),
      priorityMap[row.priority || ''] || '普通',
      displayLabel('announcement_status', row.status),
      formatTime(row.publishAt || '')
    ]),
  onImportRows: async (rows) => {
    const statusCodeByLabel: Record<string, string> = Object.fromEntries(
      dictOptions('announcement_status').flatMap(
        (o) =>
          [
            [o.label, o.value],
            [o.value, o.value]
          ] as [string, string][]
      )
    );
    let ok = 0;
    for (const row of rows) {
      const title = row['标题'] || row.title;
      if (!title?.trim()) continue;
      // 后端 create 固定为 DRAFT，忽略 body.publishAt；需发布时再调 publish
      const created = await post<OpenApiAnnouncement>('/api/v2/ops/announcements', {
        title: title.trim(),
        content: row['内容'] || row.content || '',
        targetScope: scopeCodeByLabel[row['目标'] || row.targetScope] || 'ALL',
        priority: priorityCodeByLabel[row['优先级'] || row.priority] || 'NORMAL'
      });
      const statusRaw = String(row['状态'] || row.status || '').trim();
      const status = statusCodeByLabel[statusRaw] || statusRaw.toUpperCase();
      const announceId = created?.data?.announceId;
      if (status === 'PUBLISHED' && announceId != null) {
        await post(`/api/v2/ops/announcements/${announceId}/publish`);
      }
      ok++;
    }
    await crud.load();
    return ok;
  }
};

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
  syncRouteQuery();
  void crud.load({ resetPage: true });
}

function reset() {
  keyword.value = '';
  statusFilter.value = '';
  priorityFilter.value = '';
  syncRouteQuery();
  void crud.load({ resetPage: true });
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

function rowActions(row: OpenApiAnnouncement): CrudRowAction[] {
  const actions: CrudRowAction[] = [{ key: 'preview', label: '查看', icon: View, type: 'primary' }];
  if (row.status !== 'ARCHIVED') {
    actions.push({
      key: 'edit',
      label: '编辑',
      icon: EditPen,
      type: 'primary',
      perm: 'ops:announcement:edit'
    });
  }
  if (row.status === 'DRAFT') {
    actions.push({
      key: 'publish',
      label: '发布',
      icon: Promotion,
      type: 'success',
      perm: 'ops:announcement:publish'
    });
  }
  if (row.status === 'PUBLISHED') {
    actions.push({
      key: 'archive',
      label: '归档',
      icon: FolderOpened,
      type: 'warning',
      perm: 'ops:announcement:edit'
    });
  }
  return actions;
}

function onRowAction({ key, row }: { key: string; row: OpenApiAnnouncement }) {
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

function openEdit(row: OpenApiAnnouncement) {
  editingId.value = row.announceId ?? null;
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
    await crud.load();
  } catch (e: unknown) {
    ElMessage.error(errorMessage(e, '保存失败'));
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
    const res = await post<OpenApiAnnouncement>('/api/v2/ops/announcements', formBody());
    const id = res?.data?.announceId;
    if (id) {
      await post(`/api/v2/ops/announcements/${id}/publish`);
    }
    ElMessage.success('发布成功');
    showForm.value = false;
    editingId.value = null;
    form.value = emptyForm();
    await crud.load();
  } catch (e: unknown) {
    ElMessage.error(errorMessage(e, '发布失败'));
  } finally {
    saving.value = false;
  }
}

function onPreview(row: OpenApiAnnouncement) {
  previewRow.value = row;
  previewVisible.value = true;
}

async function onPublish(row: OpenApiAnnouncement) {
  try {
    await post(`/api/v2/ops/announcements/${row.announceId ?? 0}/publish`);
    ElMessage.success('发布成功');
    void crud.load();
  } catch (e: unknown) {
    ElMessage.error(errorMessage(e, '发布失败'));
  }
}

async function onArchive(row: OpenApiAnnouncement) {
  try {
    await ElMessageBox.confirm(`确认归档公告「${row.title}」？`, '归档公告');
    await post(`/api/v2/ops/announcements/${row.announceId ?? 0}/archive`);
    ElMessage.success('归档成功');
    await crud.load();
  } catch (e: unknown) {
    if (e === 'cancel' || e === 'close') return;
    ElMessage.error(errorMessage(e, '归档失败'));
  }
}

// 首查前需先应用路由查询参数（applyRouteQuery），故关闭 autoLoad 由这里显式首查
onMounted(() => {
  applyRouteQuery();
  void crud.load();
});

async function reloadFromRouteQuery() {
  if (!applyRouteQuery()) return;
  await crud.load({ resetPage: true });
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
  font-size: var(--admin-font-size-title);
  font-weight: 600;
}
.hint {
  color: var(--el-text-color-secondary);
  font-size: var(--admin-font-size-sm);
  line-height: 1.4;
}
.page-card-head__actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.announcement-content {
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.7;
}
</style>

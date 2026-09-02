<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">定时任务</span>
            <span class="hint"
              >启停即时生效；内置任务不可删除。自定义任务可登记元数据（无代码 runner
              时不可立即执行）</span
            >
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button
            v-if="hasSelection && canEdit"
            type="success"
            :loading="batchLoading === 'enable'"
            @click="batchToggle(true)"
          >
            批量启用
          </el-button>
          <el-button
            v-if="hasSelection && canEdit"
            type="warning"
            :loading="batchLoading === 'disable'"
            @click="batchToggle(false)"
          >
            批量停用
          </el-button>
          <el-button
            v-if="hasSelection && canRun"
            type="primary"
            :loading="batchLoading === 'run'"
            @click="batchRun"
          >
            批量执行
          </el-button>
          <el-button @click="onExport">{{ exportButtonLabel }}</el-button>
          <el-button v-if="canEdit" type="primary" @click="openCreate">新增</el-button>
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </template>

    <el-form inline class="filter-bar filter-bar--compact" @submit.prevent="search">
      <el-form-item label="关键词">
        <el-input
          v-model="keyword"
          clearable
          placeholder="任务名 / 标识 / 分组 / 调度"
          style="width: 220px"
          @keyup.enter="search"
          @clear="search"
        />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </el-form-item>
    </el-form>

    <div class="table-scroll">
      <div class="table-scroll-inner">
        <el-table
          ref="tableRef"
          v-loading="loading"
          :data="paged"
          stripe
          border
          class="report-table"
          row-key="taskKey"
          empty-text=" "
          @selection-change="onSelectionChange"
        >
          <template #empty>
            <el-empty v-if="listHydrated && !loading" description="暂无定时任务" />
          </template>
          <el-table-column type="selection" width="48" align="center" />
          <el-table-column label="任务名称" min-width="170" align="center" class-name="col-text">
            <template #default="{ row }">
              <span class="cell-id">{{ row.taskName }}</span>
            </template>
          </el-table-column>
          <el-table-column label="任务标识" min-width="200" align="center" class-name="col-text">
            <template #default="{ row }">{{ row.taskKey }}</template>
          </el-table-column>
          <el-table-column label="分组" width="110" align="center">
            <template #default="{ row }">{{
              dictLabel('scheduled_task_group', row.taskGroup)
            }}</template>
          </el-table-column>
          <el-table-column label="调度说明" width="130" align="center">
            <template #default="{ row }">{{ row.scheduleDesc || '暂无' }}</template>
          </el-table-column>
          <el-table-column label="状态" width="110" align="center">
            <template #default="{ row }">
              <el-switch
                v-if="canEdit"
                :model-value="row.enabled"
                :loading="togglingKey === row.taskKey"
                @change="(v: boolean) => onToggle(row, v)"
              />
              <el-tag v-else :type="row.enabled ? 'success' : 'info'">
                {{ row.enabled ? '启用' : '停用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="最近执行" min-width="200" align="center">
            <template #default="{ row }">
              <template v-if="row.lastRunAt">
                <div>{{ formatDateTime(row.lastRunAt) }}</div>
                <el-tag size="small" :type="resultType(row.lastResult)">
                  {{ resultLabel(row.lastResult) }}
                </el-tag>
                <div v-if="row.lastDurationMs != null" class="cell-hint">
                  耗时 {{ formatDuration(row.lastDurationMs) }}
                </div>
              </template>
              <span v-else class="cell-hint">尚未执行</span>
            </template>
          </el-table-column>
          <el-table-column
            label="最近结果说明"
            min-width="200"
            align="center"
            show-overflow-tooltip
          >
            <template #default="{ row }">{{ row.lastMessage || '暂无' }}</template>
          </el-table-column>
          <el-table-column label="备注" min-width="180" align="center" show-overflow-tooltip>
            <template #default="{ row }">{{ row.remark || '暂无' }}</template>
          </el-table-column>
          <el-table-column
            v-if="showActionColumn"
            label="操作"
            width="160"
            align="center"
            class-name="col-action"
            fixed="right"
          >
            <template #default="{ row }">
              <TableActions
                :actions="rowActions(row)"
                @action="(k) => onRowAction(String(k), row)"
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
      :total="filtered.length"
      :page-sizes="[10, 20, 50, 100]"
      layout="total, sizes, prev, pager, next"
      background
    />

    <el-dialog
      v-model="editVisible"
      :title="creating ? '新增定时任务' : '编辑定时任务'"
      width="520px"
      destroy-on-close
    >
      <el-form label-width="88px">
        <el-form-item label="任务标识" required>
          <el-input
            v-model="editForm.taskKey"
            :disabled="!creating"
            placeholder="如 custom-nightly-check"
          />
        </el-form-item>
        <el-form-item label="任务名称" required>
          <el-input v-model="editForm.taskName" placeholder="展示名称" />
        </el-form-item>
        <el-form-item label="分组" required>
          <el-select v-model="editForm.taskGroup" filterable allow-create style="width: 100%">
            <el-option
              v-for="opt in groupOptions"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="调度说明">
          <el-input v-model="editForm.scheduleDesc" placeholder="如 每日 03:00" />
        </el-form-item>
        <el-form-item v-if="creating" label="启用">
          <el-switch v-model="editForm.enabled" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input
            v-model="editForm.remark"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="editSaving" @click="saveEdit">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { Delete, EditPen, Refresh, VideoPlay } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api } from '@/api/client';
import PagePager from '@/components/PagePager.vue';
import TableActions, { type TableAction } from '@/components/TableActions.vue';
import { useAdminListTable } from '@/composables/useAdminListTable';
import { useListCsv } from '@/composables/useListCsv';
import { useAuthStore } from '@/stores/auth';
import { dictLabel, dictOptions } from '@aicabinet/shared-dict';
import { formatDateTime } from '@aicabinet/shared-uni/format';

interface ScheduledTaskRow {
  taskKey: string;
  taskName: string;
  taskGroup: string;
  scheduleDesc?: string;
  enabled: boolean;
  lastRunAt?: string;
  lastResult?: string;
  lastMessage?: string;
  lastDurationMs?: number;
  remark?: string;
  registryBound?: boolean;
}

const auth = useAuthStore();
const loading = ref(false);
const listHydrated = ref(false);
const page = ref(1);
const size = ref(20);
const items = ref<ScheduledTaskRow[]>([]);
const batchLoading = ref<'enable' | 'disable' | 'run' | ''>('');
const {
  tableRef,
  keyword,
  hasSelection,
  onSelectionChange,
  pickSelected,
  exportButtonLabel,
  clearSelection,
  filterByKeyword,
  resetKeyword
} = useAdminListTable<ScheduledTaskRow>((r) => r.taskKey);
const togglingKey = ref('');
const runningKey = ref('');
const editVisible = ref(false);
const editSaving = ref(false);
const creating = ref(false);
const editForm = reactive({
  taskKey: '',
  taskName: '',
  taskGroup: 'OPS',
  scheduleDesc: '',
  enabled: true,
  remark: ''
});

const canEdit = computed(() => auth.hasPerm('ops:task:edit'));
const canRun = computed(() => auth.hasPerm('ops:task:run'));
const showActionColumn = computed(() => canEdit.value || canRun.value);
const groupOptions = computed(() => dictOptions('scheduled_task_group'));

const filtered = computed(() =>
  filterByKeyword(items.value, (row, kw) =>
    [row.taskName, row.taskKey, row.taskGroup, row.scheduleDesc].some((x) =>
      String(x || '')
        .toLowerCase()
        .includes(kw)
    )
  )
);

const { onExport } = useListCsv({
  filePrefix: '定时任务',
  headers: ['任务名称', '任务标识', '分组', '调度说明', '状态', '最近执行', '最近结果'],
  toRows: () =>
    pickSelected(filtered.value).map((r) => [
      r.taskName,
      r.taskKey,
      dictLabel('scheduled_task_group', r.taskGroup),
      r.scheduleDesc || '',
      r.enabled ? '启用' : '停用',
      r.lastRunAt ? formatDateTime(r.lastRunAt) : '',
      r.lastMessage || ''
    ])
});

const paged = computed(() => {
  const start = (page.value - 1) * size.value;
  return filtered.value.slice(start, start + size.value);
});

function resultType(result?: string) {
  if (result === 'SUCCESS') return 'success';
  if (result === 'FAILED') return 'danger';
  if (result === 'SKIPPED') return 'warning';
  return 'info';
}

function resultLabel(result?: string) {
  return { SUCCESS: '成功', FAILED: '失败', SKIPPED: '跳过' }[result || ''] || '未知';
}

function formatDuration(ms: number) {
  if (ms < 1000) return `${ms} 毫秒`;
  return `${(ms / 1000).toFixed(1)} 秒`;
}

function search() {
  page.value = 1;
}

function reset() {
  resetKeyword();
  page.value = 1;
}

function rowActions(row: ScheduledTaskRow): TableAction[] {
  const actions: TableAction[] = [];
  if (canRun.value && row.registryBound) {
    actions.push({
      key: 'run',
      label: runningKey.value === row.taskKey ? '执行中' : '立即执行',
      icon: VideoPlay,
      type: 'primary',
      disabled: runningKey.value === row.taskKey
    });
  }
  if (canEdit.value) {
    actions.push({ key: 'edit', label: '编辑', icon: EditPen, type: 'primary' });
    if (!row.registryBound) {
      actions.push({ key: 'delete', label: '删除', icon: Delete, type: 'danger', overflow: true });
    }
  }
  return actions;
}

function onRowAction(key: string, row: ScheduledTaskRow) {
  if (key === 'run') void onRun(row);
  else if (key === 'edit') openEdit(row);
  else if (key === 'delete') void onDelete(row);
}

async function load() {
  loading.value = true;
  try {
    items.value = await api.request<ScheduledTaskRow[]>('/api/v2/ops/admin/scheduled-tasks', 'GET');
    listHydrated.value = true;
    clearSelection();
  } catch (e: unknown) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

async function batchToggle(enabled: boolean) {
  const targets = pickSelected(filtered.value);
  if (!targets.length) {
    ElMessage.warning('请先勾选任务');
    return;
  }
  batchLoading.value = enabled ? 'enable' : 'disable';
  const results = await Promise.allSettled(
    targets.map((row) =>
      api.request(
        `/api/v2/ops/admin/scheduled-tasks/${encodeURIComponent(row.taskKey)}/enabled`,
        'PUT',
        { enabled }
      )
    )
  );
  batchLoading.value = '';
  const ok = results.filter((r) => r.status === 'fulfilled').length;
  ElMessage.success(
    `批量${enabled ? '启用' : '停用'}完成：成功 ${ok}，失败 ${targets.length - ok}`
  );
  await load();
}

async function batchRun() {
  const targets = pickSelected(filtered.value).filter((r) => r.registryBound);
  if (!targets.length) {
    ElMessage.warning('请先勾选已绑定 runner 的任务');
    return;
  }
  try {
    await ElMessageBox.confirm(`确认立即执行选中的 ${targets.length} 个任务？`, '批量执行', {
      type: 'warning'
    });
  } catch {
    return;
  }
  batchLoading.value = 'run';
  const results = await Promise.allSettled(
    targets.map((row) =>
      api.request(
        `/api/v2/ops/admin/scheduled-tasks/${encodeURIComponent(row.taskKey)}/run`,
        'POST'
      )
    )
  );
  batchLoading.value = '';
  const ok = results.filter((r) => r.status === 'fulfilled').length;
  ElMessage.success(`批量执行完成：成功 ${ok}，失败 ${targets.length - ok}`);
  await load();
}

async function onToggle(row: ScheduledTaskRow, enabled: boolean) {
  togglingKey.value = row.taskKey;
  try {
    await api.request(
      `/api/v2/ops/admin/scheduled-tasks/${encodeURIComponent(row.taskKey)}/enabled`,
      'PUT',
      { enabled }
    );
    row.enabled = enabled;
    ElMessage.success(enabled ? `已启用「${row.taskName}」` : `已停用「${row.taskName}」`);
  } catch (e: unknown) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败');
  } finally {
    togglingKey.value = '';
  }
}

async function onRun(row: ScheduledTaskRow) {
  try {
    await ElMessageBox.confirm(`确认立即执行「${row.taskName}」？`, '立即执行', {
      type: 'warning'
    });
  } catch {
    return;
  }
  runningKey.value = row.taskKey;
  try {
    const res = await api.request<{
      result: string;
      message: string;
      lastMessage?: string;
      lastDurationMs?: number;
    }>(`/api/v2/ops/admin/scheduled-tasks/${encodeURIComponent(row.taskKey)}/run`, 'POST');
    if (res?.result === 'SKIPPED') {
      ElMessage.warning(res.message || '任务已跳过');
    } else {
      ElMessage.success(res?.message || '已执行，请看「最近执行 / 最近结果说明」列');
    }
    await load();
  } catch (e: unknown) {
    ElMessage.error(e instanceof Error ? e.message : '执行失败');
  } finally {
    runningKey.value = '';
  }
}

function openCreate() {
  creating.value = true;
  editForm.taskKey = '';
  editForm.taskName = '';
  editForm.taskGroup = 'OPS';
  editForm.scheduleDesc = '';
  editForm.enabled = true;
  editForm.remark = '';
  editVisible.value = true;
}

function openEdit(row: ScheduledTaskRow) {
  creating.value = false;
  editForm.taskKey = row.taskKey;
  editForm.taskName = row.taskName;
  editForm.taskGroup = row.taskGroup;
  editForm.scheduleDesc = row.scheduleDesc || '';
  editForm.enabled = row.enabled;
  editForm.remark = row.remark || '';
  editVisible.value = true;
}

async function saveEdit() {
  if (!editForm.taskName.trim() || !editForm.taskGroup.trim()) {
    ElMessage.warning('请填写任务名称与分组');
    return;
  }
  if (creating.value && !editForm.taskKey.trim()) {
    ElMessage.warning('请填写任务标识');
    return;
  }
  editSaving.value = true;
  try {
    if (creating.value) {
      await api.request('/api/v2/ops/admin/scheduled-tasks', 'POST', {
        taskKey: editForm.taskKey.trim(),
        taskName: editForm.taskName.trim(),
        taskGroup: editForm.taskGroup.trim(),
        scheduleDesc: editForm.scheduleDesc.trim() || null,
        enabled: editForm.enabled,
        remark: editForm.remark.trim() || null
      });
      ElMessage.success('已新增');
    } else {
      await api.request(
        `/api/v2/ops/admin/scheduled-tasks/${encodeURIComponent(editForm.taskKey)}`,
        'PUT',
        {
          taskName: editForm.taskName.trim(),
          taskGroup: editForm.taskGroup.trim(),
          scheduleDesc: editForm.scheduleDesc.trim() || null,
          remark: editForm.remark.trim() || null
        }
      );
      ElMessage.success('已保存');
    }
    editVisible.value = false;
    await load();
  } catch (e: unknown) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败');
  } finally {
    editSaving.value = false;
  }
}

async function onDelete(row: ScheduledTaskRow) {
  try {
    await ElMessageBox.confirm(`确认删除自定义任务「${row.taskName}」？`, '删除', {
      type: 'warning'
    });
  } catch {
    return;
  }
  try {
    await api.request(
      `/api/v2/ops/admin/scheduled-tasks/${encodeURIComponent(row.taskKey)}`,
      'DELETE'
    );
    ElMessage.success('已删除');
    await load();
  } catch (e: unknown) {
    ElMessage.error(e instanceof Error ? e.message : '删除失败');
  }
}

onMounted(load);
</script>

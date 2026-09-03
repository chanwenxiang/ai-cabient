<template>
  <el-card class="page-card" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">素材库</span>
            <span class="hint">预览/演示素材管理；柜机端实际播放与曝光回写尚未接入</span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button
            v-if="hasSelection"
            v-hasPermi="['ops:ad:edit']"
            type="warning"
            :loading="batchLoading === 'deactivate'"
            @click="batchDeactivate"
          >
            批量停用
          </el-button>
          <el-button
            v-if="hasSelection"
            v-hasPermi="['ops:ad:edit']"
            type="danger"
            :loading="batchLoading === 'delete'"
            @click="batchDelete"
          >
            批量删除
          </el-button>
          <el-button @click="onExport">{{ exportButtonLabel }}</el-button>
          <input
            ref="fileInput"
            type="file"
            accept="image/*,video/*"
            class="hidden-input"
            @change="onPickFile"
          />
          <el-button
            v-hasPermi="['ops:ad:edit']"
            type="primary"
            :loading="uploading"
            @click="openUpload"
          >
            上传素材
          </el-button>
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </template>

    <div v-if="uploadOpen" class="upload-box">
      <div class="upload-row">
        <el-input v-model="uploadForm.title" placeholder="素材标题" style="width: 220px" />
        <el-select v-model="uploadForm.assetType" style="width: 120px">
          <el-option label="图片" value="IMAGE" />
          <el-option label="视频" value="VIDEO" />
          <el-option label="H5" value="H5" />
        </el-select>
        <el-input-number
          v-model="uploadForm.durationSeconds"
          :min="0"
          :max="3600"
          placeholder="时长(秒)"
        />
        <el-button type="primary" :loading="uploading" @click="doUpload">上传</el-button>
        <el-button @click="uploadOpen = false">取消</el-button>
      </div>
      <p class="muted">图片建议时长 10s；视频留 0 使用原始时长。文件不超过 50MB。</p>
    </div>

    <el-form inline class="filter-bar filter-bar--compact" @submit.prevent="search">
      <el-form-item label="关键词">
        <el-input
          v-model="keyword"
          clearable
          placeholder="标题 / 类型"
          style="width: 200px"
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
          :data="displayRows"
          stripe
          border
          row-key="assetId"
          class="report-table"
          :default-sort="idDefaultSort"
          @sort-change="onIdSortChange"
          @selection-change="onSelectionChange"
        >
          <el-table-column type="selection" width="48" align="center" reserve-selection />
          <el-table-column prop="assetId" label="ID" width="80" align="center" sortable="custom" />
          <el-table-column prop="title" label="标题" min-width="160" show-overflow-tooltip />
          <el-table-column label="类型" width="90" align="center">
            <template #default="{ row }">{{ typeLabel(row.assetType) }}</template>
          </el-table-column>
          <el-table-column label="预览" width="120" align="center">
            <template #default="{ row }">
              <el-image
                v-if="row.assetType === 'IMAGE' && row.previewUrl"
                :src="row.previewUrl"
                fit="cover"
                class="asset-thumb"
                :preview-src-list="[row.previewUrl]"
                preview-teleported
              />
              <el-tag v-else-if="row.assetType === 'VIDEO'" size="small">视频</el-tag>
              <span v-else>暂无</span>
            </template>
          </el-table-column>
          <el-table-column label="时长(秒)" prop="durationSeconds" width="90" align="center" />
          <el-table-column label="状态" width="90" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 'ACTIVE' ? 'success' : 'info'">
                {{ row.status === 'ACTIVE' ? '在用' : '停用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="上传时间" width="170" align="center">
            <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column
            label="操作"
            width="200"
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
      :total="total"
      :page-sizes="[10, 20, 50, 100]"
      layout="total, sizes, prev, pager, next"
      background
      @current-change="load"
      @size-change="onSizeChange"
    />

    <el-dialog v-model="editVisible" title="编辑素材" width="420px">
      <el-form label-position="top">
        <el-form-item label="标题">
          <el-input v-model="editForm.title" />
        </el-form-item>
        <el-form-item label="轮播时长(秒)">
          <el-input-number v-model="editForm.durationSeconds" :min="0" :max="3600" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="editForm.active" active-text="在用" inactive-text="停用" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveEdit">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { Delete, EditPen, Refresh } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api, authFetch } from '@/api/client';
import PagePager from '@/components/PagePager.vue';
import TableActions, { type TableAction } from '@/components/TableActions.vue';
import { useAdminListTable } from '@/composables/useAdminListTable';
import { useListCsv } from '@/composables/useListCsv';
import { useAuthStore } from '@/stores/auth';
import { displayLabel } from '@aicabinet/shared-dict';
import type { MediaAssetDto } from '@aicabinet/shared-types';
import { useIdColumnSort } from '@/composables/useIdColumnSort';

const loading = ref(false);
const auth = useAuthStore();
const listHydrated = ref(false);
const page = ref(1);
const size = ref(20);
const total = ref(0);
const uploading = ref(false);
const saving = ref(false);
const rows = ref<MediaAssetDto[]>([]);
const batchLoading = ref<'delete' | 'deactivate' | ''>('');
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
} = useAdminListTable<MediaAssetDto>((r) => r.assetId);
const { idDefaultSort, onIdSortChange, sortById } = useIdColumnSort<MediaAssetDto>('assetId');
const displayRows = computed(() =>
  sortById(
    filterByKeyword(rows.value, (row, kw) => {
      return (
        String(row.title || '')
          .toLowerCase()
          .includes(kw) ||
        String(row.assetType || '')
          .toLowerCase()
          .includes(kw) ||
        typeLabel(row.assetType).toLowerCase().includes(kw)
      );
    })
  )
);

const { onExport } = useListCsv({
  filePrefix: '素材库',
  headers: ['ID', '标题', '类型', '时长(秒)', '状态', '上传时间'],
  toRows: () =>
    pickSelected(displayRows.value).map((r) => [
      r.assetId,
      r.title,
      typeLabel(r.assetType),
      r.durationSeconds ?? '',
      r.status === 'ACTIVE' ? '在用' : '停用',
      formatDateTime(r.createdAt)
    ])
});
const fileInput = ref<HTMLInputElement | null>(null);
const uploadOpen = ref(false);
const uploadForm = ref({ title: '', assetType: 'IMAGE', durationSeconds: 10 });
const editVisible = ref(false);
const editForm = ref({ assetId: 0, title: '', durationSeconds: 10, active: true });

onMounted(load);

function search() {
  page.value = 1;
  load();
}

function reset() {
  resetKeyword();
  page.value = 1;
  load();
}

function rowActions(_row: MediaAssetDto): TableAction[] {
  if (!auth.hasPerm('ops:ad:edit')) return [];
  return [
    { key: 'edit', label: '编辑', icon: EditPen, type: 'primary' },
    { key: 'delete', label: '删除', icon: Delete, type: 'danger' }
  ];
}

function onRowAction(key: string, row: MediaAssetDto) {
  if (key === 'edit') openEdit(row);
  else if (key === 'delete') void removeAsset(row);
}

async function load() {
  loading.value = true;
  try {
    const q = new URLSearchParams({
      page: String(page.value - 1),
      size: String(size.value)
    });
    const data = await api.request<{ items: MediaAssetDto[]; total: number }>(
      `/api/v2/ops/admin/ad/assets?${q}`,
      'GET'
    );
    rows.value = data.items || [];
    total.value = Number(data.total) || 0;
    clearSelection();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    listHydrated.value = true;
    loading.value = false;
  }
}

function onSizeChange() {
  page.value = 1;
  load();
}

function openUpload() {
  uploadOpen.value = true;
  uploadForm.value = { title: '', assetType: 'IMAGE', durationSeconds: 10 };
  fileInput.value?.click();
}

function onPickFile() {
  const file = fileInput.value?.files?.[0];
  if (!file) return;
  void doUploadFile(file);
}

function doUpload() {
  if (!uploadForm.value.title.trim()) {
    ElMessage.warning('请填写素材标题');
    return;
  }
  fileInput.value?.click();
}

async function doUploadFile(file: File) {
  if (file.size > 50 * 1024 * 1024) {
    ElMessage.warning('文件不能超过 50MB');
    return;
  }
  uploading.value = true;
  try {
    const base =
      (import.meta.env.VITE_API_BASE || '').replace(/\/$/, '') || globalThis.location.origin;
    const fd = new FormData();
    fd.append('file', file);
    fd.append('title', uploadForm.value.title.trim());
    fd.append('assetType', uploadForm.value.assetType);
    fd.append('durationSeconds', String(uploadForm.value.durationSeconds || 0));
    const res = await authFetch(`${base}/api/v2/ops/admin/ad/assets`, {
      method: 'POST',
      body: fd
    });
    const json = await res.json().catch(() => ({}));
    if (!res.ok || json.code !== 0) {
      throw new Error(json.message || `上传失败 (${res.status})`);
    }
    ElMessage.success('上传成功');
    uploadOpen.value = false;
    await load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '上传失败');
  } finally {
    uploading.value = false;
    if (fileInput.value) fileInput.value.value = '';
  }
}

function openEdit(row: MediaAssetDto) {
  editForm.value = {
    assetId: row.assetId,
    title: row.title,
    durationSeconds: row.durationSeconds,
    active: row.status === 'ACTIVE'
  };
  editVisible.value = true;
}

async function saveEdit() {
  saving.value = true;
  try {
    await api.request(`/api/v2/ops/admin/ad/assets/${editForm.value.assetId}`, 'PUT', {
      title: editForm.value.title.trim(),
      durationSeconds: editForm.value.durationSeconds,
      status: editForm.value.active ? 'ACTIVE' : 'INACTIVE'
    });
    ElMessage.success('已保存');
    editVisible.value = false;
    await load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败');
  } finally {
    saving.value = false;
  }
}

async function removeAsset(row: MediaAssetDto) {
  try {
    await ElMessageBox.confirm(`确认删除素材「${row.title}」？删除后不可恢复。`, '删除素材', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    });
  } catch {
    return;
  }
  try {
    await api.request(`/api/v2/ops/admin/ad/assets/${row.assetId}`, 'DELETE');
    ElMessage.success('已删除');
    await load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除失败');
  }
}

async function batchDelete() {
  const targets = pickSelected(displayRows.value);
  if (!targets.length) {
    ElMessage.warning('请先勾选素材');
    return;
  }
  try {
    await ElMessageBox.confirm(`确认删除选中的 ${targets.length} 个素材？`, '批量删除', {
      type: 'warning'
    });
  } catch {
    return;
  }
  batchLoading.value = 'delete';
  const results = await Promise.allSettled(
    targets.map((row) => api.request(`/api/v2/ops/admin/ad/assets/${row.assetId}`, 'DELETE'))
  );
  batchLoading.value = '';
  const ok = results.filter((r) => r.status === 'fulfilled').length;
  ElMessage.success(`批量删除完成：成功 ${ok}，失败 ${targets.length - ok}`);
  await load();
}

async function batchDeactivate() {
  const targets = pickSelected(displayRows.value).filter((r) => r.status === 'ACTIVE');
  if (!targets.length) {
    ElMessage.warning('请先勾选在用素材');
    return;
  }
  batchLoading.value = 'deactivate';
  const results = await Promise.allSettled(
    targets.map((row) =>
      api.request(`/api/v2/ops/admin/ad/assets/${row.assetId}`, 'PUT', {
        title: row.title,
        durationSeconds: row.durationSeconds,
        status: 'INACTIVE'
      })
    )
  );
  batchLoading.value = '';
  const ok = results.filter((r) => r.status === 'fulfilled').length;
  ElMessage.success(`批量停用完成：成功 ${ok}，失败 ${targets.length - ok}`);
  await load();
}

function typeLabel(type: string) {
  return displayLabel('ad_asset_type', type, '未知');
}

function formatDateTime(iso?: string) {
  if (!iso) return '暂无';
  return new Date(iso).toLocaleString('zh-CN', { hour12: false });
}
</script>

<style scoped>
.hidden-input {
  display: none;
}
.upload-box {
  margin-bottom: 14px;
  padding: 12px;
  border: 1px dashed var(--el-border-color);
  border-radius: 8px;
}
.upload-row {
  display: flex;
  gap: 10px;
  align-items: center;
  flex-wrap: wrap;
}
.asset-thumb {
  width: 64px;
  height: 64px;
  border-radius: 6px;
}
.muted {
  margin: 8px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
</style>

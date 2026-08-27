<template>
  <el-card class="page-card" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">素材库</span>
            <span class="hint">图片 / 视频 / H5 素材，供设备屏投放使用</span>
          </div>
        </div>
        <div class="page-card-head__actions">
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

    <el-table
      v-loading="loading"
      :data="displayRows"
      stripe
      border
      :default-sort="idDefaultSort"
      @sort-change="onIdSortChange"
    >
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
      <el-table-column label="操作" width="200" align="center" fixed="right">
        <template #default="{ row }">
          <el-button v-hasPermi="['ops:ad:edit']" size="small" @click="openEdit(row)"
            >编辑</el-button
          >
          <el-button
            v-hasPermi="['ops:ad:edit']"
            size="small"
            type="danger"
            @click="removeAsset(row)"
            >删除</el-button
          >
        </template>
      </el-table-column>
    </el-table>

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
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api, authFetch } from '@/api/client';
import PagePager from '@/components/PagePager.vue';
import { dictLabel, displayLabel } from '@aicabinet/shared-dict';
import type { MediaAssetDto } from '@aicabinet/shared-types';
import { useIdColumnSort } from '@/composables/useIdColumnSort';

const loading = ref(false);
const listHydrated = ref(false);
const page = ref(1);
const size = ref(20);
const total = ref(0);
const uploading = ref(false);
const saving = ref(false);
const rows = ref<MediaAssetDto[]>([]);
const { idDefaultSort, onIdSortChange, sortById } = useIdColumnSort<MediaAssetDto>('assetId');
const displayRows = computed(() => sortById(rows.value));
const fileInput = ref<HTMLInputElement | null>(null);
const uploadOpen = ref(false);
const uploadForm = ref({ title: '', assetType: 'IMAGE', durationSeconds: 10 });
const editVisible = ref(false);
const editForm = ref({ assetId: 0, title: '', durationSeconds: 10, active: true });

onMounted(load);

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
    const base = (import.meta.env.VITE_API_BASE || '').replace(/\/$/, '') || globalThis.location.origin;
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

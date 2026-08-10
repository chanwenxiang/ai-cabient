<template>
  <el-card class="page-card" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">投放计划</span>
            <span class="hint">按时间窗与设备范围向柜机屏幕下发轮播素材</span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button v-hasPermi="['ops:ad:edit']" type="primary" @click="openCreate">
           新建投放
          </el-button>
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </template>

    <el-table v-loading="loading" :data="rows" stripe border>
      <el-table-column prop="campaignId" label="ID" width="80" align="center" />
      <el-table-column prop="name" label="名称" min-width="160" show-overflow-tooltip />
      <el-table-column label="状态" width="100" align="center">
        <template #default="{ row }">
          <el-tag size="small" :type="statusType(row.status)">{{ statusLabel(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="范围" width="120" align="center">
        <template #default="{ row }">
          {{ row.deviceScope === 'SPECIFIC' ? `${row.deviceIds.length} 台定向` : '全部设备' }}
        </template>
      </el-table-column>
      <el-table-column label="素材" width="90" align="center">
        <template #default="{ row }">{{ row.assetIds.length }} 个</template>
      </el-table-column>
      <el-table-column label="时间窗" min-width="220" align="center">
        <template #default="{ row }">
          {{ formatRange(row) }}
        </template>
      </el-table-column>
      <el-table-column label="操作" width="220" align="center">
        <template #default="{ row }">
            <el-button v-hasPermi="['ops:ad:edit']" size="small" @click="openEdit(row)"
            >编辑</el-button
          >
          <el-button
            v-if="row.status === 'DRAFT' || row.status === 'STOPPED'"
            v-hasPermi="['ops:ad:edit']"
            size="small"
            type="primary"
            @click="launch(row)"
            >上线</el-button
          >
          <el-button
            v-if="row.status === 'RUNNING'"
            v-hasPermi="['ops:ad:edit']"
            size="small"
            type="warning"
            @click="stop(row)"
            >停止</el-button
          >
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑投放' : '新建投放'" width="560px">
      <el-form label-position="top">
        <el-form-item label="名称">
          <el-input v-model="form.name" placeholder="如：暑期饮料促销" />
        </el-form-item>
        <el-form-item label="投放范围">
          <el-radio-group v-model="form.deviceScope">
            <el-radio value="ALL">全部设备</el-radio>
            <el-radio value="SPECIFIC">指定设备</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="form.deviceScope === 'SPECIFIC'" label="选择设备">
          <el-select
            v-model="form.deviceIds"
            multiple
            filterable
            placeholder="选择柜机"
            style="width: 100%"
          >
            <el-option
              v-for="d in deviceOptions"
              :key="d.deviceId"
              :label="d.deviceName || d.deviceId"
              :value="d.deviceId"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="投放时间窗（留空为不限）">
          <el-date-picker
            v-model="form.window"
            type="datetimerange"
            start-placeholder="开始"
            end-placeholder="结束"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="轮播素材（按选择顺序）">
          <el-select v-model="form.assetIds" multiple placeholder="选择素材" style="width: 100%">
            <el-option
              v-for="a in assets"
              :key="a.assetId"
              :label="`${a.title}（${typeLabel(a.assetType)}）`"
              :value="a.assetId"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { api } from '@/api/client';
import type { AdCampaignDto, MediaAssetDto } from '@aicabinet/shared-types';

const loading = ref(false);
const saving = ref(false);
const rows = ref<AdCampaignDto[]>([]);
const assets = ref<MediaAssetDto[]>([]);
const deviceOptions = ref<{ deviceId: string; deviceName?: string }[]>([]);
const dialogVisible = ref(false);
const editingId = ref<number | null>(null);
const form = ref<{
  name: string;
  deviceScope: string;
  deviceIds: string[];
  assetIds: number[];
  window: [Date, Date] | null;
}>({ name: '', deviceScope: 'ALL', deviceIds: [], assetIds: [], window: null });

onMounted(async () => {
  await Promise.all([load(), loadAssets(), loadDevices()]);
});

async function load() {
  loading.value = true;
  try {
    rows.value = (await api.request<AdCampaignDto[]>('/api/v2/ops/admin/ad/campaigns', 'GET')) || [];
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

async function loadAssets() {
  try {
    assets.value = (await api.request<MediaAssetDto[]>('/api/v2/ops/admin/ad/assets', 'GET')) || [];
  } catch {
    assets.value = [];
  }
}

async function loadDevices() {
  try {
    deviceOptions.value =
      (await api.request<{ deviceId: string; deviceName?: string }[]>(
        '/api/v2/ops/admin/devices/ref',
        'GET'
      )) || [];
  } catch {
    deviceOptions.value = [];
  }
}

function openCreate() {
  editingId.value = null;
  form.value = { name: '', deviceScope: 'ALL', deviceIds: [], assetIds: [], window: null };
  dialogVisible.value = true;
}

function openEdit(row: AdCampaignDto) {
  editingId.value = row.campaignId;
  form.value = {
    name: row.name,
    deviceScope: row.deviceScope,
    deviceIds: [...row.deviceIds],
    assetIds: [...row.assetIds],
    window: row.startAt && row.endAt ? [new Date(row.startAt), new Date(row.endAt)] : null
  };
  dialogVisible.value = true;
}

async function save() {
  if (!form.value.name.trim()) {
    ElMessage.warning('请填写名称');
    return;
  }
  if (!form.value.assetIds.length) {
    ElMessage.warning('请至少选择一个素材');
    return;
  }
  if (form.value.deviceScope === 'SPECIFIC' && !form.value.deviceIds.length) {
    ElMessage.warning('定向投放请选择设备');
    return;
  }
  saving.value = true;
  try {
    const body = {
      name: form.value.name.trim(),
      deviceScope: form.value.deviceScope,
      startAt: form.value.window?.[0]?.toISOString() ?? null,
      endAt: form.value.window?.[1]?.toISOString() ?? null,
      assetIds: form.value.assetIds,
      deviceIds: form.value.deviceScope === 'SPECIFIC' ? form.value.deviceIds : []
    };
    if (editingId.value) {
      await api.request(`/api/v2/ops/admin/ad/campaigns/${editingId.value}`, 'PUT', body);
    } else {
      await api.request('/api/v2/ops/admin/ad/campaigns', 'POST', body);
    }
    ElMessage.success('已保存');
    dialogVisible.value = false;
    await load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败');
  } finally {
    saving.value = false;
  }
}

async function launch(row: AdCampaignDto) {
  try {
    await api.request(`/api/v2/ops/admin/ad/campaigns/${row.campaignId}/launch`, 'POST');
    ElMessage.success('已上线');
    await load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '上线失败');
  }
}

async function stop(row: AdCampaignDto) {
  try {
    await api.request(`/api/v2/ops/admin/ad/campaigns/${row.campaignId}/stop`, 'POST');
    ElMessage.success('已停止');
    await load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '停止失败');
  }
}

function statusLabel(s: string) {
  return ({ DRAFT: '草稿', RUNNING: '投放中', STOPPED: '已停止' } as Record<string, string>)[s] || s;
}

function statusType(s: string) {
  return ({ DRAFT: 'info', RUNNING: 'success', STOPPED: 'warning' } as Record<string, string>)[s] || 'info';
}

function typeLabel(t: string) {
  return ({ IMAGE: '图片', VIDEO: '视频', H5: 'H5' } as Record<string, string>)[t] || t;
}

function formatRange(row: AdCampaignDto) {
  if (!row.startAt && !row.endAt) return '不限';
  const f = (iso?: string) => (iso ? new Date(iso).toLocaleString('zh-CN', { hour12: false }) : '—');
  return `${f(row.startAt)} ~ ${f(row.endAt)}`;
}
</script>

<template>
  <el-card class="page-card" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">投放计划</span>
            <span class="hint">投放后消费者小程序首页（开门页）可拉取素材并回传曝光/完播/点击</span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button
            v-if="crud.hasSelection"
            v-hasPermi="['ops:ad:edit']"
            type="warning"
            :loading="batchLoading === 'stop'"
            @click="batchStop"
          >
            批量停止
          </el-button>
          <el-button v-hasPermi="['ops:ad:edit']" type="primary" @click="openCreate">
            新建投放
          </el-button>
        </div>
      </div>
    </template>

    <el-form inline class="filter-bar filter-bar--compact" @submit.prevent="search">
      <el-form-item label="关键词">
        <el-input
          v-model="keyword"
          clearable
          placeholder="名称 / 状态"
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
        <CrudTable
          :table="crud"
          row-key="campaignId"
          selectable
          :actions="rowActions"
          :action-width="200"
          actions-testid="ad-campaign"
          empty-text="暂无投放计划"
          sort-field-label="ID"
          :csv="csvOptions"
          @action="onAction"
        >
          <el-table-column
            prop="campaignId"
            label="ID"
            width="80"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          />
          <el-table-column prop="name" label="名称" min-width="160" />
          <el-table-column
            label="状态"
            width="100"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <el-tag size="small" :type="statusType(row.status)">{{
                statusLabel(row.status)
              }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column
            label="范围"
            width="120"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              {{ row.deviceScope === 'SPECIFIC' ? `${row.deviceIds.length} 台定向` : '全部设备' }}
            </template>
          </el-table-column>
          <el-table-column
            label="素材"
            width="90"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">{{ row.assetIds.length }} 个</template>
          </el-table-column>
          <el-table-column
            label="曝光"
            width="80"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">{{ row.impressionCount ?? 0 }}</template>
          </el-table-column>
          <el-table-column
            label="完播"
            width="80"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">{{ row.completeCount ?? 0 }}</template>
          </el-table-column>
          <el-table-column
            label="完播率"
            width="90"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              {{
                Number(row.impressionCount) > 0
                  ? `${((Number(row.completeCount || 0) / Number(row.impressionCount)) * 100).toFixed(1)}%`
                  : '暂无'
              }}
            </template>
          </el-table-column>
          <el-table-column
            label="柜机数"
            width="80"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">{{
              Array.isArray(row.deviceIds) ? row.deviceIds.length : '暂无'
            }}</template>
          </el-table-column>
          <el-table-column
            label="时间窗"
            min-width="220"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              {{ formatRange(row) }}
            </template>
          </el-table-column>
        </CrudTable>
      </div>
    </div>

    <el-dialog
      v-model="dialogVisible"
      :title="editingId ? '编辑投放' : '新建投放'"
      class="dialog-wide"
    >
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
import { Delete, EditPen, VideoPause, VideoPlay } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import CrudTable, { type CrudCsvOptions, type CrudRowAction } from '@/components/CrudTable.vue';
import { useCrudTable } from '@/composables/useCrudTable';
import { displayLabel } from '@aicabinet/shared-dict';
import type { AdCampaignDto, MediaAssetDto } from '@aicabinet/shared-types';

const keyword = ref('');
const saving = ref(false);
const batchLoading = ref<'stop' | ''>('');

function matchKeyword(row: AdCampaignDto, kw: string) {
  return (
    String(row.name || '')
      .toLowerCase()
      .includes(kw) ||
    statusLabel(row.status).toLowerCase().includes(kw) ||
    String(row.status || '')
      .toLowerCase()
      .includes(kw)
  );
}

// 列表状态机统一交给 CrudTable：分页 / 排序 / 多选 / 竞态 / 空态 全部内建
const crud = useCrudTable<AdCampaignDto>({
  rowKey: (r) => r.campaignId,
  fetchPage: async (params) => {
    const q = new URLSearchParams({
      page: String(params.page), // 0 起（useCrudTable 已换算）
      size: String(params.size)
    });
    const data = await api.request<{ items: AdCampaignDto[]; total: number }>(
      AdminEndpoints.adCampaignsList(q),
      'GET'
    );
    // 关键词沿用前端过滤（只作用于当前页，与原 displayRows 行为一致），total 仍取服务端值
    const kw = keyword.value.trim().toLowerCase();
    const items = kw ? (data.items || []).filter((r) => matchKeyword(r, kw)) : data.items || [];
    return { items, total: Number(data.total) || 0 };
  },
  // 投放 ID 本地排序（替代原 useIdColumnSort 表头排序，改由壳内「按 ID 升/降序」切换）
  sort: { prop: 'campaignId', mode: 'local' }
});

const csvOptions: CrudCsvOptions = {
  filePrefix: '投放计划',
  exportPerm: 'ops:ad:export',
  headers: ['ID', '名称', '状态', '范围', '素材数', '曝光', '完播', '时间窗'],
  toRows: (rows) =>
    rows.map((r: AdCampaignDto) => [
      r.campaignId,
      r.name,
      statusLabel(r.status),
      r.deviceScope === 'SPECIFIC' ? `${r.deviceIds.length} 台定向` : '全部设备',
      r.assetIds.length,
      r.impressionCount ?? 0,
      r.completeCount ?? 0,
      formatRange(r)
    ])
};

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

// 列表首查由 useCrudTable autoLoad（默认 true）在挂载时执行；这里只拉弹窗所需的素材/设备选项
onMounted(() => {
  void loadAssets();
  void loadDevices();
});

function search() {
  void crud.search();
}

function reset() {
  keyword.value = '';
  void crud.search();
}

function rowActions(row: AdCampaignDto): CrudRowAction[] {
  const actions: CrudRowAction[] = [
    { key: 'edit', label: '编辑', icon: EditPen, type: 'primary', perm: 'ops:ad:edit' }
  ];
  if (row.status === 'DRAFT' || row.status === 'STOPPED') {
    actions.push({
      key: 'launch',
      label: '上线',
      icon: VideoPlay,
      type: 'success',
      perm: 'ops:ad:edit'
    });
  }
  if (row.status === 'RUNNING') {
    actions.push({
      key: 'stop',
      label: '停止',
      icon: VideoPause,
      type: 'warning',
      perm: 'ops:ad:edit'
    });
  }
  if (row.status !== 'RUNNING') {
    actions.push({
      key: 'delete',
      label: '删除',
      icon: Delete,
      type: 'danger',
      overflow: true,
      perm: 'ops:ad:edit'
    });
  }
  return actions;
}

function onAction({ key, row }: { key: string; row: AdCampaignDto }) {
  if (key === 'edit') openEdit(row);
  else if (key === 'launch') void launch(row);
  else if (key === 'stop') void stop(row);
  else if (key === 'delete') void removeCampaign(row);
}

async function loadAssets() {
  try {
    const q = new URLSearchParams({ page: '0', size: '500' });
    const data = await api.request<{ items: MediaAssetDto[] }>(
      AdminEndpoints.adAssetsList(q),
      'GET'
    );
    assets.value = data.items || [];
  } catch {
    assets.value = [];
  }
}

async function loadDevices() {
  try {
    deviceOptions.value =
      (await api.request<{ deviceId: string; deviceName?: string }[]>(
        AdminEndpoints.devicesRef,
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
      await api.request(AdminEndpoints.adCampaign(editingId.value), 'PUT', body);
    } else {
      await api.request(AdminEndpoints.adCampaigns, 'POST', body);
    }
    ElMessage.success('已保存');
    dialogVisible.value = false;
    await crud.load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败');
  } finally {
    saving.value = false;
  }
}

async function launch(row: AdCampaignDto) {
  try {
    await api.request(AdminEndpoints.adCampaignLaunch(row.campaignId), 'POST');
    ElMessage.success('已上线');
    await crud.load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '上线失败');
  }
}

async function stop(row: AdCampaignDto) {
  try {
    await api.request(AdminEndpoints.adCampaignStop(row.campaignId), 'POST');
    ElMessage.success('已停止');
    await crud.load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '停止失败');
  }
}

async function batchStop() {
  const targets = crud.pickSelected(crud.displayItems).filter((r) => r.status === 'RUNNING');
  if (!targets.length) {
    ElMessage.warning('请先勾选运行中的投放计划');
    return;
  }
  try {
    await ElMessageBox.confirm(`确认停止选中的 ${targets.length} 个投放计划？`, '批量停止', {
      type: 'warning'
    });
  } catch {
    return;
  }
  batchLoading.value = 'stop';
  const results = await Promise.allSettled(
    targets.map((row) => api.request(AdminEndpoints.adCampaignStop(row.campaignId), 'POST'))
  );
  batchLoading.value = '';
  const ok = results.filter((r) => r.status === 'fulfilled').length;
  ElMessage.success(`批量停止完成：成功 ${ok}，失败 ${targets.length - ok}`);
  await crud.load();
}

async function removeCampaign(row: AdCampaignDto) {
  try {
    await ElMessageBox.confirm(`确认删除投放计划「${row.name}」？`, '删除投放', {
      type: 'warning',
      confirmButtonText: '删除'
    });
  } catch {
    return;
  }
  try {
    await api.request(AdminEndpoints.adCampaign(row.campaignId), 'DELETE');
    ElMessage.success('已删除');
    await crud.load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除失败');
  }
}

function statusLabel(s: string) {
  return displayLabel('ad_campaign_status', s, '未知');
}

function statusType(s: string) {
  return (
    ({ DRAFT: 'info', RUNNING: 'success', STOPPED: 'warning' } as Record<string, string>)[s] ||
    'info'
  );
}

function typeLabel(t: string) {
  return displayLabel('ad_asset_type', t, '未知');
}

function formatRange(row: AdCampaignDto) {
  if (!row.startAt && !row.endAt) return '不限';
  const f = (iso?: string) =>
    iso ? new Date(iso).toLocaleString('zh-CN', { hour12: false }) : '暂无';
  return `${f(row.startAt)} ~ ${f(row.endAt)}`;
}
</script>

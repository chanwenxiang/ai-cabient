<template>
  <el-card class="page-card" shadow="never">
    <template #header>
      <div class="card-head">
        <span>审计日志</span>
        <div class="actions">
          <el-button @click="onExport">导出</el-button>
          <el-switch v-model="mineOnly" active-text="仅看我的" @change="load" />
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </template>
    <div class="table-scroll">
      <div class="table-scroll-inner" style="min-width: 1080px">
        <el-table v-loading="loading" :data="items" stripe border>
          <template #empty><el-empty description="暂无审计日志" /></template>
          <el-table-column label="时间" width="180"><template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template></el-table-column>
          <el-table-column label="操作人" min-width="150" show-overflow-tooltip>
            <template #default="{ row }">{{ row.operatorName || row.operatorPhone || row.operatorId || '-' }}</template>
          </el-table-column>
          <el-table-column label="动作" min-width="150" show-overflow-tooltip><template #default="{ row }">{{ actionLabel(row.action) }}</template></el-table-column>
          <el-table-column label="对象类型" width="120"><template #default="{ row }">{{ targetLabel(row.targetType) }}</template></el-table-column>
          <el-table-column prop="targetId" label="对象ID" min-width="140" show-overflow-tooltip><template #default="{ row }"><span class="cell-id">{{ row.targetId || '-' }}</span></template></el-table-column>
          <el-table-column label="详情" min-width="220" show-overflow-tooltip>
            <template #default="{ row }">{{ formatOpsActionDetail(row.detail) }}</template>
          </el-table-column>
        </el-table>
      </div>
    </div>
    <el-pagination
      v-if="!mineOnly"
      v-model:current-page="page"
      :page-size="size"
      :total="total"
      layout="prev,pager,next,total"
      style="margin-top:16px"
      @current-change="load"
    />
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { formatOpsActionDetail } from '@aicabinet/shared-dict';
import { formatDateTime } from '@aicabinet/shared-uni/format';
import type { PageResult } from '@aicabinet/shared-types';
import { api } from '@/api/client';
import { useListCsv } from '@/composables/useListCsv';

interface AuditRow {
  logId: number;
  operatorId?: number;
  operatorPhone?: string;
  operatorName?: string;
  action?: string;
  targetType?: string;
  targetId?: string;
  detail?: string;
  createdAt?: string;
}

const ACTION_LABELS: Record<string, string> = {
  BALANCE_ADJUST: '余额调整',
  SESSION_CANCEL: '取消会话',
  DICT_TYPE_CREATE: '新建字典类型',
  DICT_TYPE_UPDATE: '更新字典类型',
  DICT_DATA_CREATE: '新建字典项',
  DICT_DATA_UPDATE: '更新字典项',
  DICT_DATA_DELETE: '删除字典项',
  SKU_VISION_ENROLL_CREATE: '识别建档',
  SKU_VISION_ENROLL_UPDATE: '更新识别建档',
  SKU_VISION_STATUS: '识别状态变更'
};

const TARGET_LABELS: Record<string, string> = {
  USER: '用户',
  SESSION: '会话',
  ORDER: '订单',
  SKU: '商品',
  DEVICE: '设备',
  DICT_TYPE: '字典类型',
  DICT_DATA: '字典项'
};

const loading = ref(false);
const mineOnly = ref(false);
const page = ref(1);
const size = 20;
const total = ref(0);
const items = ref<AuditRow[]>([]);

const { onExport } = useListCsv({
  filePrefix: '审计日志',
  headers: ['时间', '操作人', '动作', '对象类型', '对象ID', '详情'],
  toRows: () =>
    items.value.map((row) => [
      formatDateTime(row.createdAt),
      row.operatorName || row.operatorPhone || row.operatorId || '-',
      actionLabel(row.action),
      targetLabel(row.targetType),
      row.targetId || '-',
      formatOpsActionDetail(row.detail)
    ])
});

function actionLabel(action?: string) {
  if (!action) return '-';
  return ACTION_LABELS[action] || action;
}

function targetLabel(type?: string) {
  if (!type) return '-';
  return TARGET_LABELS[type] || type;
}

async function load() {
  loading.value = true;
  try {
    if (mineOnly.value) {
      items.value = await api.request<AuditRow[]>('/api/v2/ops/admin/audit-logs/recent?size=50&mine=true', 'GET');
      total.value = items.value.length;
    } else {
      const q = new URLSearchParams({ page: String(page.value - 1), size: String(size) });
      const data = await api.request<PageResult<AuditRow>>(`/api/v2/ops/admin/audit-logs?${q}`, 'GET');
      items.value = data.items;
      total.value = data.total;
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

onMounted(load);
</script>

<style scoped>
.card-head { display:flex; justify-content:space-between; align-items:center; gap:8px; flex-wrap:wrap; }
.actions { display:flex; gap:8px; align-items:center; flex-wrap:wrap; }
</style>

<template>
  <el-card class="page-card">
    <template #header>
      <div style="display:flex;justify-content:space-between;align-items:center">
        <span>争议审核</span>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>
    </template>
    <el-form inline class="filter-bar">
      <el-form-item label="状态">
        <el-select v-model="status" style="width:120px" @change="load">
          <el-option v-for="item in dictOptions('dispute_status')" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
      </el-form-item>
      <el-form-item><el-button type="primary" @click="load">查询</el-button></el-form-item>
    </el-form>
    <el-table v-loading="loading" :data="items" stripe>
      <el-table-column prop="ticketId" label="工单" />
      <el-table-column prop="deviceId" label="设备" />
      <el-table-column prop="sessionId" label="会话" />
      <el-table-column label="状态"><template #default="{ row }">{{ dictLabel('dispute_status', row.status) }}</template></el-table-column>
      <el-table-column prop="reason" label="原因" show-overflow-tooltip />
      <el-table-column prop="createdAt" label="创建时间" />
      <el-table-column label="操作" width="100" fixed="right">
        <template #default="{ row }">
          <el-button type="primary" link @click="openDetail(row)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-drawer v-model="detailVisible" title="争议工单详情" size="520px">
      <el-descriptions v-if="selected" :column="1" border>
        <el-descriptions-item label="工单">{{ selected.ticketId }}</el-descriptions-item>
        <el-descriptions-item label="会话">{{ selected.sessionId }}</el-descriptions-item>
        <el-descriptions-item label="设备">{{ selected.deviceId }}</el-descriptions-item>
        <el-descriptions-item label="原因">{{ selected.reason }}</el-descriptions-item>
        <el-descriptions-item label="已扣金额">¥{{ ((selected.billedAmountCents || 0) / 100).toFixed(2) }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ dictLabel('dispute_status', selected.status) }}</el-descriptions-item>
      </el-descriptions>
      <div v-if="selected?.status === 'OPEN'" class="drawer-actions">
        <el-button type="primary" :loading="resolving" @click="resolveSelected('KEEP')">维持原账单</el-button>
        <el-button type="danger" plain :loading="resolving" @click="resolveSelected('WAIVE')">免单并退款</el-button>
      </div>
    </el-drawer>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { dictLabel, dictOptions } from '@aicabinet/shared-dict';
import { api } from '@/api/client';
import type { DisputeTicketDto, PageResult } from '@aicabinet/shared-types';

const loading = ref(false);
const status = ref('OPEN');
const items = ref<DisputeTicketDto[]>([]);
const selected = ref<DisputeTicketDto | null>(null);
const detailVisible = ref(false);
const resolving = ref(false);

function openDetail(row: DisputeTicketDto) {
  selected.value = row;
  detailVisible.value = true;
}

async function resolveSelected(resolutionType: 'KEEP' | 'WAIVE') {
  if (!selected.value || resolving.value) return;
  const action = resolutionType === 'KEEP' ? '维持原账单' : '免单并退回全部已扣余额';
  await ElMessageBox.confirm(`确认${action}？该操作会写入资金与审计记录。`, '确认争议处理', {
    type: resolutionType === 'WAIVE' ? 'warning' : 'info',
    confirmButtonText: '确认处理',
    cancelButtonText: '取消'
  });
  resolving.value = true;
  try {
    const result = await api.request<{ message?: string }>(
      `/api/v2/ops/disputes/${encodeURIComponent(selected.value.ticketId)}/resolve`,
      'POST',
      { resolutionType, items: [] }
    );
    ElMessage.success(result.message || '争议已处理');
    detailVisible.value = false;
    await load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '处理失败');
  } finally {
    resolving.value = false;
  }
}

async function load() {
  loading.value = true;
  try {
    const q = new URLSearchParams({ page: '0', size: '20', status: status.value || 'OPEN' });
    const data = await api.request<PageResult<DisputeTicketDto>>(`/api/v2/ops/disputes?${q}`, 'GET');
    items.value = data.items;
    ElMessage.success('已刷新');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

onMounted(load);
</script>

<style scoped>
.drawer-actions { display: flex; gap: 12px; margin-top: 24px; }
</style>

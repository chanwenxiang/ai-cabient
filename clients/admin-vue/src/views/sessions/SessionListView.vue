<template>
  <el-card class="page-card">
    <template #header>
      <div style="display:flex;justify-content:space-between;align-items:center">
        <span>开门记录</span>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>
    </template>
    <el-form inline @submit.prevent="search">
      <el-form-item label="设备"><el-input v-model="deviceId" clearable placeholder="留空=全部" /></el-form-item>
      <el-form-item label="状态">
        <el-select v-model="state" clearable placeholder="全部" style="width:140px">
          <el-option v-for="o in stateOptions" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
      </el-form-item>
      <el-form-item><el-button type="primary" @click="search">查询</el-button></el-form-item>
    </el-form>
    <el-table v-loading="loading" :data="items" stripe>
      <el-table-column prop="sessionId" label="会话ID" min-width="160"><template #default="{ row }"><code>{{ row.sessionId }}</code></template></el-table-column>
      <el-table-column prop="userId" label="用户" width="110" />
      <el-table-column prop="deviceId" label="设备" width="120" />
      <el-table-column prop="orderId" label="订单" min-width="130" show-overflow-tooltip />
      <el-table-column label="状态" width="120"><template #default="{ row }">{{ dictLabel('session_state', row.state) }}</template></el-table-column>
      <el-table-column prop="failureReason" label="失败原因" min-width="140" show-overflow-tooltip />
      <el-table-column label="创建时间" width="170"><template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template></el-table-column>
      <el-table-column label="更新时间" width="170"><template #default="{ row }">{{ formatDateTime(row.updatedAt) }}</template></el-table-column>
      <el-table-column label="操作" width="88" fixed="right" align="center">
        <template #default="{ row }">
          <TableActions
            v-if="canCancel(row.state)"
            :actions="[{ key: 'cancel', label: '取消会话', icon: CircleClose, type: 'danger' }]"
            @action="() => cancelSession(row.sessionId)"
          />
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
    </el-table>
    <div class="page-pager">
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="size"
        :total="total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next"
        @current-change="load"
        @size-change="onSizeChange"
      />
    </div>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { CircleClose, Refresh } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { dictLabel, dictOptions } from '@aicabinet/shared-dict';
import { api } from '@/api/client';
import TableActions from '@/components/TableActions.vue';
import type { PageResult } from '@aicabinet/shared-types';
import { formatDateTime } from '@aicabinet/shared-uni/format';

interface SessionRow {
  sessionId: string;
  userId?: string;
  deviceId?: string;
  state?: string;
  orderId?: string;
  failureReason?: string;
  createdAt?: string;
  updatedAt?: string;
}

const loading = ref(false);
const deviceId = ref('');
const state = ref('');
const page = ref(1);
const size = ref(20);
const total = ref(0);
const items = ref<SessionRow[]>([]);
const stateOptions = dictOptions('session_state');

function canCancel(s?: string) {
  return s && !['COMPLETED', 'CANCELLED', 'FAILED'].includes(s);
}

async function load() {
  loading.value = true;
  try {
    const q = new URLSearchParams({ page: String(page.value - 1), size: String(size.value) });
    if (deviceId.value.trim()) q.set('deviceId', deviceId.value.trim());
    if (state.value) q.set('state', state.value);
    const data = await api.request<PageResult<SessionRow>>(`/api/v2/ops/admin/sessions?${q}`, 'GET');
    items.value = data.items;
    total.value = data.total;
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

function search() {
  page.value = 1;
  load();
}

function onSizeChange() {
  page.value = 1;
  load();
}

async function cancelSession(sessionId: string) {
  await ElMessageBox.confirm('确认取消该会话？', '取消会话');
  try {
    await api.request(`/api/v2/ops/admin/sessions/${encodeURIComponent(sessionId)}/cancel`, 'POST');
    ElMessage.success('已取消');
    load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败');
  }
}

onMounted(load);
</script>

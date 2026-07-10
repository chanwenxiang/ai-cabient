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
      <el-table-column prop="userId" label="用户" />
      <el-table-column prop="deviceId" label="设备" />
      <el-table-column label="状态"><template #default="{ row }">{{ dictLabel('session_state', row.state) }}</template></el-table-column>
      <el-table-column prop="updatedAt" label="更新时间" width="180" />
      <el-table-column label="操作" width="100">
        <template #default="{ row }">
          <el-button v-if="canCancel(row.state)" link type="danger" @click="cancelSession(row.sessionId)">取消</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination v-model:current-page="page" :page-size="size" :total="total" layout="prev,pager,next" style="margin-top:16px" @current-change="load" />
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { dictLabel, dictOptions } from '@aicabinet/shared-dict';
import { api } from '@/api/client';
import type { PageResult } from '@aicabinet/shared-types';

interface SessionRow {
  sessionId: string;
  userId?: string;
  deviceId?: string;
  state?: string;
  updatedAt?: string;
}

const loading = ref(false);
const deviceId = ref('');
const state = ref('');
const page = ref(1);
const size = 20;
const total = ref(0);
const items = ref<SessionRow[]>([]);
const stateOptions = dictOptions('session_state');

function canCancel(s?: string) {
  return s && !['COMPLETED', 'CANCELLED', 'FAILED'].includes(s);
}

async function load() {
  loading.value = true;
  try {
    const q = new URLSearchParams({ page: String(page.value - 1), size: String(size) });
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

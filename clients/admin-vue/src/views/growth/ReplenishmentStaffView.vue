<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">补货员效率</span>
            <span class="hint">任务量 / 完成率 / 平均耗时 / 日均任务，用于排班与考核</span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-radio-group v-model="days" @change="load">
            <el-radio-button :value="7">近 7 天</el-radio-button>
            <el-radio-button :value="30">近 30 天</el-radio-button>
            <el-radio-button :value="90">近 90 天</el-radio-button>
          </el-radio-group>
          <el-button @click="onExport">导出 CSV</el-button>
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </template>

    <el-table
      v-loading="loading"
      :data="list"
      stripe
      border
      row-key="userId"
      empty-text=" "
      class="report-table"
    >
      <template #empty><el-empty v-if="!loading" description="暂无补货任务数据" /></template>
      <el-table-column prop="userId" label="工号" width="110" align="center" class-name="col-text" />
      <el-table-column label="姓名" min-width="110" align="center">
        <template #default="{ row }">{{ row.name || '—' }}</template>
      </el-table-column>
      <el-table-column label="手机" min-width="130" align="center">
        <template #default="{ row }">{{ row.phone || '—' }}</template>
      </el-table-column>
      <el-table-column prop="totalTasks" label="任务数" width="90" align="center" />
      <el-table-column prop="completedTasks" label="已完成" width="90" align="center" />
      <el-table-column label="完成率" width="90" align="center">
        <template #default="{ row }">{{ pct(row.completionRate) }}</template>
      </el-table-column>
      <el-table-column label="平均耗时(分)" width="120" align="center">
        <template #default="{ row }">{{ row.avgDurationMinutes != null ? row.avgDurationMinutes.toFixed(0) : '—' }}</template>
      </el-table-column>
      <el-table-column prop="openTasks" label="待办" width="80" align="center">
        <template #default="{ row }">
          <span :class="{ 'cell-warn': row.openTasks > 0 }">{{ row.openTasks }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="avgDailyTasks" label="日均任务" width="100" align="center" />
    </el-table>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { Refresh } from '@element-plus/icons-vue';
import { api } from '@/api/client';
import { useListCsv } from '@/composables/useListCsv';

type StaffRow = {
  userId: number;
  name?: string;
  phone?: string;
  totalTasks: number;
  completedTasks: number;
  completionRate: number;
  avgDurationMinutes?: number | null;
  openTasks: number;
  avgDailyTasks: number;
};

const loading = ref(false);
const days = ref(30);
const list = ref<StaffRow[]>([]);

const { onExport } = useListCsv({
  filePrefix: '补货员效率',
  headers: ['工号', '姓名', '手机', '任务数', '已完成', '完成率', '平均耗时(分)', '待办', '日均任务'],
  toRows: () =>
    list.value.map((r) => [
      r.userId,
      r.name || '',
      r.phone || '',
      r.totalTasks,
      r.completedTasks,
      pct(r.completionRate),
      r.avgDurationMinutes != null ? r.avgDurationMinutes.toFixed(0) : '',
      r.openTasks,
      r.avgDailyTasks
    ])
});

onMounted(load);

async function load() {
  loading.value = true;
  try {
    list.value = await api.request<StaffRow[]>(
      `/api/v2/ops/admin/replenishment-report/staff?days=${days.value}`
    );
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

function pct(v?: number) {
  if (v == null || !Number.isFinite(v)) return '—';
  return `${(v * 100).toFixed(1)}%`;
}
</script>

<style scoped>
.cell-warn {
  color: #b45309;
  font-weight: 700;
}
</style>

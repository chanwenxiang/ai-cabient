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
          <el-radio-group v-model="days" @change="search">
            <el-radio-button :value="7">近 7 天</el-radio-button>
            <el-radio-button :value="30">近 30 天</el-radio-button>
            <el-radio-button :value="90">近 90 天</el-radio-button>
          </el-radio-group>
        </div>
      </div>
    </template>

    <el-form inline class="filter-bar filter-bar--compact" @submit.prevent="search">
      <el-form-item label="关键词">
        <el-input
          v-model="keyword"
          clearable
          placeholder="姓名"
          style="width: 160px"
          @keyup.enter="search"
          @clear="search"
        />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </el-form-item>
    </el-form>

    <CrudTable
      :table="crud"
      row-key="userId"
      selectable
      :csv="csvOptions"
      empty-text="暂无补货任务数据"
    >
      <el-table-column prop="userId" label="工号" width="110" class-name="col-text" />
      <el-table-column
        label="姓名"
        min-width="110"
        align="center"
        class-name="col-status"
        label-class-name="col-status"
      >
        <template #default="{ row }">{{ row.name || '暂无' }}</template>
      </el-table-column>
      <el-table-column
        label="手机"
        min-width="130"
        class-name="col-text"
        label-class-name="col-text"
      >
        <template #default="{ row }">{{ row.phone || '暂无' }}</template>
      </el-table-column>
      <el-table-column
        prop="totalTasks"
        label="任务数"
        width="90"
        align="center"
        class-name="col-status"
        label-class-name="col-status"
      />
      <el-table-column
        prop="completedTasks"
        :label="displayLabel('order_status', 'COMPLETED')"
        width="90"
        class-name="col-text"
        label-class-name="col-text"
      />
      <el-table-column
        label="完成率"
        width="90"
        align="center"
        class-name="col-status"
        label-class-name="col-status"
      >
        <template #default="{ row }">{{ pct(row.completionRate) }}</template>
      </el-table-column>
      <el-table-column
        label="平均耗时(分)"
        width="120"
        class-name="col-text"
        label-class-name="col-text"
      >
        <template #default="{ row }">{{
          row.avgDurationMinutes != null ? row.avgDurationMinutes.toFixed(0) : '暂无'
        }}</template>
      </el-table-column>
      <el-table-column
        prop="openTasks"
        label="待办"
        width="80"
        align="center"
        class-name="col-status"
        label-class-name="col-status"
      >
        <template #default="{ row }">
          <span :class="{ 'cell-warn': row.openTasks > 0 }">{{ row.openTasks }}</span>
        </template>
      </el-table-column>
      <el-table-column
        prop="avgDailyTasks"
        label="日均任务"
        width="100"
        align="center"
        class-name="col-status"
        label-class-name="col-status"
      />
    </CrudTable>
  </el-card>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import CrudTable, { type CrudCsvOptions } from '@/components/CrudTable.vue';
import { useCrudTable } from '@/composables/useCrudTable';
import { displayLabel } from '@aicabinet/shared-dict';

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

const days = ref(30);
const keyword = ref('');

/** 关键词为纯前端过滤（接口无该参数）：原 displayList 计算属性前移到取数处，切片前先过滤保证分页计数一致 */
function filterByKeyword(rows: StaffRow[]): StaffRow[] {
  const kw = keyword.value.trim().toLowerCase();
  if (!kw) return rows;
  return rows.filter((row) => (row.name || '').toLowerCase().includes(kw));
}

// 列表状态机统一交给 CrudTable：分页（接口无分页，前端切片）/ 多选 / 竞态 / 空态 全部内建
const crud = useCrudTable<StaffRow>({
  rowKey: (r) => r.userId,
  fetchPage: async (params) => {
    const list = filterByKeyword(
      await api.request<StaffRow[]>(AdminEndpoints.replenishmentReportStaff(days.value))
    );
    const start = params.page * params.size;
    return { items: list.slice(start, start + params.size), total: list.length };
  }
});

const csvOptions: CrudCsvOptions = {
  filePrefix: '补货员效率',
  exportPerm: 'ops:replenishment:export',
  headers: [
    '工号',
    '姓名',
    '手机',
    '任务数',
    displayLabel('order_status', 'COMPLETED'),
    '完成率',
    '平均耗时(分)',
    '待办',
    '日均任务'
  ],
  toRows: (rows) =>
    rows.map((r) => [
      r.userId,
      r.name || '',
      r.phone || '',
      r.totalTasks,
      r.completedTasks,
      pct(r.completionRate),
      r.avgDurationMinutes == null ? '' : r.avgDurationMinutes.toFixed(0),
      r.openTasks,
      r.avgDailyTasks
    ])
};

function search() {
  void crud.search();
}
function reset() {
  keyword.value = '';
  void crud.search();
}

function pct(v?: number) {
  if (v == null || !Number.isFinite(v)) return '暂无';
  return `${(v * 100).toFixed(1)}%`;
}
</script>

<style scoped>
.cell-warn {
  color: #b45309;
  font-weight: 700;
}
</style>

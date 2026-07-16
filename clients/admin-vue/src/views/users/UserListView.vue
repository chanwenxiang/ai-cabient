<template>
  <el-card class="page-card" shadow="never">
    <template #header>
      <div class="card-head">
        <span class="title">灰度用户</span>
        <div class="actions">
          <el-button @click="onExport">导出</el-button>
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </template>
    <el-form inline class="filter-bar" @submit.prevent="search">
      <el-form-item label="关键词">
        <el-input
          v-model="keyword"
          clearable
          placeholder="手机号 / 姓名 / 用户ID"
          style="width: 220px"
          @clear="search"
        />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </el-form-item>
    </el-form>
    <div class="table-scroll">
      <div class="table-scroll-inner" style="min-width: 980px">
        <el-table v-loading="loading" :data="items" stripe border>
      <el-table-column prop="userId" label="用户ID" width="120" />
      <el-table-column prop="phoneNumber" label="手机号" min-width="130" />
      <el-table-column prop="name" label="姓名" min-width="110" />
      <el-table-column label="角色" width="120">
        <template #default="{ row }">{{ row.role || '-' }}</template>
      </el-table-column>
      <el-table-column label="实名" width="100">
        <template #default="{ row }">
          <el-tag :type="row.verified ? 'success' : 'warning'" size="small">
            {{ row.verified ? '已实名' : '未实名' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="测试余额" width="120">
        <template #default="{ row }">¥{{ ((row.balanceCents || 0) / 100).toFixed(2) }}</template>
      </el-table-column>
      <el-table-column label="注册时间" width="180">
        <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column v-if="canAdjust" label="操作" width="88" class-name="col-action" align="center">
        <template #default="{ row }">
          <TableActions
            :actions="[{ key: 'adjust', label: '调整余额', icon: Wallet, type: 'primary' }]"
            @action="() => adjust(row)"
          />
        </template>
      </el-table-column>
      <template #empty><el-empty description="暂无用户" /></template>
        </el-table>
      </div>
    </div>
    <div class="page-pager">
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="size"
        :total="total"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next, jumper"
        @current-change="load"
        @size-change="onSizeChange"
      />
    </div>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { Refresh, Wallet } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api } from '@/api/client';
import TableActions from '@/components/TableActions.vue';
import { useListCsv } from '@/composables/useListCsv';
import { useAuthStore } from '@/stores/auth';
import type { PageResult } from '@aicabinet/shared-types';
import { formatDateTime } from '@aicabinet/shared-uni/format';

interface UserRow {
  userId: number;
  phoneNumber?: string;
  name?: string;
  verified: boolean;
  balanceCents: number;
  role?: string;
  createdAt?: string;
}

const auth = useAuthStore();
const canAdjust = computed(() => auth.hasPerm('ops:user:balance'));

const loading = ref(false);
const keyword = ref('');
const page = ref(1);
const size = ref(20);
const total = ref(0);
const items = ref<UserRow[]>([]);

const { onExport } = useListCsv({
  filePrefix: '灰度用户',
  headers: ['用户ID', '手机号', '姓名', '角色', '实名', '测试余额', '注册时间'],
  toRows: () =>
    items.value.map((row) => [
      row.userId,
      row.phoneNumber,
      row.name,
      row.role || '-',
      row.verified ? '已实名' : '未实名',
      ((row.balanceCents || 0) / 100).toFixed(2),
      formatDateTime(row.createdAt)
    ])
});

async function load() {
  loading.value = true;
  try {
    const q = new URLSearchParams({ page: String(page.value - 1), size: String(size.value) });
    if (keyword.value.trim()) q.set('q', keyword.value.trim());
    const data = await api.request<PageResult<UserRow>>(`/api/v2/ops/admin/users?${q}`, 'GET');
    let list = data.items || [];
    const kw = keyword.value.trim().toLowerCase();
    if (kw) {
      list = list.filter((u) =>
        [u.userId, u.phoneNumber, u.name, u.role].some((x) =>
          String(x || '').toLowerCase().includes(kw)
        )
      );
    }
    items.value = list;
    total.value = data.total || list.length;
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
function reset() {
  keyword.value = '';
  search();
}
function onSizeChange() {
  page.value = 1;
  load();
}

async function adjust(row: UserRow) {
  try {
    const amount = await ElMessageBox.prompt(
      `当前测试余额 ¥${((row.balanceCents || 0) / 100).toFixed(2)}。输入变动金额，正数发放、负数扣回。`,
      '调整测试余额',
      {
        inputPattern: /^-?\d+(\.\d{1,2})?$/,
        inputErrorMessage: '请输入正确金额',
        confirmButtonText: '下一步',
        cancelButtonText: '取消'
      }
    );
    const reason = await ElMessageBox.prompt('必须填写调整原因，提交后不可删除', '确认资金操作', {
      inputValidator: (v) => !!String(v || '').trim() || '必须填写原因',
      confirmButtonText: '确认提交',
      cancelButtonText: '取消',
      type: 'warning'
    });
    const deltaCents = Math.round(Number(amount.value) * 100);
    await api.request(`/api/v2/ops/admin/users/${row.userId}/balance`, 'POST', {
      deltaCents,
      reason: reason.value,
      idempotencyKey: `admin-${row.userId}-${Date.now()}`
    });
    ElMessage.success('测试余额已调整');
    await load();
  } catch (e: any) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(e instanceof Error ? e.message : '调整失败');
    }
  }
}

onMounted(load);
</script>

<style scoped>
.card-head { display: flex; justify-content: space-between; align-items: center; }
.title { font-weight: 600; }
.filter-bar { margin-bottom: 8px; }
.page-pager { margin-top: 16px; display: flex; justify-content: flex-end; }
</style>

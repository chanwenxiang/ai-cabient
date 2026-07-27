<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">用户余额</span>
            <span class="hint">按手机号 / 姓名 / ID 筛选；余额右对齐；有权限可调整余额</span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button v-hasPermi="['ops:user:export']" @click="onExport">{{ exportButtonLabel }}</el-button>
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </template>

    <el-form inline class="filter-bar filter-bar--compact" @submit.prevent="search">
      <el-form-item label="关键词">
        <el-input
          v-model="keyword"
          clearable
          placeholder="手机号 / 姓名 / 用户ID"
          style="width: 220px"
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
      <div class="table-scroll-inner" style="min-width: 920px">
        <el-table
          v-loading="loading"
          :data="items"
          stripe
          border
          class="report-table"
          row-key="userId"
          @selection-change="onSelectionChange"
        >
          <template #empty><el-empty description="暂无用户" /></template>
          <el-table-column type="selection" width="48" align="center" />
          <el-table-column label="用户" min-width="160" class-name="col-text" label-class-name="col-text" align="left" header-align="left">
            <template #default="{ row }">
              <div class="user-cell">
                <strong>{{ row.name || row.phoneNumber || row.userId }}</strong>
                <small>ID {{ row.userId }}</small>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="手机号" width="140" class-name="col-text" label-class-name="col-text" align="left" header-align="left">
            <template #default="{ row }">{{ row.phoneNumber || '-' }}</template>
          </el-table-column>
          <el-table-column label="角色" width="110" align="center">
            <template #default="{ row }">
              <el-tag v-if="row.role" size="small" effect="plain">{{ row.role }}</el-tag>
              <span v-else class="muted">-</span>
            </template>
          </el-table-column>
          <el-table-column label="实名" width="96" align="center">
            <template #default="{ row }">
              <el-tag :type="row.verified ? 'success' : 'warning'" size="small">
                {{ row.verified ? '已实名' : '未实名' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column
            label="余额"
            width="120"
            align="right"
            class-name="col-money"
            label-class-name="col-money"
          >
            <template #default="{ row }">¥{{ ((row.balanceCents || 0) / 100).toFixed(2) }}</template>
          </el-table-column>
          <el-table-column label="注册时间" width="168" class-name="col-text">
            <template #default="{ row }">
              <span class="cell-datetime">{{ formatDateTime(row.createdAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column
            v-if="canAdjust || canVerify"
            label="操作"
            width="168"
            class-name="col-action"
            label-class-name="col-action"
            align="center"
          >
            <template #default="{ row }">
              <TableActions
                v-if="userActions(row).length"
                :actions="userActions(row)"
                @action="(key) => onUserAction(key, row)"
              />
              <span v-else class="muted">-</span>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <div class="page-pager">
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="size"
        :total="total"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        background
        @current-change="load"
        @size-change="onSizeChange"
      />
    </div>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onActivated, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { CircleCheck, Refresh, Wallet } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api } from '@/api/client';
import TableActions, { type TableAction } from '@/components/TableActions.vue';
import { useListCsv } from '@/composables/useListCsv';
import { useTableSelection } from '@/composables/useTableSelection';
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

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();
/** 运营「余额调整」按权限展示，不再绑测试开关（避免生产构建操作列空白）。 */
const canAdjust = computed(() => auth.hasPerm('ops:user:balance'));
const canVerify = computed(() => auth.hasPerm('ops:user:verify'));

function userActions(row: UserRow): TableAction[] {
  const acts: TableAction[] = [];
  if (canAdjust.value) {
    acts.push({ key: 'adjust', label: '调整余额', icon: Wallet, type: 'primary' });
  }
  if (canVerify.value && !row.verified) {
    acts.push({ key: 'verify', label: '核验实名', icon: CircleCheck, type: 'success' });
  }
  return acts;
}

function onUserAction(key: string, row: UserRow) {
  if (key === 'verify') verifyUser(row);
  else if (key === 'adjust') adjust(row);
}

async function verifyUser(row: UserRow) {
  try {
    const { value } = await ElMessageBox.prompt('确认实名姓名（可留空）', `核验用户 ${row.userId}`, {
      confirmButtonText: '确认已实名',
      cancelButtonText: '取消',
      inputPlaceholder: row.name || '真实姓名',
      inputValue: row.name || ''
    });
    await api.request(`/api/v2/ops/admin/users/${row.userId}/verify`, 'POST', {
      verified: true,
      realName: (value || '').trim() || undefined
    });
    ElMessage.success('已核验实名');
    await load();
  } catch (e) {
    if (e === 'cancel' || e === 'close') return;
    ElMessage.error(e instanceof Error ? e.message : '核验失败');
  }
}

const loading = ref(false);
const keyword = ref('');
const page = ref(1);
const size = ref(20);
const total = ref(0);
const items = ref<UserRow[]>([]);

const { onSelectionChange, pickSelected, exportButtonLabel, clearSelection } =
  useTableSelection<UserRow>((r) => r.userId);

const { onExport } = useListCsv({
  filePrefix: '用户余额',
  headers: ['用户ID', '手机号', '姓名', '角色', '实名', '余额', '注册时间'],
  toRows: () =>
    pickSelected(items.value).map((row) => [
      row.userId,
      row.phoneNumber,
      row.name,
      row.role || '-',
      row.verified ? '已实名' : '未实名',
      ((row.balanceCents || 0) / 100).toFixed(2),
      formatDateTime(row.createdAt)
    ])
});

function syncRouteQuery() {
  const query: Record<string, string> = {};
  if (keyword.value.trim()) query.keyword = keyword.value.trim();
  router.replace({ query });
}

function applyRouteQuery() {
  const qKeyword = typeof route.query.keyword === 'string' ? route.query.keyword : '';
  if (qKeyword !== keyword.value) {
    keyword.value = qKeyword;
    return true;
  }
  return false;
}

/** API accepts phone/name/role/verified — not free-text `q`. Map keyword accordingly. */
function classifyKeyword(raw: string): { phone?: string; name?: string; userId?: string } {
  const t = raw.trim();
  if (!t) return {};
  if (/^1\d{10}$/.test(t)) return { phone: t };
  if (/^\d+$/.test(t)) return { userId: t };
  return { name: t };
}

async function findUserById(userId: string): Promise<UserRow | null> {
  const pageSize = 100;
  const maxScan = 500;
  let apiPage = 0;
  let scanned = 0;
  let serverTotal = Number.POSITIVE_INFINITY;
  while (scanned < maxScan && scanned < serverTotal) {
    const q = new URLSearchParams({ page: String(apiPage), size: String(pageSize) });
    const data = await api.request<PageResult<UserRow>>(`/api/v2/ops/admin/users?${q}`, 'GET');
    const batch = data.items || [];
    serverTotal = data.total ?? batch.length;
    const hit = batch.find((u) => String(u.userId) === userId);
    if (hit) return hit;
    scanned += batch.length;
    if (!batch.length || batch.length < pageSize) break;
    apiPage += 1;
  }
  return null;
}

async function load() {
  loading.value = true;
  try {
    const classified = classifyKeyword(keyword.value);
    if (classified.userId) {
      const hit = await findUserById(classified.userId);
      items.value = hit ? [hit] : [];
      total.value = hit ? 1 : 0;
    } else {
      const q = new URLSearchParams({ page: String(page.value - 1), size: String(size.value) });
      if (classified.phone) q.set('phone', classified.phone);
      if (classified.name) q.set('name', classified.name);
      const data = await api.request<PageResult<UserRow>>(`/api/v2/ops/admin/users?${q}`, 'GET');
      items.value = data.items || [];
      total.value = data.total || 0;
    }
    clearSelection();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

function search() {
  page.value = 1;
  syncRouteQuery();
  load();
}

function reset() {
  keyword.value = '';
  page.value = 1;
  syncRouteQuery();
  load();
}

function onSizeChange() {
  page.value = 1;
  load();
}

async function adjust(row: UserRow) {
  try {
    const amount = await ElMessageBox.prompt(
      `当前余额 ¥${((row.balanceCents || 0) / 100).toFixed(2)}。输入变动金额，正数发放、负数扣回。`,
      '调整余额',
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
    ElMessage.success('余额已调整');
    await load();
  } catch (e: any) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(e instanceof Error ? e.message : '调整失败');
    }
  }
}

async function reloadFromRouteQuery() {
  if (!applyRouteQuery()) return;
  page.value = 1;
  await load();
}

watch(
  () => route.query.keyword,
  () => {
    void reloadFromRouteQuery();
  }
);

onMounted(() => {
  applyRouteQuery();
  load();
});
onActivated(() => {
  void reloadFromRouteQuery();
});
</script>

<style scoped>
.page-card-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  flex-wrap: wrap;
}
.page-card-head__meta { min-width: 0; }
.page-card-head__title { display: flex; flex-direction: column; gap: 4px; }
.title { font-weight: 600; font-size: 15px; }
.hint { color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.4; }
.page-card-head__actions { display: flex; gap: 8px; }
.user-cell { display: grid; gap: 2px; line-height: 1.35; }
.user-cell strong { font-weight: 650; }
.user-cell small {
  color: var(--el-text-color-secondary);
  font-size: 11px;
  font-family: var(--app-font-mono);
}
.muted { color: var(--el-text-color-secondary); }
</style>

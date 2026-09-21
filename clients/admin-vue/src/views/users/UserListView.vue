<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">用户余额</span>
            <span class="hint">按手机号 / 姓名 / ID 筛选；有权限可调整余额</span>
          </div>
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
      <div class="table-scroll-inner">
        <CrudTable
          :table="crud"
          row-key="userId"
          selectable
          :actions="showActionColumn ? rowActions : undefined"
          :action-width="100"
          empty-text="暂无用户"
          sort-field-label="用户编号"
          :csv="csvOptions"
          @action="onAction"
        >
          <el-table-column prop="userId" label="用户编号" width="100" class-name="col-text">
            <template #default="{ row }">
              <span class="cell-id">{{ row.userId }}</span>
            </template>
          </el-table-column>
          <el-table-column
            label="姓名"
            min-width="120"
            class-name="col-text"
            label-class-name="col-text"
            header-align="center"
          >
            <template #default="{ row }">{{ userNameText(row) }}</template>
          </el-table-column>
          <el-table-column
            label="手机号"
            width="140"
            class-name="col-text"
            label-class-name="col-text"
            header-align="center"
          >
            <template #default="{ row }">{{ textOrNone(row.phoneNumber) }}</template>
          </el-table-column>
          <el-table-column
            label="角色"
            width="110"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <el-tag v-if="row.role" size="small" effect="plain">{{ roleLabel(row.role) }}</el-tag>
              <span v-else class="muted">暂无</span>
            </template>
          </el-table-column>
          <el-table-column
            label="余额"
            width="120"
            align="right"
            header-align="right"
            class-name="col-money"
            label-class-name="col-money"
          >
            <template #default="{ row }"
              >¥{{ ((row.balanceCents || 0) / 100).toFixed(2) }}</template
            >
          </el-table-column>
          <el-table-column
            label="实名"
            width="96"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <el-tag :type="row.verified ? 'success' : 'warning'" size="small">
                {{ row.verified ? '已实名' : '未实名' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column
            label="会员等级"
            width="100"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">{{ memberLevelLabel(row.memberLevel) }}</template>
          </el-table-column>
          <el-table-column
            label="积分"
            width="80"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">{{ row.availablePoints ?? 0 }}</template>
          </el-table-column>
          <el-table-column
            label="黑名单"
            width="96"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <el-tag v-if="row.blacklisted" size="small" type="danger">已拉黑</el-tag>
              <span v-else class="muted">暂无</span>
            </template>
          </el-table-column>
          <el-table-column
            align="center"
            label="注册时间"
            width="168"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <span class="cell-datetime">{{ formatDateTime(row.createdAt) }}</span>
            </template>
          </el-table-column>
        </CrudTable>
      </div>
    </div>
  </el-card>

  <el-dialog
    v-model="adjustVisible"
    title="调整用户余额"
    append-to-body
    destroy-on-close
    :close-on-click-modal="false"
  >
    <div v-if="adjustRow" class="adjust-user">
      <div class="adjust-user__name">{{ adjustRow.name || '未命名' }}</div>
      <div class="adjust-user__id">
        用户 {{ adjustRow.userId }} · {{ adjustRow.phoneNumber || '无手机号' }}
      </div>
      <div class="adjust-user__balance">
        当前余额 <b>¥{{ ((adjustRow.balanceCents || 0) / 100).toFixed(2) }}</b>
      </div>
    </div>
    <el-form label-position="top" @submit.prevent="submitAdjust">
      <el-form-item label="变动金额（元，正数发放、负数扣回）" required>
        <el-input-number
          v-model="adjustForm.amount"
          :precision="2"
          :step="10"
          :min="-1000000"
          :max="1000000"
          controls-position="right"
          style="width: 100%"
        />
      </el-form-item>
      <el-form-item label="调整原因" required>
        <el-input
          v-model="adjustForm.reason"
          type="textarea"
          :rows="2"
          maxlength="100"
          placeholder="必填，提交后不可删除"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="adjustVisible = false">取消</el-button>
      <el-button type="primary" :loading="adjustSaving" @click="submitAdjust">确认调整</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, onActivated, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { CircleCheck, Wallet } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import CrudTable, { type CrudCsvOptions, type CrudRowAction } from '@/components/CrudTable.vue';
import { useCrudTable } from '@/composables/useCrudTable';
import { useAuthStore } from '@/stores/auth';
import type { PageResult } from '@aicabinet/shared-types';
import { displayLabel } from '@aicabinet/shared-dict';
import { textOrNone, yuanToCents } from '@/utils/display';
import { formatDateTime } from '@aicabinet/shared-uni/format';

interface UserRow {
  userId: number;
  phoneNumber?: string;
  name?: string;
  verified: boolean;
  balanceCents: number;
  role?: string;
  memberLevel?: string;
  availablePoints?: number;
  blacklisted?: boolean;
  createdAt?: string;
}

function memberLevelLabel(level?: string) {
  return displayLabel('member_level', level, '普通');
}

function userNameText(row: UserRow) {
  const name = row.name == null ? '' : String(row.name).trim();
  if (name) return name;
  return '暂无';
}

function roleLabel(role?: string) {
  return displayLabel('user_role', role, '暂无');
}

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();
/** 运营「余额调整」按权限展示，不再绑测试开关（避免生产构建操作列空白）。 */
const canAdjust = computed(() => auth.hasPerm('ops:user:balance'));
const canVerify = computed(() => auth.hasPerm('ops:user:verify'));

const keyword = ref('');
const adjustVisible = ref(false);
const adjustSaving = ref(false);
const adjustRow = ref<UserRow | null>(null);
const adjustForm = ref({ amount: 0, reason: '' });
/** Fixed for this dialog open — avoids Date.now() double-submit creating duplicate adjusts. */
const adjustIdempotencyKey = ref('');

// 列表状态机统一交给 CrudTable：分页 / 排序 / 多选 / 竞态 / 空态 / 刷新 全部内建
const crud = useCrudTable<UserRow>({
  rowKey: (r) => r.userId,
  // 首查前需先应用路由查询参数（applyRouteQuery），故关闭 autoLoad 由 onMounted 显式首查
  autoLoad: false,
  fetchPage: async (params) => {
    const classified = classifyKeyword(keyword.value);
    if (classified.userId) {
      // 纯数字关键词走「按 ID 精确查找」，不受分页影响（原 load 行为保留）
      const hit = await findUserById(classified.userId);
      return { items: hit ? [hit] : [], total: hit ? 1 : 0 };
    }
    const q = new URLSearchParams({
      page: String(params.page), // 0 起（useCrudTable 已换算）
      size: String(params.size)
    });
    if (classified.phone) q.set('phone', classified.phone);
    if (classified.name) q.set('name', classified.name);
    return api.request<PageResult<UserRow>>(AdminEndpoints.usersList(q), 'GET');
  },
  // 用户编号本地排序（替代原 useIdColumnSort 表头排序，改由壳内「按用户编号 升/降序」切换）
  sort: { prop: 'userId', mode: 'local' }
});

// 导出统一并入 CrudTable 工具条（选中优先导出、文件命名由组件内置）
const csvOptions: CrudCsvOptions = {
  filePrefix: '用户余额',
  exportPerm: 'ops:user:export',
  headers: ['用户ID', '手机号', '姓名', '角色', '实名', '余额', '注册时间'],
  toRows: (rows) =>
    rows.map((row) => [
      row.userId,
      row.phoneNumber,
      userNameText(row),
      roleLabel(row.role),
      row.verified ? '已实名' : '未实名',
      ((row.balanceCents || 0) / 100).toFixed(2),
      formatDateTime(row.createdAt)
    ])
};

function rowActions(row: UserRow): CrudRowAction[] {
  const acts: CrudRowAction[] = [];
  if (canAdjust.value) {
    acts.push({ key: 'adjust', label: '调整余额', icon: Wallet, type: 'primary' });
  }
  if (canVerify.value && !row.verified) {
    acts.push({ key: 'verify', label: '核验实名', icon: CircleCheck, type: 'success' });
  }
  return acts;
}

/** 与原逻辑一致：无任何可用操作（无权限或全部已实名）时隐藏整列 */
const showActionColumn = computed(
  () =>
    (canAdjust.value || canVerify.value) &&
    crud.items.some((row) => rowActions(row).length > 0)
);

function onAction({ key, row }: { key: string; row: UserRow }) {
  if (key === 'verify') verifyUser(row);
  else if (key === 'adjust') openAdjust(row);
}

async function verifyUser(row: UserRow) {
  try {
    const { value } = await ElMessageBox.prompt(
      '确认实名姓名（可留空）',
      `核验用户 ${row.userId}`,
      {
        confirmButtonText: '确认已实名',
        cancelButtonText: '取消',
        inputPlaceholder: row.name || '真实姓名',
        inputValue: row.name || ''
      }
    );
    await api.request(AdminEndpoints.userVerify(row.userId), 'POST', {
      verified: true,
      realName: (value || '').trim() || undefined
    });
    ElMessage.success('已核验实名');
    await crud.load();
  } catch (e) {
    if (e === 'cancel' || e === 'close') return;
    ElMessage.error(e instanceof Error ? e.message : '核验失败');
  }
}

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
  const q = new URLSearchParams({ page: '0', size: '1', userId });
  const data = await api.request<PageResult<UserRow>>(AdminEndpoints.usersList(q), 'GET');
  const hit = (data.items || []).find((u) => String(u.userId) === userId);
  return hit ?? null;
}

function search() {
  syncRouteQuery();
  void crud.search();
}

function reset() {
  keyword.value = '';
  syncRouteQuery();
  void crud.search();
}

function openAdjust(row: UserRow) {
  adjustRow.value = row;
  adjustForm.value = { amount: 0, reason: '' };
  adjustIdempotencyKey.value =
    typeof crypto !== 'undefined' && crypto.randomUUID
      ? crypto.randomUUID()
      : `admin-${row.userId}-${Math.random().toString(36).slice(2)}`;
  adjustVisible.value = true;
}

async function submitAdjust() {
  if (!adjustRow.value) return;
  const deltaCents = yuanToCents(adjustForm.value.amount);
  if (deltaCents == null || deltaCents === 0) {
    ElMessage.warning('请输入非零变动金额');
    return;
  }
  if (!adjustForm.value.reason.trim()) {
    ElMessage.warning('请填写调整原因');
    return;
  }
  try {
    await ElMessageBox.confirm(
      `确认对用户 ${adjustRow.value.userId} 调整余额 ¥${(deltaCents / 100).toFixed(2)}？该操作将写入审计日志。`,
      '余额调整二次确认',
      { type: 'warning', confirmButtonText: '确认调整', cancelButtonText: '取消' }
    );
  } catch {
    return;
  }
  adjustSaving.value = true;
  try {
    await api.request(AdminEndpoints.userBalance(adjustRow.value.userId), 'POST', {
      deltaCents,
      reason: adjustForm.value.reason.trim(),
      idempotencyKey: adjustIdempotencyKey.value || `admin-${adjustRow.value.userId}-${deltaCents}`
    });
    ElMessage.success('余额已调整');
    adjustVisible.value = false;
    await crud.load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '调整失败');
  } finally {
    adjustSaving.value = false;
  }
}

async function reloadFromRouteQuery() {
  if (!applyRouteQuery()) return;
  await crud.search();
}

watch(
  () => route.query.keyword,
  () => {
    void reloadFromRouteQuery();
  }
);

// 首查前需先应用路由查询参数（applyRouteQuery），故关闭 autoLoad 由这里显式首查
onMounted(() => {
  applyRouteQuery();
  void crud.load();
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
.page-card-head__meta {
  min-width: 0;
}
.page-card-head__title {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.title {
  font-weight: 600;
  font-size: var(--admin-font-size-title);
}
.hint {
  color: var(--el-text-color-secondary);
  font-size: var(--admin-font-size-sm);
  line-height: 1.4;
}
.user-cell {
  display: grid;
  gap: 2px;
  line-height: 1.35;
}
.user-cell strong {
  font-weight: 650;
}
.user-cell small {
  color: var(--el-text-color-secondary);
  font-family: inherit;
}
.muted {
  color: var(--el-text-color-secondary);
}
.adjust-user {
  padding: 12px 14px;
  margin-bottom: 16px;
  border: 1px solid var(--layout-border);
  border-radius: 10px;
  background: var(--el-fill-color-light);
}
.adjust-user__name {
  font-weight: 600;
  font-size: var(--admin-font-size-title);
}
.adjust-user__id {
  color: var(--layout-muted);
  font-size: var(--admin-font-size-sm);
  margin-top: 2px;
}
.adjust-user__balance {
  margin-top: 10px;
  font-size: var(--admin-font-size-table);
  color: var(--layout-muted);
}
.adjust-user__balance b {
  color: var(--layout-text);
  font-variant-numeric: tabular-nums;
}
</style>

<template>
  <el-card class="page-card" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">积分兑换管理</span>
            <span class="hint">积分兑换优惠券商品；兑换后自动发券并扣积分</span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button v-hasPermi="['ops:points:edit']" type="primary" @click="openCreate"
            >新建兑换项</el-button
          >
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
      row-key="itemId"
      empty-text=" "
      class="report-table"
    >
      <template #empty><el-empty v-if="!loading" description="暂无兑换项" /></template>
      <el-table-column prop="itemId" label="ID" width="80" align="center" class-name="col-text" />
      <el-table-column label="兑换项" min-width="170" align="center">
        <template #default="{ row }">
          <span class="cell-emoji">{{ row.coverEmoji || '🎁' }}</span>
          <span class="cell-name">{{ row.title }}</span>
          <span v-if="row.subtitle" class="cell-sub">{{ row.subtitle }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="pointsCost" label="所需积分" width="100" align="center" />
      <el-table-column prop="couponName" label="兑换优惠券" min-width="130" align="center" />
      <el-table-column label="券定义" width="90" align="center">
        <template #default="{ row }">
          <span v-if="row.couponDefId" class="cell-id">{{ row.couponDefId }}</span>
          <span v-else class="muted">暂无</span>
        </template>
      </el-table-column>
      <el-table-column label="库存 / 已兑" width="120" align="center">
        <template #default="{ row }">{{ row.stockTotal }} / {{ row.redeemedCount }}</template>
      </el-table-column>
      <el-table-column label="可兑" width="80" align="center">
        <template #default="{ row }">{{
          row.availableStock != null
            ? row.availableStock
            : Math.max(0, Number(row.stockTotal || 0) - Number(row.redeemedCount || 0))
        }}</template>
      </el-table-column>
      <el-table-column prop="sortOrder" label="排序" width="70" align="center" />
      <el-table-column label="状态" width="90" align="center">
        <template #default="{ row }">
          <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'">{{
            row.status === 'ACTIVE' ? '启用' : '停用'
          }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" width="150" align="center">
        <template #default="{ row }">{{
          row.createdAt ? formatDateTime(row.createdAt) : '暂无'
        }}</template>
      </el-table-column>
      <el-table-column label="操作" width="150" align="center">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button
            v-if="auth.hasPerm('ops:points:edit')"
            link
            :type="row.status === 'ACTIVE' ? 'danger' : 'success'"
            @click="toggleStatus(row)"
            >{{ row.status === 'ACTIVE' ? '停用' : '启用' }}</el-button
          >
        </template>
      </el-table-column>
    </el-table>

    <el-dialog
      v-model="dialogVisible"
      :title="editing ? '编辑兑换项' : '新建兑换项'"
      width="520px"
      destroy-on-close
    >
      <el-form :model="form" label-width="110px">
        <el-form-item label="标题" required>
          <el-input v-model="form.title" placeholder="如：满 20 减 5 券" />
        </el-form-item>
        <el-form-item label="副标题">
          <el-input v-model="form.subtitle" placeholder="选填，展示在标题下方" />
        </el-form-item>
        <el-form-item label="图标 emoji">
          <el-input v-model="form.coverEmoji" style="width: 120px" />
        </el-form-item>
        <el-form-item label="所需积分" required>
          <el-input-number v-model="form.pointsCost" :min="1" />
        </el-form-item>
        <el-form-item label="兑换优惠券" required>
          <el-select v-model="form.couponDefId" filterable style="width: 100%">
            <el-option
              v-for="c in couponDefs"
              :key="c.couponDefId"
              :label="`${c.couponName}（${(c.denominationCents / 100).toFixed(2)}元）`"
              :value="c.couponDefId"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="库存总量" required>
          <el-input-number v-model="form.stockTotal" :min="0" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sortOrder" :min="0" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.status" active-value="ACTIVE" inactive-value="INACTIVE" />
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
import { onMounted, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { Refresh } from '@element-plus/icons-vue';
import { api } from '@/api/client';
import { useAuthStore } from '@/stores/auth';
import { useListCsv } from '@/composables/useListCsv';
import { formatDateTime } from '@aicabinet/shared-uni/format';

type RedeemItem = {
  itemId: number;
  title: string;
  subtitle?: string;
  coverEmoji: string;
  pointsCost: number;
  couponDefId: number;
  couponName?: string;
  stockTotal: number;
  redeemedCount: number;
  sortOrder: number;
  status: string;
};

type CouponDef = {
  couponDefId: number;
  couponName: string;
  denominationCents: number;
};

const loading = ref(false);
const saving = ref(false);
const list = ref<RedeemItem[]>([]);
const couponDefs = ref<CouponDef[]>([]);
const dialogVisible = ref(false);
const editing = ref(false);
const auth = useAuthStore();

const { onExport } = useListCsv({
  filePrefix: '积分兑换管理',
  headers: ['ID', '标题', '副标题', '所需积分', '兑换优惠券', '库存', '已兑', '排序', '状态'],
  toRows: () =>
    list.value.map((r) => [
      r.itemId,
      r.title,
      r.subtitle || '',
      r.pointsCost,
      r.couponName || '',
      r.stockTotal,
      r.redeemedCount,
      r.sortOrder,
      r.status === 'ACTIVE' ? '启用' : '停用'
    ])
});

const form = reactive({
  itemId: 0 as number | null,
  title: '',
  subtitle: '',
  coverEmoji: '🎁',
  pointsCost: 100,
  couponDefId: undefined as number | undefined,
  stockTotal: 100,
  sortOrder: 0,
  status: 'ACTIVE'
});

onMounted(async () => {
  await Promise.all([load(), loadCouponDefs()]);
});

async function load() {
  loading.value = true;
  try {
    list.value = await api.request<RedeemItem[]>('/api/v2/ops/admin/growth/points-redeem');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

async function loadCouponDefs() {
  try {
    couponDefs.value = await api.request<CouponDef[]>('/api/v2/coupons/definitions');
  } catch {
    couponDefs.value = [];
  }
}

function openCreate() {
  editing.value = false;
  Object.assign(form, {
    itemId: null,
    title: '',
    subtitle: '',
    coverEmoji: '🎁',
    pointsCost: 100,
    couponDefId: undefined,
    stockTotal: 100,
    sortOrder: 0,
    status: 'ACTIVE'
  });
  dialogVisible.value = true;
}

function openEdit(row: RedeemItem) {
  editing.value = true;
  Object.assign(form, {
    itemId: row.itemId,
    title: row.title,
    subtitle: row.subtitle || '',
    coverEmoji: row.coverEmoji || '🎁',
    pointsCost: row.pointsCost,
    couponDefId: row.couponDefId,
    stockTotal: row.stockTotal,
    sortOrder: row.sortOrder,
    status: row.status
  });
  dialogVisible.value = true;
}

async function save() {
  if (!form.title.trim() || !form.couponDefId || !form.pointsCost) {
    ElMessage.warning('请填写标题、优惠券与所需积分');
    return;
  }
  saving.value = true;
  try {
    await api.request<RedeemItem>('/api/v2/ops/admin/growth/points-redeem', 'PUT', {
      ...form,
      itemId: form.itemId ?? undefined
    });
    ElMessage.success('已保存');
    dialogVisible.value = false;
    await load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败');
  } finally {
    saving.value = false;
  }
}

async function toggleStatus(row: RedeemItem) {
  const next = row.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE';
  try {
    await api.request<RedeemItem>(
      `/api/v2/ops/admin/growth/points-redeem/${row.itemId}/status`,
      'POST',
      { status: next }
    );
    ElMessage.success(next === 'ACTIVE' ? '已启用' : '已停用');
    await load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败');
  }
}
</script>

<style scoped>
.cell-emoji {
  margin-right: 6px;
  font-size: 18px;
}
.cell-name {
  font-weight: 600;
}
.cell-sub {
  display: block;
  margin-top: 2px;
  font-size: 12px;
  color: #8a968e;
}
</style>

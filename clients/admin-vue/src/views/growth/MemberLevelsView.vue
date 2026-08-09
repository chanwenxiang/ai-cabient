<template>
  <el-card class="page-card" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">会员等级规则</span>
            <span class="hint">累计消费门槛 + 积分倍率；修改后消费返积分与会员等级即时生效</span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button v-hasPermi="['ops:member-level:edit']" type="primary" @click="openCreate"
            >新建等级</el-button
          >
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </template>

    <el-table
      v-loading="loading"
      :data="list"
      stripe
      border
      row-key="id"
      empty-text=" "
      class="report-table"
    >
      <template #empty><el-empty v-if="!loading" description="暂无等级规则" /></template>
      <el-table-column prop="levelCode" label="等级编码" width="120" align="center" class-name="col-text" />
      <el-table-column prop="levelName" label="等级名称" min-width="120" align="center" />
      <el-table-column label="累计消费区间(元)" width="180" align="center">
        <template #default="{ row }"
          >{{ yuan(row.minSpent) }} ~ {{ row.maxSpent != null ? yuan(row.maxSpent) : '+' }}</template
        >
      </el-table-column>
      <el-table-column label="累计积分区间" width="150" align="center">
        <template #default="{ row }"
          >{{ row.minPoints ?? 0 }} ~ {{ row.maxPoints != null ? row.maxPoints : '+' }}</template
        >
      </el-table-column>
      <el-table-column label="积分倍率" width="100" align="center">
        <template #default="{ row }">{{ row.pointsRate ?? 1 }}</template>
      </el-table-column>
      <el-table-column prop="sortOrder" label="排序" width="70" align="center" />
      <el-table-column label="状态" width="90" align="center">
        <template #default="{ row }">
          <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'">{{
            row.status === 'ACTIVE' ? '启用' : '停用'
          }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="150" align="center">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button
            v-if="auth.hasPerm('ops:member-level:edit')"
            link
            :type="row.status === 'ACTIVE' ? 'danger' : 'success'"
            @click="toggleStatus(row)"
            >{{ row.status === 'ACTIVE' ? '停用' : '启用' }}</el-button
          >
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑等级' : '新建等级'" width="520px" destroy-on-close>
      <el-form :model="form" label-width="130px">
        <el-form-item label="等级编码" required>
          <el-input v-model="form.levelCode" :disabled="editing" placeholder="如 GOLD" style="text-transform: uppercase" />
        </el-form-item>
        <el-form-item label="等级名称" required>
          <el-input v-model="form.levelName" placeholder="如 金卡会员" />
        </el-form-item>
        <el-form-item label="最低累计消费(元)">
          <el-input-number v-model="form.minSpent" :min="0" :precision="2" />
        </el-form-item>
        <el-form-item label="最高累计消费(元)">
          <el-input-number v-model="form.maxSpent" :min="0" :precision="2" :controls="false" placeholder="留空表示不设上限" />
        </el-form-item>
        <el-form-item label="最低累计积分">
          <el-input-number v-model="form.minPoints" :min="0" />
        </el-form-item>
        <el-form-item label="最高累计积分">
          <el-input-number v-model="form.maxPoints" :min="0" :controls="false" placeholder="留空表示不设上限" />
        </el-form-item>
        <el-form-item label="积分倍率">
          <el-input-number v-model="form.pointsRate" :min="0" :precision="2" :step="0.1" />
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

type LevelRule = {
  id?: number;
  levelCode: string;
  levelName: string;
  minSpent?: number;
  maxSpent?: number | null;
  minPoints?: number;
  maxPoints?: number | null;
  pointsRate?: number;
  sortOrder: number;
  status: string;
};

const loading = ref(false);
const saving = ref(false);
const list = ref<LevelRule[]>([]);
const dialogVisible = ref(false);
const editing = ref(false);
const auth = useAuthStore();

const form = reactive({
  id: undefined as number | undefined,
  levelCode: '',
  levelName: '',
  minSpent: 0,
  maxSpent: null as number | null,
  minPoints: 0,
  maxPoints: null as number | null,
  pointsRate: 1,
  sortOrder: 0,
  status: 'ACTIVE'
});

onMounted(load);

async function load() {
  loading.value = true;
  try {
    list.value = await api.request<LevelRule[]>('/api/v2/ops/admin/growth/member-levels');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

function openCreate() {
  editing.value = false;
  Object.assign(form, {
    id: undefined,
    levelCode: '',
    levelName: '',
    minSpent: 0,
    maxSpent: null,
    minPoints: 0,
    maxPoints: null,
    pointsRate: 1,
    sortOrder: 0,
    status: 'ACTIVE'
  });
  dialogVisible.value = true;
}

function openEdit(row: LevelRule) {
  editing.value = true;
  Object.assign(form, {
    id: row.id,
    levelCode: row.levelCode,
    levelName: row.levelName,
    minSpent: row.minSpent ?? 0,
    maxSpent: row.maxSpent ?? null,
    minPoints: row.minPoints ?? 0,
    maxPoints: row.maxPoints ?? null,
    pointsRate: row.pointsRate ?? 1,
    sortOrder: row.sortOrder,
    status: row.status
  });
  dialogVisible.value = true;
}

async function save() {
  if (!form.levelCode.trim() || !form.levelName.trim()) {
    ElMessage.warning('请填写等级编码与名称');
    return;
  }
  saving.value = true;
  try {
    await api.request<LevelRule>('/api/v2/ops/admin/growth/member-levels', 'PUT', {
      ...form,
      maxSpent: form.maxSpent == null ? undefined : form.maxSpent,
      maxPoints: form.maxPoints == null ? undefined : form.maxPoints
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

async function toggleStatus(row: LevelRule) {
  const next = row.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE';
  try {
    await api.request<LevelRule>(
      `/api/v2/ops/admin/growth/member-levels/${row.id}/status`,
      'POST',
      { status: next }
    );
    ElMessage.success(next === 'ACTIVE' ? '已启用' : '已停用');
    await load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败');
  }
}

function yuan(v?: number) {
  return v == null ? '—' : `¥${v}`;
}
</script>

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
          <el-button
            v-if="crud.hasSelection"
            v-hasPermi="['ops:member-level:edit']"
            type="success"
            @click="batchSetStatus('ACTIVE')"
          >
            批量启用 ({{ crud.selectedKeys.length }})
          </el-button>
          <el-button
            v-if="crud.hasSelection"
            v-hasPermi="['ops:member-level:edit']"
            type="warning"
            @click="batchSetStatus('INACTIVE')"
          >
            批量停用 ({{ crud.selectedKeys.length }})
          </el-button>
          <el-button v-hasPermi="['ops:member-level:edit']" type="primary" @click="openCreate"
            >新建等级</el-button
          >
        </div>
      </div>
    </template>

    <div class="table-scroll">
      <div class="table-scroll-inner">
        <CrudTable
          :table="crud"
          selectable
          :actions="rowActions"
          :action-width="150"
          actions-testid="member-level"
          empty-text="暂无等级规则"
          @action="onAction"
        >
          <el-table-column label="等级编码" width="120" class-name="col-text">
            <template #default="{ row }">
              {{ levelCodeLabel(row.levelCode, row.levelName) }}
            </template>
          </el-table-column>
          <el-table-column
            prop="levelName"
            label="等级名称"
            min-width="120"
            class-name="col-text"
            label-class-name="col-text"
          />
          <el-table-column
            label="累计消费区间(元)"
            width="180"
            align="center"
            class-name="col-money"
            label-class-name="col-money"
          >
            <template #default="{ row }"
              >{{ yuan(row.minSpent) }} ~
              {{ row.maxSpent != null ? yuan(row.maxSpent) : '+' }}</template
            >
          </el-table-column>
          <el-table-column
            label="累计积分区间"
            width="150"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }"
              >{{ row.minPoints ?? 0 }} ~
              {{ row.maxPoints != null ? row.maxPoints : '+' }}</template
            >
          </el-table-column>
          <el-table-column
            label="积分倍率"
            width="100"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">{{ row.pointsRate ?? 1 }}</template>
          </el-table-column>
          <el-table-column
            label="会员折扣"
            width="100"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <span v-if="row.priceDiscountPct != null && Number(row.priceDiscountPct) > 0">
                {{ row.priceDiscountPct }}%
              </span>
              <span v-else class="muted">无</span>
            </template>
          </el-table-column>
          <el-table-column
            prop="sortOrder"
            label="排序"
            width="70"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          />
          <el-table-column
            label="状态"
            width="90"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'">{{
                displayLabel('enable_status', row.status === 'ACTIVE' ? 'ACTIVE' : 'INACTIVE')
              }}</el-tag>
            </template>
          </el-table-column>
        </CrudTable>
      </div>
    </div>

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑等级' : '新建等级'" destroy-on-close>
      <el-form :model="form" label-width="auto">
        <el-form-item label="等级编码" required>
          <el-input
            v-model="form.levelCode"
            :disabled="editing"
            placeholder="内部编码，如 GOLD"
            style="text-transform: uppercase"
          />
          <div v-if="form.levelCode" class="form-hint">
            展示名：{{ levelCodeLabel(form.levelCode, form.levelName) }}
          </div>
        </el-form-item>
        <el-form-item label="等级名称" required>
          <el-input v-model="form.levelName" placeholder="如 金卡会员" />
        </el-form-item>
        <el-form-item label="最低累计消费(元)">
          <el-input-number v-model="form.minSpent" :min="0" :precision="2" />
        </el-form-item>
        <el-form-item label="最高累计消费(元)">
          <el-input-number
            v-model="form.maxSpent"
            :min="0"
            :precision="2"
            :controls="false"
            placeholder="留空表示不设上限"
          />
        </el-form-item>
        <el-form-item label="最低累计积分">
          <el-input-number v-model="form.minPoints" :min="0" />
        </el-form-item>
        <el-form-item label="最高累计积分">
          <el-input-number
            v-model="form.maxPoints"
            :min="0"
            :controls="false"
            placeholder="留空表示不设上限"
          />
        </el-form-item>
        <el-form-item label="积分倍率">
          <el-input-number v-model="form.pointsRate" :min="0" :precision="2" :step="0.1" />
        </el-form-item>
        <el-form-item label="会员折扣(%)">
          <el-input-number
            v-model="form.priceDiscountPct"
            :min="0"
            :max="100"
            :precision="2"
            :step="1"
          />
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
import { reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { EditPen, SwitchButton } from '@element-plus/icons-vue';
import { displayLabel } from '@aicabinet/shared-dict';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import CrudTable, { type CrudRowAction } from '@/components/CrudTable.vue';
import { useCrudTable } from '@/composables/useCrudTable';

type LevelRule = {
  id?: number;
  levelCode: string;
  levelName: string;
  minSpent?: number;
  maxSpent?: number | null;
  minPoints?: number;
  maxPoints?: number | null;
  pointsRate?: number;
  priceDiscountPct?: number;
  sortOrder: number;
  status: string;
};

const saving = ref(false);
const dialogVisible = ref(false);
const editing = ref(false);

// 列表状态机统一交给 CrudTable：分页（接口无分页，前端切片）/ 多选 / 竞态 / 空态 / 刷新 全部内建
const crud = useCrudTable<LevelRule>({
  rowKey: (r) => r.id ?? r.levelCode,
  fetchPage: async (params) => {
    const list = await api.request<LevelRule[]>(AdminEndpoints.growthMemberLevels);
    const start = params.page * params.size;
    return { items: list.slice(start, start + params.size), total: list.length };
  }
});

function levelCodeLabel(code?: string, fallbackName?: string) {
  if (!code) return fallbackName || '—';
  const label = displayLabel('member_level', code, '');
  if (label && label !== code) return label;
  return fallbackName || code;
}

function rowActions(row: LevelRule): CrudRowAction[] {
  const active = row.status === 'ACTIVE';
  return [
    { key: 'edit', label: '编辑', icon: EditPen, type: 'primary' },
    {
      key: 'toggle',
      label: displayLabel('enable_status', active ? 'INACTIVE' : 'ACTIVE'),
      icon: SwitchButton,
      type: active ? 'danger' : 'success',
      perm: 'ops:member-level:edit'
    }
  ];
}

function onAction({ key, row }: { key: string; row: LevelRule }) {
  if (key === 'edit') openEdit(row);
  else if (key === 'toggle') void toggleStatus(row);
}

const form = reactive({
  id: undefined as number | undefined,
  levelCode: '',
  levelName: '',
  minSpent: 0,
  maxSpent: null as number | null,
  minPoints: 0,
  maxPoints: null as number | null,
  pointsRate: 1,
  priceDiscountPct: 0,
  sortOrder: 0,
  status: 'ACTIVE'
});

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
    priceDiscountPct: 0,
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
    priceDiscountPct: row.priceDiscountPct ?? 0,
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
    const body = {
      ...form,
      maxSpent: form.maxSpent == null ? undefined : form.maxSpent,
      maxPoints: form.maxPoints == null ? undefined : form.maxPoints
    };
    if (editing.value && form.id != null) {
      await api.request<LevelRule>(AdminEndpoints.growthMemberLevels, 'PUT', body);
    } else {
      await api.request<LevelRule>(AdminEndpoints.growthMemberLevels, 'POST', {
        ...body,
        id: undefined
      });
    }
    ElMessage.success('已保存');
    dialogVisible.value = false;
    await crud.load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败');
  } finally {
    saving.value = false;
  }
}

async function toggleStatus(row: LevelRule) {
  if (row.id == null) {
    ElMessage.error('该等级规则缺少 ID，无法操作');
    return;
  }
  const next = row.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE';
  try {
    await api.request<LevelRule>(AdminEndpoints.growthMemberLevelStatus(row.id), 'POST', {
      status: next
    });
    ElMessage.success(next === 'ACTIVE' ? '已启用' : '已停用');
    await crud.load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败');
  }
}

async function batchSetStatus(next: 'ACTIVE' | 'INACTIVE') {
  const targets = crud.pickSelected(crud.items).filter(
    (r): r is LevelRule & { id: number } => r.status !== next && r.id != null
  );
  if (!targets.length) {
    ElMessage.info(next === 'ACTIVE' ? '选中项均已启用' : '选中项均已停用');
    return;
  }
  const action = displayLabel('enable_status', next);
  try {
    await ElMessageBox.confirm(
      `确认批量${action}选中的 ${targets.length} 条等级规则？`,
      `批量${action}`,
      {
        type: 'warning'
      }
    );
  } catch {
    return;
  }
  try {
    for (const row of targets) {
      await api.request<LevelRule>(AdminEndpoints.growthMemberLevelStatus(row.id), 'POST', {
        status: next
      });
    }
    ElMessage.success(`已批量${action} ${targets.length} 条`);
    await crud.load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : `批量${action}失败`);
  }
}

function yuan(v?: number) {
  return v == null ? '暂无' : `¥${Number(v).toFixed(2)}`;
}
</script>

<style scoped>
.form-hint {
  margin-top: 4px;
  font-size: var(--admin-font-size-sm);
  color: var(--el-text-color-secondary);
}
</style>

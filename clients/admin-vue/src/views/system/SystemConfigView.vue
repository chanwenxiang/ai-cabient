<template>
  <el-card class="page-card">
    <template #header>
      <div class="head">
        <div>
          <strong>参数配置</strong>
          <span class="hint">运营可读写的系统键值；保存后立即生效</span>
        </div>
        <div>
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
          <el-button type="primary" @click="openCreate">新增</el-button>
        </div>
      </div>
    </template>

    <el-table v-loading="loading" :data="items" stripe>
      <el-table-column prop="configKey" label="配置键" min-width="180" />
      <el-table-column prop="configValue" label="配置值" min-width="160" show-overflow-tooltip />
      <el-table-column prop="description" label="说明" min-width="160" show-overflow-tooltip />
      <el-table-column label="更新时间" width="180">
        <template #default="{ row }">{{ formatDateTime(row.updatedAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="88" fixed="right" align="center">
        <template #default="{ row }">
          <TableActions
            :actions="[{ key: 'edit', label: '编辑', icon: EditPen, type: 'primary' }]"
            @action="() => openEdit(row)"
          />
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="form.configKey && !creating ? '编辑参数' : '新增参数'" width="480px">
      <el-form label-position="top">
        <el-form-item label="配置键">
          <el-input v-model="form.configKey" :disabled="!creating" placeholder="例如 consumer.service_phone" />
        </el-form-item>
        <el-form-item label="配置值">
          <el-input v-model="form.configValue" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="form.description" />
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
import { EditPen, Refresh } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { api } from '@/api/client';
import TableActions from '@/components/TableActions.vue';
import { formatDateTime } from '@aicabinet/shared-uni/format';

interface SystemConfigRow {
  configKey: string;
  configValue: string;
  description?: string;
  updatedAt?: string;
}

const loading = ref(false);
const saving = ref(false);
const items = ref<SystemConfigRow[]>([]);
const dialogVisible = ref(false);
const creating = ref(false);
const form = reactive({ configKey: '', configValue: '', description: '' });

async function load() {
  loading.value = true;
  try {
    items.value = await api.request<SystemConfigRow[]>('/api/v2/ops/admin/system-configs', 'GET');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

function openCreate() {
  creating.value = true;
  form.configKey = '';
  form.configValue = '';
  form.description = '';
  dialogVisible.value = true;
}

function openEdit(row: SystemConfigRow) {
  creating.value = false;
  form.configKey = row.configKey;
  form.configValue = row.configValue;
  form.description = row.description || '';
  dialogVisible.value = true;
}

async function save() {
  if (!form.configKey.trim() || !form.configValue.trim()) {
    ElMessage.warning('请填写配置键与配置值');
    return;
  }
  saving.value = true;
  try {
    await api.request('/api/v2/ops/admin/system-configs', 'PUT', {
      configKey: form.configKey.trim(),
      configValue: form.configValue.trim(),
      description: form.description.trim()
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

onMounted(load);
</script>

<style scoped>
.head { display:flex; justify-content:space-between; align-items:center; gap:12px; flex-wrap:wrap; }
.hint { margin-left:10px; color:var(--layout-muted); font-size:12px; font-weight:400; }
</style>

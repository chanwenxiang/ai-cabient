<template>
  <div v-loading="loading">
    <el-page-header @back="router.push('/devices')" :content="deviceId" />
    <el-card class="page-card" style="margin-top:16px">
      <template #header>
        <div style="display:flex;gap:8px;align-items:center">
          <span>货道陈列 (Planogram)</span>
          <el-button type="primary" size="small" :loading="applying" @click="applyTemplate">套用模板</el-button>
          <el-button size="small" :icon="Refresh" @click="load">刷新</el-button>
        </div>
      </template>
      <SlotGrid :slots="slots" editable @edit="openEditor" />
    </el-card>

    <el-dialog v-model="editorVisible" :title="`编辑货道 ${editForm.slotCode}`" width="480px">
      <el-form label-width="100px">
        <el-form-item label="SKU">
          <el-select v-model="editForm.assignedSkuId" filterable clearable placeholder="选择商品" style="width:100%">
            <el-option v-for="s in skus" :key="s.skuId" :label="`${s.skuName} (${s.skuId})`" :value="s.skuId" />
          </el-select>
        </el-form-item>
        <el-form-item label="目标陈列"><el-input-number v-model="editForm.parLevel" :min="0" /></el-form-item>
        <el-form-item label="最低库存"><el-input-number v-model="editForm.minLevel" :min="0" /></el-form-item>
        <el-form-item label="最大容量"><el-input-number v-model="editForm.maxLevel" :min="0" /></el-form-item>
        <el-form-item label="启用"><el-switch v-model="editForm.enabled" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editorVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveSlot">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { api } from '@/api/client';
import SlotGrid from '@/components/SlotGrid.vue';
import type { DeviceSlot, SkuCatalog, UpsertDeviceSlotRequest } from '@aicabinet/shared-types';

const route = useRoute();
const router = useRouter();
const deviceId = route.params.id as string;
const loading = ref(false);
const applying = ref(false);
const saving = ref(false);
const slots = ref<DeviceSlot[]>([]);
const skus = ref<SkuCatalog[]>([]);
const editorVisible = ref(false);
const editForm = reactive({
  slotCode: '',
  assignedSkuId: '' as string | undefined,
  parLevel: 0,
  minLevel: 0,
  maxLevel: 0,
  enabled: true
});

async function load() {
  loading.value = true;
  try {
    slots.value = await api.request<DeviceSlot[]>(`/api/v2/ops/admin/devices/${encodeURIComponent(deviceId)}/slots`, 'GET');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

async function loadSkus() {
  skus.value = await api.request<SkuCatalog[]>('/api/v2/ops/admin/skus', 'GET');
}

async function applyTemplate() {
  applying.value = true;
  try {
    const n = await api.request<number>(`/api/v2/ops/admin/devices/${encodeURIComponent(deviceId)}/slots/apply-template`, 'POST');
    ElMessage.success(`已套用模板，新增 ${n} 个货道`);
    await load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '套用失败');
  } finally {
    applying.value = false;
  }
}

function openEditor(slot: DeviceSlot) {
  editForm.slotCode = slot.slotCode;
  editForm.assignedSkuId = slot.assignedSkuId || '';
  editForm.parLevel = slot.parLevel;
  editForm.minLevel = slot.minLevel;
  editForm.maxLevel = slot.maxLevel;
  editForm.enabled = slot.enabled;
  editorVisible.value = true;
}

async function saveSlot() {
  saving.value = true;
  const body: UpsertDeviceSlotRequest[] = [{
    slotCode: editForm.slotCode,
    assignedSkuId: editForm.assignedSkuId || '',
    parLevel: editForm.parLevel,
    minLevel: editForm.minLevel,
    maxLevel: editForm.maxLevel,
    enabled: editForm.enabled
  }];
  try {
    slots.value = await api.request<DeviceSlot[]>(
      `/api/v2/ops/admin/devices/${encodeURIComponent(deviceId)}/slots`,
      'PUT',
      body
    );
    editorVisible.value = false;
    ElMessage.success('已保存');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败');
  } finally {
    saving.value = false;
  }
}

onMounted(async () => {
  await Promise.all([load(), loadSkus()]);
});
</script>

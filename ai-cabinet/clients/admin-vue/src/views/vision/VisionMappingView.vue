<template>
  <el-card class="page-card">
    <template #header>
      <div style="display:flex;justify-content:space-between;align-items:center">
        <span>识别配置</span>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>
    </template>
    <el-tabs v-model="tab">
      <el-tab-pane label="YOLO 映射" name="yolo">
        <el-table :data="yoloMappings" stripe>
          <el-table-column prop="className" label="类别" />
          <el-table-column prop="skuId" label="SKU" />
          <el-table-column prop="skuName" label="商品名" />
          <el-table-column prop="minConfidence" label="最低置信度" width="120" />
        </el-table>
      </el-tab-pane>
      <el-tab-pane label="阿里云映射" name="aliyun">
        <el-table :data="aliyunMappings" stripe>
          <el-table-column prop="categoryId" label="类目ID" />
          <el-table-column prop="skuId" label="SKU" />
          <el-table-column prop="skuName" label="商品名" />
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { api } from '@/api/client';

const loading = ref(false);
const tab = ref('yolo');
const yoloMappings = ref<Record<string, unknown>[]>([]);
const aliyunMappings = ref<Record<string, unknown>[]>([]);

async function load() {
  loading.value = true;
  try {
    const data = await api.request<{ yolo?: Record<string, unknown>[]; aliyun?: Record<string, unknown>[] }>(
      '/api/v2/ops/admin/vision-mappings',
      'GET'
    );
    yoloMappings.value = data.yolo || (Array.isArray(data) ? data : []);
    aliyunMappings.value = data.aliyun || [];
    if (Array.isArray(data)) yoloMappings.value = data;
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

onMounted(load);
</script>

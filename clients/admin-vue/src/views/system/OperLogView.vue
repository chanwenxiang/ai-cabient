<template>
  <div class="page">
    <div class="page-header"><h2>操作日志</h2><el-button @click="onExport">导出</el-button></div>
    <div v-if="error" class="error-state">
      <el-alert :title="error" type="error" show-icon />
      <el-button size="small" @click="load">重试</el-button>
    </div>
    <el-table :data="list" border stripe v-loading="loading">
      <el-table-column type="index" label="#" width="50" />
      <el-table-column prop="operatorName" label="操作人" width="120" />
      <el-table-column prop="action" label="操作" width="100" />
      <el-table-column prop="title" label="标题" min-width="160" />
      <el-table-column prop="targetType" label="目标类型" width="100" />
      <el-table-column prop="targetId" label="目标ID" width="120" />
      <el-table-column prop="costMs" label="耗时(ms)" width="80" />
      <el-table-column label="结果" width="60">
        <template #default="{ row }"><el-tag :type="row.status === 0 ? 'success' : 'danger'">{{ row.status === 0 ? '成功' : '失败' }}</el-tag></template>
      </el-table-column>
      <el-table-column label="时间" width="160">
        <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!loading && !error && !list.length" description="暂无日志" />
  </div>
</template>
<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { get } from '@/api/client';
import { ElMessage } from 'element-plus';
const loading = ref(false);
const list = ref<any[]>([]);
const error = ref('');
async function load() {
  loading.value = true;
  try { const res = await get('/api/v2/ops/oper-logs'); list.value = res.data ?? []; } catch (e: any) { error.value = e?.message || '加载失败';}
  finally { loading.value = false; }
}
onMounted(() => { load(); });
function formatTime(t: string) { if (!t) return ''; return t.substring(0, 16).replace('T', ' '); }
function onExport() { ElMessage.info('导出功能开发中'); }
</script>
<style scoped>.page { padding: 20px; } .page-header { display: flex; justify-content: space-between; margin-bottom: 20px; } .page-header h2 { margin: 0; font-size: 20px; }</style>

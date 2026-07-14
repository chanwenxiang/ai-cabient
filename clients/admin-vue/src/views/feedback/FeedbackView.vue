<template>
  <div class="page">
    <div class="page-header"><h2>用户反馈</h2></div>
    <div v-if="error" class="error-state">
      <el-alert :title="error" type="error" show-icon />
      <el-button size="small" @click="load">重试</el-button>
    </div>
    <el-table :data="list" border stripe v-loading="loading">
      <el-table-column prop="feedbackId" label="ID" width="80" />
      <el-table-column prop="feedbackType" label="类型" width="100" />
      <el-table-column prop="content" label="内容" min-width="300" show-overflow-tooltip />
      <el-table-column prop="deviceId" label="设备" width="120" />
      <el-table-column prop="rating" label="评分" width="60" />
      <el-table-column prop="status" label="状态" width="80">
        <template #default="{ row }"><el-tag :type="row.status === 'PENDING' ? 'warning' : 'success'">{{ row.status }}</el-tag></template>
      </el-table-column>
      <el-table-column label="时间" width="160">
        <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!loading && !error && !list.length" description="暂无反馈" />
  </div>
</template>
<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { get } from '@/api/client';
const loading = ref(false);
const list = ref<any[]>([]);
const error = ref('');
async function load() {
  loading.value = true;
  try { const res = await get('/api/v2/ops/feedback'); list.value = res.data ?? []; } catch (e: any) { error.value = e?.message || '加载失败';}
  finally { loading.value = false; }
}
onMounted(() => { load(); });
function formatTime(t: string) { if (!t) return ''; return t.substring(0, 16).replace('T', ' '); }
</script>
<style scoped>.page { padding: 20px; } .page-header { margin-bottom: 20px; } .page-header h2 { margin: 0; font-size: 20px; }</style>

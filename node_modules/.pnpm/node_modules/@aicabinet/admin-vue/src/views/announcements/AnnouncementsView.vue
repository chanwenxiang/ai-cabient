<template>
  <div class="page">
    <div class="page-header">
      <h2>公告管理</h2>
      <el-button type="primary" @click="showCreate = true">发布公告</el-button>
    </div>

    <div v-if="error" class="error-state">
      <el-alert :title="error" type="error" show-icon />
      <el-button size="small" @click="load">重试</el-button>
    </div>
    <el-table :data="list" border stripe v-loading="loading">
      <el-table-column type="index" label="#" width="50" />
      <el-table-column prop="title" label="标题" min-width="200" />
      <el-table-column prop="priority" label="优先级" width="80">
        <template #default="{ row }">
          <el-tag :type="priorityType(row.priority)">{{ row.priority }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="targetScope" label="目标" width="100">
        <template #default="{ row }">{{ scopeMap[row.targetScope] || row.targetScope }}</template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="80">
        <template #default="{ row }">
          <el-tag :type="row.status === 'PUBLISHED' ? 'success' : 'info'">{{ statusMap[row.status] || row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="发布时间" width="160">
        <template #default="{ row }">{{ formatTime(row.publishAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click="onPreview(row)">查看</el-button>
          <el-button v-if="row.status === 'DRAFT'" size="small" type="success" @click="onPublish(row)">发布</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!loading && !error && !list.length" description="暂无公告" />

    <el-dialog v-model="showCreate" title="发布公告" width="600px">
      <el-form :model="form" label-width="80px">
        <el-form-item label="标题"><el-input v-model="form.title" /></el-form-item>
        <el-form-item label="内容"><el-input type="textarea" v-model="form.content" :rows="6" /></el-form-item>
        <el-form-item label="目标">
          <el-select v-model="form.targetScope">
            <el-option label="全部用户" value="ALL" />
            <el-option label="商户" value="MERCHANT" />
            <el-option label="消费者" value="CONSUMER" />
          </el-select>
        </el-form-item>
        <el-form-item label="优先级">
          <el-select v-model="form.priority">
            <el-option label="普通" value="NORMAL" />
            <el-option label="高" value="HIGH" />
            <el-option label="紧急" value="URGENT" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showCreate = false">取消</el-button>
        <el-button type="primary" @click="onPublishSubmit">发布</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { get, post } from '@/api/client';
import { ElMessage } from 'element-plus';

const loading = ref(false);
const error = ref('');
const list = ref<any[]>([]);
const showCreate = ref(false);
const form = ref<any>({ title: '', content: '', targetScope: 'ALL', priority: 'NORMAL' });

const scopeMap: Record<string,string> = { ALL: '全部', MERCHANT: '商户', CONSUMER: '消费者' };
const statusMap: Record<string,string> = { DRAFT: '草稿', PUBLISHED: '已发布', ARCHIVED: '存档' };

onMounted(() => load());

async function load() {
  loading.value = true;
  try { const res = await get('/api/v2/ops/announcements'); list.value = res.data ?? []; }
  catch (e: any) { error.value = e?.message || '加载失败'; ElMessage.error('加载失败'); }
  finally { loading.value = false; }
}

function priorityType(p: string) {
  const m: Record<string,string> = { LOW: 'info', NORMAL: '', HIGH: 'warning', URGENT: 'danger' };
  return m[p] || '';
}

function formatTime(t: string) { if (!t) return ''; return t.substring(0, 16).replace('T', ' '); }

async function onPublishSubmit() {
  try {
    await post('/api/v2/ops/announcements', { ...form.value, publishAt: new Date().toISOString() });
    ElMessage.success('发布成功');
    showCreate.value = false;
    load();
  } catch (e: any) { ElMessage.error(e?.message || '发布失败'); }
}

function onPreview(row: any) { ElMessage.info(row.content?.substring(0, 100)); }
async function onPublish(row: any) {
  try { await post(`/api/v2/ops/announcements/${row.announceId}/publish`); ElMessage.success('已发布'); load(); }
  catch (e: any) { ElMessage.error(e?.message || '发布失败'); }
}
</script>

<style scoped>
.page { padding: 20px; }
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px; }
.page-header h2 { margin: 0; font-size: 20px; }
</style>

<template>
  <el-card class="page-card" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">消息记录</span>
            <span class="hint">站内信发送记录；可手动向消费者 / 商户发信</span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button v-hasPermi="['ops:notify:list']" type="primary" @click="openSend"
            >发送站内信</el-button
          >
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </template>

    <el-table
      v-loading="loading"
      :data="displayList"
      stripe
      border
      row-key="id"
      empty-text=" "
      class="report-table"
      :default-sort="idDefaultSort"
      @sort-change="onIdSortChange"
    >
      <template #empty><el-empty v-if="!loading" description="暂无消息记录" /></template>
      <el-table-column
        prop="id"
        label="ID"
        width="80"
        align="center"
        class-name="col-text"
        sortable="custom"
      />
      <el-table-column label="时间" width="150" align="center">
        <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="受众" width="90" align="center">
        <template #default="{ row }">
          <el-tag :type="row.audience === 'CONSUMER' ? 'primary' : 'warning'">{{
            row.audience === 'CONSUMER' ? '消费者' : '商户'
          }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="title" label="标题" min-width="150" align="center" />
      <el-table-column label="内容" min-width="240" show-overflow-tooltip>
        <template #default="{ row }">{{ rewriteBizNosInText(row.body) }}</template>
      </el-table-column>
      <el-table-column label="业务" width="100" align="center" show-overflow-tooltip>
        <template #default="{ row }">{{
          dictLabel('notification_biz_type', row.bizType)
        }}</template>
      </el-table-column>
      <el-table-column label="关联单号" width="150" align="center" class-name="col-text">
        <template #default="{ row }">{{ displayBizNo(row.bizId, '无') }}</template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="sendVisible" title="发送站内信" width="520px" destroy-on-close>
      <el-form label-width="96px">
        <el-form-item label="受众" required>
          <el-radio-group v-model="sendForm.audience">
            <el-radio value="CONSUMER">消费者</el-radio>
            <el-radio value="MERCHANT">商户</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="sendForm.audience === 'CONSUMER'" label="用户ID" required>
          <el-input v-model="sendForm.userId" placeholder="如 10001" />
        </el-form-item>
        <el-form-item v-else label="商户编号" required>
          <el-input v-model="sendForm.merchantId" placeholder="如 MCH-DEFAULT" />
        </el-form-item>
        <el-form-item label="标题" required>
          <el-input v-model="sendForm.title" maxlength="80" show-word-limit />
        </el-form-item>
        <el-form-item label="内容" required>
          <el-input
            v-model="sendForm.body"
            type="textarea"
            :rows="4"
            maxlength="500"
            show-word-limit
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="sendVisible = false">取消</el-button>
        <el-button type="primary" :loading="sending" @click="doSend">发送</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { Refresh } from '@element-plus/icons-vue';
import { dictLabel } from '@aicabinet/shared-dict';
import { api } from '@/api/client';
import { displayBizNo, rewriteBizNosInText } from '@aicabinet/shared-uni/format';
import { useIdColumnSort } from '@/composables/useIdColumnSort';

type NotificationRow = {
  id: number;
  title: string;
  body: string;
  audience?: string;
  bizType?: string;
  bizId?: string;
  createdAt: string;
};

const loading = ref(false);
const sending = ref(false);
const list = ref<NotificationRow[]>([]);
const { idDefaultSort, onIdSortChange, sortById } = useIdColumnSort<NotificationRow>('id');
const displayList = computed(() => sortById(list.value));
const sendVisible = ref(false);
const sendForm = reactive({
  audience: 'CONSUMER',
  userId: '',
  merchantId: '',
  title: '',
  body: ''
});

onMounted(load);

async function load() {
  loading.value = true;
  try {
    list.value = await api.request<NotificationRow[]>(
      '/api/v2/ops/admin/growth/notifications?limit=200'
    );
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

function openSend() {
  sendForm.audience = 'CONSUMER';
  sendForm.userId = '';
  sendForm.merchantId = '';
  sendForm.title = '';
  sendForm.body = '';
  sendVisible.value = true;
}

async function doSend() {
  if (!sendForm.title.trim() || !sendForm.body.trim()) {
    ElMessage.warning('请填写标题与内容');
    return;
  }
  if (sendForm.audience === 'CONSUMER' && !sendForm.userId.trim()) {
    ElMessage.warning('请填写用户ID');
    return;
  }
  if (sendForm.audience === 'MERCHANT' && !sendForm.merchantId.trim()) {
    ElMessage.warning('请填写商户编号');
    return;
  }
  sending.value = true;
  try {
    await api.request('/api/v2/ops/admin/growth/notifications/send', 'POST', {
      audience: sendForm.audience,
      userId: sendForm.audience === 'CONSUMER' ? Number(sendForm.userId) : null,
      merchantId: sendForm.audience === 'MERCHANT' ? sendForm.merchantId.trim() : null,
      title: sendForm.title.trim(),
      body: sendForm.body.trim()
    });
    ElMessage.success('已发送');
    sendVisible.value = false;
    await load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '发送失败');
  } finally {
    sending.value = false;
  }
}

function formatTime(t?: string) {
  if (!t) return '暂无';
  const d = new Date(t);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(
    d.getMinutes()
  )}`;
}
</script>

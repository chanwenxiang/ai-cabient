<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">手机验证流水</span>
            <span class="hint">用户手机号验证审计，服务客诉与风控</span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button type="primary" @click="openCreate">登记验证</el-button>
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </template>

    <el-form inline class="filter-bar filter-bar--compact">
      <el-form-item label="手机号">
        <el-input v-model="phone" clearable placeholder="模糊搜索" style="width: 160px" />
      </el-form-item>
      <el-form-item label="渠道">
        <el-select v-model="channel" clearable placeholder="全部" style="width: 120px">
          <el-option
            v-for="item in dictOptions('pay_channel').filter((o) => ['WECHAT', 'ALIPAY'].includes(o.value))"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="load">查询</el-button>
      </el-form-item>
    </el-form>

    <el-table v-loading="loading" :data="displayItems"
        :default-sort="idDefaultSort"
        @sort-change="onIdSortChange" stripe border class="report-table" empty-text=" ">
      <template #empty><el-empty v-if="listHydrated && !loading" description="暂无验证记录" /></template>
      <el-table-column prop="logId" label="记录ID" width="100" align="center" sortable="custom" />
      <el-table-column prop="phone" label="手机号" width="140" align="center" />
      <el-table-column prop="userId" label="用户ID" width="120" align="center" />
      <el-table-column prop="channel" label="渠道" width="100" align="center">
        <template #default="{ row }">{{ dictLabel('pay_channel', row.channel) }}</template>
      </el-table-column>
      <el-table-column prop="merchantId" label="商户" min-width="140" align="center" />
      <el-table-column label="验证时间" width="170" align="center">
        <template #default="{ row }">{{ String(row.verifiedAt || '').replace('T', ' ').slice(0, 19) }}</template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dlg" title="登记手机验证" width="420px" destroy-on-close>
      <el-form label-width="88px">
        <el-form-item label="手机号" required>
          <el-input v-model="form.phone" />
        </el-form-item>
        <el-form-item label="用户ID">
          <el-input v-model="form.userId" />
        </el-form-item>
        <el-form-item label="渠道">
          <el-select v-model="form.channel" style="width: 100%">
            <el-option
              v-for="item in dictOptions('pay_channel').filter((o) => ['WECHAT', 'ALIPAY'].includes(o.value))"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="商户ID">
          <el-input v-model="form.merchantId" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { dictLabel, dictOptions } from '@aicabinet/shared-dict';
import { api } from '@/api/client';
import { useIdColumnSort } from '@/composables/useIdColumnSort';

const loading = ref(false);
const listHydrated = ref(false);
const saving = ref(false);
const phone = ref('');
const channel = ref('');
const items = ref<any[]>([]);
const { defaultSort: idDefaultSort, onSortChange: onIdSortChange, sortById } = useIdColumnSort('logId');
const displayItems = computed(() => sortById(items.value));
const dlg = ref(false);
const form = reactive({ phone: '', userId: '', channel: 'WECHAT', merchantId: '' });

async function load() {
  loading.value = true;
  try {
    const q = new URLSearchParams({ page: '0', size: '50' });
    if (phone.value) q.set('phone', phone.value);
    if (channel.value) q.set('channel', channel.value);
    const data = await api.request<{ items: any[] }>(`/api/v2/ops/admin/phone-verify/logs?${q}`, 'GET');
    items.value = data.items || [];
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    listHydrated.value = true;
    loading.value = false;
  }
}

function openCreate() {
  form.phone = '';
  form.userId = '';
  form.channel = 'WECHAT';
  form.merchantId = '';
  dlg.value = true;
}

async function save() {
  saving.value = true;
  try {
    await api.request('/api/v2/ops/admin/phone-verify/logs', 'POST', {
      phone: form.phone,
      userId: form.userId ? Number(form.userId) : null,
      channel: form.channel,
      merchantId: form.merchantId || null
    });
    ElMessage.success('已登记');
    dlg.value = false;
    await load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败');
  } finally {
    saving.value = false;
  }
}

onMounted(load);
</script>

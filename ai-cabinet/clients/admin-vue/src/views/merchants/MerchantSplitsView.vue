<template>
  <el-card class="page-card">
    <template #header>
      <div style="display:flex;justify-content:space-between;align-items:center">
        <span>商户分账</span>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>
    </template>
    <el-tabs v-model="tab">
      <el-tab-pane label="商户列表" name="merchants">
        <el-table v-loading="loadingMerchants" :data="merchants" stripe>
          <el-table-column prop="merchantId" label="商户编号" />
          <el-table-column prop="merchantName" label="名称" />
          <el-table-column label="抽成">
            <template #default="{ row }">{{ (row.platformRateBps / 100).toFixed(1) }}%</template>
          </el-table-column>
          <el-table-column label="商户改货道">
            <template #default="{ row }">
              <el-switch :model-value="row.allowMerchantPlanogramEdit" @change="(v: boolean) => toggleFlag(row, 'planogram', v)" />
            </template>
          </el-table-column>
          <el-table-column label="商户改价">
            <template #default="{ row }">
              <el-switch :model-value="row.allowMerchantPricingEdit" @change="(v: boolean) => toggleFlag(row, 'pricing', v)" />
            </template>
          </el-table-column>
          <el-table-column prop="deviceCount" label="设备数" width="90" />
        </el-table>
      </el-tab-pane>
      <el-tab-pane label="分账明细" name="splits">
        <el-form inline class="filter-bar">
          <el-form-item label="状态">
            <el-select v-model="status" clearable style="width:140px" @change="loadSplits">
              <el-option v-for="item in dictOptions('split_status')" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
          </el-form-item>
          <el-form-item><el-button type="primary" @click="loadSplits">查询</el-button></el-form-item>
        </el-form>
        <el-table v-loading="loading" :data="splits" stripe>
          <el-table-column label="分账编号" min-width="140"><template #default="{ row }"><span class="cell-id">{{ row.splitId }}</span></template></el-table-column>
          <el-table-column prop="orderId" label="订单" />
          <el-table-column prop="merchantName" label="商户" />
          <el-table-column label="商户收入"><template #default="{ row }">¥{{ (row.merchantCents / 100).toFixed(2) }}</template></el-table-column>
          <el-table-column label="状态"><template #default="{ row }">{{ dictLabel('split_status', row.status) }}</template></el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { dictLabel, dictOptions } from '@aicabinet/shared-dict';
import { api } from '@/api/client';
import type { MerchantDto, PageResult, RevenueSplit } from '@aicabinet/shared-types';

const tab = ref('merchants');
const loading = ref(false);
const loadingMerchants = ref(false);
const status = ref('');
const splits = ref<RevenueSplit[]>([]);
const merchants = ref<MerchantDto[]>([]);

async function loadMerchants() {
  loadingMerchants.value = true;
  try {
    merchants.value = await api.request<MerchantDto[]>('/api/v2/ops/admin/merchants', 'GET');
  } finally {
    loadingMerchants.value = false;
  }
}

async function loadSplits() {
  loading.value = true;
  try {
    const q = new URLSearchParams({ page: '0', size: '50' });
    if (status.value) q.set('status', status.value);
    const data = await api.request<PageResult<RevenueSplit>>(`/api/v2/ops/admin/merchants/revenue-splits?${q}`, 'GET');
    splits.value = data.items;
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

function load() {
  loadMerchants();
  loadSplits();
  ElMessage.success('已刷新');
}

async function toggleFlag(row: MerchantDto, kind: 'planogram' | 'pricing', value: boolean) {
  try {
    await api.request('/api/v2/ops/admin/merchants', 'POST', {
      merchantId: row.merchantId,
      merchantName: row.merchantName,
      contactPhone: row.contactPhone,
      platformRateBps: row.platformRateBps,
      wechatReceiverId: row.wechatReceiverId,
      status: row.status,
      remark: row.remark,
      allowMerchantPlanogramEdit: kind === 'planogram' ? value : row.allowMerchantPlanogramEdit,
      allowMerchantPricingEdit: kind === 'pricing' ? value : row.allowMerchantPricingEdit
    });
    if (kind === 'planogram') row.allowMerchantPlanogramEdit = value;
    else row.allowMerchantPricingEdit = value;
    ElMessage.success('已更新');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '更新失败');
  }
}

onMounted(load);
</script>

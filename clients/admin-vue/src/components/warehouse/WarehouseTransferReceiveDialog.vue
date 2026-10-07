<script setup lang="ts">
// V314：跨仓调拨「收货」时的在途损耗登记。
//
//   改造前：收货直接按发运量全量入库（后端 `doReceive` 无参数），
//   「发 100、到 95」的 5 件差额**无处记录** ⇒ B 仓虚增 5 件、损耗成黑洞。
//   改造后：逐行填**实收**，损耗由「发运 − 实收」**自动推导**、不让人手填。
//
//   🔴 损耗不让手填的原因：手填就会出现「实收 90、损耗 0」而发运是 100，
//      那 10 件**凭空消失**而系统不知情。后端 `TransferLossValidator` 会拒，
//      这里前端同步算一遍，让运营当场就看到正确值。

import { computed, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import { errorMessage } from '@/utils/error-message';

export interface TransferLine {
  lineId: string | number;
  skuId?: string;
  batchNo?: string;
  quantity: number;
}

const visible = ref(false);
const saving = ref(false);
const transferId = ref<string | number | null>(null);
const transferNo = ref('');
const lines = ref<Array<TransferLine & { receivedQty: number }>>([]);

const LOSS_REASONS = [
  { value: 'HANDLING_DAMAGE', label: '搬运破损' },
  { value: 'LOST', label: '运输丢失' },
  { value: 'EXPIRED', label: '过期/临期' },
  { value: 'MISENTRY', label: '错记' },
  { value: 'OTHER', label: '其他' }
];

/** 每行：损耗 = 发运 − 实收（自动算，只读展示）。 */
const derived = computed(() =>
  lines.value.map((l) => ({
    ...l,
    loss: Math.max(0, (Number(l.quantity) || 0) - (Number(l.receivedQty) || 0))
  }))
);

const totalLoss = computed(() => derived.value.reduce((s, l) => s + l.loss, 0));
const hasLoss = computed(() => totalLoss.value > 0);

function open(id: string | number, no: string, rawLines: TransferLine[]) {
  transferId.value = id;
  transferNo.value = no;
  lines.value = (rawLines || []).map((l) => ({
    ...l,
    // 默认实收 = 发运（全到），运营只需在有损耗的行改数字
    receivedQty: Number(l.quantity) || 0
  }));
  visible.value = true;
}

async function save() {
  if (transferId.value == null) return;
  // 🔴 前端先校验一次（后端也会拒，但当场提示比往返一轮更好）
  for (const l of derived.value) {
    if (l.receivedQty < 0) {
      ElMessage.warning('实收数量不能为负');
      return;
    }
    if (l.receivedQty > Number(l.quantity)) {
      ElMessage.warning(`实收不能大于发运数量（${l.quantity}）`);
      return;
    }
  }
  saving.value = true;
  try {
    await api.request(AdminEndpoints.warehouseTransferReceive(transferId.value), 'POST', {
      lines: derived.value.map((l) => ({
        lineId: l.lineId,
        receivedQty: l.receivedQty,
        // 损耗仍显式传（与服务端算出的 derived 保持一致），
        // 服务端会比对：手填的 loss 与「发运−实收」不符会 400
        lossQty: l.loss,
        lossReason: l.loss > 0 ? (l as { lossReason?: string }).lossReason || 'OTHER' : undefined,
        lossNote: l.loss > 0 ? (l as { lossNote?: string }).lossNote || undefined : undefined
      }))
    });
    visible.value = false;
    ElMessage.success(hasLoss.value ? `已收货（在途损耗 ${totalLoss.value} 件）` : '已收货入库');
    emit('done');
  } catch (e) {
    ElMessage.error(errorMessage(e, '收货失败'));
  } finally {
    saving.value = false;
  }
}

const emit = defineEmits<{ (e: 'done'): void }>();
defineExpose({ open });
</script>

<template>
  <el-dialog v-model="visible" title="收货登记" width="720px" :close-on-click-modal="false">
    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="实收数量与发运量不一致时，差额会自动记为「在途损耗」，请填损耗原因。"
      style="margin-bottom: 12px"
    />
    <el-table :data="derived" border size="small" row-key="lineId">
      <el-table-column prop="skuId" label="SKU" min-width="130" />
      <el-table-column prop="batchNo" label="批次" min-width="100" />
      <el-table-column prop="quantity" label="发运" width="80" align="right" />
      <el-table-column label="实收" width="120">
        <template #default="{ row }">
          <el-input-number
            v-model="row.receivedQty"
            :min="0"
            :max="Number(row.quantity)"
            size="small"
            controls-position="right"
            style="width: 100%"
          />
        </template>
      </el-table-column>
      <el-table-column label="损耗" width="80" align="right">
        <template #default="{ row }">
          <span :style="row.loss > 0 ? 'color: var(--el-color-danger); font-weight: 600' : ''">
            {{ row.loss }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="损耗原因" min-width="150">
        <template #default="{ row }">
          <el-select
            v-if="row.loss > 0"
            v-model="row.lossReason"
            size="small"
            placeholder="必填"
            style="width: 100%"
          >
            <el-option v-for="r in LOSS_REASONS" :key="r.value" :label="r.label" :value="r.value" />
          </el-select>
          <span v-else style="color: var(--el-text-color-placeholder)">—</span>
        </template>
      </el-table-column>
    </el-table>
    <div v-if="hasLoss" class="summary">
      在途损耗合计 <b>{{ totalLoss }}</b> 件 —— 该差额<b>不入 B 仓库存</b>，仅记录在调拨行上。
    </div>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">确认收货</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.summary {
  margin-top: 10px;
  font-size: var(--admin-font-size-sm);
  color: var(--el-color-danger);
}
</style>

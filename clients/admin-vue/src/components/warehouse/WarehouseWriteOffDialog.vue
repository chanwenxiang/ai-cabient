<script setup lang="ts">
// V313 仓库侧报损（损耗核销）对话框。
//
// 🔴 为什么独立组件而不是写进 WarehouseView：
// `check-admin-bundle-budget.mjs` 的 `ADMIN_BUDGET_ROUTE_KB=150` 卡着
// WarehouseView 这条路由（曾 151.7KB OVER，见该文件注释），
// 实测当前 125.3KB、余量仅 24.7KB。报损表单（含责任归属、索赔等字段）
// 塞进主视图会把余量吃掉。⇒ 异步组件，按需加载。
//
//   改造前：仓库里的破损/过期/丢失**没有核销入口** ——
//   只能走盘点改账，无法记录「为什么损」。

import { computed, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import { errorMessage } from '@/utils/error-message';
import { yuanToCents } from '@/utils/display';

export interface WriteOffTarget {
  warehouseId: string;
  skuId: string;
  batchNo: string;
  skuName?: string;
  /** 该批次现存数量，用于数量上限提示与校验 */
  quantity?: number;
}

const visible = ref(false);
const saving = ref(false);
const target = ref<WriteOffTarget | null>(null);

const form = reactive({
  quantity: 1,
  /** 复用后端白名单（InventoryOpsService.WRITE_OFF_REASONS） */
  reason: 'DAMAGED',
  reasonCategory: '' as string,
  responsibleParty: '' as string,
  claimNo: '',
  claimAmountYuan: '' as string
});

/**
 * 🔴 reason 与 reasonCategory 是**两个维度**，不要合并成一个下拉：
 * - `reason` 是后端白名单（EXPIRED/DAMAGED/THEFT/OTHER），决定能否入库；
 * - `reasonCategory` 是更细的分类（V311），白名单里 OTHER 是「兜底桶」，
 *   大量损耗都会落进去 ⇒ **无法区分过期/破损/丢失，责任判定就废了**。
 * 合并成一个选择框 = 退回「无法分类」的状态。
 */
const REASONS = [
  { value: 'DAMAGED', label: '破损' },
  { value: 'EXPIRED', label: '过期' },
  { value: 'THEFT', label: '疑似失窃' },
  { value: 'OTHER', label: '其他' }
];

const CATEGORIES = [
  { value: '', label: '未分类（待补）' },
  { value: 'EXPIRED', label: '过期/临期' },
  { value: 'DAMAGED', label: '外力损坏' },
  { value: 'LOST', label: '运输丢失' },
  { value: 'SHRINKAGE', label: '盘亏（原因待查）' },
  { value: 'SAMPLING', label: '样品/试吃' },
  { value: 'OTHER', label: '其他' }
];

const PARTIES = [
  { value: '', label: '尚未认定' },
  { value: 'SUPPLIER', label: '供应商' },
  { value: 'LOGISTICS', label: '物流' },
  { value: 'MERCHANT', label: '商户' },
  { value: 'NONE', label: '无责任方（自然损耗）' },
  { value: 'UNDETERMINED', label: '待判定' }
];

const maxQty = computed(() => target.value?.quantity ?? null);

function open(t: WriteOffTarget) {
  target.value = t;
  Object.assign(form, {
    quantity: 1,
    reason: 'DAMAGED',
    reasonCategory: '',
    responsibleParty: '',
    claimNo: '',
    claimAmountYuan: ''
  });
  visible.value = true;
}

async function save() {
  const t = target.value;
  if (!t) return;
  const qty = Number(form.quantity);
  if (!Number.isFinite(qty) || qty <= 0) {
    ElMessage.warning('报损数量须大于 0');
    return;
  }
  // 🔴 超量报损必须拦在UI 层：后端也会拒（防负库存），
  // 但那时用户已经填完一圈表单了。
  if (maxQty.value != null && qty > maxQty.value) {
    ElMessage.warning(`报损数量不能超过该批次现存数量（${maxQty.value}）`);
    return;
  }
  const claimYuan = form.claimAmountYuan.trim();
  if (claimYuan) {
    const cents = yuanToCents(claimYuan);
    if (cents == null || cents < 0) {
      ElMessage.warning('索赔金额须为非负数字');
      return;
    }
  }
  saving.value = true;
  try {
    await api.request(AdminEndpoints.inventoryWriteOff, 'POST', {
      // V313：仓库侧用 warehouseId，**deviceId 留空**。
      // 后端有 CHECK「deviceId 与 warehouseId 恰好填一个」兜底。
      deviceId: null,
      warehouseId: t.warehouseId,
      skuId: t.skuId,
      batchNo: t.batchNo || undefined,
      quantity: qty,
      reason: form.reason,
      reasonCategory: form.reasonCategory || undefined,
      responsibleParty: form.responsibleParty || undefined,
      claimNo: form.claimNo.trim() || undefined,
      claimAmountCents: claimYuan ? yuanToCents(claimYuan) : undefined
    });
    visible.value = false;
    ElMessage.success(
      form.responsibleParty || form.reasonCategory
        ? '已报损（归因已记录）'
        : '已报损（未填归因，可后续在台账补）'
    );
    emit('done');
  } catch (e) {
    ElMessage.error(errorMessage(e, '报损失败'));
  } finally {
    saving.value = false;
  }
}

const emit = defineEmits<{ (e: 'done'): void }>();
defineExpose({ open });
</script>

<template>
  <el-dialog v-model="visible" title="批次报损" width="520px" :close-on-click-modal="false">
    <el-alert
      type="warning"
      :closable="false"
      show-icon
      title="报损会直接扣减该批次的仓库库存，且不可撤销。"
      style="margin-bottom: 12px"
    />
    <el-form label-width="96px">
      <el-form-item label="批次">
        <div>
          {{ target?.skuName || target?.skuId }}
          <el-tag v-if="target?.batchNo" size="small" style="margin-left: 6px">
            {{ target.batchNo }}
          </el-tag>
          <span
            v-if="maxQty != null"
            style="margin-left: 8px; color: var(--el-text-color-secondary)"
          >
            现存 {{ maxQty }}
          </span>
        </div>
      </el-form-item>
      <el-form-item label="报损数量">
        <el-input-number v-model="form.quantity" :min="1" :max="maxQty ?? 99999" />
      </el-form-item>
      <el-form-item label="报损原因">
        <el-select v-model="form.reason" style="width: 100%">
          <el-option v-for="r in REASONS" :key="r.value" :label="r.label" :value="r.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="原因分类">
        <el-select v-model="form.reasonCategory" style="width: 100%">
          <el-option v-for="c in CATEGORIES" :key="c.value" :label="c.label" :value="c.value" />
        </el-select>
        <div class="hint">用于定位「过期/破损/丢失」各占多少，选「其他」会把损耗归入兜底桶。</div>
      </el-form-item>
      <el-form-item label="责任方">
        <el-select v-model="form.responsibleParty" style="width: 100%">
          <el-option v-for="p in PARTIES" :key="p.value" :label="p.label" :value="p.value" />
        </el-select>
        <div class="hint">「尚未认定」与「无责任方」是不同状态：前者还没查，后者是查过了。</div>
      </el-form-item>
      <el-form-item label="理赔单号">
        <el-input v-model="form.claimNo" placeholder="与供应商/物流结算单勾稽，可留空" />
      </el-form-item>
      <el-form-item label="索赔金额">
        <el-input v-model="form.claimAmountYuan" placeholder="元；与账面损失不同，可协商折价" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="danger" :loading="saving" @click="save">确认报损</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.hint {
  margin-top: 2px;
  font-size: var(--admin-font-size-sm);
  color: var(--el-text-color-secondary);
}
</style>

<script setup lang="ts">
import AddressPicker from '@/components/AddressPicker.vue';
import type { DeviceAssetForm } from '@/composables/device/useDeviceAsset';
import { dictOptions } from '@aicabinet/shared-dict';
import { formatDateTime } from '@aicabinet/shared-uni/format';

defineProps<{
  asset: DeviceAssetForm;
  canEditDevice: boolean;
  canRegenerateDeviceId: boolean;
  assetSaving: boolean;
  geoConfigured: boolean;
  hardwareResetLoading: boolean;
  regenerateIdLoading: boolean;
  lifeLoading: string;
  bindDialogVisible: boolean;
  bindMerchantId: string;
  bindMerchantsLoading: boolean;
  bindMerchantOptions: Array<{ merchantId: string; merchantName?: string }>;
  lifecycleLabel: (status?: string | null) => string;
  canLifecycle: (action: string) => boolean;
  lifecycleDisabledReason: (action: string) => string;
}>();

const emit = defineEmits<{
  save: [];
  'reset-hardware': [];
  'regenerate-id': [];
  'open-bind': [];
  'run-lifecycle': [action: string, confirm?: boolean];
  'confirm-bind': [];
  'update:bindDialogVisible': [value: boolean];
  'update:bindMerchantId': [value: string];
}>();
</script>

<template>
  <el-card class="page-card" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">资产与投放</span>
            <span class="hint">IMEI / 合作方式 / 路线与生命周期流转</span>
          </div>
        </div>
        <el-button
          v-hasPermi="['ops:device:edit']"
          type="primary"
          size="small"
          :loading="assetSaving"
          @click="emit('save')"
          >保存资产</el-button
        >
        <el-button
          v-if="canEditDevice && asset.imei"
          v-hasPermi="['ops:device:edit']"
          size="small"
          :loading="hardwareResetLoading"
          @click="emit('reset-hardware')"
          >解绑硬件</el-button
        >
        <el-button
          v-if="canRegenerateDeviceId"
          v-hasPermi="['ops:device:edit']"
          size="small"
          type="warning"
          :loading="regenerateIdLoading"
          @click="emit('regenerate-id')"
          >重新生成编号</el-button
        >
      </div>
    </template>
    <el-form label-width="auto" class="asset-form" @submit.prevent>
      <el-row :gutter="12">
        <el-col :xs="24" :sm="12" :md="8">
          <el-form-item label="IMEI">
            <div class="imei-field">
              <el-tag v-if="!asset.imei" type="info" effect="plain">未绑定</el-tag>
              <el-input
                v-else
                :model-value="asset.imei"
                disabled
                placeholder="柜机心跳自动绑定"
              />
            </div>
            <p v-if="canEditDevice" class="form-hint muted">
              仅柜机联网上报或「解绑硬件」后重新绑定
            </p>
          </el-form-item>
        </el-col>
        <el-col :xs="24" :sm="12" :md="8">
          <el-form-item label="资产方">
            <el-input
              v-model="asset.assetOwner"
              :disabled="!canEditDevice"
              clearable
              placeholder="自营/加盟商名"
            />
          </el-form-item>
        </el-col>
        <el-col :xs="24" :sm="12" :md="8">
          <el-form-item label="合作方式">
            <el-select
              v-model="asset.coopMode"
              :disabled="!canEditDevice"
              clearable
              placeholder="选择"
              style="width: 100%"
            >
              <el-option
                v-for="item in dictOptions('device_coop_mode')"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :xs="24" :sm="12" :md="8">
          <el-form-item label="开门押金(分)">
            <el-input-number
              v-model="asset.depositCents"
              :disabled="!canEditDevice"
              :min="0"
              :step="100"
              controls-position="right"
              style="width: 100%"
            />
            <div class="field-hint">
              &gt;0 时作为该柜开门预授权冻结额；否则用系统配置 checkout.preauth_cents（默认 2000）
            </div>
          </el-form-item>
        </el-col>
        <el-col :xs="24" :sm="12" :md="8">
          <el-form-item label="流量费(分/月)">
            <el-input-number
              v-model="asset.dataFeeCents"
              :disabled="!canEditDevice"
              :min="0"
              :step="100"
              controls-position="right"
              style="width: 100%"
            />
            <div class="field-hint">按月出账到「组织与点位 → 费用账单」；标记已付不自动扣款</div>
          </el-form-item>
        </el-col>
        <el-col :xs="24" :sm="12" :md="8">
          <el-form-item label="路线编码">
            <el-select
              v-model="asset.routeCode"
              :disabled="!canEditDevice"
              clearable
              filterable
              allow-create
              default-first-option
              placeholder="选择或输入路线"
              style="width: 100%"
            >
              <el-option
                v-for="item in dictOptions('route_code')"
                :key="item.value"
                :label="`${item.label}（${item.value}）`"
                :value="item.value"
              />
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :xs="24" :sm="12" :md="8">
          <el-form-item label="运营标签">
            <el-input
              v-model="asset.opsTags"
              :disabled="!canEditDevice"
              clearable
              placeholder="逗号分隔"
            />
          </el-form-item>
        </el-col>
        <el-col :xs="24" :sm="24" :md="24">
          <el-form-item label="投放地址">
            <AddressPicker
              v-model="asset.address"
              :disabled="!canEditDevice"
              :resolvable="geoConfigured"
              @update:latitude="(v) => (asset.latitude = v ?? undefined)"
              @update:longitude="(v) => (asset.longitude = v ?? undefined)"
            />
            <div class="field-hint">
              先选省 / 市 /
              区，再填写详细地址：未限定行政区划的地址会被地图服务在全国范围内匹配到别的城市。
              <template v-if="!geoConfigured">
                当前未配置 AMAP_WEB_KEY，无法自动解析，可用下方经纬度手工填写。
              </template>
            </div>
          </el-form-item>
        </el-col>
        <el-col :xs="24" :sm="12" :md="8">
          <el-form-item label="纬度">
            <el-input-number
              v-model="asset.latitude"
              :disabled="!canEditDevice"
              :controls="false"
              :precision="6"
              :step="0.0001"
              style="width: 100%"
              placeholder="如 31.230400"
            />
          </el-form-item>
        </el-col>
        <el-col :xs="24" :sm="12" :md="8">
          <el-form-item label="经度">
            <el-input-number
              v-model="asset.longitude"
              :disabled="!canEditDevice"
              :controls="false"
              :precision="6"
              :step="0.0001"
              style="width: 100%"
              placeholder="如 121.473700"
            />
          </el-form-item>
        </el-col>
        <el-col :xs="24" :sm="12" :md="8">
          <el-form-item label="生命周期">
            <el-tag size="small">{{ lifecycleLabel(asset.lifecycleStatus) }}</el-tag>
            <span v-if="asset.deployedAt" class="muted asset-deployed"
              >投放 {{ formatDateTime(asset.deployedAt) }}</span
            >
          </el-form-item>
        </el-col>
        <el-col :xs="24" :sm="24" :md="16">
          <el-form-item label="备注">
            <el-input
              v-model="asset.lifecycleRemark"
              :disabled="!canEditDevice"
              clearable
              placeholder="投放/退役备注"
            />
          </el-form-item>
        </el-col>
      </el-row>
    </el-form>
    <div class="cmd-section-label">生命周期操作</div>
    <div class="cmd-bar">
      <el-tooltip
        :disabled="canLifecycle('BIND')"
        :content="lifecycleDisabledReason('BIND')"
        placement="top"
      >
        <span class="life-btn-wrap">
          <el-button
            v-hasPermi="['ops:device:edit']"
            :loading="lifeLoading === 'BIND'"
            :disabled="!canLifecycle('BIND')"
            @click="emit('open-bind')"
            >绑定商户</el-button
          >
        </span>
      </el-tooltip>
      <el-tooltip
        :disabled="canLifecycle('UNBIND')"
        :content="lifecycleDisabledReason('UNBIND')"
        placement="top"
      >
        <span class="life-btn-wrap">
          <el-button
            v-hasPermi="['ops:device:edit']"
            :loading="lifeLoading === 'UNBIND'"
            :disabled="!canLifecycle('UNBIND')"
            @click="emit('run-lifecycle', 'UNBIND')"
            >解绑</el-button
          >
        </span>
      </el-tooltip>
      <el-tooltip
        :disabled="canLifecycle('DEPLOY')"
        :content="lifecycleDisabledReason('DEPLOY')"
        placement="top"
      >
        <span class="life-btn-wrap">
          <el-button
            v-hasPermi="['ops:device:edit']"
            :type="canLifecycle('DEPLOY') ? 'primary' : undefined"
            plain
            :loading="lifeLoading === 'DEPLOY'"
            :disabled="!canLifecycle('DEPLOY')"
            @click="emit('run-lifecycle', 'DEPLOY')"
            >投放</el-button
          >
        </span>
      </el-tooltip>
      <el-tooltip
        :disabled="canLifecycle('UNDEPLOY')"
        :content="lifecycleDisabledReason('UNDEPLOY')"
        placement="top"
      >
        <span class="life-btn-wrap">
          <el-button
            v-hasPermi="['ops:device:edit']"
            plain
            :loading="lifeLoading === 'UNDEPLOY'"
            :disabled="!canLifecycle('UNDEPLOY')"
            @click="emit('run-lifecycle', 'UNDEPLOY')"
            >撤回未投放</el-button
          >
        </span>
      </el-tooltip>
      <el-tooltip
        :disabled="canLifecycle('RETURN')"
        :content="lifecycleDisabledReason('RETURN')"
        placement="top"
      >
        <span class="life-btn-wrap">
          <el-button
            v-hasPermi="['ops:device:edit']"
            type="warning"
            plain
            :loading="lifeLoading === 'RETURN'"
            :disabled="!canLifecycle('RETURN')"
            @click="emit('run-lifecycle', 'RETURN')"
            >返厂</el-button
          >
        </span>
      </el-tooltip>
      <el-tooltip
        :disabled="canLifecycle('RETIRE')"
        :content="lifecycleDisabledReason('RETIRE')"
        placement="top"
      >
        <span class="life-btn-wrap">
          <el-button
            v-hasPermi="['ops:device:edit']"
            type="danger"
            plain
            :loading="lifeLoading === 'RETIRE'"
            :disabled="!canLifecycle('RETIRE')"
            @click="emit('run-lifecycle', 'RETIRE', true)"
            >退役</el-button
          >
        </span>
      </el-tooltip>
      <el-tooltip
        :disabled="canLifecycle('INBOUND')"
        :content="lifecycleDisabledReason('INBOUND')"
        placement="top"
      >
        <span class="life-btn-wrap">
          <el-button
            v-hasPermi="['ops:device:edit']"
            plain
            :loading="lifeLoading === 'INBOUND'"
            :disabled="!canLifecycle('INBOUND')"
            @click="emit('run-lifecycle', 'INBOUND')"
            >入库</el-button
          >
        </span>
      </el-tooltip>
    </div>

    <el-dialog
      :model-value="bindDialogVisible"
      title="绑定商户"
      destroy-on-close
      @update:model-value="emit('update:bindDialogVisible', $event)"
    >
      <p class="dialog-hint">选择要绑定的商户。绑定成功后柜机将进入投放状态。</p>
      <el-select
        :model-value="bindMerchantId"
        filterable
        clearable
        placeholder="请选择商户"
        style="width: 100%"
        :loading="bindMerchantsLoading"
        @update:model-value="emit('update:bindMerchantId', $event)"
      >
        <el-option
          v-for="m in bindMerchantOptions"
          :key="m.merchantId"
          :label="`${m.merchantName || m.merchantId}（${m.merchantId}）`"
          :value="m.merchantId"
        />
      </el-select>
      <template #footer>
        <el-button @click="emit('update:bindDialogVisible', false)">取消</el-button>
        <el-button
          type="primary"
          :loading="lifeLoading === 'BIND'"
          :disabled="!bindMerchantId"
          @click="emit('confirm-bind')"
          >确认绑定</el-button
        >
      </template>
    </el-dialog>
  </el-card>
</template>

<style scoped>
.page-card-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
}
.page-card-head__title {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.title {
  font-weight: 600;
  font-size: var(--admin-font-size-title);
}
.hint {
  font-size: var(--admin-font-size-sm);
  color: var(--el-text-color-secondary);
  line-height: 1.4;
}
.imei-field {
  display: flex;
  align-items: center;
  min-height: 32px;
}
.muted {
  color: var(--el-text-color-secondary);
}
.form-hint {
  margin: 4px 0 0;
  font-size: var(--admin-font-size-sm);
  line-height: 1.4;
}
.field-hint {
  font-size: var(--admin-font-size-sm);
  color: var(--el-text-color-secondary);
  line-height: 1.4;
  margin-top: 4px;
}
.asset-form {
  margin-bottom: 4px;
}
.asset-deployed {
  margin-left: 8px;
}
.cmd-section-label {
  margin: 4px 0 8px;
  font-size: var(--admin-font-size-sm);
  font-weight: 600;
  color: var(--el-text-color-secondary);
}
.cmd-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
  align-items: center;
}
.life-btn-wrap {
  display: inline-flex;
}
.dialog-hint {
  margin: 0 0 12px;
  font-size: var(--admin-font-size-table);
  color: var(--el-text-color-secondary);
  line-height: 1.5;
}
</style>

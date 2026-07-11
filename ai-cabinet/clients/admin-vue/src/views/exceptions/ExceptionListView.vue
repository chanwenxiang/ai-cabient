<template>
  <div>
    <div class="page-heading"><div><h1>运营异常中心</h1><p>集中处理影响消费者、设备占用和资金一致性的异常。</p></div><el-button :loading="loading" @click="load">刷新</el-button></div>
    <el-card shadow="never">
      <div class="filters"><el-select v-model="status" clearable placeholder="全部状态" @change="load"><el-option label="待处理" value="OPEN"/><el-option label="处理中" value="PROCESSING"/><el-option label="已解决" value="RESOLVED"/></el-select></div>
      <el-table v-loading="loading" :data="items" stripe empty-text="暂无异常">
        <el-table-column label="级别" width="90"><template #default="{row}"><el-tag :type="row.severity==='HIGH'?'danger':row.severity==='MEDIUM'?'warning':'info'">{{ row.severity }}</el-tag></template></el-table-column>
        <el-table-column prop="exceptionType" label="类型" width="170"/>
        <el-table-column prop="title" label="异常" min-width="180"/>
        <el-table-column prop="deviceId" label="设备" width="130"/>
        <el-table-column prop="sessionId" label="会话" width="180"/>
        <el-table-column prop="status" label="状态" width="100"/>
        <el-table-column label="操作" width="240"><template #default="{row}"><el-button link @click="openDetail(row)">详情</el-button><el-button v-if="row.status==='OPEN'" link type="primary" @click="claim(row)">领取</el-button><el-button v-if="row.status!=='RESOLVED'" link type="success" @click="resolve(row)">解决</el-button></template></el-table-column>
      </el-table>
    </el-card>
    <el-drawer v-model="drawer" title="异常处理详情" size="560px">
      <div v-loading="detailLoading" v-if="detail">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="异常编号">{{ detail.exception.exceptionId }}</el-descriptions-item>
          <el-descriptions-item label="异常内容">{{ detail.exception.title }}</el-descriptions-item>
          <el-descriptions-item label="详细信息">{{ detail.exception.detail || '-' }}</el-descriptions-item>
          <el-descriptions-item label="关联设备/会话">{{ detail.exception.deviceId || '-' }} / {{ detail.exception.sessionId || '-' }}</el-descriptions-item>
          <el-descriptions-item label="负责人">{{ detail.exception.assigneeUserId || '未领取' }}</el-descriptions-item>
        </el-descriptions>
        <div v-if="detail.exception.status!=='RESOLVED'" class="actions">
          <el-button type="primary" @click="addNote">添加备注</el-button>
          <el-button @click="transfer">转派</el-button>
          <el-button v-if="canRetry(detail.exception)" type="warning" @click="retryException">重试识别/结算</el-button>
          <el-button v-if="canManualResolve(detail.exception)" type="success" @click="openManualResolve">人工确认商品</el-button>
          <el-button v-if="canManualResolve(detail.exception)" type="danger" plain @click="waiveOrder">免单/全额退回</el-button>
          <el-button v-if="detail.exception.sessionId" type="danger" @click="cancelSession">取消会话并释放设备</el-button>
        </div>
        <h3>处理记录</h3>
        <el-timeline>
          <el-timeline-item v-for="action in detail.actions" :key="action.actionId" :timestamp="formatTime(action.createdAt)">
            <strong>{{ action.action }}</strong> · 操作人 {{ action.operatorId }}<div class="action-detail">{{ action.detail || '-' }}</div>
          </el-timeline-item>
        </el-timeline>
      </div>
    </el-drawer>
    <el-dialog v-model="manualDialog" title="人工确认商品与结算金额" width="640px">
      <el-alert type="warning" :closable="false" title="提交后可能产生首次扣款、补扣或余额退差，并同步变更库存。"/>
      <div class="manual-lines">
        <div v-for="(line,index) in manualLines" :key="index" class="manual-line">
          <el-select v-model="line.skuId" filterable placeholder="选择商品" style="flex:1">
            <el-option v-for="sku in skus" :key="sku.skuId" :label="`${sku.skuName}（¥${(sku.priceCents/100).toFixed(2)}）`" :value="sku.skuId"/>
          </el-select>
          <el-input-number v-model="line.quantity" :min="1" :max="99"/>
          <el-button type="danger" link @click="manualLines.splice(index,1)">删除</el-button>
        </div>
        <el-button @click="manualLines.push({skuId:'',quantity:1})">添加商品</el-button>
      </div>
      <el-input v-model="manualReason" type="textarea" :rows="3" maxlength="500" show-word-limit placeholder="必须填写判断依据和处理原因"/>
      <template #footer><el-button @click="manualDialog=false">取消</el-button><el-button type="primary" :loading="manualSubmitting" @click="submitManualResolve">确认商品并结算</el-button></template>
    </el-dialog>
  </div>
</template>
<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api } from '@/api/client';
interface OpsException { exceptionId:string; exceptionType:string; severity:string; status:string; title:string; detail?:string; deviceId?:string; sessionId?:string; assigneeUserId?:number }
interface OpsAction { actionId:number; operatorId:number; action:string; detail?:string; createdAt:string }
interface OpsDetail { exception:OpsException; actions:OpsAction[] }
interface Sku { skuId:string; skuName:string; priceCents:number }
const loading=ref(false); const status=ref(''); const items=ref<OpsException[]>([]);
const drawer=ref(false); const detailLoading=ref(false); const detail=ref<OpsDetail|null>(null);
const manualDialog=ref(false); const manualSubmitting=ref(false); const manualReason=ref('');
const manualLines=ref<{skuId:string;quantity:number}[]>([{skuId:'',quantity:1}]); const skus=ref<Sku[]>([]);
async function load(){loading.value=true;try{const q=status.value?`?status=${status.value}`:'';const page=await api.request<{items:OpsException[]}>(`/api/v2/ops/admin/exceptions${q}`,'GET');items.value=(page.items||[]).sort((a,b)=>priority(b)-priority(a))}catch(e){ElMessage.error(e instanceof Error?e.message:'加载失败')}finally{loading.value=false}}
function priority(item:OpsException){const severity={CRITICAL:400,HIGH:300,MEDIUM:200,LOW:100}[item.severity]||0;const impact=['DOOR_OPEN_TOO_LONG','SETTLEMENT_STUCK','BALANCE_INSUFFICIENT','UPLOAD_STUCK'].includes(item.exceptionType)?50:0;return severity+impact}
async function claim(row:OpsException){await api.request(`/api/v2/ops/admin/exceptions/${row.exceptionId}/claim`,'POST');ElMessage.success('已领取');await load()}
async function resolve(row:OpsException){const {value}=await ElMessageBox.prompt('请填写处理结果，记录将进入审计日志','解决异常',{inputValidator:v=>!!String(v||'').trim()||'必须填写处理结果',confirmButtonText:'确认解决',cancelButtonText:'取消'});await api.request(`/api/v2/ops/admin/exceptions/${row.exceptionId}/resolve`,'POST',{resolution:value});ElMessage.success('异常已解决');await load()}
async function openDetail(row:OpsException){drawer.value=true;detailLoading.value=true;try{detail.value=await api.request<OpsDetail>(`/api/v2/ops/admin/exceptions/${row.exceptionId}`,'GET')}catch(e){ElMessage.error(e instanceof Error?e.message:'详情加载失败')}finally{detailLoading.value=false}}
async function refreshDetail(){if(detail.value)await openDetail(detail.value.exception)}
async function addNote(){if(!detail.value)return;const {value}=await ElMessageBox.prompt('请输入处理备注','添加备注',{inputValidator:v=>!!String(v||'').trim()||'备注不能为空'});await api.request(`/api/v2/ops/admin/exceptions/${detail.value.exception.exceptionId}/notes`,'POST',{note:value});ElMessage.success('备注已记录');await refreshDetail()}
async function transfer(){if(!detail.value)return;const {value}=await ElMessageBox.prompt('请输入接收人的用户 ID','转派异常',{inputPattern:/^\d+$/,inputErrorMessage:'请输入有效用户 ID'});await api.request(`/api/v2/ops/admin/exceptions/${detail.value.exception.exceptionId}/transfer`,'POST',{assigneeUserId:Number(value),reason:'运营工作台转派'});ElMessage.success('已转派');await Promise.all([load(),refreshDetail()])}
async function cancelSession(){if(!detail.value)return;const item=detail.value.exception;const {value}=await ElMessageBox.prompt(`将终止会话 ${item.sessionId} 并释放设备 ${item.deviceId||'-'}，请填写原因`,'危险操作确认',{type:'warning',confirmButtonText:'确认终止',cancelButtonText:'取消',inputValidator:v=>!!String(v||'').trim()||'必须填写原因'});await api.request(`/api/v2/ops/admin/exceptions/${item.exceptionId}/cancel-session`,'POST',{reason:value,idempotencyKey:`ops-cancel-${item.exceptionId}`});ElMessage.success('会话已终止，设备占用已释放');await Promise.all([load(),refreshDetail()])}
function canRetry(item:OpsException){return !!item.sessionId&&['RECOGNITION_UNAVAILABLE','RECOGNITION_FAILED','SETTLEMENT_FAILED'].includes(item.exceptionType)}
async function retryException(){if(!detail.value)return;const item=detail.value.exception;await ElMessageBox.confirm(`将重新处理会话 ${item.sessionId}，系统仍会执行订单、库存和余额幂等校验。`,'确认重试',{type:'warning',confirmButtonText:'开始重试'});await api.request(`/api/v2/ops/admin/exceptions/${item.exceptionId}/retry`,'POST',{reason:'运营人工触发重试',idempotencyKey:`ops-retry-${item.exceptionId}-${Date.now()}`});ElMessage.success('重试请求已执行');await Promise.all([load(),refreshDetail()])}
function canManualResolve(item:OpsException){return !!item.sessionId&&['BALANCE_INSUFFICIENT','RECOGNITION_UNAVAILABLE','RECOGNITION_FAILED','SETTLEMENT_FAILED'].includes(item.exceptionType)}
async function openManualResolve(){if(!skus.value.length)skus.value=await api.request<Sku[]>('/api/v2/admin/skus','GET');manualLines.value=[{skuId:'',quantity:1}];manualReason.value='';manualDialog.value=true}
async function submitManualResolve(){if(!detail.value)return;const lines=manualLines.value.filter(line=>line.skuId&&line.quantity>0);if(!lines.length){ElMessage.warning('请至少选择一个商品');return}if(!manualReason.value.trim()){ElMessage.warning('必须填写处理原因');return}await ElMessageBox.confirm('确认按当前商品清单结算？系统将自动计算补扣或退差金额。','资金操作二次确认',{type:'warning',confirmButtonText:'确认结算'});manualSubmitting.value=true;try{await api.request(`/api/v2/ops/admin/exceptions/${detail.value.exception.exceptionId}/manual-resolve`,'POST',{resolutionType:'CONFIRM',items:lines,reason:manualReason.value.trim(),idempotencyKey:`ops-manual-${detail.value.exception.exceptionId}-${Date.now()}`});manualDialog.value=false;ElMessage.success('人工商品清单已结算');await Promise.all([load(),refreshDetail()])}finally{manualSubmitting.value=false}}
async function waiveOrder(){if(!detail.value)return;const item=detail.value.exception;const {value}=await ElMessageBox.prompt('该操作会取消本次消费并退回已经扣除的测试余额，请填写免单原因。','免单与全额退款',{type:'warning',confirmButtonText:'确认免单',inputValidator:v=>!!String(v||'').trim()||'必须填写免单原因'});await api.request(`/api/v2/ops/admin/exceptions/${item.exceptionId}/manual-resolve`,'POST',{resolutionType:'WAIVE',items:[],reason:value,idempotencyKey:`ops-waive-${item.exceptionId}`});ElMessage.success('免单处理完成');await Promise.all([load(),refreshDetail()])}
function formatTime(value:string){return value?new Date(value).toLocaleString():'-'}
onMounted(load);
</script>
<style scoped>.page-heading{display:flex;justify-content:space-between;align-items:flex-start;margin-bottom:20px}.page-heading h1{margin:0;font-size:24px}.page-heading p{margin:6px 0 0;color:#64748b}.filters{margin-bottom:16px}.filters .el-select{width:180px}.actions{display:flex;gap:10px;flex-wrap:wrap;margin:20px 0}.action-detail{color:#64748b;margin-top:5px;white-space:pre-wrap}.manual-lines{display:flex;flex-direction:column;gap:12px;margin:18px 0}.manual-line{display:flex;align-items:center;gap:10px}</style>

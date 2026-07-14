<template>
  <view class="page">
    <view class="hero">
      <view class="hero-orb orb-one" /><view class="hero-orb orb-two" />
      <text class="eyebrow">现场补货</text>
      <text class="title">今日补货任务</text>
      <text class="subtitle">到店签到、核对批次与数量，完成后库存自动入柜。</text>
      <view class="stats"><view><text class="stat-value">{{ pendingCount }}</text><text class="stat-label">待处理</text></view><view><text class="stat-value">{{ completedCount }}</text><text class="stat-label">已完成</text></view></view>
    </view>

    <view class="filters">
      <view v-for="item in statusOptions" :key="item.value" class="filter" :class="{ active: status === item.value }" @click="changeStatus(item.value)">{{ item.label }}</view>
    </view>

    <view v-if="loading" class="empty">任务加载中…</view>
    <view v-else-if="!tasks.length" class="empty">当前没有补货任务</view>
    <view v-for="task in tasks" :key="task.taskId" class="task-card">
      <view class="task-accent" />
      <view class="task-head">
        <view><text class="device-name">{{ deviceName(task.deviceId) }}</text><text class="device-code">{{ task.deviceId }}</text></view>
        <text class="status" :class="task.status.toLowerCase()">{{ dictLabel('replenishment_task_status', task.status) }}</text>
      </view>
      <view class="task-meta"><text>任务 #{{ task.taskId }}</text><text>{{ formatTime(task.createdAt) }}</text></view>
      <view v-if="task.notes" class="task-note">{{ task.notes }}</view>
      <button class="detail-btn" @click="openTask(task)">{{ task.status === 'COMPLETED' ? '查看完成明细' : '开始补货' }}</button>
    </view>

    <view v-if="detailVisible" class="mask" @click.self="closeDetail">
      <view class="sheet">
        <view class="sheet-handle" />
        <view class="sheet-head"><view><text class="sheet-title">{{ deviceName(selected?.deviceId) }}</text><text class="device-code">任务 #{{ selected?.taskId }}</text></view><text class="close" @click="closeDetail">×</text></view>
        <view class="step-row">
          <view class="step" :class="{ done: !!selected?.checkInAt }"><text>1</text><span>现场签到</span></view>
          <view class="step" :class="{ done: linesConfirmed }"><text>2</text><span>核对商品</span></view>
          <view class="step" :class="{ done: selected?.status === 'COMPLETED' }"><text>3</text><span>确认上架</span></view>
        </view>

        <button v-if="selected?.status !== 'COMPLETED' && !selected?.checkInAt" class="primary-btn" :disabled="submitting" @click="checkIn">现场签到</button>
        <view class="section-heading"><view><text class="section-title">本次补货商品</text><text class="section-subtitle">请逐项核对商品、批次和货道</text></view><text class="line-count">{{ lines.length }} 项</text></view>
        <view v-if="detailLoading" class="empty small">明细加载中…</view>
        <view v-for="line in lines" :key="line.lineId || `${line.skuId}-${line.batchNo}-${line.slotId}`" class="line-card">
          <view class="line-main"><view class="product-thumb">{{ productIcon(line.skuId) }}</view><view class="product-copy"><text class="sku-name">{{ skuName(line.skuId) }}</text><text class="device-code">{{ line.skuId }}</text></view><text class="qty">× {{ line.quantity }}</text></view>
          <view class="line-meta"><text>批次 {{ line.batchNo || '-' }}</text><text>货道 {{ line.slotId || '待分配' }}</text></view>
          <view class="line-meta"><text>到期 {{ line.expiryDate || '-' }}</text><text>{{ line.applied ? '已入柜' : '待上架' }}</text></view>
        </view>

        <view v-if="selected?.status !== 'COMPLETED' && selected?.checkInAt" class="action-dock">
          <button v-if="!linesConfirmed" class="secondary-btn" :disabled="submitting || !lines.length" @click="confirmLines">确认商品与数量</button>
          <button class="primary-btn" :disabled="submitting || !lines.length || !linesConfirmed" @click="completeTask">确认全部上架</button>
        </view>
        <view v-if="selected?.status === 'COMPLETED'" class="complete-banner">任务已完成，商品库存和在途状态已同步更新</view>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app';
import { dictLabel } from '@aicabinet/shared-dict';
import { formatDateTimeShort } from '@aicabinet/shared-uni/format';
import { merchantApi } from '@/utils/merchant-api';

type Task = { taskId:number; deviceId:string; status:string; notes?:string; checkInAt?:string; createdAt?:string };
type Line = { lineId?:number; lineType:string; skuId:string; batchNo?:string; productionDate?:string; expiryDate?:string; quantity:number; slotId?:string; applied:boolean };
const loading=ref(false); const detailLoading=ref(false); const submitting=ref(false);
const status=ref('IN_PROGRESS'); const allTasks=ref<Task[]>([]); const devices=ref<Record<string,any>[]>([]); const skus=ref<Record<string,any>[]>([]);
const detailVisible=ref(false); const selected=ref<Task|null>(null); const lines=ref<Line[]>([]); const linesConfirmed=ref(false);
const statusOptions=computed(() => [{value:'',label:'全部'}, ...dictOptions('replenishment_task_status').filter(item=>['PENDING','IN_PROGRESS','COMPLETED'].includes(item.value))]);
const tasks=computed(()=>status.value?allTasks.value.filter(item=>item.status===status.value):allTasks.value);
const pendingCount=computed(()=>allTasks.value.filter(item=>item.status!=='COMPLETED').length); const completedCount=computed(()=>allTasks.value.filter(item=>item.status==='COMPLETED').length);
function deviceName(id?:string){return devices.value.find(item=>item.deviceId===id)?.deviceName||id||'未知柜机'}
function skuName(id:string){return skus.value.find(item=>item.skuId===id)?.skuName||id}
function productIcon(id:string){if(id.includes('WATER'))return '💧';if(id.includes('MILK'))return '🥛';if(id.includes('NOODLE'))return '🍜';if(id.includes('SNACK'))return '🥔';return '🥤'}
function formatTime(value?:string){return formatDateTimeShort(value)}
async function load(){if(!uni.getStorageSync('merchant_token'))return uni.reLaunch({url:'/pages/login/login'});loading.value=true;try{const [taskRows,deviceRows,skuRows]=await Promise.all([merchantApi.replenishmentTasks(),merchantApi.devices(),merchantApi.pricing()]);allTasks.value=taskRows as Task[];devices.value=deviceRows as any[];skus.value=skuRows as any[]}catch(error){uni.showToast({title:error instanceof Error?error.message:'加载失败',icon:'none'})}finally{loading.value=false;uni.stopPullDownRefresh()}}
function changeStatus(value:string){status.value=value}
async function openTask(task:Task){selected.value={...task};detailVisible.value=true;linesConfirmed.value=task.status==='COMPLETED';detailLoading.value=true;try{lines.value=await merchantApi.replenishmentTaskLines(task.taskId) as Line[]}catch(error){uni.showToast({title:error instanceof Error?error.message:'明细加载失败',icon:'none'})}finally{detailLoading.value=false}}
function closeDetail(){if(!submitting.value)detailVisible.value=false}
async function checkIn(){if(!selected.value||submitting.value)return;submitting.value=true;let body:Record<string,number>={};try{const location=await new Promise<UniApp.GetLocationSuccess>((resolve,reject)=>uni.getLocation({type:'gcj02',success:resolve,fail:reject}));body={latitude:location.latitude,longitude:location.longitude}}catch{}try{selected.value=await merchantApi.checkInReplenishmentTask(selected.value.taskId,body) as Task;uni.showToast({title:'签到成功',icon:'success'})}catch(error){uni.showToast({title:error instanceof Error?error.message:'签到失败',icon:'none'})}finally{submitting.value=false}}
async function confirmLines(){if(!selected.value||submitting.value)return;submitting.value=true;try{lines.value=await merchantApi.confirmReplenishmentLines(selected.value.taskId,lines.value.map(({lineId,...line})=>line)) as Line[];linesConfirmed.value=true;uni.showToast({title:'清单已确认',icon:'success'})}catch(error){uni.showToast({title:error instanceof Error?error.message:'确认失败',icon:'none'})}finally{submitting.value=false}}
async function completeTask(){if(!selected.value||submitting.value)return;const ok=await new Promise<boolean>(resolve=>uni.showModal({title:'确认全部上架',content:'完成后将更新柜机库存并签收在途商品，请确认商品、批次和货道无误。',confirmText:'确认完成',success:r=>resolve(r.confirm),fail:()=>resolve(false)}));if(!ok)return;submitting.value=true;try{selected.value=await merchantApi.completeReplenishmentTask(selected.value.taskId) as Task;lines.value=lines.value.map(line=>({...line,applied:true}));uni.showToast({title:'补货完成',icon:'success'});await load()}catch(error){uni.showToast({title:error instanceof Error?error.message:'完成失败',icon:'none'})}finally{submitting.value=false}}
onShow(load);onPullDownRefresh(load);
</script>

<style scoped>
.page{min-height:100vh;padding:24rpx;background:#f0fdfa;box-sizing:border-box}.hero{padding:34rpx;border-radius:28rpx;color:#fff;background:linear-gradient(135deg,#064e3b,#0f766e 58%,#14b8a6);box-shadow:0 18rpx 40rpx rgba(15,118,110,.2)}.eyebrow,.title,.subtitle{display:block}.eyebrow{font-size:22rpx;opacity:.75;letter-spacing:4rpx}.title{margin-top:10rpx;font-size:42rpx;font-weight:800}.subtitle{margin-top:10rpx;font-size:24rpx;opacity:.82;line-height:1.55}.stats{display:flex;gap:60rpx;margin-top:28rpx}.stat-value,.stat-label{display:block}.stat-value{font-size:40rpx;font-weight:800}.stat-label{font-size:22rpx;opacity:.75}.filters{display:flex;gap:12rpx;margin:24rpx 0;overflow-x:auto}.filter{padding:14rpx 24rpx;border-radius:999rpx;color:#64748b;background:#fff;font-size:24rpx;white-space:nowrap}.filter.active{color:#fff;background:#0f766e}.task-card{margin-bottom:18rpx;padding:26rpx;border-radius:24rpx;background:#fff;box-shadow:0 8rpx 30rpx rgba(15,118,110,.08)}.task-head,.task-meta,.line-main,.line-meta,.sheet-head{display:flex;align-items:center;justify-content:space-between;gap:18rpx}.device-name,.device-code{display:block}.device-name{font-size:30rpx;font-weight:700;color:#0f172a}.device-code{margin-top:4rpx;color:#94a3b8;font-size:21rpx}.status{padding:8rpx 16rpx;border-radius:999rpx;color:#92400e;background:#fef3c7;font-size:22rpx}.status.completed{color:#166534;background:#dcfce7}.task-meta,.line-meta{margin-top:16rpx;color:#64748b;font-size:22rpx}.task-note{margin-top:16rpx;padding:16rpx;border-radius:14rpx;color:#475569;background:#f8fafc;font-size:22rpx}.detail-btn,.primary-btn,.secondary-btn{margin-top:22rpx;border:0;border-radius:18rpx;font-size:27rpx}.detail-btn,.primary-btn{color:#fff;background:#0f766e}.secondary-btn{color:#0f766e;background:#ccfbf1}.empty{padding:80rpx 20rpx;text-align:center;color:#94a3b8}.empty.small{padding:30rpx}.mask{position:fixed;inset:0;z-index:20;display:flex;align-items:flex-end;background:rgba(15,23,42,.45)}.sheet{width:100%;max-height:88vh;padding:30rpx 26rpx calc(30rpx + env(safe-area-inset-bottom));border-radius:32rpx 32rpx 0 0;background:#fff;overflow-y:auto;box-sizing:border-box}.sheet-title{display:block;font-size:34rpx;font-weight:800}.close{padding:10rpx;color:#64748b;font-size:46rpx}.step-row{display:grid;grid-template-columns:repeat(3,1fr);gap:12rpx;margin:26rpx 0}.step{text-align:center;color:#94a3b8;font-size:21rpx}.step text{display:flex;width:44rpx;height:44rpx;margin:0 auto 8rpx;align-items:center;justify-content:center;border-radius:50%;color:#64748b;background:#e2e8f0}.step span{display:block}.step.done{color:#0f766e}.step.done text{color:#fff;background:#0f766e}.section-title{margin:28rpx 0 14rpx;font-size:28rpx;font-weight:700}.line-card{margin-bottom:14rpx;padding:20rpx;border:1rpx solid #e2e8f0;border-radius:18rpx}.sku-name{display:block;font-size:27rpx;font-weight:700}.qty{color:#0f766e;font-size:30rpx;font-weight:800}.complete-banner{margin-top:22rpx;padding:22rpx;border-radius:18rpx;color:#166534;background:#dcfce7;text-align:center;font-size:24rpx}button[disabled]{opacity:.45}
</style>
<style scoped>
.page{position:relative;max-width:520px;margin:0 auto;padding:18px 16px 40px;background:linear-gradient(180deg,#ecfdf5 0,#f8fafc 320px,#f8fafc 100%);overflow:hidden}
.hero{position:relative;overflow:hidden;padding:28px 24px 24px;border-radius:26px;background:linear-gradient(145deg,#064e3b 0%,#047857 52%,#0d9488 100%);box-shadow:0 18px 45px rgba(6,95,70,.24)}
.hero-orb{position:absolute;border-radius:50%;background:rgba(255,255,255,.09);pointer-events:none}.orb-one{width:160px;height:160px;right:-54px;top:-68px}.orb-two{width:88px;height:88px;right:80px;bottom:-55px}
.eyebrow{position:relative;padding:5px 10px;width:max-content;border-radius:999px;background:rgba(255,255,255,.12);font-size:11px;letter-spacing:2px}.title{position:relative;margin-top:12px;font-size:29px;letter-spacing:-.5px}.subtitle{position:relative;max-width:350px;margin-top:8px;font-size:13px}.stats{position:relative;margin-top:22px;padding-top:18px;border-top:1px solid rgba(255,255,255,.16)}
.filters{padding:3px 2px;margin:18px 0 14px}.filter{padding:8px 15px;border:1px solid #e2e8f0;box-shadow:0 4px 12px rgba(15,23,42,.04)}.filter.active{border-color:#0f766e;box-shadow:0 7px 18px rgba(15,118,110,.18)}
.task-card{position:relative;overflow:hidden;margin-bottom:14px;padding:20px 18px 18px;border:1px solid #e2e8f0;border-radius:22px;box-shadow:0 10px 28px rgba(15,23,42,.07)}.task-accent{position:absolute;left:0;top:0;bottom:0;width:4px;background:linear-gradient(#10b981,#0d9488)}
.device-name{font-size:18px}.status{font-weight:700}.task-meta{padding-top:13px;border-top:1px dashed #e2e8f0}.task-note{line-height:1.55}.detail-btn{height:44px;border-radius:14px;font-weight:700;box-shadow:0 8px 18px rgba(15,118,110,.17)}
.mask{justify-content:center}.sheet{position:relative;width:100%;max-width:520px;max-height:91vh;padding:12px 18px 28px;border-radius:28px 28px 0 0;box-shadow:0 -18px 55px rgba(15,23,42,.2)}.sheet-handle{width:42px;height:5px;margin:0 auto 16px;border-radius:999px;background:#cbd5e1}.sheet-head{padding:0 4px 15px;border-bottom:1px solid #eef2f7}.sheet-title{font-size:21px}.close{font-size:30px}
.step-row{position:relative;margin:20px 0 10px;padding:14px 6px;border-radius:18px;background:#f8fafc}.step text{box-shadow:0 0 0 5px #f8fafc}.step.done text{box-shadow:0 0 0 5px #ccfbf1}
.section-heading{display:flex;align-items:flex-end;justify-content:space-between;margin:24px 2px 12px}.section-title{display:block;margin:0;font-size:17px}.section-subtitle{display:block;margin-top:4px;color:#94a3b8;font-size:11px}.line-count{padding:5px 9px;border-radius:999px;color:#0f766e;background:#ccfbf1;font-size:11px;font-weight:700}
.line-card{padding:15px;border-color:#e5e7eb;border-radius:17px;background:linear-gradient(180deg,#fff,#fcfdfd);box-shadow:0 5px 15px rgba(15,23,42,.035)}.line-main{justify-content:flex-start}.product-thumb{display:flex;flex:0 0 46px;height:46px;align-items:center;justify-content:center;border-radius:14px;background:linear-gradient(145deg,#ecfdf5,#ccfbf1);font-size:24px}.product-copy{min-width:0;flex:1}.sku-name{font-size:15px}.qty{padding:5px 9px;border-radius:10px;background:#f0fdfa;font-size:17px}.line-meta{padding-left:58px;margin-top:9px}
.action-dock{position:sticky;bottom:-28px;margin:22px -18px -28px;padding:14px 18px calc(14px + env(safe-area-inset-bottom));background:rgba(255,255,255,.96);box-shadow:0 -10px 30px rgba(15,23,42,.08);backdrop-filter:blur(14px)}.primary-btn,.secondary-btn{height:46px;margin-top:9px;border-radius:15px;font-weight:700}.complete-banner{padding:16px;line-height:1.5}
@media(min-width:600px){.page{margin-top:18px;border-radius:28px;box-shadow:0 20px 70px rgba(15,23,42,.12)}.sheet{margin-bottom:18px;border-radius:28px}}
</style>

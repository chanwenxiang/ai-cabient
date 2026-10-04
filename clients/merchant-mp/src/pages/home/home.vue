<template>
  <view class="page">
    <view v-if="loading && !meName" class="card"
      ><text>{{ UI_COPY.loading }}</text></view
    >
    <error-state v-else-if="error && !meName" :title="error" @retry="load" />
    <view v-else>
      <view v-if="error" class="banner-err"
        ><text>{{ error }}</text
        ><text role="button" aria-label="重试" class="banner-retry" @click="load">重试</text></view
      >
      <view class="dash-header" :style="headerPadStyle">
        <text class="hello">你好，{{ meName }}</text>
        <text class="sub" :class="{ 'sub--unbound': isMerchantUnbound }">{{ headerSubLine }}</text>
      </view>

      <view class="card ops-overview">
        <view class="ov-item" role="button" @click="onDisputeTap">
          <text class="ov-num warn">{{ openDisputeCount }}</text>
          <text class="ov-label">待审核争议</text>
        </view>
        <view class="ov-item">
          <text class="ov-num">{{ refundOrders }}</text>
          <text class="ov-label">退款单</text>
        </view>
        <view class="ov-item">
          <text class="ov-num">{{ lowStockCount }}</text>
          <text class="ov-label">库存偏低</text>
        </view>
        <view class="ov-item">
          <text class="ov-num">{{ expiryCount }}</text>
          <text class="ov-label">临期提醒</text>
        </view>
        <view class="ov-item">
          <text class="ov-num">{{ slotDiscrepancyCount }}</text>
          <text class="ov-label">货道差异</text>
        </view>
      </view>

      <!--
        经营概览前置（对标竞品首页信息架构）：
        竞品（友宝/丰e足食等）商户端首页第一屏即为「今日营收」，而本页原先把该卡片排在
        页尾第 8 位，需滚动 2-3 屏才能看到 —— 商户打开 App 最想看的数字反而最难找到。
        柱图已删：7 日里大多为 0、只剩一根柱，占位大且看不出趋势；数字 KPI 仍保留。
        无财务权限时回落到「柜机概况」，不会出现空白区块。
      -->
      <view v-if="canFinanceKpi || canDevices" class="card section-card">
        <text class="section">{{ canFinanceKpi ? '今日概况' : '柜机概况' }}</text>
        <view class="kpi-mini">
          <view v-if="canFinanceKpi">
            <text class="kpi-label">今日营收</text>
            <text class="kpi-value">{{ revenueToday }}</text>
          </view>
          <view v-if="canFinanceKpi && stats?.ordersToday != null">
            <text class="kpi-label">今日订单</text>
            <text class="kpi-value">{{ stats.ordersToday }}</text>
          </view>
          <view v-if="canFinanceKpi">
            <text class="kpi-label">在线柜机</text>
            <text class="kpi-value">{{ onlineText }}</text>
          </view>
          <view v-if="canFinanceKpi && canBusiness">
            <text class="kpi-label">近{{ analyticsDays }}天销量</text>
            <text class="kpi-value">{{ salesQty7d }}</text>
          </view>
          <template v-if="!canFinanceKpi">
            <!-- F1-UX：补货员无 finance 权限时概况卡补离线/停售，避免大卡空旷 -->
            <view>
              <text class="kpi-label">离线柜机</text>
              <text class="kpi-value">{{ offlineCount }}</text>
            </view>
            <view>
              <text class="kpi-label">停售柜机</text>
              <text class="kpi-value">{{ lockedCount }}</text>
            </view>
          </template>
        </view>
      </view>

      <!-- 竞品式主路径：扫码到柜 → 补货/查看（无补货权限时不展示，避免财务误操作） -->
      <view v-if="canReplenishment" class="scan-card">
        <view class="scan-copy">
          <text class="scan-title">扫码到柜</text>
          <text class="scan-desc">扫描柜门二维码，查看库存或开始补货</text>
        </view>
        <button
          class="scan-btn"
          :loading="scanning"
          :disabled="scanning"
          aria-label="扫码"
          hover-class="btn-hover"
          @click="onScan"
        >
          扫码
        </button>
      </view>

      <view v-if="canReplenishment || canDevices || canAlerts || canBusiness" class="quick-row">
        <view
          v-if="canReplenishment"
          class="quick-item"
          role="button"
          aria-label="补货任务"
          @click="goReplenishment()"
        >
          <image
            class="quick-icon"
            :src="menuIcon('replenish')"
            mode="aspectFit"
            aria-hidden="true"
          />
          <text class="quick-label">补货任务</text>
          <text v-if="pendingTaskCount" class="quick-badge">{{ pendingTaskCount }}</text>
        </view>
        <view
          v-if="canDevices"
          class="quick-item"
          role="button"
          aria-label="柜机列表"
          @click="goTab('/pages/devices/devices')"
        >
          <image
            class="quick-icon"
            :src="menuIcon('cabinet')"
            mode="aspectFit"
            aria-hidden="true"
          />
          <text class="quick-label">柜机列表</text>
        </view>
        <view
          v-if="canAlerts"
          class="quick-item"
          role="button"
          aria-label="待办事项"
          @click="goTab('/pages/alerts/alerts')"
        >
          <image
            class="quick-icon"
            :src="menuIcon('pending')"
            mode="aspectFit"
            aria-hidden="true"
          />
          <text class="quick-label">待办事项</text>
          <text v-if="pendingCount" class="quick-badge">{{ pendingCount }}</text>
        </view>
        <!-- 竞品惯例：经营/营收入口应在首屏可及，而非只藏在页尾「经营工具」网格里 -->
        <view
          v-if="canBusiness"
          class="quick-item"
          role="button"
          aria-label="经营分析"
          @click="goBusiness"
        >
          <image
            class="quick-icon"
            :src="menuIcon('business')"
            mode="aspectFit"
            aria-hidden="true"
          />
          <text class="quick-label">经营分析</text>
        </view>
      </view>

      <view v-if="canReplenishment" class="card section-card">
        <view class="section-head">
          <text class="section">今日补货</text>
          <view
            role="button"
            aria-label="查看更多"
            class="section-more app-link-chevron"
            @click="goReplenishment()"
            >全部</view
          >
        </view>
        <text v-if="preferredId" class="pref-tip">常驻柜 {{ preferredId }} 优先置顶</text>
        <!-- 仅首次进入显示加载；之后切回工作台保留上次列表/空态，避免「任务加载中」闪一下 -->
        <view v-if="taskPreviewLoading && !taskPreviewBooted" class="empty-inline">{{
          loadingLabel('任务')
        }}</view>
        <empty-state
          v-else-if="!taskPreview.length"
          compact
          kind="alerts"
          :title="homeEmptyTitle"
          :hint="homeEmptyHint"
        >
          <!-- 同屏 scan-card 已有扫码主入口，空态不再重复放大按钮（样式收敛：三入口→一入口） -->
          <text class="empty-scan-tip" role="button" @click="onScan">点此扫码到柜</text>
        </empty-state>
        <block v-else>
          <view
            v-for="task in taskPreview"
            :key="task.taskId"
            class="task-row"
            hover-class="task-row-hover"
            role="button"
            :aria-label="`补货任务 ${deviceLabel(task.deviceId)} ${statusLabel(task.status)}`"
            @click="goReplenishment(task.deviceId, task.taskId)"
          >
            <view class="task-copy">
              <text class="task-name">
                {{ deviceLabel(task.deviceId) }}
                <text v-if="preferredId && task.deviceId === preferredId" class="pref-mark"
                  >常驻</text
                >
              </text>
              <text class="task-meta">{{ task.deviceId }} · {{ statusLabel(task.status) }}</text>
            </view>
            <text class="task-go app-link-chevron">去补货</text>
          </view>
        </block>
      </view>

      <view
        v-if="canAlerts && actionItems.length"
        role="button"
        class="card section-card"
        @click="goTab('/pages/alerts/alerts')"
      >
        <view class="section-head">
          <text class="section">优先待办</text>
          <view class="section-more app-link-chevron">查看全部</view>
        </view>
        <view
          v-for="item in actionItems"
          role="button"
          :key="item.type"
          class="todo-row"
          hover-class="todo-row-hover"
          @click.stop="goTab('/pages/alerts/alerts')"
        >
          <text class="todo-dot" />
          <view class="todo-copy">
            <text class="todo-title">{{ sanitizeNotifyTitle(item.title) }}</text>
            <text v-if="item.deviceId" class="todo-detail">柜机 {{ item.deviceId }}</text>
            <text v-if="item.detail" class="todo-detail">{{
              sanitizeNotifyTitle(item.detail)
            }}</text>
          </view>
          <text class="todo-go app-link-chevron">去处理</text>
        </view>
      </view>

      <view
        class="home-notice"
        role="button"
        :aria-label="latestAnnouncement ? `公告：${latestAnnouncement.title}` : '暂无通知'"
        @click="goAnnouncementDetail"
      >
        <image class="home-notice-icon" :src="menuIcon('notice')" mode="aspectFit" />
        <text v-if="latestAnnouncement" class="home-notice-title">{{
          latestAnnouncement.title
        }}</text>
        <text v-else class="home-notice-title home-notice-title--empty">暂无通知</text>
        <view class="home-notice-more app-icon app-icon--chevron" aria-hidden="true" />
      </view>
    </view>
  </view>
  <PrivacyConsentModal
    :visible="showPrivacy"
    policy-url="/pages/policy/privacy"
    @accepted="onPrivacyAccepted"
    @declined="onPrivacyDeclined"
  />
</template>

<script setup lang="ts">
import { onShow, onPullDownRefresh } from '@dcloudio/uni-app';
import PrivacyConsentModal from '@aicabinet/shared-uni/components/privacy-consent-modal.vue';
import { usePrivacyConsentModal } from '@aicabinet/shared-uni/use-privacy-consent';
import { UI_COPY, loadingLabel } from '@aicabinet/shared-uni/ui-copy';
import { sanitizeNotifyTitle } from '@aicabinet/shared-uni/format';
import { getBelowCapsulePadPx } from '@aicabinet/shared-uni/status-bar';
import { menuIcon } from '@/utils/menu-icon';
import { useHomeWorkbench } from '@/composables/useHomeWorkbench';
import { useAutoRefresh } from '@/composables/use-auto-refresh';

const { showPrivacy, refreshPrivacyGate, onPrivacyAccepted, onPrivacyDeclined } =
  usePrivacyConsentModal();

/** Tab 页底栏已有「工作台」：去掉重复标题，只留状态栏占位 */
const headerPadStyle = {
  paddingTop: getBelowCapsulePadPx(8) + 'px'
};

const {
  preferredId,
  loading,
  taskPreviewLoading,
  taskPreviewBooted,
  scanning,
  error,
  meName,
  revenueToday,
  salesQty7d,
  stats,
  analyticsDays,
  pendingCount,
  refundOrders,
  slotDiscrepancyCount,
  openDisputeCount,
  lowStockCount,
  expiryCount,
  offlineCount,
  pendingTaskCount,
  actionItems,
  taskPreview,
  latestAnnouncement,
  onlineText,
  isMerchantUnbound,
  headerSubLine,
  homeEmptyTitle,
  homeEmptyHint,
  canReplenishment,
  canDevices,
  canAlerts,
  canPricing,
  canSettlements,
  canDisputes,
  lockedCount,
  canBusiness,
  canFinanceKpi,
  deviceLabel,
  statusLabel,
  goTab,
  goReplenishment,
  goRequest,
  goPricing,
  goSettlements,
  goDisputes,
  goBusiness,
  goAnnouncementDetail,
  onScan,
  load
} = useHomeWorkbench();

/** F1-UX：「无权限=看不见」——争议 KPI 点击按权限分流（补货员走柜机列表处理柜端） */
function onDisputeTap() {
  if (canDisputes.value) {
    goDisputes();
    return;
  }
  uni.switchTab({ url: '/pages/devices/devices' });
}

onShow(() => {
  refreshPrivacyGate();
  void load();
});
onPullDownRefresh(() => load().finally(() => uni.stopPullDownRefresh()));

/**
 * 工作台是 tabBar 常驻页，用户会长时间停在这里等「待办 / 柜机离线」计数变化。
 * 只有确实存在待跟进项时才轮询（计数归零即停表），避免无人看时也一直打接口。
 * 15 秒：工作台聚合了多个接口，比订单详情放宽。
 */
useAutoRefresh({
  intervalMs: 15_000,
  load,
  shouldContinue: () =>
    pendingCount.value > 0 || pendingTaskCount.value > 0 || offlineCount.value > 0,
  maxDurationMs: 300_000,
  canRefresh: () => !loading.value && !scanning.value
});
</script>

<style scoped>
.page {
  min-height: 100%;
  padding-bottom: calc(24rpx + env(safe-area-inset-bottom));
  background: var(--page-bg, #f4faf7);
}
.dash-header {
  background: linear-gradient(
    165deg,
    var(--brand-deep, #134e4a) 0%,
    var(--brand, #0f766e) 55%,
    var(--brand, #0f766e) 100%
  );
  padding: 12rpx 24rpx 28rpx;
  color: #ffffff;
  border-radius: 0;
  margin: 0;
  box-sizing: border-box;
}
/* 微信 <text> 不继承父级 color，问候/统计必须显式白色 */
.hello {
  font-size: var(--font-size-xl);
  font-weight: 700;
  display: block;
  color: #ffffff;
}
.sub {
  font-size: var(--font-size-sm);
  opacity: 0.85;
  display: block;
  margin-top: 4rpx;
  color: #ffffff;
}
.sub--unbound {
  opacity: 1;
  color: var(--warning-soft, #fff7ed);
}

.scan-card {
  margin: 12rpx 24rpx 0;
  position: relative;
  z-index: 2;
  background: var(--card-bg, #fff);
  border-radius: var(--radius-control);
  padding: 16rpx 18rpx;
  display: flex;
  align-items: center;
  gap: 12rpx;
  box-shadow: 0 6rpx 16rpx rgba(15, 118, 110, 0.08);
  border: 1rpx solid var(--brand-tint, var(--brand-mist));
}
.scan-copy {
  flex: 1;
  min-width: 0;
}
.scan-title {
  display: block;
  font-size: var(--font-size-md);
  font-weight: 700;
  color: var(--brand-deep, #134e4a);
}
.scan-desc {
  display: block;
  margin-top: 4rpx;
  font-size: var(--font-size-sm);
  color: var(--text-muted);
  line-height: 1.35;
}
.scan-btn {
  /* 与「我的 → 开启提醒」同款：浅底深字，避免实心主按钮抢视觉 */
  margin: 0;
  flex-shrink: 0;
  min-height: 72rpx;
  height: 72rpx;
  line-height: 72rpx;
  padding: 0 28rpx;
  border: none;
  border-radius: var(--radius-card);
  background: var(--brand-soft, #ecfdf5);
  color: var(--brand, #0f766e);
  font-size: var(--font-size-caption);
  font-weight: 600;
  box-shadow: none;
}
.scan-btn::after {
  border: none;
}
.btn-hover {
  opacity: 0.88;
}

.quick-row {
  display: flex;
  gap: 12rpx;
  margin: 14rpx 24rpx 0;
}
.quick-item {
  position: relative;
  flex: 1;
  min-width: 0;
  background: var(--card-bg, #fff);
  border-radius: var(--radius-panel);
  padding: 18rpx 10rpx;
  text-align: center;
  border: none;
  box-shadow: none;
  box-sizing: border-box;
}
.quick-icon {
  display: block;
  width: 76rpx;
  height: 76rpx;
  margin: 0 auto;
  border-radius: var(--radius-card);
  background: var(--brand-soft);
  color: var(--brand, #0f766e);
  font-size: var(--font-size-h3);
  font-weight: 700;
  line-height: 76rpx;
}
.quick-label {
  display: block;
  margin-top: 8rpx;
  font-size: var(--font-size-caption);
  color: var(--text-muted, #334155);
  font-weight: 600;
}
.quick-badge {
  position: absolute;
  top: 10rpx;
  right: 10rpx;
  min-width: 32rpx;
  height: 32rpx;
  padding: 0 8rpx;
  border-radius: var(--radius-panel);
  background: var(--color-danger);
  color: var(--white);
  font-size: var(--font-size-xs);
  line-height: 32rpx;
  text-align: center;
}

.card {
  margin: 16rpx 24rpx 0;
  padding: 32rpx;
  width: auto;
  max-width: none;
  background: var(--card-bg, #fff);
  border-radius: var(--radius-card);
  border: none;
  box-shadow: none;
  box-sizing: border-box;
}
.home-notice {
  margin: 16rpx 24rpx 12rpx;
  padding: 18rpx 20rpx;
  display: flex;
  align-items: center;
  gap: 14rpx;
  background: var(--card-bg, #fff);
  border-radius: var(--radius-card);
  border: none;
  box-shadow: none;
}
.home-notice-icon {
  width: 40rpx;
  height: 40rpx;
  flex-shrink: 0;
}
.home-notice-title {
  flex: 1;
  min-width: 0;
  font-size: var(--font-size-md);
  font-weight: 600;
  color: var(--text-primary, #0f172a);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.home-notice-title--empty {
  font-weight: 500;
  color: var(--text-muted, #334155);
}
.home-notice-more {
  flex-shrink: 0;
  color: var(--text-subtle, #cbd5e1);
  width: 0.55em;
  height: 0.55em;
  font-size: var(--font-size-md);
}
.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8rpx;
}
.section {
  font-weight: 700;
  font-size: var(--font-size-lg);
  color: var(--text-primary, #0f172a);
}
.section-more {
  /* 必须用 view 而不是 text：mp-weixin 的 text 不吃 inline-flex，::after 会被卡片圆角裁成另一种箭头 */
  flex-shrink: 0;
  color: var(--brand, #0f766e);
  font-size: var(--font-size-body);
  font-weight: 600;
  white-space: nowrap;
  line-height: 1.2;
  /* 与「去处理」同一右缘：todo-go 实测箭头约在内容区右侧内 16px+，仅 32rpx 在真机上仍显贴边 */
  box-sizing: border-box;
  padding-right: 48rpx;
}
.pref-tip {
  display: block;
  margin: 0 0 12rpx;
  font-size: var(--font-size-sm);
  color: var(--brand, #0f766e);
}
.empty-inline {
  padding: 16rpx 0 4rpx;
  text-align: center;
  color: var(--text-subtle);
  font-size: var(--font-size-body);
}
.empty-actions {
  padding-bottom: 16rpx;
}
.empty-title {
  display: block;
  color: var(--text-muted);
  font-size: var(--font-size-md);
  font-weight: 600;
}
.empty-hint {
  display: block;
  margin-top: 8rpx;
  font-size: var(--font-size-sm);
  color: var(--text-subtle, #cbd5e1);
}
.task-row {
  display: flex;
  align-items: center;
  padding: 20rpx 0;
  border-top: 1rpx solid var(--color-border-subtle, #f1f5f9);
  cursor: pointer;
  -webkit-tap-highlight-color: transparent;
}
.task-row-hover {
  opacity: 0.72;
}
.task-row:first-of-type {
  border-top: 0;
}
.task-copy,
.task-name,
.task-meta,
.task-go,
.pref-mark {
  pointer-events: none;
}
.task-copy {
  flex: 1;
  min-width: 0;
}
.task-name {
  display: block;
  font-size: var(--font-size-md);
  font-weight: 600;
  color: var(--text-primary, #0f172a);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.pref-mark {
  margin-left: 10rpx;
  padding: 2rpx 10rpx;
  border-radius: var(--radius-tag);
  background: var(--brand-tint, var(--brand-mist));
  color: var(--brand, #0f766e);
  font-size: var(--font-size-xs);
  font-weight: 700;
}
.task-meta {
  display: block;
  margin-top: 4rpx;
  font-size: var(--font-size-sm);
  color: var(--text-subtle);
}
.task-go {
  flex: 0 0 120rpx;
  width: 120rpx;
  color: var(--brand, #0f766e);
  font-size: var(--font-size-body);
  font-weight: 600;
  text-align: right;
  white-space: nowrap;
}

.todo-row {
  display: flex;
  align-items: flex-start;
  padding: 14rpx 0;
  border-top: 1rpx solid var(--color-border-subtle, #f1f5f9);
  gap: 12rpx;
}
.todo-row-hover {
  opacity: 0.72;
}
.todo-dot {
  width: 12rpx;
  height: 12rpx;
  flex: 0 0 auto;
  margin: 13rpx 2rpx 0 0;
  border-radius: 50%;
  background: var(--warning, #f59e0b);
}
.todo-copy {
  min-width: 0;
  flex: 1;
}
.todo-go {
  flex: 0 0 120rpx;
  width: 120rpx;
  margin-top: 4rpx;
  color: var(--brand, #0f766e);
  font-size: var(--font-size-body);
  font-weight: 600;
  text-align: right;
  white-space: nowrap;
}
.todo-title {
  display: block;
  font-size: var(--font-size-body);
  color: var(--text-primary, #0f172a);
}
.todo-detail {
  display: block;
  margin-top: 4rpx;
  color: var(--text-muted);
  font-size: var(--font-size-sm);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.ops-overview {
  display: flex;
  margin: 16rpx 24rpx 0;
  padding: 28rpx 16rpx 24rpx;
  background: var(--card-bg, #fff);
  border-radius: var(--radius-panel, 24rpx);
  width: auto;
  box-sizing: border-box;
}
.ov-item {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4rpx;
  min-width: 0;
}
.ov-num {
  font-size: 20px;
  font-weight: 800;
  color: var(--text-primary, #0f172a);
}
.ov-num.warn {
  color: #d97706;
}
.ov-label {
  font-size: 20rpx;
  color: var(--text-subtle);
  text-align: center;
  line-height: 1.3;
}

.kpi-mini {
  display: flex;
  gap: 12rpx;
  margin: 16rpx 0 20rpx;
}
.kpi-mini > view {
  flex: 1;
  text-align: center;
}
.kpi-label {
  display: block;
  font-size: var(--font-size-sm);
  color: var(--text-muted);
  text-align: center;
}
.kpi-value {
  display: block;
  margin-top: 6rpx;
  font-size: var(--font-size-md);
  font-weight: 700;
  color: var(--brand, #0f766e);
  text-align: center;
}
.err {
  color: var(--color-danger);
}
.banner-err {
  margin: 16rpx 24rpx 0;
  padding: 18rpx 22rpx;
  border-radius: var(--radius-panel);
  background: #f9eded;
  color: var(--color-danger);
  font-size: var(--font-size-caption);
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16rpx;
}
.banner-retry {
  color: var(--brand, #0f766e);
  font-weight: 600;
  flex-shrink: 0;
}
</style>

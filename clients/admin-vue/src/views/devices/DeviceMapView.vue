<template>
  <div class="map-page" v-loading="loading">
    <div ref="mapEl" class="map-canvas" />

    <div class="map-float-bar">
      <el-checkbox v-model="onlineOnly" @change="applyFilters">在线</el-checkbox>
      <el-checkbox v-model="selfOperatedOnly" @change="applyFilters">自营</el-checkbox>
      <el-input
        v-model="machineNo"
        clearable
        placeholder="请输入机器编号…"
        class="float-input"
        @keyup.enter="applyFilters"
        @clear="applyFilters"
      >
        <template #prepend>机器编号</template>
      </el-input>
      <el-input
        v-model="areaKeyword"
        clearable
        placeholder="请输入地区…"
        class="float-input"
        @keyup.enter="applyFilters"
        @clear="applyFilters"
      >
        <template #prepend>地区</template>
      </el-input>
      <el-input
        v-model="keyword"
        clearable
        placeholder="请输入关键字…"
        class="float-input"
        @keyup.enter="applyFilters"
        @clear="applyFilters"
      >
        <template #prepend>关键字</template>
      </el-input>
      <el-select
        v-model="lifecycleStatus"
        style="width: 120px"
        @change="load"
      >
        <el-option
          v-for="item in lifecycleOptions"
          :key="item.value"
          :label="item.label"
          :value="item.value"
        />
      </el-select>
      <el-button type="primary" @click="load">查询</el-button>
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
    </div>

    <div class="map-float-meta">
      共 {{ filteredPoints.length }} 个柜机落点
      <span v-if="tileHint" class="tile-hint">{{ tileHint }}</span>
    </div>

    <div class="map-side-panel">
      <div class="map-side__title">点位列表</div>
      <el-scrollbar max-height="70vh">
        <div
          v-for="p in filteredPoints"
          :key="p.deviceId"
          class="map-side__item"
          :class="{ active: selectedId === p.deviceId }"
          @click="focusPoint(p)"
        >
          <div class="row-main">
            <strong>{{ p.deviceName || p.deviceId }}</strong>
            <el-tag size="small" :type="p.onlineStatus === 'ONLINE' ? 'success' : 'info'">
              {{ p.onlineStatus === 'ONLINE' ? '在线' : '离线' }}
            </el-tag>
          </div>
          <div class="row-sub">{{ p.deviceId }} · {{ lifecycleLabel(p.lifecycleStatus) }} · {{ p.salesLocked ? '停售' : '可售' }}</div>
          <div class="row-sub">{{ p.address || '无地址' }}</div>
          <div class="row-actions">
            <el-button
              link
              type="primary"
              size="small"
              @click.stop="goPath(`/devices/${encodeURIComponent(p.deviceId)}`)"
            >
              详情
            </el-button>
          </div>
        </div>
        <el-empty v-if="!filteredPoints.length" description="暂无落点（需设备填写经纬度）" :image-size="64" />
      </el-scrollbar>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import 'leaflet.markercluster';
import { api } from '@/api/client';
import { useNavAccess } from '@/composables/useNavAccess';
import { dictOptions } from '@aicabinet/shared-dict';

interface MapPoint {
  deviceId: string;
  deviceName?: string;
  merchantId?: string;
  merchantName?: string;
  onlineStatus?: string;
  lifecycleStatus?: string;
  routeCode?: string;
  salesLocked?: boolean;
  latitude: number;
  longitude: number;
  address?: string;
}

const { goPath } = useNavAccess();
const loading = ref(false);
const lifecycleStatus = ref('DEPLOYED');
const onlineOnly = ref(false);
const selfOperatedOnly = ref(false);
const machineNo = ref('');
const areaKeyword = ref('');
const keyword = ref('');
const selectedId = ref('');
const tileHint = ref('');
const points = ref<MapPoint[]>([]);
const mapEl = ref<HTMLElement | null>(null);
let map: L.Map | null = null;
let cluster: L.MarkerClusterGroup | null = null;
const markerById = new Map<string, L.Marker>();
/** 侧栏点选后保持特写，避免 renderMarkers 再次 fitBounds 拉回总览 */
let keepViewAfterRender = false;
const FOCUS_ZOOM = 18;

const lifecycleOptions = [
  ...dictOptions('device_lifecycle'),
  { label: '全部有坐标', value: 'ALL' }
];

const filteredPoints = computed(() => {
  const machine = machineNo.value.trim().toLowerCase();
  const area = areaKeyword.value.trim().toLowerCase();
  const kw = keyword.value.trim().toLowerCase();
  return points.value.filter((p) => {
    if (onlineOnly.value && p.onlineStatus !== 'ONLINE') return false;
    if (selfOperatedOnly.value) {
      const mid = String(p.merchantId || '').toUpperCase();
      if (!(mid.includes('DEFAULT') || mid.includes('SELF') || mid.includes('自营'))) return false;
    }
    if (machine && !String(p.deviceId || '').toLowerCase().includes(machine)) return false;
    if (area) {
      const hay = `${p.address || ''} ${p.routeCode || ''}`.toLowerCase();
      if (!hay.includes(area)) return false;
    }
    if (kw) {
      const hay = `${p.deviceId || ''} ${p.deviceName || ''} ${p.merchantId || ''} ${p.address || ''}`.toLowerCase();
      if (!hay.includes(kw)) return false;
    }
    return true;
  });
});

function lifecycleLabel(s?: string) {
  return lifecycleOptions.find((o) => o.value === s)?.label || s || '未知状态';
}

function applyFilters() {
  renderMarkers();
}

async function load() {
  loading.value = true;
  try {
    const q = new URLSearchParams();
    if (lifecycleStatus.value) q.set('lifecycleStatus', lifecycleStatus.value);
    points.value = await api.request<MapPoint[]>(`/api/v2/ops/admin/devices/map-points?${q}`, 'GET');
    await nextTick();
    renderMarkers();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

function clusterTone(count: number): 'purple' | 'red' | 'yellow' | 'blue' {
  if (count >= 40) return 'purple';
  if (count >= 18) return 'red';
  if (count >= 9) return 'yellow';
  return 'blue';
}

function clusterSize(count: number) {
  if (count >= 100) return 64;
  if (count >= 40) return 56;
  if (count >= 18) return 48;
  if (count >= 9) return 42;
  return 36;
}

function ensureMap() {
  if (map || !mapEl.value) return;
  map = L.map(mapEl.value, {
    zoomControl: true,
    preferCanvas: false
  }).setView([31.23, 121.47], 11);

  // 优先高德彩色路网（参考图风格）；失败再切 Esri / GeoQ
  const layers = [
    L.tileLayer('https://webrd0{s}.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}', {
      maxZoom: 18,
      subdomains: '1234',
      attribution: '© 高德'
    }),
    L.tileLayer('https://wprd0{s}.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=7&x={x}&y={y}&z={z}', {
      maxZoom: 18,
      subdomains: '1234',
      attribution: '© 高德'
    }),
    L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/{z}/{y}/{x}', {
      maxZoom: 19,
      attribution: '© Esri'
    }),
    L.tileLayer('https://map.geoq.cn/ArcGIS/rest/services/ChinaOnlineCommunity/MapServer/tile/{z}/{y}/{x}', {
      maxZoom: 16,
      attribution: '© GeoQ'
    })
  ];
  const attach = (i: number) => {
    if (!map || i >= layers.length) return;
    const layerTile = layers[i];
    let errors = 0;
    layerTile.on('tileerror', () => {
      errors += 1;
      if (!map || !map.hasLayer(layerTile) || errors < 6) return;
      map.removeLayer(layerTile);
      if (i + 1 < layers.length) {
        attach(i + 1);
        tileHint.value = `底图已切换备用源（${i + 2}/${layers.length}）`;
      } else {
        tileHint.value = '底图加载失败，右侧列表仍可查看点位';
      }
    });
    layerTile.addTo(map);
  };
  attach(0);

  const group = (L as any).markerClusterGroup({
    showCoverageOnHover: false,
    maxClusterRadius: 70,
    spiderfyOnMaxZoom: true,
    disableClusteringAtZoom: 17,
    iconCreateFunction(c: any) {
      const count = c.getChildCount();
      const tone = clusterTone(count);
      const size = clusterSize(count);
      return L.divIcon({
        html: `<div class="pulse-cluster pulse-${tone}" style="--sz:${size}px">
          <span class="pulse-ring r1"></span>
          <span class="pulse-ring r2"></span>
          <span class="pulse-ring r3"></span>
          <span class="pulse-core"><b>${count}</b></span>
        </div>`,
        className: 'map-cluster-icon',
        iconSize: L.point(size + 36, size + 36),
        iconAnchor: L.point((size + 36) / 2, (size + 36) / 2)
      });
    }
  }) as L.Layer;
  cluster = group as any;
  map.addLayer(group);
}

function pinIcon(selected: boolean) {
  const scale = selected ? 1.15 : 1;
  const w = Math.round(28 * scale);
  const h = Math.round(40 * scale);
  return L.divIcon({
    className: 'map-pin-icon',
    html: `<div class="teardrop-pin" style="--w:${w}px;--h:${h}px">
      <svg viewBox="0 0 28 40" width="${w}" height="${h}" aria-hidden="true">
        <path d="M14 0C6.3 0 0 6.3 0 14c0 10.5 14 26 14 26S28 24.5 28 14C28 6.3 21.7 0 14 0z" fill="#e53935"/>
        <circle cx="14" cy="14" r="6" fill="#fff"/>
      </svg>
    </div>`,
    iconSize: [w, h],
    iconAnchor: [w / 2, h],
    popupAnchor: [0, -h + 4]
  });
}

function renderMarkers() {
  ensureMap();
  if (!map || !cluster) return;
  cluster.clearLayers();
  markerById.clear();
  const list = filteredPoints.value;
  const bounds: L.LatLngExpression[] = [];
  for (const p of list) {
    const latlng: L.LatLngExpression = [p.latitude, p.longitude];
    bounds.push(latlng);
    const marker = L.marker(latlng, {
      icon: pinIcon(selectedId.value === p.deviceId)
    });
    const isOnline = p.onlineStatus === 'ONLINE';
    const status = isOnline ? '在线' : '离线';
    const locked = p.salesLocked ? '停售' : '可售';
    marker.bindPopup(
      `<strong>${escapeHtml(p.deviceName || p.deviceId)}</strong><br/>${escapeHtml(p.deviceId)}<br/>${status} · ${locked} · ${escapeHtml(lifecycleLabel(p.lifecycleStatus))}<br/>路线：${escapeHtml(p.routeCode || '无')}<br/>${escapeHtml(p.address || '')}<br/><a href="#" class="map-goto" data-id="${escapeAttr(p.deviceId)}">查看详情</a>`
    );
    marker.on('click', () => {
      selectedId.value = p.deviceId;
    });
    marker.on('popupopen', () => {
      const link = document.querySelector(`a.map-goto[data-id="${CSS.escape(p.deviceId)}"]`);
      link?.addEventListener('click', (ev) => {
        ev.preventDefault();
        goPath(`/devices/${encodeURIComponent(p.deviceId)}`);
      });
    });
    markerById.set(p.deviceId, marker);
    cluster.addLayer(marker);
  }
  // 点选柜子后保持特写；仅在初次加载/筛选刷新时按真实落点框选
  if (!keepViewAfterRender) {
    if (bounds.length === 1) {
      map.setView(bounds[0], 14);
    } else if (bounds.length > 1) {
      map.fitBounds(L.latLngBounds(bounds as L.LatLngTuple[]), { padding: [48, 48], maxZoom: 14 });
    }
  }
  keepViewAfterRender = false;
  setTimeout(() => map?.invalidateSize(), 80);
}

function focusPoint(p: MapPoint) {
  selectedId.value = p.deviceId;
  ensureMap();
  keepViewAfterRender = true;
  renderMarkers();
  const maxZ = Math.min(FOCUS_ZOOM, map?.getMaxZoom() ?? FOCUS_ZOOM);
  // 高于 disableClusteringAtZoom(17)，展开为红色水滴钉
  map?.flyTo([p.latitude, p.longitude], maxZ, { duration: 0.55 });
  const openSelected = () => {
    const m = markerById.get(p.deviceId);
    if (!m || !map || !cluster) return;
    // 若仍被聚合包住，先缩放到能看到单点
    const visible = (cluster as any).getVisibleParent?.(m);
    if (visible && visible !== m) {
      (cluster as any).zoomToShowLayer?.(m, () => m.openPopup());
    } else {
      m.openPopup();
    }
  };
  map?.once('moveend', openSelected);
  setTimeout(openSelected, 700);
}

function escapeHtml(s: string) {
  return s.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c] || c);
}
function escapeAttr(s: string) {
  return s.replace(/"/g, '&quot;');
}

onMounted(async () => {
  await nextTick();
  ensureMap();
  await load();
});

onBeforeUnmount(() => {
  map?.remove();
  map = null;
  cluster = null;
});
</script>

<style scoped>
.map-page {
  position: relative;
  height: calc(100vh - 120px);
  min-height: 560px;
  border-radius: 10px;
  overflow: hidden;
  border: 1px solid var(--el-border-color-lighter);
  background: #dce8f5;
}
.map-canvas {
  width: 100%;
  height: 100%;
  z-index: 0;
}
.map-float-bar {
  position: absolute;
  top: 14px;
  left: 54px;
  right: 300px;
  z-index: 500;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  padding: 10px 12px;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.96);
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.12);
  border: 1px solid rgba(148, 163, 184, 0.35);
}
.float-input {
  width: 180px;
}
.map-float-meta {
  position: absolute;
  left: 54px;
  top: 74px;
  z-index: 500;
  font-size: 12px;
  color: #334155;
  background: rgba(255, 255, 255, 0.92);
  padding: 4px 10px;
  border-radius: 999px;
  box-shadow: 0 2px 8px rgba(15, 23, 42, 0.08);
}
.tile-hint { margin-left: 8px; color: var(--el-color-warning); }
.map-side-panel {
  position: absolute;
  top: 14px;
  right: 14px;
  bottom: 14px;
  width: 280px;
  z-index: 500;
  border-radius: 10px;
  padding: 12px;
  background: rgba(255, 255, 255, 0.96);
  border: 1px solid rgba(148, 163, 184, 0.35);
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.12);
  overflow: hidden;
}
.map-side__title { font-weight: 600; margin-bottom: 8px; font-size: 13px; }
.map-side__item {
  padding: 10px 8px;
  border-radius: 8px;
  cursor: pointer;
  border: 1px solid transparent;
  margin-bottom: 6px;
}
.map-side__item:hover,
.map-side__item.active {
  background: var(--el-fill-color-light);
  border-color: var(--el-color-primary-light-5);
}
.row-main { display: flex; justify-content: space-between; gap: 8px; align-items: center; }
.row-sub { font-size: 12px; color: var(--el-text-color-secondary); margin-top: 2px; }
.row-actions { margin-top: 4px; }
@media (max-width: 1100px) {
  .map-float-bar { left: 12px; right: 12px; }
  .map-side-panel { display: none; }
  .map-float-meta { left: 12px; top: auto; bottom: 14px; }
}
</style>

<style>
/* 去掉 leaflet.markercluster 默认灰气泡，改用参考图脉冲环 */
.map-cluster-icon {
  background: transparent !important;
  border: none !important;
}
.pulse-cluster {
  position: relative;
  width: calc(var(--sz, 48px) + 36px);
  height: calc(var(--sz, 48px) + 36px);
  display: flex;
  align-items: center;
  justify-content: center;
  --c: #3b82f6;
}
.pulse-purple { --c: #9b2cf3; }
.pulse-red { --c: #ef4444; }
.pulse-yellow { --c: #f5c518; }
.pulse-blue { --c: #3b82f6; }

.pulse-core {
  position: relative;
  z-index: 2;
  width: var(--sz, 48px);
  height: var(--sz, 48px);
  border-radius: 50%;
  background: var(--c);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
  font-size: 15px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.25);
  border: 2px solid rgba(255, 255, 255, 0.85);
}
.pulse-yellow .pulse-core { color: #1f2937; }

.pulse-ring {
  position: absolute;
  left: 50%;
  top: 50%;
  width: var(--sz, 48px);
  height: var(--sz, 48px);
  margin-left: calc(var(--sz, 48px) / -2);
  margin-top: calc(var(--sz, 48px) / -2);
  border-radius: 50%;
  border: 2px solid var(--c);
  opacity: 0;
  pointer-events: none;
  animation: map-pulse-ring 2.4s ease-out infinite;
}
.pulse-ring.r2 { animation-delay: 0.8s; }
.pulse-ring.r3 { animation-delay: 1.6s; }

@keyframes map-pulse-ring {
  0% {
    transform: scale(1);
    opacity: 0.55;
  }
  70% {
    opacity: 0.12;
  }
  100% {
    transform: scale(2.35);
    opacity: 0;
  }
}

@media (prefers-reduced-motion: reduce) {
  .pulse-ring,
  .pulse-ring.r2,
  .pulse-ring.r3 {
    animation: none;
    opacity: 0.28;
  }
}

.map-pin-icon {
  background: transparent !important;
  border: none !important;
}
.teardrop-pin {
  filter: drop-shadow(0 2px 4px rgba(0, 0, 0, 0.35));
  line-height: 0;
}
</style>

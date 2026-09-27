<template>
  <div class="map-wrapper">
    <div class="map-header">
      <span class="map-title">🗺️ 当日游览路线轨迹与空间打点</span>
      <span class="map-tip">点击标记可定位左侧卡片</span>
    </div>
    <div id="leaflet-map-container" ref="mapContainerRef" class="map-container"></div>
  </div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount, watch, nextTick } from 'vue'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'

const props = defineProps({
  items: {
    type: Array,
    default: () => []
  },
  activeItemId: {
    type: [Number, String],
    default: null
  },
  destination: {
    type: String,
    default: '成都'
  }
})

const emit = defineEmits(['select-item'])

const mapContainerRef = ref(null)
let map = null
let markerLayerGroup = null
let polylineLayer = null

// 常用知名旅游城市中心基准坐标库（用于老数据坐标兜底降级）
const CITY_COORDINATES = {
  '成都': [30.5728, 104.0668],
  '大理': [25.6065, 100.2237],
  '丽江': [26.8721, 100.2299],
  '苏州': [31.2990, 120.5853],
  '杭州': [30.2741, 120.1551],
  '黄山': [29.7147, 118.3375],
  '西安': [34.3416, 108.9398],
  '北京': [39.9042, 116.4074],
  '上海': [31.2304, 121.4737],
  '金华': [29.1029, 119.6474],
  '太原': [37.8706, 112.5489],
  '甘孜': [30.0495, 101.9625],
  '无锡': [31.4912, 120.3119]
}

// 1. 获取有效坐标（带智能散点偏移降级机制）
const resolveCoordinates = (item, index, total) => {
  if (item.latitude && item.longitude && !isNaN(item.latitude) && !isNaN(item.longitude)) {
    return [Number(item.latitude), Number(item.longitude)]
  }
  
  // 查找城市基准点
  let basePoint = [30.5728, 104.0668] // 默认成都
  for (const city in CITY_COORDINATES) {
    if (props.destination && props.destination.includes(city)) {
      basePoint = CITY_COORDINATES[city]
      break
    }
  }

  // 沿圆周散开生成微小的确定性坐标偏移，避免所有点重叠在同一个像素上
  const angle = (index / (total || 1)) * 2 * Math.PI
  const radius = 0.02 + (index * 0.006) // 约 2-3 公里散落范围
  const latOffset = Math.sin(angle) * radius
  const lngOffset = Math.cos(angle) * radius

  return [basePoint[0] + latOffset, basePoint[1] + lngOffset]
}

// 2. 初始化地图底图
const initMap = () => {
  if (map || !mapContainerRef.value) return

  // 使用高德/OpenStreetMap 免 Key 高清瓦片底图
  map = L.map(mapContainerRef.value, {
    zoomControl: true,
    attributionControl: false
  }).setView([30.5728, 104.0668], 13)

  L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
    maxZoom: 18
  }).addTo(map)

  markerLayerGroup = L.layerGroup().addTo(map)
}

// 3. 核心：重绘当天的景点标记点与轨迹连线
const renderMapElements = () => {
  if (!map || !markerLayerGroup) return

  markerLayerGroup.clearLayers()
  if (polylineLayer) {
    map.removeLayer(polylineLayer)
    polylineLayer = null
  }

  if (!props.items || props.items.length === 0) return

  const latLngs = []
  const markers = []

  props.items.forEach((item, index) => {
    const latLng = resolveCoordinates(item, index, props.items.length)
    latLngs.push(latLng)

    // 创建带序号的高颜值自绘 HTML 图标
    const isFood = item.itemType === 'FOOD'
    const markerHtml = `
      <div class="custom-pin ${isFood ? 'food-pin' : 'scenic-pin'} ${props.activeItemId === item.id ? 'active-pin' : ''}">
        <span class="pin-num">${index + 1}</span>
      </div>
    `
    const customIcon = L.divIcon({
      className: 'custom-leaflet-icon',
      html: markerHtml,
      iconSize: [32, 32],
      iconAnchor: [16, 32],
      popupAnchor: [0, -32]
    })

    const marker = L.marker(latLng, { icon: customIcon })
      .bindPopup(`
        <div class="map-popup-content">
          <strong style="color: #1e3c72; font-size: 14px;">#${index + 1} ${item.itemName}</strong>
          <p style="margin: 4px 0 0 0; color: #64748b; font-size: 12px;">⏰ ${item.startTime || '时间待定'} | 预估 ￥${item.costEstimate || 0}</p>
        </div>
      `)
      .on('click', () => {
        emit('select-item', item.id)
      })

    marker.itemId = item.id
    markerLayerGroup.addLayer(marker)
    markers.push(marker)
  })

  // 绘制游览顺序轨迹虚线
  if (latLngs.length > 1) {
    polylineLayer = L.polyline(latLngs, {
      color: '#2563eb',
      weight: 4,
      dashArray: '8, 8',
      opacity: 0.8
    }).addTo(map)
  }

  // 视野自适应居中包揽所有景点标记
  if (markers.length > 0) {
    const group = L.featureGroup(markers)
    map.fitBounds(group.getBounds().pad(0.2))
  }
}

// 4. 聚焦平移到指定活动卡片
const focusMarker = (itemId) => {
  if (!map || !markerLayerGroup || !itemId) return
  
  const targetIndex = props.items.findIndex(i => i.id === itemId)
  if (targetIndex !== -1) {
    const item = props.items[targetIndex]
    const latLng = resolveCoordinates(item, targetIndex, props.items.length)
    map.flyTo(latLng, 14, { duration: 0.8 })

    // 打开对应的 Popup 弹窗
    markerLayerGroup.eachLayer(layer => {
      if (layer.itemId === itemId) {
        layer.openPopup()
      }
    })
  }
}

// 监听数据变化刷新地图
watch(() => props.items, () => {
  nextTick(() => {
    renderMapElements()
  })
}, { deep: true })

watch(() => props.activeItemId, (newId) => {
  focusMarker(newId)
  // 重新渲染高亮状态
  renderMapElements()
})

onMounted(() => {
  nextTick(() => {
    initMap()
    renderMapElements()
  })
})

onBeforeUnmount(() => {
  if (map) {
    map.remove()
    map = null
  }
})
</script>

<style scoped>
.map-wrapper {
  background: white;
  border-radius: 12px;
  border: 1px solid #e2e8f0;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.05);
  display: flex;
  flex-direction: column;
  height: calc(100vh - 120px);
  position: sticky;
  top: 24px;
  overflow: hidden;
}

.map-header {
  padding: 12px 16px;
  background: #f8fafc;
  border-bottom: 1px solid #e2e8f0;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.map-title {
  font-size: 13px;
  font-weight: bold;
  color: #0f172a;
}

.map-tip {
  font-size: 11px;
  color: #94a3b8;
}

.map-container {
  flex: 1;
  width: 100%;
  height: 100%;
  z-index: 10;
}

/* 自定义 Leaflet 标记点样式 */
:deep(.custom-leaflet-icon) {
  background: transparent;
  border: none;
}

:deep(.custom-pin) {
  width: 28px;
  height: 28px;
  border-radius: 50% 50% 50% 0;
  transform: rotate(-45deg);
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.3);
  transition: transform 0.2s, background 0.2s;
}

:deep(.scenic-pin) {
  background: #2563eb;
}

:deep(.food-pin) {
  background: #ea580c;
}

:deep(.active-pin) {
  transform: rotate(-45deg) scale(1.25);
  background: #dc2626 !important;
  box-shadow: 0 0 12px rgba(220, 38, 38, 0.6);
}

:deep(.pin-num) {
  transform: rotate(45deg);
  color: white;
  font-weight: bold;
  font-size: 12px;
}

:deep(.leaflet-popup-content-wrapper) {
  border-radius: 8px;
  padding: 4px;
}
</style>
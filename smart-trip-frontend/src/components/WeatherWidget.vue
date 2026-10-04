<template>
  <div class="weather-widget-card" :class="widgetThemeClass" v-if="weather">
    <!-- 核心温度与天气行 -->
    <div class="weather-main-row">
      <div class="temp-condition-box">
        <span class="weather-icon-symbol">{{ getWeatherEmoji(weather.weatherIcon) }}</span>
        <div class="temp-text-wrap">
          <span class="temp-range-text">{{ weather.tempRange }}</span>
          <span class="condition-text">{{ weather.condition }}</span>
        </div>
      </div>

      <div class="meta-pills-box">
        <span class="meta-pill" :title="'紫外线强度: ' + weather.uvLevel">
          ☀️ 紫外线: {{ weather.uvLevel }}
        </span>
        <span class="meta-pill" :title="weather.dressingIndex">
          👔 {{ weather.dressingIndex }}
        </span>
      </div>
    </div>

    <!-- 动态预警横幅（仅在非 NORMAL 状态展开） -->
    <div class="weather-alert-banner" v-if="weather.alertLevel !== 'NORMAL'">
      <span class="alert-icon">{{ weather.alertLevel === 'SEVERE' ? '🚨' : '⚠️' }}</span>
      <p class="alert-msg">{{ weather.alertMessage }}</p>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { getWeatherForecastApi } from '@/api/weather'

const props = defineProps({
  destination: { type: String, default: '成都' },
  startDate: { type: String, default: '' }
})

const weather = ref(null)

const fetchWeather = async () => {
  if (!props.destination) return
  try {
    const res = await getWeatherForecastApi(props.destination, props.startDate)
    weather.value = res.data
  } catch (err) {
    console.error('拉取气象预警数据失败:', err)
  }
}

watch(() => [props.destination, props.startDate], () => {
  fetchWeather()
}, { deep: true })

onMounted(() => {
  fetchWeather()
})

const widgetThemeClass = computed(() => {
  if (!weather.value) return 'theme-normal'
  const level = weather.value.alertLevel
  if (level === 'SEVERE') return 'theme-severe'
  if (level === 'WARNING') return 'theme-warning'
  return 'theme-normal'
})

const getWeatherEmoji = (icon) => {
  const map = { SUNNY: '☀️', RAINY: '🌧️', CLOUDY: '⛅', SNOWY: '❄️' }
  return map[icon] || '🌤️'
}
</script>

<style scoped>
.weather-widget-card {
  border-radius: 10px;
  padding: 12px 14px;
  margin-bottom: 16px;
  transition: all 0.2s;
  border: 1px solid;
}

/* 三级威胁预警色彩状态机 */
.theme-normal {
  background: #f8fafc;
  border-color: #e2e8f0;
}
.theme-warning {
  background: #fffbeb;
  border-color: #fde68a;
}
.theme-severe {
  background: #fef2f2;
  border-color: #fecaca;
}

.weather-main-row {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.temp-condition-box {
  display: flex;
  align-items: center;
  gap: 10px;
}

.weather-icon-symbol {
  font-size: 26px;
}

.temp-text-wrap {
  display: flex;
  flex-direction: column;
}

.temp-range-text {
  font-size: 14px;
  font-weight: bold;
  color: #0f172a;
}

.condition-text {
  font-size: 11px;
  color: #64748b;
}

.meta-pills-box {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.meta-pill {
  font-size: 11px;
  color: #475569;
  background: rgba(255, 255, 255, 0.75);
  padding: 2px 6px;
  border-radius: 4px;
  border: 1px solid rgba(0, 0, 0, 0.05);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.weather-alert-banner {
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px dashed rgba(0, 0, 0, 0.08);
  display: flex;
  align-items: flex-start;
  gap: 6px;
}

.alert-icon {
  font-size: 12px;
}

.alert-msg {
  margin: 0;
  font-size: 11px;
  line-height: 1.4;
  font-weight: 500;
}

.theme-warning .alert-msg {
  color: #b45309;
}

.theme-severe .alert-msg {
  color: #dc2626;
}
</style>
<script setup lang="ts">
import { ref, watch, onBeforeUnmount, nextTick } from "vue";
import { useSession } from "./session";
import type { Trip, Day, Weather } from "./types";
import { loadAmapSdk, type MapInstance } from "./amap-sdk";
import { useGeoQueries } from "./useGeoQueries";
import { capabilityEnabled, capabilityNotice } from "./capabilities";
const props = defineProps<{ trip: Trip; day: Day; dirty: boolean }>(),
  emit = defineEmits<{ adjust: [] }>();
const s = useSession(),
  mode = ref("walking"),
  mapHost = ref<HTMLElement>(),
  mapError = ref("");
const {
  route,
  weather,
  routeBusy,
  weatherBusy,
  routeError,
  weatherError,
  epoch,
  refresh,
  getWeather,
} = useGeoQueries(() => ({ ...props, mode: mode.value }));
let map: MapInstance | undefined;
async function draw(requestEpoch: number) {
  if (!s.capabilities.map || !route.value) return;
  const A = await loadAmapSdk();
  await nextTick();
  if (!mapHost.value || requestEpoch !== epoch.value || !route.value) return;
  map?.destroy();
  map = new A.Map(mapHost.value, { zoom: 12 });
  const overlays = [];
  for (const p of route.value.points) {
    if (p.resolved && p.location) {
      const pos = p.location.split(",").map(Number);
      const marker = new A.Marker({ position: pos, title: p.name });
      map.add(marker);
      overlays.push(marker);
    }
  }
  for (const leg of route.value.legs) {
    if (leg.available)
      for (const line of leg.polylines || []) {
        const poly = new A.Polyline({
          path: line.split(";").map((p) => p.split(",").map(Number)),
          strokeColor: "#28684f",
          strokeWeight: 5,
        });
        map.add(poly);
        overlays.push(poly);
      }
  }
  if (overlays.length) map.setFitView(overlays);
}

watch(
  epoch,
  () => {
    map?.destroy();
    map = undefined;
    mapError.value = "";
  },
  { flush: "sync" },
);
watch(route, () => {
  const current = epoch.value;
  mapError.value = "";
  void draw(current).catch((e) => {
    if (current === epoch.value)
      mapError.value = e instanceof Error ? e.message : "地图绘制失败，请重试";
  });
});
function redraw() {
  const current = epoch.value;
  mapError.value = "";
  void draw(current).catch((e) => {
    if (current === epoch.value)
      mapError.value = e instanceof Error ? e.message : "地图加载失败，请重试";
  });
}
onBeforeUnmount(() => {
  map?.destroy();
  map = undefined;
});
</script>
<template>
  <div class="panel-stack">
    <div
      v-if="
        !capabilityEnabled(s.capabilities, 'route') &&
        !capabilityEnabled(s.capabilities, 'weather')
      "
      class="notice"
    >
      路线与天气查询尚未启用。可以继续手动安排路书，按需查询真实路线与日预报。
    </div>
    <section class="paper editor-panel">
      <div class="row">
        <h2>当日路线</h2>
        <div class="button-row">
          <select
            v-model="mode"
            aria-label="交通方式"
            style="width: auto; margin: 0"
          >
            <option value="walking">步行</option>
            <option value="driving">驾车</option></select
          ><button
            class="button small"
            :disabled="
              routeBusy || !capabilityEnabled(s.capabilities, 'route') || dirty
            "
            @click="refresh(false)"
          >
            查询路线</button
          ><button
            v-if="route"
            class="text-button"
            :disabled="
              routeBusy || dirty || !capabilityEnabled(s.capabilities, 'route')
            "
            @click="refresh(true)"
          >
            重新查询
          </button>
        </div>
      </div>
      <p v-if="!capabilityEnabled(s.capabilities, 'route')" class="notice">
        {{ capabilityNotice(s.capabilities, "route") }}
      </p>
      <p class="muted">
        未关联地点的节点会标注待确认。修改地点或顺序后，保存并重新查询。
      </p>
      <div
        v-if="s.capabilities.map && route"
        ref="mapHost"
        class="map-container"
      ></div>
      <div v-else class="map-placeholder">
        <p>
          {{
            s.capabilities.map
              ? "查询真实路线后将显示地图。"
              : capabilityNotice(s.capabilities, "map")
          }}路线数据可用时仍会显示距离与预计耗时。
        </p>
      </div>
      <template v-if="route"
        ><p class="muted">
          数据来源：高德 · {{ mode === "walking" ? "步行" : "驾车" }} · 路书版本
          {{ route.tripVersion }} · 查询时间 {{ route.retrievedAt }}
        </p>
        <div v-for="p in route.points" :key="p.itemId" class="packing-row">
          <span class="pill">{{ p.resolved ? "已确认" : "待确认" }}</span
          ><span>{{ p.name }}</span>
        </div>
        <div
          v-for="l in route.legs"
          :key="l.from + '-' + l.to"
          class="packing-row"
        >
          <span v-if="l.available"
            >约 {{ (Number(l.distanceMeters) / 1000).toFixed(1) }} km ·
            {{ Math.ceil(Number(l.durationSeconds) / 60) }} 分钟</span
          ><span v-else class="muted">{{ l.reason }}</span>
        </div></template
      >
      <p v-if="routeError" class="form-error" role="alert">{{ routeError }}</p>
      <p v-if="mapError" class="form-error" role="alert">
        {{ mapError }}
        <button class="text-button" @click="redraw">重试地图加载</button>
      </p>
    </section>
    <section class="paper editor-panel">
      <div class="row">
        <h2>出行天气</h2>
        <button
          class="button secondary small"
          :disabled="
            weatherBusy ||
            !capabilityEnabled(s.capabilities, 'weather') ||
            dirty
          "
          @click="getWeather"
        >
          查看 {{ day.dayDate || "当日" }} 天气
        </button>
      </div>
      <p v-if="!capabilityEnabled(s.capabilities, 'weather')" class="notice">
        {{ capabilityNotice(s.capabilities, "weather") }}
      </p>
      <p class="muted">
        只展示数据源实际提供的近期预报；较远的出行日期会提示暂无预报。
      </p>
      <div v-if="weather?.available" class="weather-card">
        <strong class="weather-temp"
          >{{ weather.low }}–{{ weather.high }}°</strong
        >
        <div>
          <h3>{{ weather.city }} · {{ weather.dayWeather }}</h3>
          <small
            >{{ weather.date }} · 高德 · 发布时间 {{ weather.issuedAt }}</small
          >
        </div>
      </div>
      <p v-else-if="weather" class="notice">{{ weather.reason }}</p>
      <button
        v-if="weather?.available"
        class="button secondary small"
        :disabled="dirty"
        @click="emit('adjust')"
      >
        查看天气与行程诊断
      </button>
      <p v-if="weatherError" class="form-error" role="alert">
        {{ weatherError }}
      </p>
    </section>
  </div>
</template>

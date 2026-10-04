<script setup lang="ts">
import { ref, watch, onBeforeUnmount, nextTick } from "vue";
import { api } from "./api";
import { useAction } from "./ui";
import { useSession } from "./session";
import type { Trip, Day, RouteData, Weather } from "./types";
const props = defineProps<{ trip: Trip; day: Day; dirty: boolean }>(),
  emit = defineEmits<{ adjust: [weather: Weather] }>(),
  s = useSession(),
  mode = ref("walking"),
  route = ref<RouteData>(),
  weather = ref<Weather>(),
  mapHost = ref<HTMLElement>(),
  { busy, error, run } = useAction();
interface MapInstance {
  destroy(): void;
  add(overlay: object): void;
  setFitView(overlays: object[]): void;
}
interface AMapSDK {
  Map: new (host: HTMLElement, options: { zoom: number }) => MapInstance;
  Marker: new (options: { position: number[]; title: string }) => object;
  Polyline: new (options: {
    path: number[][];
    strokeColor: string;
    strokeWeight: number;
  }) => object;
}
let map: MapInstance | undefined;
let epoch = 0;
const win = window as unknown as {
  AMap?: AMapSDK;
  _AMapSecurityConfig: unknown;
};
async function sdk() {
  if (win.AMap) return win.AMap;
  const config = await api<{ key: string; securityJsCode: string }>(
    "/geo/config",
  );
  win._AMapSecurityConfig = { securityJsCode: config.securityJsCode };
  await new Promise<void>((resolve, reject) => {
    const script = document.createElement("script");
    script.src =
      "https://webapi.amap.com/maps?v=2.0&key=" +
      encodeURIComponent(config.key);
    script.onload = () => resolve();
    script.onerror = () => reject(new Error("地图底图加载失败"));
    document.head.appendChild(script);
  });
  if (!win.AMap) throw new Error("地图底图初始化失败");
  return win.AMap;
}
async function draw(requestEpoch: number) {
  if (!s.capabilities.map || !route.value) return;
  const A = await sdk();
  await nextTick();
  if (!mapHost.value || requestEpoch !== epoch || !route.value) return;
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
function refresh() {
  run(async () => {
    if (props.dirty) throw new Error("请先保存路书，再查询最新路线");
    if (!props.day.id) return;
    const requestEpoch = epoch;
    const version = props.trip.version;
    const data = await api<RouteData>(
      "/geo/trips/" +
        props.trip.id +
        "/days/" +
        props.day.id +
        "/route?mode=" +
        mode.value,
    );
    if (requestEpoch !== epoch || props.dirty) return;
    if (data.tripVersion !== version)
      throw new Error("路书已变更，请刷新后重新查询路线");
    route.value = data;
    await draw(requestEpoch);
  });
}
function getWeather() {
  run(async () => {
    if (!props.day.dayDate) throw new Error("请先设置出发日期并保存路书");
    const requestEpoch = epoch;
    const data = await api<Weather>(
      "/geo/weather?city=" +
        encodeURIComponent(props.trip.destination) +
        "&date=" +
        props.day.dayDate,
    );
    if (requestEpoch === epoch && !props.dirty) weather.value = data;
  });
}
watch(
  () => [props.trip.version, props.day.id, props.dirty, mode.value],
  () => {
    epoch++;
    route.value = undefined;
    weather.value = undefined;
    map?.destroy();
    map = undefined;
  },
);
onBeforeUnmount(() => {
  epoch++;
  map?.destroy();
});
</script>
<template>
  <div class="panel-stack">
    <div v-if="!s.capabilities.geo" class="notice">
      地图与天气服务暂未启用。服务可用后，将按真实地点查询步行或驾车路线及近期预报。
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
            :disabled="busy || !s.capabilities.geo || dirty"
            @click="refresh"
          >
            查询路线
          </button>
        </div>
      </div>
      <p class="muted">
        未关联地点的节点会标注待确认。修改地点或顺序后，保存并重新查询。
      </p>
      <div v-if="s.capabilities.map" ref="mapHost" class="map-container"></div>
      <div v-else class="map-placeholder">
        <p>地图暂不可用。可以先在路书中安排地点与顺序。</p>
      </div>
      <template v-if="route"
        ><p class="muted">
          数据来源：高德 · {{ mode === "walking" ? "步行" : "驾车" }} · 路书版本
          {{ route.tripVersion }}
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
    </section>
    <section class="paper editor-panel">
      <div class="row">
        <h2>出行天气</h2>
        <button
          class="button secondary small"
          :disabled="busy || !s.capabilities.geo || dirty"
          @click="getWeather"
        >
          查看 {{ day.dayDate || "当日" }} 天气
        </button>
      </div>
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
        v-if="weather?.available && s.capabilities.ai"
        class="button secondary small"
        :disabled="dirty"
        @click="emit('adjust', weather)"
      >
        依据天气预览行程调整
      </button>
    </section>
    <p v-if="error" class="form-error">{{ error }}</p>
  </div>
</template>

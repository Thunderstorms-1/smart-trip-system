<script setup lang="ts">
import { computed, ref, watch, nextTick, onBeforeUnmount } from "vue";
import type { Trip, Day, Item } from "../types";
import { useSession } from "../session";
import { capabilityEnabled, capabilityNotice } from "../capabilities";
import { useGeoQueries } from "../useGeoQueries";
import { loadAmapSdk, type MapInstance } from "../amap-sdk";
import {
  canvasPoints,
  routePaths,
  measured,
  usableWeather,
} from "./canvas-data";
const p = defineProps<{
  trip: Trip;
  day: Day;
  dirty: boolean;
  active: boolean;
  controlsOpen: boolean;
  inlineMap?: boolean;
  selectedNode: string;
  nodeKey: (item: Item) => string;
}>();
const emit = defineEmits<{ select: [item: Item]; close: []; adjust: [] }>();
const s = useSession(),
  mode = ref("walking"),
  host = ref<HTMLElement>(),
  controls = ref<HTMLElement>();
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
  cancel,
} = useGeoQueries(() => ({
  trip: p.trip,
  day: p.day,
  dirty: p.dirty,
  mode: mode.value,
}));
const enabled = (feature: "map" | "route" | "weather" | "poi") =>
  capabilityEnabled(s.capabilities, feature);
const requested = ref(false),
  mapReady = ref(false),
  mapBusy = ref(false),
  mapError = ref("");
const points = computed(() =>
  p.dirty ? [] : canvasPoints(p.day, route.value),
);
const forecast = computed(() => usableWeather(weather.value, p.day.dayDate));
let map: MapInstance | undefined,
  generation = 0,
  disposed = false;
let overlays: object[] = [];
const buttons = new Map<string, HTMLButtonElement>();
function clearMap() {
  generation++;
  map?.destroy();
  map = undefined;
  overlays = [];
  buttons.clear();
  mapReady.value = false;
  mapBusy.value = false;
  mapError.value = "";
  requested.value = false;
}
function focusSelection() {
  for (const [key, button] of buttons)
    button.setAttribute("aria-pressed", String(key === p.selectedNode));
  const point = points.value.find((v) => p.nodeKey(v.item) === p.selectedNode);
  if (point)
    map?.setCenter?.(
      point.position,
      window.matchMedia("(prefers-reduced-motion: reduce)").matches,
    );
}
async function draw() {
  if (
    !requested.value ||
    !enabled("map") ||
    !p.active ||
    p.dirty ||
    !points.value.length
  )
    return;
  const current = ++generation,
    requestEpoch = epoch.value;
  mapBusy.value = true;
  mapError.value = "";
  try {
    const A = await loadAmapSdk();
    await nextTick();
    if (
      disposed ||
      current !== generation ||
      requestEpoch !== epoch.value ||
      !host.value ||
      !p.active ||
      p.dirty ||
      !enabled("map")
    )
      return;
    map?.destroy();
    buttons.clear();
    overlays = [];
    map = new A.Map(host.value, {
      zoom: 12,
      center: points.value[0]!.position,
    });
    for (const point of points.value) {
      const key = p.nodeKey(point.item),
        button = document.createElement("button");
      button.className = "wb-map-pin";
      button.type = "button";
      button.textContent = String(point.index + 1);
      button.setAttribute("aria-label", "地图安排：" + point.item.itemName);
      button.addEventListener("click", () => emit("select", point.item));
      buttons.set(key, button);
      const marker = new A.Marker({
        position: point.position,
        title: point.item.itemName,
        content: button,
      });
      map.add(marker);
      overlays.push(marker);
    }
    const known = new Set(points.value.map((v) => v.item.id!));
    for (const leg of route.value?.legs || [])
      for (const path of routePaths(leg, known)) {
        const line = new A.Polyline({
          path,
          strokeColor: "#c95323",
          strokeWeight: 5,
        });
        map.add(line);
        overlays.push(line);
      }
    if (overlays.length) map.setFitView(overlays);
    mapReady.value = true;
    focusSelection();
  } catch (error) {
    if (!disposed && current === generation && requestEpoch === epoch.value)
      mapError.value =
        error instanceof Error ? error.message : "地图加载失败，请重试";
  } finally {
    if (current === generation) mapBusy.value = false;
  }
}
function loadMap() {
  if (!enabled("map") || p.dirty || !p.active) return;
  requested.value = true;
  void draw();
}
function queryRoute(force = false) {
  if (!enabled("route") || p.dirty || !p.active) return;
  return refresh(force);
}
function queryWeather() {
  if (!enabled("weather") || p.dirty || !p.active) return;
  return getWeather();
}
watch(epoch, clearMap, { flush: "sync" });
watch(route, (value) => {
  if (value && enabled("map") && p.active && !p.dirty && points.value.length) {
    requested.value = true;
    void draw();
  }
});
watch(
  () => [p.active, enabled("map"), enabled("route"), enabled("weather")],
  () => {
    cancel();
    clearMap();
  },
  { flush: "sync" },
);
watch(() => p.selectedNode, focusSelection);
watch(
  () => p.inlineMap,
  async () => {
    await nextTick();
    if (!disposed && map && overlays.length) map.setFitView(overlays);
  },
);
watch(
  () => p.controlsOpen,
  async (open) => {
    if (open) {
      await nextTick();
      controls.value?.focus({ preventScroll: true });
    }
  },
);
watch([routeBusy, weatherBusy], async ([routePending, weatherPending]) => {
  if (routePending || weatherPending || !p.controlsOpen) return;
  await nextTick();
  if (controls.value && !controls.value.contains(document.activeElement))
    controls.value.focus({ preventScroll: true });
});
onBeforeUnmount(() => {
  disposed = true;
  clearMap();
});
</script>
<template>
  <div class="wb-geo-canvas">
    <div id="workbench-scene-map-host"></div>

    <div
      v-if="!mapReady"
      class="wb-map-fallback"
      role="region"
      aria-label="当天旅程概要"
    >
      <div class="wb-canvas-title">
        <span class="wb-scene-kicker">当前目的地</span>
        <strong>{{ trip.destination || "你的目的地" }}</strong>
        <p class="wb-scene-theme">{{ day.dayTheme || "自由探索" }}</p>
        <span class="wb-scene-kicker"
          >{{ day.items.length }} 个安排<span v-if="day.dayDate">
            · {{ day.dayDate }}</span
          ></span
        >
        <p v-if="day.daySummary || trip.summary" class="wb-scene-summary">
          {{ day.daySummary || trip.summary }}
        </p>
      </div>
      <p class="wb-canvas-notice">
        旅行场景 · 非地图定位<br />真实地图、路线与天气按需查询
      </p>
    </div>
    <div
      v-if="forecast"
      class="wb-canvas-weather"
      aria-label="已查询的天气预报"
    >
      <strong class="weather-temp"
        >{{ weather!.low }}–{{ weather!.high }}°</strong
      >
      <span
        >{{ weather!.city }} · {{ weather!.dayWeather
        }}<small
          >{{ weather!.date }} · 高德 · 发布时间
          {{ weather!.issuedAt || "未提供" }}</small
        ></span
      >
    </div>
    <div v-if="mapReady" class="wb-map-navigation" aria-label="地图操作">
      <button
        aria-label="放大地图"
        @click="map?.setZoom?.((map?.getZoom?.() || 12) + 1)"
      >
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path d="M12 5v14M5 12h14" />
        </svg>
      </button>
      <button
        aria-label="缩小地图"
        @click="map?.setZoom?.((map?.getZoom?.() || 12) - 1)"
      >
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 12h14" /></svg>
      </button>
      <button
        aria-label="查看全部已确认地点"
        :disabled="!overlays.length"
        @click="map?.setFitView(overlays)"
      >
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path d="M4 9V4h5M15 4h5v5M20 15v5h-5M9 20H4v-5" />
        </svg>
      </button>
    </div>
    <p v-if="mapReady" class="wb-map-source">
      高德底图 · {{ points.length }} 个已确认地点{{
        route ? " · 路线查询时间 " + route.retrievedAt : " · 尚未查询路线"
      }}
    </p>
    <Teleport defer to="#workbench-geo-host">
      <Transition name="motion-drawer"
        ><section
          v-show="controlsOpen"
          ref="controls"
          class="wb-geo-controls"
          tabindex="-1"
          aria-label="路线与天气查询面板"
          @keydown.esc.stop.prevent="emit('close')"
        >
          <header>
            <h2>路线与天气</h2>
            <button class="wb-text-button" @click="emit('close')">
              返回行程编辑
            </button>
          </header>
          <div v-show="inlineMap && requested" class="wb-inline-map-preview">
            <div id="workbench-inline-map-host"></div>
          </div>
          <p v-if="dirty" class="notice">
            请先保存当前修改，再查询最新地图、路线和天气。
          </p>
          <p v-if="!enabled('route') && !enabled('weather')" class="notice">
            路线与天气查询尚未启用。可以继续手动安排路书。
          </p>
          <div class="wb-geo-actions">
            <button
              :disabled="!enabled('map') || dirty || mapBusy || !points.length"
              @click="loadMap"
            >
              {{ mapBusy ? "加载地图中…" : mapReady ? "重载地图" : "加载地图" }}
            </button>
            <p v-if="!enabled('map')" class="muted">
              {{ capabilityNotice(s.capabilities, "map") }}
            </p>
            <p v-else class="muted">
              只标记已确认的地点；名称不会自动生成坐标。
            </p>
          </div>
          <p v-if="enabled('map') && !points.length" class="muted">
            当前日期没有已确认坐标。请关联地点并保存，或显式查询路线。
          </p>
          <div class="wb-geo-actions">
            <h3>当日路线</h3>
            <div class="button-row">
              <select v-model="mode" aria-label="交通方式">
                <option value="walking">步行</option>
                <option value="driving">驾车</option>
              </select>
              <button
                :disabled="routeBusy || !enabled('route') || dirty"
                @click="queryRoute()"
              >
                {{ routeBusy ? "查询路线中…" : "查询路线" }}
              </button>
              <button
                v-if="route"
                :disabled="routeBusy || !enabled('route') || dirty"
                @click="queryRoute(true)"
              >
                重新查询
              </button>
            </div>
            <p v-if="!enabled('route')" class="muted">
              {{ capabilityNotice(s.capabilities, "route") }}
            </p>
            <p class="muted">
              修改地点或顺序后，保存并重新查询。未确认地点不绘制标记或连线。
            </p>
            <template v-if="route">
              <p class="muted">
                数据来源：高德 ·
                {{ route.mode === "walking" ? "步行" : "驾车" }} · 路书版本
                {{ route.tripVersion }} · 查询时间 {{ route.retrievedAt }}
              </p>
              <p v-for="point in route.points" :key="point.itemId">
                {{ point.name }} · {{ point.resolved ? "已确认" : "待确认" }}
              </p>
              <p v-for="leg in route.legs" :key="leg.from + '-' + leg.to">
                <template v-if="leg.available"
                  >{{
                    measured(leg.distanceMeters) == null
                      ? "距离未提供"
                      : "约 " +
                        (measured(leg.distanceMeters)! / 1000).toFixed(1) +
                        " km"
                  }}
                  ·
                  {{
                    measured(leg.durationSeconds) == null
                      ? "耗时未提供"
                      : Math.ceil(measured(leg.durationSeconds)! / 60) + " 分钟"
                  }}</template
                >
                <template v-else>{{ leg.reason || "此段路线不可用" }}</template>
              </p>
            </template>
            <p v-if="routeError" role="alert" class="form-error">
              {{ routeError }}
            </p>
          </div>
          <button
            v-if="routeBusy || weatherBusy"
            class="wb-text-button"
            @click="cancel"
          >
            取消查询
          </button>
          <div class="wb-geo-actions">
            <h3>出行天气</h3>
            <button
              :disabled="weatherBusy || !enabled('weather') || dirty"
              @click="queryWeather"
            >
              {{
                weatherBusy
                  ? "查询天气中…"
                  : "查看 " + (day.dayDate || "当日") + " 天气"
              }}
            </button>
            <p v-if="!enabled('weather')" class="muted">
              {{ capabilityNotice(s.capabilities, "weather") }}
            </p>
            <p class="muted">
              仅显示数据源提供的对应日期预报，较远日期不会推测天气。
            </p>
            <p v-if="forecast">
              {{ weather!.city }} · {{ weather!.dayWeather }} ·
              {{ weather!.low }}–{{ weather!.high }}° · {{ weather!.date }}
            </p>
            <p v-else-if="weather" class="notice">
              {{ weather.reason || "对应日期的完整天气预报暂不可用" }}
            </p>
            <button v-if="forecast" :disabled="dirty" @click="emit('adjust')">
              查看天气与行程诊断
            </button>
            <p v-if="weatherError" role="alert" class="form-error">
              {{ weatherError }}
            </p>
          </div>
          <p class="muted">
            {{
              enabled("poi")
                ? "在安排 Inspector 中关联真实地点，再保存路书。"
                : capabilityNotice(s.capabilities, "poi")
            }}
          </p>
          <p v-if="mapError" class="form-error" role="alert">
            {{ mapError }} <button @click="loadMap">重试地图加载</button>
          </p>
        </section></Transition
      >
    </Teleport>
    <Teleport
      defer
      :to="
        inlineMap ? '#workbench-inline-map-host' : '#workbench-scene-map-host'
      "
    >
      <div
        v-show="requested"
        ref="host"
        class="wb-real-map"
        aria-label="高德地图画布"
      ></div>
    </Teleport>
  </div>
</template>

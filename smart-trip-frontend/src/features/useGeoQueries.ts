import { ref, watch, onScopeDispose } from "vue";
import { api } from "./api";
import type { Trip, Day, RouteData, Weather } from "./types";
export function useGeoQueries(
  get: () => { trip: Trip; day: Day; dirty: boolean; mode: string },
) {
  const route = ref<RouteData>(),
    weather = ref<Weather>(),
    epoch = ref(0);
  const routeBusy = ref(false),
    weatherBusy = ref(false),
    routeError = ref(""),
    weatherError = ref("");
  let routeKey = "";
  const channels = {
    route: {
      controller: undefined as AbortController | undefined,
      timer: undefined as ReturnType<typeof setTimeout> | undefined,
    },
    weather: {
      controller: undefined as AbortController | undefined,
      timer: undefined as ReturnType<typeof setTimeout> | undefined,
    },
  };
  function invalidate() {
    epoch.value++;
    for (const channel of Object.values(channels)) {
      channel.controller?.abort();
      clearTimeout(channel.timer);
      channel.controller = undefined;
    }
    route.value = undefined;
    weather.value = undefined;
    routeKey = "";
    routeBusy.value = false;
    weatherBusy.value = false;
    routeError.value = "";
    weatherError.value = "";
  }
  watch(
    () => {
      const v = get();
      return [
        v.trip.id,
        v.trip.version,
        v.trip.destination,
        v.trip.startDate,
        v.day.id,
        v.day.dayDate,
        v.dirty,
        v.mode,
      ];
    },
    invalidate,
    { flush: "sync" },
  );
  onScopeDispose(invalidate);
  async function query<T>(
    channelName: "route" | "weather",
    path: string,
    apply: (value: T) => void,
  ) {
    const busy = channelName === "route" ? routeBusy : weatherBusy;
    const error = channelName === "route" ? routeError : weatherError;
    if (busy.value) return;
    const current = epoch.value,
      channel = channels[channelName],
      controller = new AbortController();
    channel.controller = controller;
    busy.value = true;
    error.value = "";
    channel.timer = setTimeout(() => {
      if (current !== epoch.value || channel.controller !== controller) return;
      error.value = "查询超时，请重试";
      busy.value = false;
      controller.abort();
    }, 35000);
    const timer = channel.timer;
    try {
      const value = await api<T>(path, "GET", undefined, controller.signal);
      if (
        current === epoch.value &&
        !controller.signal.aborted &&
        channel.controller === controller
      )
        apply(value);
    } catch (e) {
      if (
        current === epoch.value &&
        !controller.signal.aborted &&
        channel.controller === controller
      )
        error.value = e instanceof Error ? e.message : "查询失败，请重试";
    } finally {
      clearTimeout(timer);
      if (current === epoch.value && channel.controller === controller) {
        busy.value = false;
        channel.controller = undefined;
      }
    }
  }
  function refresh(force = false) {
    const v = get();
    if (v.dirty) {
      routeError.value = "请先保存路书，再查询最新路线";
      return Promise.resolve();
    }
    if (!v.day.id) {
      routeError.value = "请先保存当日行程";
      return Promise.resolve();
    }
    const key = JSON.stringify([v.trip.id, v.trip.version, v.day.id, v.mode]);
    if (!force && route.value && routeKey === key) return Promise.resolve();
    const version = v.trip.version;
    return query<RouteData>(
      "route",
      "/geo/trips/" + v.trip.id + "/days/" + v.day.id + "/route?mode=" + v.mode,
      (data) => {
        if (data.tripVersion !== version) {
          routeError.value = "路书已变更，请刷新后重新查询路线";
          return;
        }
        route.value = data;
        routeKey = key;
      },
    );
  }
  function getWeather() {
    const v = get();
    if (v.dirty) {
      weatherError.value = "请先保存路书，再查询天气";
      return Promise.resolve();
    }
    if (!v.day.dayDate) {
      weatherError.value = "请先设置出发日期并保存路书";
      return Promise.resolve();
    }
    return query<Weather>(
      "weather",
      "/geo/weather?city=" +
        encodeURIComponent(v.trip.destination) +
        "&date=" +
        v.day.dayDate,
      (data) => {
        weather.value = data;
      },
    );
  }
  return {
    route,
    weather,
    routeBusy,
    weatherBusy,
    routeError,
    weatherError,
    epoch,
    refresh,
    getWeather,
    cancel: invalidate,
  };
}

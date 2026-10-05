import { ref, watch, onScopeDispose } from "vue";
import { api } from "./api";
import type { Item, Poi } from "./types";
export function usePoiSearch(
  get: () => {
    tripId?: number;
    version?: number;
    destination: string;
    item?: Item;
    open: boolean;
  },
  keyword: () => string,
) {
  const results = ref<Poi[]>([]),
    busy = ref(false),
    error = ref(""),
    searched = ref(false);
  let epoch = 0,
    controller: AbortController | undefined,
    timer: ReturnType<typeof setTimeout> | undefined;
  function reset() {
    epoch++;
    controller?.abort();
    clearTimeout(timer);
    controller = undefined;
    results.value = [];
    busy.value = false;
    error.value = "";
    searched.value = false;
  }
  watch(
    () => {
      const s = get();
      return [s.tripId, s.version, s.destination, s.item, s.open, keyword()];
    },
    reset,
    { flush: "sync" },
  );
  onScopeDispose(reset);
  async function search() {
    const context = get();
    if (!context.open || !context.item || busy.value) return;
    const term = keyword().trim();
    if (!term || !context.destination.trim()) {
      error.value = "请填写城市和地点名称";
      return;
    }
    const current = ++epoch,
      abort = new AbortController();
    controller = abort;
    busy.value = true;
    error.value = "";
    searched.value = true;
    results.value = [];
    const timeout = setTimeout(() => {
      if (current === epoch) {
        error.value = "地点查询超时，请手动重试";
        busy.value = false;
        abort.abort();
      }
    }, 35000);
    timer = timeout;
    try {
      const data = await api<Poi[]>(
        "/geo/search?city=" +
          encodeURIComponent(context.destination) +
          "&keyword=" +
          encodeURIComponent(term),
        "GET",
        undefined,
        abort.signal,
      );
      if (current === epoch && !abort.signal.aborted) results.value = data;
    } catch (e) {
      if (current === epoch && !abort.signal.aborted)
        error.value =
          e instanceof Error ? e.message : "地点查询失败，请手动重试";
    } finally {
      clearTimeout(timeout);
      if (current === epoch && controller === abort) {
        busy.value = false;
        controller = undefined;
      }
    }
  }
  return { results, busy, error, searched, search, reset };
}

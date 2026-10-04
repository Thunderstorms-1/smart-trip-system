import { ref, computed, watch, onScopeDispose } from "vue";
import { api, requestKey } from "./api";
import { followTask } from "./task-stream";
import type { Trip, Task, AnalysisReport } from "./types";

export function useItineraryAnalysis(
  get: () => { trip: Trip; dirty: boolean; dayIndex: number },
  applied: (trip: Trip) => void,
) {
  const dayIds = ref<number[]>([]),
    mode = ref("walking"),
    report = ref<AnalysisReport>(),
    task = ref<Task>(),
    error = ref(""),
    busy = ref(false),
    applying = ref(false),
    sourceBusy = ref<Record<string, boolean>>({}),
    sourceErrors = ref<Record<string, string>>({});
  let epoch = 0,
    submitKey = "",
    pollTimer: ReturnType<typeof setTimeout> | undefined,
    stream: AbortController | undefined;
  const channels = new Map<
    string,
    { controller: AbortController; timer: ReturnType<typeof setTimeout> }
  >();
  const now = ref(Date.now());
  const clock = setInterval(() => {
    now.value = Date.now();
  }, 1000);
  const running = computed(() =>
    Boolean(task.value && ["QUEUED", "RUNNING"].includes(task.value.status)),
  );
  const stale = computed(
    () =>
      task.value?.baseVersion !== undefined &&
      task.value.baseVersion !== get().trip.version,
  );
  const expired = computed(() =>
    Boolean(
      task.value?.proposal?.expiresAt &&
        Date.parse(task.value.proposal.expiresAt) <= now.value,
    ),
  );
  const canApply = computed(
    () =>
      task.value?.status === "PREVIEW" &&
      Boolean(task.value.proposal?.changes.length) &&
      !busy.value &&
      !applying.value &&
      !get().dirty &&
      !stale.value &&
      !expired.value,
  );
  function invalidate() {
    epoch++;
    for (const channel of channels.values()) {
      channel.controller.abort();
      clearTimeout(channel.timer);
    }
    channels.clear();
    stream?.abort();
    clearTimeout(pollTimer);
    report.value = undefined;
    task.value = undefined;
    error.value = "";
    busy.value = false;
    sourceBusy.value = {};
    sourceErrors.value = {};
    submitKey = "";
  }
  watch(
    () => [get().trip.id, get().trip.version, get().dirty, get().dayIndex],
    () => {
      invalidate();
      const id = get().trip.days[get().dayIndex]?.id;
      dayIds.value = id ? [id] : [];
    },
    { immediate: true, flush: "sync" },
  );
  watch(() => JSON.stringify([dayIds.value, mode.value]), invalidate, {
    flush: "sync",
  });
  onScopeDispose(() => {
    invalidate();
    clearInterval(clock);
  });
  async function query<T>(
    channel: string,
    path: string,
    method: string,
    body: unknown,
    accept: (value: T) => void,
    failure: (message: string) => void,
  ): Promise<void> {
    const existing = channels.get(channel);
    existing?.controller.abort();
    if (existing) clearTimeout(existing.timer);
    const current = epoch,
      controller = new AbortController();
    const timer = setTimeout(() => {
      if (
        current === epoch &&
        channels.get(channel)?.controller === controller
      ) {
        failure("查询超时，请重试");
        controller.abort();
      }
    }, 35000);
    channels.set(channel, { controller, timer });
    let abortListener: (() => void) | undefined;
    try {
      const aborted = new Promise<T>((_, reject) => {
        abortListener = () => reject(new DOMException("Aborted", "AbortError"));
        controller.signal.addEventListener("abort", abortListener, {
          once: true,
        });
      });
      const value = await Promise.race([
        api<T>(path, method, body, controller.signal),
        aborted,
      ]);
      if (
        current === epoch &&
        !controller.signal.aborted &&
        channels.get(channel)?.controller === controller
      )
        accept(value);
    } catch (e) {
      if (
        current === epoch &&
        !controller.signal.aborted &&
        channels.get(channel)?.controller === controller
      )
        failure(e instanceof Error ? e.message : "查询失败，请重试");
    } finally {
      clearTimeout(timer);
      if (abortListener)
        controller.signal.removeEventListener("abort", abortListener);
      if (channels.get(channel)?.controller === controller)
        channels.delete(channel);
    }
  }
  const path = () => "/workbench/" + get().trip.id + "/analyses";
  function acceptReport(value: AnalysisReport) {
    if (
      !report.value ||
      value.id !== report.value.id ||
      value.revision >= report.value.revision
    )
      report.value = value;
  }
  async function analyze() {
    if (get().dirty) {
      error.value = "请先保存当前修改，再分析";
      return;
    }
    if (!dayIds.value.length) {
      error.value = "至少选择一天";
      return;
    }
    busy.value = true;
    error.value = "";
    task.value = undefined;
    submitKey = "";
    const current = epoch;
    await query<AnalysisReport>(
      "analysis",
      path(),
      "POST",
      {
        baseVersion: get().trip.version,
        dayIds: dayIds.value,
        mode: mode.value,
      },
      (value) => {
        report.value = value;
      },
      (message) => {
        error.value = message;
      },
    );
    if (current === epoch) busy.value = false;
  }
  async function source(
    dayId: number,
    kind: "WEATHER" | "ROUTE",
    refresh = false,
  ) {
    if (!report.value || get().dirty) return;
    if (Date.parse(report.value.expiresAt) <= now.value) {
      error.value = "分析已过期，请重新检查行程";
      return;
    }
    const key = kind + ":" + dayId;
    const cached = report.value.sources.find((s) => s.id === key);
    if (
      !refresh &&
      cached &&
      ["READY", "PARTIAL", "UNAVAILABLE"].includes(cached.status)
    )
      return;
    if (sourceBusy.value[key]) return;
    sourceBusy.value[key] = true;
    sourceErrors.value[key] = "";
    const current = epoch;
    await query<AnalysisReport>(
      key,
      path() + "/" + report.value.id + "/sources",
      "POST",
      { dayId, kind, refresh },
      (value) => {
        acceptReport(value);
        submitKey = "";
      },
      (message) => {
        sourceErrors.value[key] = message;
      },
    );
    if (current === epoch) sourceBusy.value[key] = false;
  }
  function taskKey() {
    return "SMARTTRIP_ADJUSTMENT_" + get().trip.id;
  }
  function receive(value: Task) {
    task.value = value;
    if (!running.value) {
      stream?.abort();
      clearTimeout(pollTimer);
    }
  }
  function monitor(id: string) {
    const current = epoch;
    stream?.abort();
    stream = new AbortController();
    const signal = stream.signal;
    const poll = async () => {
      if (current !== epoch || signal.aborted) return;
      await query<Task>(
        "task",
        "/ai/tasks/" + id,
        "GET",
        undefined,
        (value) => {
          receive(value);
          if (running.value) pollTimer = setTimeout(poll, 1500);
        },
        (message) => {
          error.value = message;
          pollTimer = setTimeout(poll, 3000);
        },
      );
    };
    void followTask(
      id,
      (value) => {
        if (current === epoch && !signal.aborted) receive(value);
      },
      signal,
    ).catch(() => {
      if (current === epoch && !signal.aborted) void poll();
    });
  }
  async function generate() {
    if (!report.value || get().dirty || running.value || busy.value) return;
    if (Date.parse(report.value.expiresAt) <= now.value) {
      error.value = "分析已过期，请重新检查行程";
      return;
    }
    if (!submitKey || task.value) submitKey = requestKey();
    busy.value = true;
    error.value = "";
    const current = epoch;
    await query<Task>(
      "submit",
      "/ai/tasks",
      "POST",
      {
        requestKey: submitKey,
        type: "ADJUST",
        tripId: get().trip.id,
        analysisId: report.value.id,
      },
      (value) => {
        receive(value);
        localStorage.setItem(taskKey(), value.id);
        if (running.value) monitor(value.id);
      },
      (message) => {
        error.value = message;
      },
    );
    if (current === epoch) busy.value = false;
  }
  async function restore() {
    const id = localStorage.getItem(taskKey());
    if (!id) return;
    await query<Task>(
      "restore",
      "/ai/tasks/" + id,
      "GET",
      undefined,
      (value) => {
        if (value.type !== "ADJUST" || value.tripId !== get().trip.id) return;
        receive(value);
        if (running.value) monitor(value.id);
      },
      (message) => {
        error.value = message;
      },
    );
  }
  async function apply() {
    if (!canApply.value || !task.value) return;
    applying.value = true;
    error.value = "";
    try {
      await query<Trip>(
        "apply",
        "/ai/tasks/" + task.value.id + "/apply",
        "POST",
        {},
        (value) => {
          localStorage.removeItem(taskKey());
          applied(value);
        },
        (message) => {
          error.value = message;
        },
      );
    } finally {
      applying.value = false;
    }
  }
  return {
    dayIds,
    mode,
    report,
    task,
    error,
    busy,
    applying,
    running,
    stale,
    expired,
    canApply,
    sourceBusy,
    sourceErrors,
    analyze,
    source,
    generate,
    restore,
    apply,
  };
}

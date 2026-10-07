import { ref, computed, watch, onScopeDispose } from "vue";
import { api } from "../api";
import type { Trip, AnalysisReport } from "../types";

// Local to the editor: a report belongs to one saved tree, never to a title or day selection.
export function useWorkbenchQuality(
  get: () => { trip?: Trip; dirty: boolean; blocked: boolean },
) {
  const report = ref<AnalysisReport>();
  const busy = ref(false),
    error = ref(""),
    changed = ref(false),
    now = ref(Date.now());
  let epoch = 0,
    controller: AbortController | undefined,
    analyzedTree = "";
  const tree = () => JSON.stringify(get().trip);
  const clock = setInterval(() => {
    now.value = Date.now();
  }, 1000);
  function cancel() {
    epoch++;
    controller?.abort();
    controller = undefined;
    busy.value = false;
  }
  watch(
    tree,
    (_, old) => {
      cancel();
      if (old !== undefined && report.value) changed.value = true;
      report.value = undefined;
      error.value = "";
    },
    { flush: "sync" },
  );
  const valid = computed(() => {
    const t = get().trip,
      r = report.value;
    return Boolean(
      t &&
        r?.quality &&
        !get().dirty &&
        tree() === analyzedTree &&
        r.tripId === t.id &&
        r.baseVersion === t.version &&
        r.quality.baseVersion === t.version &&
        r.quality.scope === "TRIP" &&
        Number.isFinite(Date.parse(r.expiresAt)) &&
        Date.parse(r.expiresAt) > now.value,
    );
  });
  const state = computed(() =>
    busy.value
      ? "正在检查已保存行程"
      : get().dirty
        ? "草稿已变化，请先保存再分析"
        : changed.value
          ? "行程已变化，请重新分析"
          : report.value && !valid.value
            ? "分析已过期或版本不匹配，请重新分析"
            : valid.value
              ? "当前已保存版本的分析"
              : "尚未分析，不显示评分",
  );
  function accept(value: AnalysisReport) {
    const t = get().trip;
    if (
      !t ||
      get().dirty ||
      value.tripId !== t.id ||
      value.baseVersion !== t.version ||
      !value.quality ||
      value.quality.baseVersion !== t.version ||
      value.quality.scope !== "TRIP"
    ) {
      error.value = "未取得当前版本的质量报告，请重新分析";
      return;
    }
    if (report.value?.id === value.id && report.value.revision > value.revision)
      return;
    report.value = value;
    analyzedTree = tree();
    changed.value = false;
    error.value = "";
    now.value = Date.now();
  }
  async function analyze() {
    const t = get().trip;
    if (!t || get().dirty || get().blocked || busy.value) return;
    const ids = t.days
      .map((d) => d.id)
      .filter((id): id is number => id != null);
    if (!ids.length || ids.length !== t.days.length) {
      error.value = "请先保存日程，再分析";
      return;
    }
    cancel();
    const current = epoch,
      snapshot = tree(),
      abort = new AbortController();
    controller = abort;
    busy.value = true;
    error.value = "";
    report.value = undefined;
    const timeout = setTimeout(() => abort.abort(), 35000);
    try {
      const value = await api<AnalysisReport>(
        "/workbench/" + t.id + "/analyses",
        "POST",
        { baseVersion: t.version, dayIds: ids, mode: "walking" },
        abort.signal,
      );
      if (current === epoch && snapshot === tree() && !abort.signal.aborted)
        accept(value);
    } catch (e) {
      if (current === epoch)
        error.value = abort.signal.aborted
          ? "分析超时，请重试"
          : e instanceof Error
            ? e.message
            : "分析失败，请重试";
    } finally {
      clearTimeout(timeout);
      if (current === epoch) {
        busy.value = false;
        controller = undefined;
      }
    }
  }
  onScopeDispose(() => {
    cancel();
    clearInterval(clock);
  });
  return { report, busy, error, valid, state, analyze, accept };
}

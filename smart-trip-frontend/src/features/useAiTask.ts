import { ref, onScopeDispose, type Ref } from "vue";
import { api, requestKey } from "./api";
import { followTask } from "./task-stream";
import { notify, type useAction } from "./ui";
import type { Trip, Task, Weather } from "./types";
export function useAiTask(
  trip: Ref<Trip | undefined>,
  saved: Ref<string>,
  dayIndex: Ref<number>,
  dirty: Ref<boolean>,
  modal: Ref<string>,
  guardSaved: () => Promise<boolean>,
  run: ReturnType<typeof useAction>["run"],
) {
  const prompt = ref(""),
    task = ref<Task>(),
    preview = ref<Trip>();
  let timer: ReturnType<typeof setTimeout>;
  let stream = new AbortController();
  function resetTask() {
    clearTimeout(timer);
    stream.abort();
    stream = new AbortController();
    prompt.value = "";
    task.value = undefined;
    preview.value = undefined;
  }
  onScopeDispose(() => {
    clearTimeout(timer);
    stream.abort();
  });
  function aiAdjust(weather?: Weather) {
    if (dirty.value) {
      notify("请先保存路书再发起AI调整", "error");
      return;
    }
    const pending = localStorage.getItem(
      "SMARTTRIP_PROPOSAL_" + trip.value?.id,
    );
    if (pending && !weather) {
      modal.value = "ai";
      poll(pending);
      return;
    }
    prompt.value = weather
      ? "依据以下天气预报调整第" +
        (dayIndex.value + 1) +
        "天，保留其他天数和已有节点ID：" +
        JSON.stringify(weather)
      : "";
    task.value = undefined;
    preview.value = undefined;
    modal.value = "ai";
  }
  async function poll(id: string) {
    const tripId = trip.value?.id;
    const signal = stream.signal;
    try {
      const result = await api<Task>(
        "/ai/tasks/" + id,
        "GET",
        undefined,
        signal,
      );
      if (signal.aborted || trip.value?.id !== tripId) return;
      task.value = result;
      if (task.value.status === "PREVIEW") {
        preview.value = task.value.result;
      } else if (["QUEUED", "RUNNING"].includes(task.value.status))
        timer = setTimeout(() => poll(id), 1500);
    } catch (e) {
      if (!signal.aborted)
        notify(e instanceof Error ? e.message : "无法查询任务", "error");
    }
  }
  function requestAi() {
    run(async () => {
      if (!(await guardSaved())) return;
      task.value = await api<Task>("/ai/tasks", "POST", {
        requestKey: requestKey(),
        type: "MODIFY",
        tripId: trip.value!.id,
        prompt: prompt.value,
      });
      const id = task.value.id;
      const signal = stream.signal;
      const tripId = trip.value!.id;
      localStorage.setItem("SMARTTRIP_PROPOSAL_" + trip.value!.id, id);
      followTask(
        id,
        (t) => {
          if (signal.aborted || trip.value?.id !== tripId) return;
          task.value = t;
          if (t.status === "PREVIEW") preview.value = t.result;
        },
        signal,
      ).catch(() => {
        if (!signal.aborted) poll(id);
      });
    });
  }
  function applyAi() {
    if (dirty.value) {
      notify("请先保存当前修改", "error");
      return;
    }
    run(async () => {
      trip.value = await api<Trip>(
        "/ai/tasks/" + task.value!.id + "/apply",
        "POST",
        {},
      );
      saved.value = JSON.stringify(trip.value);
      dayIndex.value = 0;
      modal.value = "";
      localStorage.removeItem("SMARTTRIP_PROPOSAL_" + trip.value!.id);
    }, "AI修改已确认保存");
  }

  return { prompt, task, preview, aiAdjust, requestAi, applyAi, resetTask };
}

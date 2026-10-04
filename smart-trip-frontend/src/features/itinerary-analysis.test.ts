import { beforeEach, afterEach, expect, it, vi } from "vitest";
import { effectScope, reactive } from "vue";
import { api } from "./api";
import { followTask } from "./task-stream";
import { useItineraryAnalysis } from "./useItineraryAnalysis";
import type { Trip, Task, AnalysisReport } from "./types";
vi.mock("./api", () => ({
  api: vi.fn(),
  requestKey: () => "request-fixture-123",
}));
vi.mock("./task-stream", () => ({
  followTask: vi.fn(() => new Promise<void>(() => {})),
}));
let scope: ReturnType<typeof effectScope>;
const trip = (id = 1): Trip => ({
  id,
  version: 1,
  title: "行程",
  destination: "成都",
  days: [
    { id: 11, items: [] },
    { id: 12, items: [] },
  ],
});
const report = (revision = 0): AnalysisReport => ({
  id: "analysis-1",
  tripId: 1,
  baseVersion: 1,
  revision,
  dayIds: [11],
  mode: "walking",
  expiresAt: "2026-10-04T11:00:00Z",
  issues: [],
  sources: [],
});
function pending<T>() {
  let resolve!: (v: T) => void, reject!: (e: Error) => void;
  const promise = new Promise<T>((a, b) => {
    resolve = a;
    reject = b;
  });
  return { resolve, reject, promise };
}
function setup() {
  const input = reactive({ trip: trip(), dirty: false, dayIndex: 0 });
  const applied = vi.fn();
  const state = scope.run(() => useItineraryAnalysis(() => input, applied))!;
  return { input, state, applied };
}
function preview(): Task {
  return {
    id: "task-1",
    type: "ADJUST",
    tripId: 1,
    baseVersion: 1,
    status: "PREVIEW",
    result: trip(),
    proposal: {
      dayIds: [11],
      changes: [
        {
          itemId: 101,
          itemName: "公园",
          fromDayId: 11,
          toDayId: 11,
          fromDayIndex: 1,
          toDayIndex: 1,
          fromOrder: 1,
          toOrder: 1,
          fromTime: "09:00",
          toTime: "11:00",
          kinds: ["TIME"],
        },
      ],
      reasons: [],
      remainingIssues: [],
      unverifiedRouteDayIds: [],
      sources: [],
    },
  };
}
beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(new Date("2026-10-04T10:00:00Z"));
  const saved = new Map<string, string>();
  vi.stubGlobal("localStorage", {
    getItem: (k: string) => saved.get(k) || null,
    setItem: (k: string, v: string) => saved.set(k, v),
    removeItem: (k: string) => saved.delete(k),
  });
  vi.mocked(api).mockReset();
  vi.mocked(followTask).mockClear();
  scope = effectScope();
});
afterEach(() => {
  scope.stop();
  vi.useRealTimers();
  vi.unstubAllGlobals();
});
it("defaults to current day and refuses unsaved analysis", async () => {
  const { state, input } = setup();
  expect(state.dayIds.value).toEqual([11]);
  input.dirty = true;
  await state.analyze();
  expect(api).not.toHaveBeenCalled();
  expect(state.error.value).toContain("保存");
});
it("reuses a source until explicitly refreshed", async () => {
  const { state } = setup();
  vi.mocked(api).mockResolvedValueOnce(report());
  await state.analyze();
  const sourced = {
    ...report(1),
    sources: [
      {
        id: "ROUTE:11",
        dayId: 11,
        kind: "ROUTE" as const,
        status: "READY",
        summary: "已测得",
        retrievedAt: "2026-10-04T10:00:00Z",
      },
    ],
  };
  vi.mocked(api).mockResolvedValue(sourced);
  await state.source(11, "ROUTE");
  await state.source(11, "ROUTE");
  expect(api).toHaveBeenCalledTimes(2);
  await state.source(11, "ROUTE", true);
  expect(api).toHaveBeenCalledTimes(3);
  expect(vi.mocked(api).mock.calls[2]?.[2]).toEqual({
    dayId: 11,
    kind: "ROUTE",
    refresh: true,
  });
});
it("older source reports cannot erase a newer independent result", async () => {
  const { state } = setup();
  vi.mocked(api).mockResolvedValueOnce(report());
  await state.analyze();
  const old = pending<AnalysisReport>(),
    fresh = {
      ...report(2),
      sources: [
        {
          id: "WEATHER:11",
          dayId: 11,
          kind: "WEATHER" as const,
          status: "READY",
          summary: "天气",
        },
      ],
    };
  vi.mocked(api).mockReturnValueOnce(old.promise).mockResolvedValueOnce(fresh);
  const first = state.source(11, "ROUTE");
  await state.source(11, "WEATHER");
  old.resolve(report(1));
  await first;
  expect(state.report.value?.revision).toBe(2);
  expect(state.report.value?.sources).toEqual(fresh.sources);
});
it("scope changes abort and discard old successes", async () => {
  const { state } = setup();
  const old = pending<AnalysisReport>();
  vi.mocked(api).mockReturnValueOnce(old.promise);
  const first = state.analyze();
  const signal = vi.mocked(api).mock.calls[0]?.[3];
  state.dayIds.value = [11, 12];
  expect(signal?.aborted).toBe(true);
  vi.mocked(api).mockResolvedValueOnce({
    ...report(),
    id: "new",
    dayIds: [11, 12],
  });
  await state.analyze();
  old.resolve(report());
  await first;
  expect(state.report.value?.id).toBe("new");
});
it("old errors do not contaminate a new trip", async () => {
  const { state, input } = setup();
  const old = pending<AnalysisReport>();
  vi.mocked(api).mockReturnValueOnce(old.promise);
  const first = state.analyze();
  input.trip = trip(2);
  old.reject(new Error("旧错误"));
  await first;
  expect(state.error.value).toBe("");
  expect(state.report.value).toBeUndefined();
});
it("source failure leaves local analysis and other channels usable", async () => {
  const { state } = setup();
  vi.mocked(api).mockResolvedValueOnce(report());
  await state.analyze();
  vi.mocked(api).mockRejectedValueOnce(new Error("天气离线"));
  await state.source(11, "WEATHER");
  expect(state.sourceErrors.value["WEATHER:11"]).toBe("天气离线");
  expect(state.report.value?.id).toBe("analysis-1");
  vi.mocked(api).mockResolvedValueOnce(report(1));
  await state.source(11, "ROUTE");
  expect(state.sourceErrors.value["ROUTE:11"]).toBe("");
});
it("35 second deadline clears busy even if upstream ignores cancellation", async () => {
  const { state } = setup();
  vi.mocked(api).mockReturnValue(new Promise(() => {}));
  const request = state.analyze();
  await vi.advanceTimersByTimeAsync(35000);
  await request;
  expect(state.busy.value).toBe(false);
  expect(state.error.value).toContain("超时");
  expect(vi.mocked(api).mock.calls[0]?.[3]?.aborted).toBe(true);
});
it("unmount cancels in-flight queries and ignores their result", async () => {
  const { state } = setup();
  const old = pending<AnalysisReport>();
  vi.mocked(api).mockReturnValue(old.promise);
  const request = state.analyze();
  const signal = vi.mocked(api).mock.calls[0]?.[3];
  scope.stop();
  expect(signal?.aborted).toBe(true);
  old.resolve(report());
  await request;
  expect(state.report.value).toBeUndefined();
});
it("preview does not apply until explicit confirmation", async () => {
  const { state, applied } = setup();
  vi.mocked(api).mockResolvedValueOnce(report());
  await state.analyze();
  vi.mocked(api).mockResolvedValueOnce(preview());
  await state.generate();
  expect(applied).not.toHaveBeenCalled();
  expect(state.canApply.value).toBe(true);
  expect(vi.mocked(api).mock.calls[1]?.[2]).toMatchObject({
    type: "ADJUST",
    analysisId: "analysis-1",
  });
  vi.mocked(api).mockResolvedValueOnce({ ...trip(), version: 2 });
  await state.apply();
  expect(applied).toHaveBeenCalledOnce();
});
it("a conflict preserves the proposal and displays the error", async () => {
  const { state, applied } = setup();
  state.task.value = preview();
  vi.mocked(api).mockRejectedValueOnce(new Error("路书已被修改，请刷新后重试"));
  await state.apply();
  expect(state.task.value?.status).toBe("PREVIEW");
  expect(state.error.value).toContain("路书已被修改");
  expect(applied).not.toHaveBeenCalled();
});
it("expired external evidence and empty proposals cannot be applied", async () => {
  const { state } = setup();
  state.task.value = preview();
  state.task.value.proposal!.expiresAt = "2026-10-04T10:00:00Z";
  expect(state.canApply.value).toBe(false);
  await state.apply();
  expect(api).not.toHaveBeenCalled();
  state.task.value = preview();
  state.task.value.proposal!.changes = [];
  expect(state.canApply.value).toBe(false);
});
it("remembered tasks resume but wrong-trip tasks are ignored", async () => {
  const { state } = setup();
  localStorage.setItem("SMARTTRIP_ADJUSTMENT_1", "task-1");
  vi.mocked(api).mockResolvedValueOnce(preview());
  await state.restore();
  expect(state.task.value?.id).toBe("task-1");
  state.task.value = undefined;
  vi.mocked(api).mockResolvedValueOnce({ ...preview(), tripId: 2 });
  await state.restore();
  expect(state.task.value).toBeUndefined();
});

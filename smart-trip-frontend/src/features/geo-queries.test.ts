import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import { effectScope, reactive } from "vue";
import { api } from "./api";
import { useGeoQueries } from "./useGeoQueries";
import type { Trip, Day, RouteData } from "./types";
vi.mock("./api", () => ({ api: vi.fn() }));
const data: RouteData = {
  tripVersion: 7,
  mode: "walking",
  points: [],
  legs: [],
  retrievedAt: "2026-10-04T12:00:00Z",
};
let scope: ReturnType<typeof effectScope>;
beforeEach(() => {
  scope = effectScope();
  vi.mocked(api).mockReset();
});
afterEach(() => {
  scope.stop();
  vi.useRealTimers();
});
function setup() {
  const value = reactive({
    trip: {
      id: 1,
      version: 7,
      destination: "成都",
      title: "测试路书",
      days: [],
    } as Trip,
    day: { id: 2, dayDate: "2026-10-10", items: [] } as Day,
    dirty: false,
    mode: "walking",
  });
  const q = scope.run(() => useGeoQueries(() => value))!;
  return { value, q };
}
function pending<T>() {
  let resolve!: (v: T) => void, reject!: (v: Error) => void;
  const promise = new Promise<T>((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { promise, resolve, reject };
}
describe("geo query lifetimes", () => {
  it("reuses current route and only forces a new call for explicit refresh", async () => {
    vi.mocked(api).mockResolvedValue(data);
    const { q } = setup();
    await q.refresh();
    await q.refresh();
    expect(api).toHaveBeenCalledTimes(1);
    await q.refresh(true);
    expect(api).toHaveBeenCalledTimes(2);
  });
  it("loads weather while a route is still pending", async () => {
    const r = pending<RouteData>();
    vi.mocked(api).mockImplementation((path) =>
      path.includes("/route")
        ? r.promise
        : Promise.resolve({ available: false, date: "2026-10-10" }),
    );
    const { q } = setup();
    const task = q.refresh();
    await q.getWeather();
    expect(q.routeBusy.value).toBe(true);
    expect(q.weatherBusy.value).toBe(false);
    expect(q.weather.value?.available).toBe(false);
    r.resolve(data);
    await task;
  });
  it("aborts on condition changes and ignores late route data", async () => {
    const r = pending<RouteData>();
    vi.mocked(api).mockReturnValue(r.promise);
    const { q, value } = setup();
    const task = q.refresh();
    const signal = vi.mocked(api).mock.calls[0]![3]!;
    value.mode = "driving";
    expect(signal.aborted).toBe(true);
    r.resolve(data);
    await task;
    expect(q.route.value).toBeUndefined();
    expect(q.routeError.value).toBe("");
  });
  it("ignores late errors and does not clear the next request busy state", async () => {
    const old = pending<RouteData>(),
      next = pending<RouteData>();
    vi.mocked(api)
      .mockReturnValueOnce(old.promise)
      .mockReturnValueOnce(next.promise);
    const { q, value } = setup();
    const a = q.refresh();
    value.trip.version = 8;
    const b = q.refresh();
    old.reject(new Error("old failure"));
    await a;
    expect(q.routeError.value).toBe("");
    expect(q.routeBusy.value).toBe(true);
    next.resolve({ ...data, tripVersion: 8 });
    await b;
    expect(q.route.value?.tripVersion).toBe(8);
  });
  it("disposal aborts both channels and ignores subsequent replies", async () => {
    const r = pending<RouteData>();
    vi.mocked(api).mockReturnValue(r.promise);
    const { q } = setup();
    const a = q.refresh(),
      b = q.getWeather();
    const signals = vi.mocked(api).mock.calls.map((c) => c[3]!);
    scope.stop();
    expect(signals.every((s) => s.aborted)).toBe(true);
    r.resolve(data);
    await Promise.all([a, b]);
    expect(q.route.value).toBeUndefined();
    expect(q.weather.value).toBeUndefined();
  });
  it("times out at 35 seconds and preserves timeout after a late response", async () => {
    vi.useFakeTimers();
    const r = pending<RouteData>();
    vi.mocked(api).mockReturnValue(r.promise);
    const { q } = setup();
    const a = q.refresh();
    await vi.advanceTimersByTimeAsync(35000);
    expect(q.routeBusy.value).toBe(false);
    expect(q.routeError.value).toContain("超时");
    expect(vi.mocked(api).mock.calls[0]![3]!.aborted).toBe(true);
    r.resolve(data);
    await a;
    expect(q.route.value).toBeUndefined();
    expect(q.routeError.value).toContain("超时");
  });
  it("rejects stale server versions and avoids dirty draft queries", async () => {
    vi.mocked(api).mockResolvedValue({ ...data, tripVersion: 6 });
    const { q, value } = setup();
    await q.refresh();
    expect(q.route.value).toBeUndefined();
    expect(q.routeError.value).toContain("变更");
    value.dirty = true;
    await q.refresh();
    await q.getWeather();
    expect(api).toHaveBeenCalledTimes(1);
  });
});

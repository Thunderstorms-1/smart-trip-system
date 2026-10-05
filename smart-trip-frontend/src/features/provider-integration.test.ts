import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { effectScope, reactive, ref } from "vue";
import { api } from "./api";
import { capabilityEnabled, capabilityNotice } from "./capabilities";
import { usePoiSearch } from "./usePoiSearch";
import type { Capabilities, Poi, Item } from "./types";
vi.mock("./api", () => ({ api: vi.fn() }));
let scope: ReturnType<typeof effectScope>;
beforeEach(() => {
  scope = effectScope();
  vi.mocked(api).mockReset();
});
afterEach(() => {
  scope.stop();
  vi.useRealTimers();
});
const poi: Poi = {
  poiId: "B00001",
  name: "博物馆",
  address: "成都",
  location: "104,30",
  adcode: "510100",
  provider: "AMAP",
};
function deferred<T>() {
  let resolve!: (v: T) => void, reject!: (v: Error) => void;
  const promise = new Promise<T>((a, b) => {
    resolve = a;
    reject = b;
  });
  return { promise, resolve, reject };
}
function setup() {
  const context = reactive({
    tripId: 1,
    version: 1,
    destination: "成都",
    item: { id: 1, itemName: "博物馆", itemType: "SCENIC" } as Item,
    open: true,
  });
  const keyword = ref("博物馆"),
    query = scope.run(() =>
      usePoiSearch(
        () => context,
        () => keyword.value,
      ),
    )!;
  return { context, keyword, query };
}
it("uses individual capabilities instead of an enabled geo aggregate", () => {
  const c: Capabilities = {
    ai: false,
    geo: true,
    map: false,
    poi: false,
    route: true,
    weather: false,
    status: {
      ai: "DISABLED",
      geo: "READY",
      map: "BROWSER_KEY_MISSING",
      poi: "DISABLED",
      route: "READY",
      weather: "QUOTA_UNVERIFIED",
    },
    notice: "",
  };
  expect(capabilityEnabled(c, "poi")).toBe(false);
  expect(capabilityEnabled(c, "route")).toBe(true);
  expect(capabilityNotice(c, "weather")).toContain("权限尚未确认");
  expect(capabilityNotice(c, "map")).toContain("浏览器 Key");
});
it("supports previous capability payloads without inventing enabled services", () => {
  const c: Capabilities = { ai: false, geo: false, map: false, notice: "" };
  expect(capabilityEnabled(c, "route")).toBe(false);
  expect(capabilityNotice(c, "poi")).toContain("暂未启用");
  c.geo = true;
  expect(capabilityEnabled(c, "weather")).toBe(true);
});
it("search is explicit and sends an encoded city and term", async () => {
  vi.mocked(api).mockResolvedValue([poi]);
  const { query } = setup();
  expect(api).not.toHaveBeenCalled();
  await query.search();
  expect(api).toHaveBeenCalledTimes(1);
  expect(vi.mocked(api).mock.calls[0][0]).toContain(encodeURIComponent("成都"));
  expect(query.results.value).toEqual([poi]);
  expect(query.searched.value).toBe(true);
});
it("does not submit duplicate searches while the current request is running", async () => {
  const wait = deferred<Poi[]>();
  vi.mocked(api).mockReturnValue(wait.promise);
  const { query } = setup();
  const first = query.search();
  await query.search();
  expect(api).toHaveBeenCalledTimes(1);
  wait.resolve([]);
  await first;
  expect(query.busy.value).toBe(false);
});
it("discards a late response when the user types another keyword", async () => {
  const wait = deferred<Poi[]>();
  vi.mocked(api).mockReturnValueOnce(wait.promise).mockResolvedValueOnce([]);
  const { query, keyword } = setup();
  const first = query.search();
  const signal = vi.mocked(api).mock.calls[0][3]!;
  keyword.value = "公园";
  expect(signal.aborted).toBe(true);
  await query.search();
  wait.resolve([poi]);
  await first;
  expect(query.results.value).toEqual([]);
  expect(query.searched.value).toBe(true);
});
it("discards old errors after switching the selected node", async () => {
  const wait = deferred<Poi[]>();
  vi.mocked(api).mockReturnValueOnce(wait.promise).mockResolvedValueOnce([poi]);
  const { query, context } = setup();
  const first = query.search();
  context.item = { id: 2, itemName: "新地点", itemType: "SCENIC", costEstimate: 0 };
  await query.search();
  wait.reject(new Error("旧请求失败"));
  await first;
  expect(query.results.value).toEqual([poi]);
  expect(query.error.value).toBe("");
});
it("closing the dialog and unmounting cancels requests and clears results", async () => {
  const wait = deferred<Poi[]>();
  vi.mocked(api).mockReturnValue(wait.promise);
  const { query, context } = setup();
  const first = query.search();
  const signal = vi.mocked(api).mock.calls[0][3]!;
  context.open = false;
  expect(signal.aborted).toBe(true);
  scope.stop();
  wait.resolve([poi]);
  await first;
  expect(query.results.value).toEqual([]);
  expect(query.busy.value).toBe(false);
});
it("the 35 second limit releases busy state and permits a manual retry", async () => {
  vi.useFakeTimers();
  const wait = deferred<Poi[]>();
  vi.mocked(api).mockReturnValueOnce(wait.promise).mockResolvedValueOnce([poi]);
  const { query } = setup();
  const first = query.search();
  await vi.advanceTimersByTimeAsync(35000);
  expect(query.busy.value).toBe(false);
  expect(query.error.value).toContain("超时");
  await query.search();
  wait.resolve([]);
  await first;
  expect(query.results.value).toEqual([poi]);
  expect(query.error.value).toBe("");
  expect(api).toHaveBeenCalledTimes(2);
});
it("failed searches are retryable without storing a fake point", async () => {
  vi.mocked(api)
    .mockRejectedValueOnce(new Error("服务限流"))
    .mockResolvedValueOnce([poi]);
  const { query } = setup();
  await query.search();
  expect(query.error.value).toBe("服务限流");
  expect(query.results.value).toEqual([]);
  await query.search();
  expect(query.results.value).toEqual([poi]);
});

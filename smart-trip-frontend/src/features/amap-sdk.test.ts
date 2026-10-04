import { beforeEach, afterEach, expect, it, vi } from "vitest";
const { request } = vi.hoisted(() => ({ request: vi.fn() }));
vi.mock("./api", () => ({ api: request }));
let scripts: Array<{
  src: string;
  onload: (() => void) | null;
  onerror: (() => void) | null;
  remove: ReturnType<typeof vi.fn>;
}>;
let win: { AMap?: object; _AMapSecurityConfig?: object };
beforeEach(() => {
  vi.resetModules();
  vi.useFakeTimers();
  scripts = [];
  win = {};
  request.mockReset();
  request.mockResolvedValue({ key: "fixture", securityJsCode: "fixture" });
  vi.stubGlobal("window", win);
  vi.stubGlobal("document", {
    createElement: () => ({
      src: "",
      onload: null,
      onerror: null,
      remove: vi.fn(),
    }),
    head: { appendChild: (s: (typeof scripts)[number]) => scripts.push(s) },
  });
});
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});
const tick = async () => {
  await Promise.resolve();
  await Promise.resolve();
};
it("shares configuration and a single script among concurrent callers", async () => {
  const { loadAmapSdk } = await import("./amap-sdk");
  const a = loadAmapSdk(),
    b = loadAmapSdk();
  expect(a).toBe(b);
  await tick();
  expect(request).toHaveBeenCalledTimes(1);
  expect(scripts).toHaveLength(1);
  win.AMap = {};
  scripts[0]!.onload!();
  await expect(a).resolves.toBe(win.AMap);
  expect(scripts[0]!.onload).toBeNull();
  expect(vi.getTimerCount()).toBe(0);
});
it("times out, removes only its script, and can retry", async () => {
  const { loadAmapSdk } = await import("./amap-sdk");
  const a = loadAmapSdk();
  const rejection = expect(a).rejects.toThrow("超时");
  await tick();
  await vi.advanceTimersByTimeAsync(10000);
  await rejection;
  expect(scripts[0]!.remove).toHaveBeenCalledTimes(1);
  const b = loadAmapSdk();
  await tick();
  expect(scripts).toHaveLength(2);
  win.AMap = {};
  scripts[1]!.onload!();
  await expect(b).resolves.toBe(win.AMap);
});
it("releases failed scripts and retries after a load error", async () => {
  const { loadAmapSdk } = await import("./amap-sdk");
  const a = loadAmapSdk();
  const rejected = expect(a).rejects.toThrow("失败");
  await tick();
  scripts[0]!.onerror!();
  await rejected;
  expect(scripts[0]!.remove).toHaveBeenCalledOnce();
  const b = loadAmapSdk();
  await tick();
  win.AMap = {};
  scripts[1]!.onload!();
  await b;
});
it("times out stalled config without creating a script from its late result", async () => {
  let resolve!: (v: unknown) => void;
  request.mockReturnValueOnce(new Promise((r) => (resolve = r)));
  const { loadAmapSdk } = await import("./amap-sdk");
  const a = loadAmapSdk();
  const rejected = expect(a).rejects.toThrow("超时");
  await vi.advanceTimersByTimeAsync(10000);
  await rejected;
  expect(request.mock.calls[0]![3].aborted).toBe(true);
  resolve({ key: "late", securityJsCode: "late" });
  await tick();
  expect(scripts).toHaveLength(0);
  const b = loadAmapSdk();
  await tick();
  win.AMap = {};
  scripts[0]!.onload!();
  await b;
});

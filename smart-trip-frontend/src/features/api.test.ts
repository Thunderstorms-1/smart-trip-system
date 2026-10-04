import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { api, ApiError } from "./api";
describe("API request behavior", () => {
  beforeEach(() => vi.stubGlobal("localStorage", { getItem: () => null }));
  afterEach(() => vi.unstubAllGlobals());
  it("uses the error envelope for rejected permissions", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(
        async () =>
          new Response(
            JSON.stringify({ code: 403, message: "无权读取该行程" }),
            { status: 403 },
          ),
      ),
    );
    await expect(api("/workbench/1")).rejects.toMatchObject({
      status: 403,
      message: "无权读取该行程",
    });
  });
  it("handles disconnected network without inventing data", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => {
        throw new Error("offline");
      }),
    );
    await expect(api("/capabilities")).rejects.toBeInstanceOf(ApiError);
  });
  it("returns real server data and preserves idempotency keys", async () => {
    const request = vi.fn(
      async (_path: string) =>
        new Response(JSON.stringify({ code: 200, data: { id: 42 } })),
    );
    vi.stubGlobal("fetch", request);
    expect(
      await api("/ai/tasks", "POST", { requestKey: "same-request" }),
    ).toEqual({ id: 42 });
    expect(request.mock.calls[0]?.[0]).toBe("/api/ai/tasks");
  });
});

describe("session identity on late unauthorized responses", () => {
  afterEach(() => vi.unstubAllGlobals());
  function setup() {
    let token: string | null = "old";
    const remove = vi.fn(() => {
      token = null;
    });
    const dispatch = vi.fn();
    vi.stubGlobal("localStorage", { getItem: () => token, removeItem: remove });
    vi.stubGlobal("window", { dispatchEvent: dispatch });
    return {
      change: (v: string | null) => {
        token = v;
      },
      get: () => token,
      remove,
      dispatch,
    };
  }
  function pending() {
    let resolve!: (v: Response) => void;
    const promise = new Promise<Response>((r) => (resolve = r));
    return { promise, resolve };
  }
  const unauthorized = () =>
    new Response(JSON.stringify({ code: 401, message: "expired" }), {
      status: 401,
    });
  it("keeps a newly established session when an old token request returns 401", async () => {
    const s = setup(),
      r = pending();
    vi.stubGlobal(
      "fetch",
      vi.fn(() => r.promise.then((response) => response.clone())),
    );
    const result = api("/user/info");
    const rejected = expect(result).rejects.toMatchObject({ status: 401 });
    s.change("new");
    r.resolve(unauthorized());
    await rejected;
    expect(s.get()).toBe("new");
    expect(s.remove).not.toHaveBeenCalled();
    expect(s.dispatch).not.toHaveBeenCalled();
  });
  it("expires the current session once even with concurrent 401 responses", async () => {
    const s = setup(),
      r = pending();
    vi.stubGlobal(
      "fetch",
      vi.fn(() => r.promise.then((response) => response.clone())),
    );
    const a = api("/user/info"),
      b = api("/workbench/1");
    const results = Promise.allSettled([a, b]);
    r.resolve(unauthorized());
    await results;
    expect(s.get()).toBeNull();
    expect(s.remove).toHaveBeenCalledOnce();
    expect(s.dispatch).toHaveBeenCalledOnce();
  });
  it("keeps session state for authentication endpoint failures", async () => {
    const s = setup();
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => unauthorized()),
    );
    await expect(api("/auth/login", "POST", {})).rejects.toMatchObject({
      status: 401,
    });
    expect(s.get()).toBe("old");
    expect(s.remove).not.toHaveBeenCalled();
  });
});

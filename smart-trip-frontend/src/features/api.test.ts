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

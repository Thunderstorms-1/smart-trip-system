import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import { effectScope, ref } from "vue";
import { createPinia, setActivePinia } from "pinia";
import { api } from "./api";
import { useSession } from "./session";
import { useTripDraft } from "./useTripDraft";
import { useLatestQuery } from "./useLatestQuery";
import type { Trip } from "./types";

vi.mock("./api", () => ({ api: vi.fn() }));
function pending<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}
function trip(id: number): Trip {
  return {
    id,
    version: 0,
    userId: 1,
    title: "路书" + id,
    destination: "成都",
    days: [{ id: 10 + id, items: [] }],
  };
}
let scope: ReturnType<typeof effectScope>;
beforeEach(() => {
  vi.stubGlobal("localStorage", {
    getItem: () => "fixture",
    removeItem: vi.fn(),
  });
  setActivePinia(createPinia());
  useSession().profile = { id: 1, nickname: "游客" };
  scope = effectScope();
  vi.mocked(api).mockReset();
});
afterEach(() => {
  scope.stop();
  vi.unstubAllGlobals();
});

describe("route-local drafts", () => {
  it("ignores a late response from a superseded trip even if upstream ignores abort", async () => {
    const old = pending<Trip>(),
      id = ref("1");
    vi.mocked(api).mockImplementation(async (path) =>
      path.startsWith("/membership")
        ? [{ userId: 1, role: "OWNER" }]
        : path === "/workbench/1"
          ? old.promise
          : trip(2),
    );
    const draft = scope.run(() => useTripDraft(() => id.value))!;
    const previous = draft.load();
    id.value = "2";
    draft.reset();
    await draft.load();
    old.resolve(trip(1));
    await previous;
    expect(draft.trip.value?.id).toBe(2);
    expect(draft.loading.value).toBe(false);
    expect(draft.dirty.value).toBe(false);
  });
  it("keeps an unsaved draft after a rejected save", async () => {
    vi.mocked(api).mockImplementation(async (path, method) => {
      if (method === "PUT") throw new Error("版本冲突");
      return path.startsWith("/membership")
        ? [{ userId: 1, role: "OWNER" }]
        : trip(1);
    });
    const draft = scope.run(() => useTripDraft(() => "1"))!;
    await draft.load();
    draft.trip.value!.title = "尚未保存";
    await expect(draft.save()).rejects.toThrow("版本冲突");
    expect(draft.trip.value!.title).toBe("尚未保存");
    expect(draft.dirty.value).toBe(true);
  });
});

describe("cancelable page queries", () => {
  it("applies a new search while an older query is pending", async () => {
    const old = pending<string>(),
      state = ref("");
    const query = scope.run(useLatestQuery)!;
    const first = query.query(
      () => old.promise,
      (value) => {
        state.value = value;
      },
    );
    await query.query(
      async () => "新搜索",
      (value) => {
        state.value = value;
      },
    );
    old.resolve("旧搜索");
    await first;
    expect(state.value).toBe("新搜索");
    expect(query.loading.value).toBe(false);
    expect(query.error.value).toBe("");
  });
  it("allows retry after failure without applying a stale result", async () => {
    const state = ref("");
    const query = scope.run(useLatestQuery)!;
    await query.query(
      async () => {
        throw new Error("离线");
      },
      (value) => {
        state.value = value;
      },
    );
    expect(query.error.value).toBe("离线");
    await query.query(
      async () => "恢复",
      (value) => {
        state.value = value;
      },
    );
    expect(state.value).toBe("恢复");
    expect(query.error.value).toBe("");
  });
});

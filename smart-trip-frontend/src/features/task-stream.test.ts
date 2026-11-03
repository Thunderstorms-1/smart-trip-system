import { describe, it, expect, vi } from "vitest";
import { SseFrames, followTask } from "./task-stream";
describe("SSE frame buffering", () => {
  it("handles a CRLF frame split across network chunks", () => {
    const p = new SseFrames();
    expect(p.push('event: task\r\nid: 42\r\ndata: {"city":"成')).toEqual([]);
    expect(p.push('都"}\r\n\r')).toEqual([]);
    expect(p.push("\n")).toEqual([
      { event: "task", id: "42", data: '{"city":"成都"}' },
    ]);
  });
  it("preserves multiple frames and multiline data", () => {
    const p = new SseFrames();
    expect(
      p.push("data: one\ndata: two\n\nevent: task\nid: 1\ndata: {}\n\n"),
    ).toEqual([
      { event: "message", id: "", data: "one\ntwo" },
      { event: "task", id: "1", data: "{}" },
    ]);
  });
  it("rejects incomplete final frame", () => {
    const p = new SseFrames();
    expect(() => p.push("data: partial", true)).toThrow("提前中断");
  });
});

describe("task progress transport", () => {
  function response(text: string) {
    const bytes = new TextEncoder().encode(text);
    return new Response(
      new ReadableStream({
        start(controller) {
          controller.enqueue(bytes.slice(0, 27));
          controller.enqueue(bytes.slice(27));
          controller.close();
        },
      }),
      { status: 200, headers: { "Content-Type": "text/event-stream" } },
    );
  }
  it("delivers true phase history and stops on the same task's preview", async () => {
    vi.stubGlobal("localStorage", { getItem: () => null });
    const preview = {
      id: "same-task",
      type: "GENERATE",
      status: "PREVIEW",
      generation: {
        phaseHistory: [
          { phase: "MODEL", at: "2026-10-24T00:00:00Z" },
          { phase: "PREVIEW", at: "2026-10-24T00:00:04Z" },
        ],
      },
    };
    const fetcher = vi
      .fn()
      .mockResolvedValue(
        response(
          "event: task\nid: same-task\ndata: " +
            JSON.stringify(preview) +
            "\n\n",
        ),
      );
    vi.stubGlobal("fetch", fetcher);
    try {
      const onTask = vi.fn();
      await followTask("same-task", onTask);
      expect(onTask).toHaveBeenCalledWith(preview);
      expect(fetcher).toHaveBeenCalledTimes(1);
      expect(fetcher.mock.calls[0]?.[0]).toBe("/api/ai/tasks/same-task/events");
      expect(fetcher.mock.calls[0]?.[1]?.method).toBeUndefined();
    } finally {
      vi.unstubAllGlobals();
    }
  });
  it("reports a disconnected running stream without submitting a new generation", async () => {
    vi.stubGlobal("localStorage", { getItem: () => null });
    const running = {
      id: "same-task",
      type: "GENERATE",
      status: "RUNNING",
      phase: "MODEL",
    };
    const fetcher = vi
      .fn()
      .mockResolvedValue(
        response(
          "event: task\nid: same-task\ndata: " +
            JSON.stringify(running) +
            "\n\n",
        ),
      );
    vi.stubGlobal("fetch", fetcher);
    try {
      const onTask = vi.fn();
      await expect(followTask("same-task", onTask)).rejects.toThrow("提前结束");
      expect(onTask).toHaveBeenCalledWith(running);
      expect(fetcher).toHaveBeenCalledTimes(1);
      expect(fetcher.mock.calls[0]?.[0]).toBe("/api/ai/tasks/same-task/events");
    } finally {
      vi.unstubAllGlobals();
    }
  });
});

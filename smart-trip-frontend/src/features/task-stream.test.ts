import { describe, it, expect } from "vitest";
import { SseFrames } from "./task-stream";
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

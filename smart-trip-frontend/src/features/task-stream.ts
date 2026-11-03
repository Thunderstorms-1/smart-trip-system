import { ApiError } from "./api";
import type { Task } from "./types";
export class SseFrames {
  private buffer = "";
  push(chunk: string, final = false) {
    this.buffer += chunk;
    const events: Array<{ event: string; id: string; data: string }> = [];
    let match: RegExpExecArray | null;
    while ((match = /\r?\n\r?\n/.exec(this.buffer))) {
      const frame = this.buffer.slice(0, match.index);
      this.buffer = this.buffer.slice(match.index + match[0].length);
      let event = "message",
        id = "";
      const data: string[] = [];
      for (const line of frame.split(/\r?\n/)) {
        if (line.startsWith("event:")) event = line.slice(6).trim();
        if (line.startsWith("id:")) id = line.slice(3).trim();
        if (line.startsWith("data:"))
          data.push(line.slice(5).replace(/^ /, ""));
      }
      if (data.length) events.push({ event, id, data: data.join("\n") });
    }
    if (final && this.buffer.trim()) throw new Error("任务流提前中断");
    if (this.buffer.length > 400000) throw new Error("任务事件超出长度限制");
    return events;
  }
}
export async function followTask(
  id: string,
  onTask: (task: Task) => void,
  signal?: AbortSignal,
) {
  const token = localStorage.getItem("AUTH_TOKEN");
  const response = await fetch(
    "/api/ai/tasks/" + encodeURIComponent(id) + "/events",
    {
      headers: token ? { Authorization: "Bearer " + token } : {},
      signal,
    },
  );
  if (!response.ok || !response.body)
    throw new ApiError(response.status, "无法连接任务进度，请重试查询。");
  const reader = response.body.getReader(),
    decoder = new TextDecoder(),
    frames = new SseFrames();
  try {
    while (true) {
      const part = await reader.read();
      const decoded = decoder.decode(part.value, { stream: !part.done });
      for (const frame of frames.push(decoded, part.done)) {
        if (frame.event === "task" && frame.id === id) {
          const task = JSON.parse(frame.data) as Task;
          if (task.id !== id) throw new Error("任务进度编号不匹配");
          onTask(task);
          if (!["QUEUED", "RUNNING"].includes(task.status)) return;
        }
      }
      if (part.done) throw new Error("任务进度连接提前结束，请重试查询。");
    }
  } finally {
    await reader.cancel().catch(() => {});
    reader.releaseLock();
  }
}

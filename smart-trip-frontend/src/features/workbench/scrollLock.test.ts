import { it, expect, vi, afterEach } from "vitest";
import { lockEditorScroll } from "./scrollLock";
afterEach(() => vi.unstubAllGlobals());
it("closing a nested modal keeps the outer sheet locked and restores the exact previous style", () => {
  const style = { overflow: "clip" };
  vi.stubGlobal("document", { body: { style } });
  const sheet = lockEditorScroll(),
    nested = lockEditorScroll();
  expect(style.overflow).toBe("hidden");
  nested();
  expect(style.overflow).toBe("hidden");
  sheet();
  expect(style.overflow).toBe("clip");
  nested();
  expect(style.overflow).toBe("clip");
});
it("resize can release the sheet first without unlocking another open modal", () => {
  const style = { overflow: "" };
  vi.stubGlobal("document", { body: { style } });
  const sheet = lockEditorScroll(),
    nested = lockEditorScroll();
  sheet();
  expect(style.overflow).toBe("hidden");
  nested();
  expect(style.overflow).toBe("");
});

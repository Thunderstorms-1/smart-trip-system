import { ref } from "vue";
export const message = ref("");
export const severity = ref<"success" | "error">("success");
let timer: ReturnType<typeof setTimeout>;
export function notify(text: string, type: "success" | "error" = "success") {
  clearTimeout(timer);
  message.value = text;
  severity.value = type;
  timer = setTimeout(() => (message.value = ""), 6000);
}
export function useAction() {
  const busy = ref(false),
    error = ref("");
  async function run<T>(
    fn: () => Promise<T>,
    success?: string,
  ): Promise<T | undefined> {
    if (busy.value) return;
    busy.value = true;
    error.value = "";
    try {
      const result = await fn();
      if (success) notify(success);
      return result;
    } catch (e) {
      error.value = e instanceof Error ? e.message : "操作失败，请重试";
      notify(error.value, "error");
      return undefined;
    } finally {
      busy.value = false;
    }
  }
  return { busy, error, run };
}

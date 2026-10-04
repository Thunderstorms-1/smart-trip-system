import { ref, onScopeDispose } from "vue";
/** A query is superseded, never silently skipped because an older query is busy. */
export function useLatestQuery() {
  const loading = ref(false),
    error = ref("");
  let epoch = 0,
    controller: AbortController | undefined;
  function cancel() {
    epoch++;
    controller?.abort();
    loading.value = false;
  }
  async function query<T>(
    fetcher: (signal: AbortSignal) => Promise<T>,
    apply: (value: T) => void,
  ) {
    cancel();
    const current = epoch;
    const request = new AbortController();
    controller = request;
    loading.value = true;
    error.value = "";
    try {
      const value = await fetcher(request.signal);
      if (current === epoch && !request.signal.aborted) apply(value);
    } catch (e) {
      if (current === epoch && !request.signal.aborted)
        error.value = e instanceof Error ? e.message : "加载失败，请重试";
    } finally {
      if (current === epoch) loading.value = false;
    }
  }
  onScopeDispose(cancel);
  return { loading, error, query, cancel };
}

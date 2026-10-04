import { computed, ref, onScopeDispose } from "vue";
import { api } from "./api";
import { useSession } from "./session";
import type { Trip } from "./types";

/** Route-local draft. Reads use latest-response-wins; writes snapshot the submitted tree. */
export function useTripDraft(getId: () => string) {
  const session = useSession();
  const trip = ref<Trip>();
  const saved = ref("");
  const role = ref("VIEWER");
  const loading = ref(false);
  const loadError = ref("");
  let epoch = 0;
  let request: AbortController | undefined;
  const dirty = computed(() =>
    Boolean(trip.value && JSON.stringify(trip.value) !== saved.value),
  );
  function cancel() {
    epoch++;
    request?.abort();
    loading.value = false;
  }
  function reset() {
    cancel();
    trip.value = undefined;
    saved.value = "";
    role.value = "VIEWER";
    loadError.value = "";
  }
  async function load() {
    cancel();
    const current = epoch,
      id = getId();
    const controller = new AbortController();
    request = controller;
    loading.value = true;
    loadError.value = "";
    try {
      if (!session.profile && session.token) await session.refresh();
      if (current !== epoch) return;
      const [value, members] = await Promise.all([
        api<Trip>("/workbench/" + id, "GET", undefined, controller.signal),
        api<Array<{ userId: number; role: string }>>(
          "/membership/trips/" + id,
          "GET",
          undefined,
          controller.signal,
        ),
      ]);
      if (current !== epoch || getId() !== id) return;
      trip.value = value;
      saved.value = JSON.stringify(value);
      role.value =
        members.find((m) => m.userId === session.profile?.id)?.role || "VIEWER";
    } catch (e) {
      if (current === epoch && !controller.signal.aborted) {
        loadError.value = e instanceof Error ? e.message : "无法打开路书";
        throw e;
      }
    } finally {
      if (current === epoch) loading.value = false;
    }
  }
  async function save() {
    if (!trip.value) return;
    const current = epoch,
      id = getId();
    const input = JSON.parse(JSON.stringify(trip.value)) as Trip;
    const result = await api<Trip>("/workbench/" + input.id, "PUT", input);
    if (current === epoch && getId() === id) {
      trip.value = result;
      saved.value = JSON.stringify(result);
    }
  }
  onScopeDispose(cancel);
  return { trip, saved, role, dirty, loading, loadError, load, save, reset };
}

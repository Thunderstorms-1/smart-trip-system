<script setup lang="ts">
import { ref, watch, onBeforeUnmount, nextTick } from "vue";
import type { Item, Trip } from "../types";
import NodeFields from "./NodeFields.vue";
import { lockEditorScroll } from "./scrollLock";
const p = defineProps<{
  item: Item;
  nodeKey: string;
  trip: Trip;
  dayIndex: number;
  index: number;
  editable: boolean;
  busy: boolean;
  dirty: boolean;
  geo: boolean;
  error: string;
}>();
const emit = defineEmits<{
  close: [];
  move: [delta: number];
  remove: [];
  transfer: [index: number];
  poi: [];
  record: [];
  save: [];
}>();
const dialog = ref<HTMLDialogElement>();
const mobile = ref(window.matchMedia("(max-width: 700px)").matches),
  full = ref(false);
const media = window.matchMedia("(max-width: 700px)");
let unlock: (() => void) | undefined;
function resize(event: MediaQueryListEvent) {
  mobile.value = event.matches;
}
media.addEventListener("change", resize);
async function present() {
  const d = dialog.value;
  if (!d) return;
  const focused = d.contains(document.activeElement)
    ? (document.activeElement as HTMLElement)
    : undefined;
  if (d.open) d.close();
  unlock?.();
  unlock = undefined;
  if (mobile.value) {
    unlock = lockEditorScroll();
    d.showModal();
  } else d.show();
  await nextTick();
  if (focused?.isConnected) focused.focus({ preventScroll: true });
  else
    d.querySelector<HTMLInputElement>(".wb-node-fields input")?.focus({
      preventScroll: true,
    });
}
watch([dialog, mobile], present, { flush: "post" });
watch(
  () => p.nodeKey,
  async () => {
    await nextTick();
    // Rebinding an unsaved node after save retains the existing input and focus.
    if (dialog.value && !dialog.value.contains(document.activeElement))
      dialog.value
        .querySelector<HTMLInputElement>(".wb-node-fields input")
        ?.focus();
  },
);
watch(
  () => p.busy,
  async (busy) => {
    if (busy) return;
    await nextTick();
    const d = dialog.value;
    if (mobile.value && d && !d.contains(document.activeElement))
      d.querySelector<HTMLInputElement>(".wb-node-fields input")?.focus();
  },
);
watch(
  () => p.index,
  async () => {
    await nextTick();
    const d = dialog.value;
    if (
      d &&
      (!d.contains(document.activeElement) ||
        document.activeElement?.matches(":disabled"))
    )
      d.querySelector<HTMLInputElement>(".wb-node-fields input")?.focus({
        preventScroll: true,
      });
  },
);
function trapTab(event: KeyboardEvent) {
  if (!mobile.value || !dialog.value) return;
  const nodes = [
    ...dialog.value.querySelectorAll<HTMLElement>(
      "button:not(:disabled), input:not(:disabled), select:not(:disabled), textarea:not(:disabled), [tabindex]:not([tabindex='-1'])",
    ),
  ].filter((node) => node.getClientRects().length > 0);
  const first = nodes[0],
    last = nodes.at(-1),
    active = document.activeElement;
  if (!first || !last) {
    event.preventDefault();
    dialog.value.focus();
    return;
  }
  if (
    !dialog.value.contains(active) ||
    (event.shiftKey && active === first) ||
    (!event.shiftKey && active === last)
  ) {
    event.preventDefault();
    (event.shiftKey ? last : first).focus();
  }
}
function backdrop(event: MouseEvent) {
  const d = dialog.value;
  if (!d || event.target !== d) return;
  const r = d.getBoundingClientRect();
  if (
    event.clientX < r.left ||
    event.clientX > r.right ||
    event.clientY < r.top ||
    event.clientY > r.bottom
  )
    emit("close");
}
onBeforeUnmount(() => {
  dialog.value?.close();
  unlock?.();
  media.removeEventListener("change", resize);
});
</script>
<template>
  <dialog
    ref="dialog"
    class="wb-inspector-dialog"
    :class="{ 'is-full': full }"
    aria-label="安排 Inspector"
    @cancel.prevent="emit('close')"
    @click="backdrop"
    @keydown.esc.stop.prevent="emit('close')"
    @keydown.tab="trapTab"
  >
    <header class="wb-inspector-heading">
      <div>
        <h2>{{ editable ? "编辑安排" : "安排详情" }}</h2>
        <p>第 {{ dayIndex + 1 }} 天 · 第 {{ index + 1 }} 个安排</p>
      </div>
      <button class="wb-text-button" @click="emit('close')">收起编辑</button>
    </header>
    <button class="wb-sheet-size" :aria-expanded="full" @click="full = !full">
      {{ full ? "收起编辑面板" : "展开编辑面板" }}
    </button>
    <div class="wb-inspector-content">
      <NodeFields
        :id="'fields-' + nodeKey"
        :item="item"
        :trip="trip"
        :day-index="dayIndex"
        :index="index"
        :editable="editable"
        :busy="busy"
        :dirty="dirty"
        :geo="geo"
        @close="emit('close')"
        @move="emit('move', $event)"
        @remove="emit('remove')"
        @transfer="emit('transfer', $event)"
        @poi="emit('poi')"
        @record="emit('record')"
      />
    </div>
    <footer class="wb-inspector-footer">
      <p v-if="error && mobile" class="form-error" role="alert">{{ error }}</p>
      <p>
        {{
          dirty
            ? "修改保留在当前路书草稿，保存后生效。"
            : editable
              ? "正在编辑已保存的安排。"
              : "正在查看已保存的安排。"
        }}
      </p>
      <button
        v-if="editable"
        class="wb-inspector-save"
        :disabled="busy || !dirty"
        @click="emit('save')"
      >
        {{ busy ? "保存中…" : "保存路书" }}
      </button>
    </footer>
  </dialog>
</template>

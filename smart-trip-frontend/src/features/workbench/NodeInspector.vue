<script setup lang="ts">
import { ref, watch, onBeforeUnmount, nextTick } from "vue";
import { gsap } from "gsap";
import { Flip } from "gsap/Flip";
import type { Item, Trip } from "../types";
import NodeFields from "./NodeFields.vue";
import { lockEditorScroll } from "./scrollLock";
gsap.registerPlugin(Flip);
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
const dismissing = ref(false),
  savedHint = ref(false),
  full = ref(false);
const mobileMedia = window.matchMedia("(max-width: 700px)");
const reducedMedia = window.matchMedia("(prefers-reduced-motion: reduce)");
const mobile = ref(mobileMedia.matches);
let disposed = false,
  operation = 0;
let unlock: (() => void) | undefined;
let intro: gsap.core.Timeline | undefined;
let finishIntro: (() => void) | undefined;
let morphTween: gsap.core.Tween | undefined;
let finishMorph: (() => void) | undefined;
let savedHintTimer: ReturnType<typeof setTimeout> | undefined;
let returnTimer: ReturnType<typeof setTimeout> | undefined;
let shownVersion = p.trip.version;
let saveTarget:
  | { version: number | undefined; day: number; index: number; id?: number }
  | undefined;
function parts() {
  return dialog.value
    ? [
        ...dialog.value.querySelectorAll<HTMLElement>(
          ".wb-inspector-heading, .wb-inspector-content, .wb-inspector-footer",
        ),
      ]
    : [];
}
function cancelMorph() {
  morphTween?.kill();
  morphTween = undefined;
  finishMorph?.();
  finishMorph = undefined;
}
function cancelAnimations() {
  intro?.kill();
  intro = undefined;
  finishIntro?.();
  finishIntro = undefined;
  cancelMorph();
  if (dialog.value)
    gsap.set([dialog.value, ...parts()], { clearProps: "opacity,transform" });
}
function clearReturn() {
  clearTimeout(returnTimer);
  returnTimer = undefined;
}
function sourceCard() {
  return document
    .getElementById("timeline-" + p.nodeKey)
    ?.closest<HTMLElement>(".wb-node-card");
}
function usable(element: HTMLElement | undefined | null) {
  if (!element || !element.isConnected || element.closest("[inert]"))
    return false;
  const box = element.getBoundingClientRect();
  return (
    box.width > 0 &&
    box.height > 0 &&
    box.bottom > 0 &&
    box.top < window.innerHeight &&
    box.right > 0 &&
    box.left < window.innerWidth
  );
}
function morph(
  from: HTMLElement | undefined | null,
  to: HTMLElement | undefined | null,
  duration: number,
) {
  cancelMorph();
  if (reducedMedia.matches || mobile.value || !usable(from) || !usable(to))
    return Promise.resolve(true);
  const box = from!.getBoundingClientRect();
  const proxy = document.createElement("div");
  proxy.className = "wb-v5-glass-morph";
  proxy.inert = true;
  proxy.setAttribute("aria-hidden", "true");
  Object.assign(proxy.style, {
    left: box.left + "px",
    top: box.top + "px",
    width: box.width + "px",
    height: box.height + "px",
  });
  document.body.append(proxy);
  return new Promise<boolean>((resolve) => {
    let settled = false;
    const complete = (completed: boolean) => {
      if (settled) return;
      settled = true;
      proxy.remove();
      morphTween = undefined;
      finishMorph = undefined;
      resolve(completed);
    };
    finishMorph = () => complete(false);
    morphTween = Flip.fit(proxy, to!, {
      duration,
      ease: "power3.inOut",
      scale: true,
      onComplete: () => complete(true),
    }) as gsap.core.Tween | undefined;
    if (!morphTween) complete(false);
  });
}
function reveal() {
  cancelAnimations();
  const d = dialog.value;
  if (!d || reducedMedia.matches || disposed) return;
  void morph(sourceCard(), d, 0.36);
  intro = gsap.timeline({
    defaults: { ease: "power2.out" },
    onComplete: () => {
      if (!disposed && dialog.value)
        gsap.set([dialog.value, ...parts()], {
          clearProps: "opacity,transform",
        });
    },
  });
  intro.fromTo(d, { opacity: 0.3 }, { opacity: 1, duration: 0.24 }, 0.05);
  const [heading, content, footer] = parts();
  if (heading)
    intro.fromTo(
      heading,
      { y: 9, opacity: 0 },
      { y: 0, opacity: 1, duration: 0.24 },
      0.12,
    );
  if (content)
    intro.fromTo(
      content,
      { y: 12, opacity: 0 },
      { y: 0, opacity: 1, duration: 0.28 },
      0.19,
    );
  if (footer)
    intro.fromTo(
      footer,
      { y: 8, opacity: 0 },
      { y: 0, opacity: 1, duration: 0.24 },
      0.26,
    );
}
async function dismiss() {
  if (dismissing.value || p.busy) return;
  clearReturn();
  const current = ++operation;
  dismissing.value = true;
  cancelAnimations();
  const d = dialog.value;
  if (!reducedMedia.matches && d) {
    const movement = morph(d, sourceCard(), 0.25);
    const contentGone = new Promise<void>((resolve) => {
      finishIntro = resolve;
      intro = gsap.timeline({
        onComplete: () => {
          finishIntro = undefined;
          resolve();
        },
        onInterrupt: resolve,
      });
      intro.to(parts(), {
        y: 5,
        opacity: 0,
        duration: 0.15,
        stagger: 0.025,
        ease: "power2.in",
      });
    });
    await Promise.all([movement, contentGone]);
  }
  if (!disposed && current === operation) {
    dismissing.value = false;
    emit("close");
  }
}
async function present() {
  const d = dialog.value;
  if (!d || disposed) return;
  const current = ++operation;
  dismissing.value = false;
  cancelAnimations();
  const focused = d.contains(document.activeElement)
    ? (document.activeElement as HTMLElement)
    : undefined;
  if (d.open) d.close();
  unlock?.();
  unlock = undefined;
  unlock = lockEditorScroll();
  d.showModal();
  await nextTick();
  if (disposed || current !== operation) return;
  reveal();
  if (focused?.isConnected) focused.focus({ preventScroll: true });
  else
    d.querySelector<HTMLInputElement>(".wb-node-fields input")?.focus({
      preventScroll: true,
    });
}
function resize(event: MediaQueryListEvent) {
  mobile.value = event.matches;
}
function reduceChanged() {
  if (reducedMedia.matches) cancelAnimations();
}
mobileMedia.addEventListener("change", resize);
reducedMedia.addEventListener("change", reduceChanged);
watch([dialog, mobile], present, { flush: "post" });
watch(
  () => p.nodeKey,
  async () => {
    const current = ++operation;
    dismissing.value = false;
    const sameSave =
      saveTarget &&
      p.dayIndex === saveTarget.day &&
      p.index === saveTarget.index &&
      (saveTarget.id == null || p.item.id === saveTarget.id);
    if (!sameSave) {
      clearReturn();
      saveTarget = undefined;
    }
    await nextTick();
    if (disposed || current !== operation) return;
    if (!sameSave) reveal();
    if (dialog.value && !dialog.value.contains(document.activeElement))
      dialog.value
        .querySelector<HTMLInputElement>(".wb-node-fields input")
        ?.focus({ preventScroll: true });
  },
);
function requestSave() {
  if (p.busy || !p.dirty || !p.editable) return;
  clearReturn();
  savedHint.value = false;
  saveTarget = {
    version: p.trip.version,
    day: p.dayIndex,
    index: p.index,
    id: p.item.id,
  };
  emit("save");
}
watch(
  [() => p.trip.version, () => p.busy, () => p.dirty, () => p.error],
  ([version, busy, dirty, error]) => {
    if (dirty) {
      clearTimeout(savedHintTimer);
      savedHint.value = false;
      clearReturn();
      if (!busy && saveTarget && version !== saveTarget.version)
        saveTarget = undefined;
    }
    if (!busy && error) {
      saveTarget = undefined;
      clearReturn();
      return;
    }
    if (busy || dirty || error || version == null || version === shownVersion)
      return;
    shownVersion = version;
    savedHint.value = true;
    clearTimeout(savedHintTimer);
    savedHintTimer = setTimeout(() => {
      savedHint.value = false;
    }, 2400);
    const target = saveTarget;
    if (target && version !== target.version) {
      clearReturn();
      returnTimer = setTimeout(() => {
        if (
          !disposed &&
          !p.busy &&
          !p.dirty &&
          !p.error &&
          p.trip.version === version &&
          p.dayIndex === target.day &&
          p.index === target.index
        )
          void dismiss();
      }, 1000);
    }
  },
  { flush: "post" },
);
watch(
  () => p.busy,
  async (busy) => {
    if (busy) return;
    await nextTick();
    if (
      !disposed &&
      !p.busy &&
      !dismissing.value &&
      dialog.value &&
      !dialog.value.contains(document.activeElement)
    )
      dialog.value
        .querySelector<HTMLInputElement>(".wb-node-fields input")
        ?.focus();
  },
);
watch(
  () => p.index,
  async () => {
    await nextTick();
    const d = dialog.value;
    if (disposed || dismissing.value || !d) return;
    if (
      !d.contains(document.activeElement) ||
      document.activeElement?.matches(":disabled")
    )
      d.querySelector<HTMLInputElement>(".wb-node-fields input")?.focus({
        preventScroll: true,
      });
  },
);
function trapTab(event: KeyboardEvent) {
  if (!dialog.value) return;
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
    void dismiss();
}
onBeforeUnmount(() => {
  disposed = true;
  operation++;
  clearTimeout(savedHintTimer);
  clearReturn();
  cancelAnimations();
  dialog.value?.close();
  unlock?.();
  mobileMedia.removeEventListener("change", resize);
  reducedMedia.removeEventListener("change", reduceChanged);
});
</script>
<template>
  <dialog
    ref="dialog"
    class="wb-inspector-dialog"
    :class="{ 'is-full': full, 'is-dismissing': dismissing }"
    :aria-label="editable ? '编辑行程安排' : '查看行程安排'"
    :aria-describedby="
      error ? 'wb-inspector-state wb-inspector-error' : 'wb-inspector-state'
    "
    :aria-busy="busy"
    @cancel.prevent="dismiss"
    @click="backdrop"
    @keydown.esc.stop.prevent="dismiss"
    @keydown.tab="trapTab"
  >
    <header class="wb-inspector-heading">
      <div>
        <h2>{{ editable ? "编辑安排" : "安排详情" }}</h2>
        <p>第 {{ dayIndex + 1 }} 天 · 第 {{ index + 1 }} 个安排</p>
      </div>
      <button
        class="wb-text-button"
        aria-label="收起安排详情，保留当前草稿"
        :disabled="busy"
        @click="dismiss"
      >
        收起编辑
      </button>
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
        @close="dismiss"
        @move="emit('move', $event)"
        @remove="emit('remove')"
        @transfer="emit('transfer', $event)"
        @poi="emit('poi')"
        @record="emit('record')"
      />
    </div>
    <footer class="wb-inspector-footer">
      <p v-if="error" id="wb-inspector-error" class="form-error" role="alert">
        {{ error }}
      </p>
      <p
        id="wb-inspector-state"
        role="status"
        aria-live="polite"
        aria-atomic="true"
        :class="{ 'is-saved': savedHint && !dirty }"
      >
        <span v-if="savedHint && !dirty" aria-hidden="true">✓ </span
        >{{
          savedHint && !dirty
            ? "已保存到路书 · 版本 " + trip.version
            : dirty
              ? "有未保存修改。收起编辑仍保留当前草稿。"
              : editable
                ? "安排已与当前路书同步。"
                : "正在查看已保存的安排。"
        }}
      </p>
      <div class="wb-inspector-footer-actions">
        <button
          type="button"
          class="wb-inspector-done"
          :disabled="busy"
          @click="dismiss"
        >
          {{ editable ? "完成编辑" : "关闭详情" }}
        </button>
        <button
          v-if="editable"
          class="wb-inspector-save"
          :disabled="busy || !dirty"
          @click="requestSave"
          :aria-busy="busy"
        >
          {{ busy ? "处理中…" : savedHint && !dirty ? "✓ 已保存" : "保存路书" }}
        </button>
      </div>
    </footer>
  </dialog>
</template>
